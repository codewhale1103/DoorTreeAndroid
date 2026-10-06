package codewhale.doortreeandroid

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant

class AuthSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("door_tree_auth", Context.MODE_PRIVATE)
    private val restClient = FirebaseRestClient()
    private val firebaseAuth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance(FirebaseConfig.databaseUrl).reference
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val serializer = Json { ignoreUnknownKeys = true }

    private var currentSession: PersistedSession? = null
    private var pendingFirstLoginResetUID: String? = null

    var user by mutableStateOf<AuthUser?>(null)
        private set
    var isRestoringSession by mutableStateOf(true)
        private set
    var isAuthenticating by mutableStateOf(false)
        private set
    var pendingVerificationEmail by mutableStateOf<String?>(null)
    var pendingFirstLoginResetEmail by mutableStateOf<String?>(null)

    init {
        pendingFirstLoginResetUID = prefs.getString(KEY_PENDING_RESET_UID, null)
        pendingFirstLoginResetEmail = prefs.getString(KEY_PENDING_RESET_EMAIL, null)
        scope.launch {
            restoreSession()
        }
    }

    fun signOut() {
        firebaseAuth.signOut()
        currentSession = null
        prefs.edit()
            .remove(KEY_SESSION)
            .remove(KEY_PENDING_RESET_UID)
            .remove(KEY_PENDING_RESET_EMAIL)
            .apply()
        user = null
        pendingVerificationEmail = null
        pendingFirstLoginResetUID = null
        pendingFirstLoginResetEmail = null
        isAuthenticating = false
        isRestoringSession = false
    }

    fun signInWithEmail(email: String, password: String, completion: (String?) -> Unit) {
        val normalizedEmail = normalizeEmail(email)
        val trimmedPassword = password.trim()
        val emailDomain = normalizedEmail.substringAfter("@", missingDelimiterValue = "missing-domain")

        when {
            normalizedEmail.isBlank() -> {
                completion(L("auth.error.enter_email"))
                return
            }
            trimmedPassword.isBlank() -> {
                completion(L("auth.error.enter_password"))
                return
            }
        }

        isAuthenticating = true
        scope.launch {
            val message = runCatching {
                Log.d(TAG, "signInWithEmail start emailDomain=$emailDomain")
                val response = restClient.signInWithEmail(normalizedEmail, password)
                Log.d(TAG, "signInWithEmail REST success uid=${response.localId.takeLast(6)} emailDomain=$emailDomain")
                firebaseAuth.signInWithEmailAndPassword(normalizedEmail, password).await()
                Log.d(TAG, "signInWithEmail FirebaseAuth success uid=${firebaseAuth.currentUser?.uid?.takeLast(6).orEmpty()}")
                val lookup = restClient.lookupAccount(response.idToken).users.firstOrNull()
                Log.d(
                    TAG,
                    "signInWithEmail lookup success lookupFound=${lookup != null} emailVerified=${lookup?.emailVerified}"
                )
                currentSession = PersistedSession(
                    uid = response.localId,
                    email = response.email,
                    idToken = response.idToken,
                    refreshToken = response.refreshToken,
                    expiresAtEpochSeconds = Instant.now().epochSecond + response.expiresIn.toLong()
                )
                persistSession()

                val accessResolution = resolveAccess(lookupEmail = lookup?.email ?: normalizedEmail, isSessionRestore = false)
                Log.d(TAG, "signInWithEmail resolveAccess result=$accessResolution uid=${response.localId.takeLast(6)}")

                when (accessResolution) {
                    AccessResolution.Allowed -> null
                    AccessResolution.NeedsFirstLoginReset -> null
                    AccessResolution.NeedsEmailVerification -> L("auth.verify.before_continue")
                    AccessResolution.Failure -> L("auth.error.sign_in_unavailable")
                }
            }.getOrElse { throwable ->
                Log.e(TAG, "signInWithEmail failed emailDomain=$emailDomain reason=${debugThrowable(throwable)}", throwable)
                readableMessage(throwable, fallbackKey = "auth.error.sign_in_unavailable")
            }

            Log.d(TAG, "signInWithEmail finished success=${message == null} message=${message ?: "none"}")
            isAuthenticating = false
            completion(message)
        }
    }

    fun signInWithGoogle(idToken: String, completion: (String?) -> Unit) {
        isAuthenticating = true
        scope.launch {
            val message = runCatching {
                val response = restClient.signInWithGoogle(idToken)
                val firebaseUser = firebaseAuth.signInWithCredential(
                    GoogleAuthProvider.getCredential(idToken, null)
                ).await().user ?: error("Google sign-in did not return a user")
                check(response.localId == firebaseUser.uid) { "Google account mismatch" }
                check(response.idToken.isNotBlank() && response.refreshToken.isNotBlank()) {
                    "Google sign-in did not return a session"
                }
                currentSession = PersistedSession(
                    uid = response.localId,
                    email = response.email.ifBlank { firebaseUser.email.orEmpty() },
                    idToken = response.idToken,
                    refreshToken = response.refreshToken,
                    expiresAtEpochSeconds = Instant.now().epochSecond + response.expiresIn.toLong(),
                    provider = "google.com"
                )
                persistSession()
                when (resolveAccess(lookupEmail = response.email, isSessionRestore = false)) {
                    AccessResolution.Allowed, AccessResolution.NeedsFirstLoginReset -> null
                    AccessResolution.NeedsEmailVerification -> L("auth.verify.before_continue")
                    AccessResolution.Failure -> L("auth.error.sign_in_unavailable")
                }
            }.getOrElse { throwable ->
                Log.e(TAG, "signInWithGoogle failed: ${debugThrowable(throwable)}", throwable)
                readableMessage(throwable, fallbackKey = "auth.google.unavailable")
            }
            isAuthenticating = false
            completion(message)
        }
    }

    fun createEmailAccount(email: String, password: String, completion: (String?) -> Unit) {
        val normalizedEmail = normalizeEmail(email)
        val trimmedPassword = password.trim()

        when {
            normalizedEmail.isBlank() -> {
                completion(L("auth.error.enter_email"))
                return
            }
            !normalizedEmail.contains("@") || !normalizedEmail.contains(".") -> {
                completion(L("auth.error.invalid_email"))
                return
            }
            trimmedPassword.isBlank() -> {
                completion(L("auth.error.enter_password"))
                return
            }
            trimmedPassword.length < 6 -> {
                completion(L("auth.error.weak_password"))
                return
            }
        }

        isAuthenticating = true
        scope.launch {
            val message = runCatching {
                val response = restClient.createEmailAccount(normalizedEmail, trimmedPassword)
                firebaseAuth.signInWithEmailAndPassword(normalizedEmail, trimmedPassword).await()
                restClient.sendVerificationEmail(response.idToken)
                pendingVerificationEmail = normalizedEmail
                signOut()
                null
            }.getOrElse { throwable ->
                readableMessage(throwable, fallbackKey = "auth.error.create_account_unavailable")
            }

            isAuthenticating = false
            completion(message)
        }
    }

    fun sendPasswordReset(email: String, completion: (String?) -> Unit) {
        val normalizedEmail = normalizeEmail(email)
        if (normalizedEmail.isBlank()) {
            completion(L("auth.error.enter_email"))
            return
        }

        isAuthenticating = true
        scope.launch {
            val message = runCatching {
                restClient.sendPasswordReset(normalizedEmail)
                null
            }.getOrElse { throwable ->
                readableMessage(throwable, fallbackKey = "auth.error.reset_unavailable")
            }

            isAuthenticating = false
            completion(message)
        }
    }

    fun resendVerificationEmail(completion: (String?) -> Unit) {
        val idToken = currentSession?.idToken
        if (idToken.isNullOrBlank()) {
            completion(L("auth.error.resend_requires_sign_in"))
            return
        }

        isAuthenticating = true
        scope.launch {
            val message = runCatching {
                restClient.sendVerificationEmail(idToken)
                null
            }.getOrElse { throwable ->
                readableMessage(throwable, fallbackKey = "auth.error.resend_verification_unavailable")
            }

            isAuthenticating = false
            completion(message)
        }
    }

    fun refreshVerificationStatus(completion: (Boolean, String?) -> Unit) {
        isAuthenticating = true
        scope.launch {
            val outcome = runCatching {
                val idToken = ensureValidIdToken() ?: return@runCatching false to L("auth.error.sign_in_again")
                val lookup = restClient.lookupAccount(idToken).users.firstOrNull()
                if (lookup?.emailVerified == true) {
                    when (resolveAccess(lookupEmail = lookup.email, isSessionRestore = false)) {
                        AccessResolution.Allowed,
                        AccessResolution.NeedsFirstLoginReset -> true to null
                        AccessResolution.NeedsEmailVerification -> false to L("auth.verify.not_verified_detailed")
                        AccessResolution.Failure -> false to L("auth.error.refresh_account_unavailable")
                    }
                } else {
                    false to L("auth.verify.not_verified")
                }
            }.getOrElse { throwable ->
                false to readableMessage(throwable, fallbackKey = "auth.error.refresh_account_unavailable")
            }

            isAuthenticating = false
            completion(outcome.first, outcome.second)
        }
    }

    fun beginFirstLoginResetRequirement(completion: (String?) -> Unit) {
        val session = currentSession
        val uid = pendingFirstLoginResetUID
        if (session == null || uid == null || session.uid != uid) {
            completion(L("auth.error.sign_in_again"))
            return
        }

        persistPendingFirstLoginResetRequest(uid = uid, email = session.email)
        signOut()
        completion(null)
    }

    suspend fun deleteCurrentAccount() {
        val firebaseUser = firebaseAuth.currentUser ?: throw IllegalStateException(L("auth.error.sign_in_again"))
        val uid = firebaseUser.uid.trim()
        if (uid.isBlank()) {
            throw IllegalStateException(L("auth.error.sign_in_again"))
        }

        isAuthenticating = true
        try {
            database.child("users").child(uid).removeValue().await()
            firebaseUser.delete().await()
            signOut()
        } catch (throwable: Throwable) {
            throw IllegalStateException(readableDeleteAccountMessage(throwable), throwable)
        } finally {
            isAuthenticating = false
        }
    }

    suspend fun ensureValidIdToken(): String? {
        val session = currentSession ?: return null
        if (session.expiresAtEpochSeconds - Instant.now().epochSecond > 60) {
            Log.d(TAG, "ensureValidIdToken using cached token uid=${session.uid.takeLast(6)}")
            return session.idToken
        }

        return runCatching {
            Log.d(TAG, "ensureValidIdToken refreshing token uid=${session.uid.takeLast(6)}")
            val refreshed = restClient.refreshToken(session.refreshToken)
            currentSession = session.copy(
                uid = refreshed.userId,
                idToken = refreshed.idToken,
                refreshToken = refreshed.refreshToken,
                expiresAtEpochSeconds = Instant.now().epochSecond + refreshed.expiresIn.toLong()
            )
            persistSession()
            Log.d(TAG, "ensureValidIdToken refresh success uid=${refreshed.userId.takeLast(6)}")
            currentSession?.idToken
        }.getOrElse { throwable ->
            Log.e(TAG, "ensureValidIdToken refresh failed uid=${session.uid.takeLast(6)} reason=${debugThrowable(throwable)}", throwable)
            null
        }
    }

    private suspend fun restoreSession() {
        val serialized = prefs.getString(KEY_SESSION, null)
        if (serialized.isNullOrBlank()) {
            Log.d(TAG, "restoreSession skipped: no persisted session")
            isRestoringSession = false
            return
        }

        val restored = runCatching { serializer.decodeFromString<PersistedSession>(serialized) }.getOrNull()
        if (restored == null) {
            Log.w(TAG, "restoreSession failed to decode persisted session")
            signOut()
            return
        }

        currentSession = restored
        Log.d(TAG, "restoreSession start uid=${restored.uid.takeLast(6)}")
        val idToken = ensureValidIdToken()
        if (idToken.isNullOrBlank()) {
            Log.w(TAG, "restoreSession failed: missing valid id token uid=${restored.uid.takeLast(6)}")
            signOut()
            return
        }

        val lookupEmail = runCatching {
            restClient.lookupAccount(idToken).users.firstOrNull()?.email ?: restored.email
        }.getOrElse { throwable ->
            Log.e(TAG, "restoreSession lookup failed uid=${restored.uid.takeLast(6)} reason=${debugThrowable(throwable)}", throwable)
            restored.email
        }

        val accessResolution = resolveAccess(lookupEmail = lookupEmail, isSessionRestore = true)
        Log.d(TAG, "restoreSession resolveAccess result=$accessResolution uid=${restored.uid.takeLast(6)}")
    }

    private suspend fun resolveAccess(
        lookupEmail: String,
        isSessionRestore: Boolean
    ): AccessResolution {
        val session = currentSession ?: return AccessResolution.Failure.also {
            Log.w(TAG, "resolveAccess failed: missing currentSession restore=$isSessionRestore")
            isRestoringSession = false
        }

        val idToken = ensureValidIdToken() ?: return AccessResolution.Failure.also {
            Log.w(TAG, "resolveAccess failed: missing idToken uid=${session.uid.takeLast(6)} restore=$isSessionRestore")
            isRestoringSession = false
            user = null
        }

        if (session.provider == "google.com") {
            clearPersistedFirstLoginResetRequest()
            pendingVerificationEmail = null
            pendingFirstLoginResetEmail = null
            pendingFirstLoginResetUID = null
            user = AuthUser(uid = session.uid, email = session.email)
            if (isSessionRestore) isRestoringSession = false
            return AccessResolution.Allowed
        }

        val isFirstLoginComplete = runCatching {
            Log.d(TAG, "resolveAccess fetching isFirstLoginComplete uid=${session.uid.takeLast(6)} restore=$isSessionRestore")
            restClient.fetchIsFirstLoginComplete(session.uid, idToken)
        }.getOrElse { throwable ->
            Log.e(
                TAG,
                "resolveAccess failed fetching isFirstLoginComplete uid=${session.uid.takeLast(6)} reason=${debugThrowable(throwable)}",
                throwable
            )
            if (isSessionRestore) isRestoringSession = false
            user = null
            pendingVerificationEmail = null
            pendingFirstLoginResetEmail = null
            pendingFirstLoginResetUID = null
            return AccessResolution.Failure
        }
        Log.d(TAG, "resolveAccess isFirstLoginComplete=$isFirstLoginComplete uid=${session.uid.takeLast(6)}")

        if (isFirstLoginComplete) {
            clearPersistedFirstLoginResetRequest()
            pendingVerificationEmail = null
            pendingFirstLoginResetEmail = null
            pendingFirstLoginResetUID = null
            user = AuthUser(uid = session.uid, email = session.email)
            if (isSessionRestore) isRestoringSession = false
            Log.d(TAG, "resolveAccess allowed uid=${session.uid.takeLast(6)}")
            return AccessResolution.Allowed
        }

        if (hasPersistedFirstLoginResetRequest(session.uid)) {
            val updated = runCatching {
                Log.d(TAG, "resolveAccess marking first login complete uid=${session.uid.takeLast(6)}")
                restClient.markFirstLoginComplete(session.uid, idToken)
            }.getOrElse { throwable ->
                Log.e(
                    TAG,
                    "resolveAccess markFirstLoginComplete failed uid=${session.uid.takeLast(6)} reason=${debugThrowable(throwable)}",
                    throwable
                )
                false
            }
            if (updated) {
                clearPersistedFirstLoginResetRequest()
                pendingVerificationEmail = null
                pendingFirstLoginResetEmail = null
                pendingFirstLoginResetUID = null
                user = AuthUser(uid = session.uid, email = session.email)
                if (isSessionRestore) isRestoringSession = false
                Log.d(TAG, "resolveAccess allowed after first-login reset uid=${session.uid.takeLast(6)}")
                return AccessResolution.Allowed
            }

            user = null
            if (isSessionRestore) isRestoringSession = false
            Log.w(TAG, "resolveAccess failed: persisted first-login reset could not be completed uid=${session.uid.takeLast(6)}")
            return AccessResolution.Failure
        }

        pendingVerificationEmail = null
        pendingFirstLoginResetUID = session.uid
        pendingFirstLoginResetEmail = lookupEmail.ifBlank { session.email }
        user = null
        if (isSessionRestore) isRestoringSession = false
        Log.d(TAG, "resolveAccess needs first-login reset uid=${session.uid.takeLast(6)}")
        return AccessResolution.NeedsFirstLoginReset
    }

    private fun persistPendingFirstLoginResetRequest(uid: String, email: String?) {
        pendingFirstLoginResetUID = uid
        pendingFirstLoginResetEmail = email
        prefs.edit()
            .putString(KEY_PENDING_RESET_UID, uid)
            .putString(KEY_PENDING_RESET_EMAIL, email)
            .apply()
    }

    private fun clearPersistedFirstLoginResetRequest() {
        prefs.edit()
            .remove(KEY_PENDING_RESET_UID)
            .remove(KEY_PENDING_RESET_EMAIL)
            .apply()
    }

    private fun hasPersistedFirstLoginResetRequest(uid: String): Boolean {
        return prefs.getString(KEY_PENDING_RESET_UID, null) == uid
    }

    private fun persistSession() {
        val session = currentSession ?: return
        prefs.edit().putString(KEY_SESSION, serializer.encodeToString(PersistedSession.serializer(), session)).apply()
    }

    private fun readableMessage(throwable: Throwable, fallbackKey: String): String {
        val code = (throwable as? FirebaseRestException)?.code.orEmpty()
        Log.d(TAG, "readableMessage throwable=${debugThrowable(throwable)} fallbackKey=$fallbackKey")
        if (throwable.hasCause<UnknownHostException>()) {
            return "No internet connection. Please check your network and try again."
        }

        if (throwable.hasCause<SocketTimeoutException>()) {
            return "The sign-in request timed out. Please check your connection and try again."
        }

        if (throwable is IOException) {
            return "Network error. Please check your connection and try again."
        }

        return when (code) {
            "INVALID_LOGIN_CREDENTIALS", "INVALID_PASSWORD" -> L("auth.error.invalid_credentials")
            "EMAIL_NOT_FOUND" -> L("auth.error.no_account")
            "USER_DISABLED" -> L("auth.error.account_disabled")
            "EMAIL_EXISTS" -> L("auth.error.email_already_in_use")
            "WEAK_PASSWORD" -> L("auth.error.weak_password")
            "TOO_MANY_ATTEMPTS_TRY_LATER" -> L("auth.error.too_many_attempts")
            "API_KEY_INVALID", "INVALID_API_KEY" -> "Firebase sign-in is not configured correctly for this app."
            "INVALID_ID_TOKEN", "TOKEN_EXPIRED", "USER_NOT_FOUND" -> L("auth.error.sign_in_again")
            else -> L(fallbackKey)
        }
    }

    private fun readableDeleteAccountMessage(throwable: Throwable): String {
        return when (throwable) {
            is FirebaseAuthRecentLoginRequiredException -> L("profile.delete_account.error.recent_login")
            else -> L("profile.delete_account.error.generic")
        }
    }

    private fun normalizeEmail(value: String): String {
        return value.trim().lowercase()
    }

    private fun debugThrowable(throwable: Throwable): String {
        val restCode = (throwable as? FirebaseRestException)?.code
        val firebaseCode = (throwable as? FirebaseAuthException)?.errorCode
        return buildString {
            append(throwable::class.java.simpleName)
            if (!restCode.isNullOrBlank()) append("(restCode=").append(restCode).append(")")
            if (!firebaseCode.isNullOrBlank()) append("(firebaseCode=").append(firebaseCode).append(")")
            throwable.message?.let { append(": ").append(it) }
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    @Serializable
    private data class PersistedSession(
        val uid: String,
        val email: String,
        val idToken: String,
        val refreshToken: String,
        val expiresAtEpochSeconds: Long,
        val provider: String = "password"
    )

    private enum class AccessResolution {
        Allowed,
        NeedsEmailVerification,
        NeedsFirstLoginReset,
        Failure
    }

    companion object {
        private const val TAG = "DoorTreeAuth"
        private const val KEY_SESSION = "door_tree_session"
        private const val KEY_PENDING_RESET_UID = "pendingFirstLoginResetUID"
        private const val KEY_PENDING_RESET_EMAIL = "pendingFirstLoginResetEmail"
    }
}
