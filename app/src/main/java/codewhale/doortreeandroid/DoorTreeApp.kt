package codewhale.doortreeandroid

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.math.BigDecimal
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.firebase.database.FirebaseDatabase
import codewhale.doortreeandroid.ui.theme.DoorTreeAndroidTheme
import codewhale.doortreeandroid.ui.theme.DoorTreeTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun DoorTreeAndroidApp() {
    val context = LocalContext.current
    remember { Localization.ensureLoaded(context); true }

    val authSession = remember { AuthSessionStore(context) }
    val tenantDataStore = remember { TenantDataStore(authSession, context.applicationContext) }
    val landlordDataStore = remember { LandlordDataStore() }
    var accountRole by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showSplash by remember { mutableStateOf(true) }
    var splashAnimationCompleted by remember { mutableStateOf(false) }
    var minimumVersionCheckCompleted by remember { mutableStateOf(false) }
    var forceUpdateRequired by remember { mutableStateOf(false) }
    var showEulaDialog by remember { mutableStateOf(false) }
    var didCheckEula by remember { mutableStateOf(false) }
    var didHandleEulaDecision by remember { mutableStateOf(false) }
    var hasAcceptedEula by remember { mutableStateOf(false) }
    val database = remember { FirebaseDatabase.getInstance(FirebaseConfig.databaseUrl).reference }

    LaunchedEffect(Unit) {
        checkMinimumVersionRequirement(database)?.let { forceUpdateRequired = it }
        minimumVersionCheckCompleted = true
    }

    LaunchedEffect(splashAnimationCompleted, minimumVersionCheckCompleted) {
        if (splashAnimationCompleted && minimumVersionCheckCompleted) {
            showSplash = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                coroutineScope.launch {
                    checkMinimumVersionRequirement(database)?.let { forceUpdateRequired = it }
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(authSession.user?.uid) {
        accountRole = null
        tenantDataStore.handleAuthState(null)
        landlordDataStore.stop()
        val uid = authSession.user?.uid?.trim().orEmpty()
        if (uid.isEmpty()) {
            didCheckEula = true
            didHandleEulaDecision = false
            hasAcceptedEula = true
            showEulaDialog = false
        } else {
            val roleResult = runCatching {
                database.child("users").child(uid).child("userType").get().await()
                    .getValue(String::class.java)?.trim()?.lowercase()
            }
            accountRole = when {
                roleResult.isFailure -> "unavailable"
                roleResult.getOrNull() == "landlord" -> "landlord"
                else -> "tenant"
            }
            if (accountRole == "landlord") landlordDataStore.start(uid)
            else if (accountRole == "tenant") tenantDataStore.handleAuthState(uid)
            didCheckEula = false
            didHandleEulaDecision = false
            hasAcceptedEula = false
            showEulaDialog = false

            val accepted = runCatching {
                val snapshot = database.child("users").child(uid).child("EULA").get().await()
                when (val value = snapshot.value) {
                    is Boolean -> value
                    is String -> value.trim().lowercase() in setOf("1", "true", "yes", "agreed")
                    else -> false
                }
            }.getOrDefault(false)

            didCheckEula = true
            hasAcceptedEula = accepted
            showEulaDialog = !accepted
        }
    }

    DoorTreeAndroidTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DoorTreeTheme.backgroundPrimary)
        ) {
            when {
                forceUpdateRequired -> Unit
                showSplash -> SplashLoadingView(onFinish = { splashAnimationCompleted = true })
                authSession.isRestoringSession -> AuthLoadingOverlay(
                    title = L("auth.restoring.title"),
                    subtitle = L("auth.restoring.subtitle")
                )
                authSession.user != null && !didCheckEula -> AuthLoadingOverlay(
                    title = "Checking agreement",
                    subtitle = "Reviewing your access requirements."
                )
                authSession.user != null && !hasAcceptedEula -> AuthLoadingOverlay(
                    title = "Agreement required",
                    subtitle = "Please review and accept the agreement to continue."
                )
                authSession.user != null && accountRole == null -> AuthLoadingOverlay(
                    title = "Loading your account",
                    subtitle = "Finding your workspace."
                )
                authSession.user != null && accountRole == "unavailable" -> TenantLoadFailureView(
                    title = "Account unavailable",
                    message = "We couldn't load your account type. Check your connection and try again.",
                    onRetry = {
                        coroutineScope.launch {
                            val uid = authSession.user?.uid?.trim().orEmpty()
                            if (uid.isNotEmpty()) {
                                val role = runCatching {
                                    database.child("users").child(uid).child("userType").get().await()
                                        .getValue(String::class.java)?.trim()?.lowercase()
                                }
                                accountRole = when {
                                    role.isFailure -> "unavailable"
                                    role.getOrNull() == "landlord" -> "landlord"
                                    else -> "tenant"
                                }
                                if (accountRole == "landlord") landlordDataStore.start(uid)
                                else if (accountRole == "tenant") tenantDataStore.handleAuthState(uid)
                            }
                        }
                    },
                    onSignOut = authSession::signOut
                )
                authSession.user != null && accountRole == "landlord" -> LandlordContentView(
                    landlordDataStore = landlordDataStore,
                    onSignOut = authSession::signOut
                )
                authSession.user != null && tenantDataStore.isLoading && tenantDataStore.tenantRecord == null -> AuthLoadingOverlay(
                    title = L("Loading your account"),
                    subtitle = L("Loading your tenant profile.")
                )
                authSession.user != null && tenantDataStore.tenantRecord != null -> ContentView(
                    authSession = authSession,
                    tenantDataStore = tenantDataStore
                )
                authSession.user != null -> TenantLoadFailureView(
                    message = tenantDataStore.loadError ?: L("We couldn't load your tenant profile."),
                    onRetry = tenantDataStore::reload,
                    onSignOut = authSession::signOut
                )
                else -> LoginView(authSession = authSession)
            }

            if (forceUpdateRequired) {
                ForceUpdateOverlay(
                    onUpdate = { openPlayStorePage(context) }
                )
            }
        }

        if (showEulaDialog) {
            AlertDialog(
                onDismissRequest = {
                    if (!didHandleEulaDecision) {
                        didHandleEulaDecision = true
                        val uid = authSession.user?.uid?.trim().orEmpty()
                        if (uid.isNotEmpty()) {
                            coroutineScope.launch {
                                runCatching {
                                    database.child("users").child(uid).child("EULA").setValue(false).await()
                                }
                            }
                        }
                        hasAcceptedEula = false
                        showEulaDialog = false
                        authSession.signOut()
                    }
                },
                title = { Text(L("eula")) },
                text = { Text(L("eula_text")) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            didHandleEulaDecision = true
                            val uid = authSession.user?.uid?.trim().orEmpty()
                            if (uid.isNotEmpty()) {
                                coroutineScope.launch {
                                    runCatching {
                                        database.child("users").child(uid).child("EULA").setValue(true).await()
                                    }
                                }
                            }
                            hasAcceptedEula = true
                            showEulaDialog = false
                        }
                    ) {
                        Text(L("agree"))
                    }
                },
                dismissButton = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                didHandleEulaDecision = true
                                val uid = authSession.user?.uid?.trim().orEmpty()
                                if (uid.isNotEmpty()) {
                                    coroutineScope.launch {
                                        runCatching {
                                            database.child("users").child(uid).child("EULA").setValue(false).await()
                                        }
                                    }
                                }
                                hasAcceptedEula = false
                                showEulaDialog = false
                                authSession.signOut()
                            }
                        ) {
                            Text(L("disagree"), color = DoorTreeTheme.destructive)
                        }

                        TextButton(
                            onClick = {
                                didHandleEulaDecision = true
                                val uid = authSession.user?.uid?.trim().orEmpty()
                                if (uid.isNotEmpty()) {
                                    coroutineScope.launch {
                                        runCatching {
                                            database.child("users").child(uid).child("EULA").setValue(false).await()
                                        }
                                    }
                                }
                                hasAcceptedEula = false
                                showEulaDialog = false
                                authSession.signOut()
                            }
                        ) {
                            Text(L("cancel"))
                        }
                    }
                }
            )
        }
    }
}

private suspend fun checkMinimumVersionRequirement(database: com.google.firebase.database.DatabaseReference): Boolean? {
    val currentVersion = BuildConfig.VERSION_NAME.trim()
    if (currentVersion.isEmpty()) {
        Log.d("DoorTreeVersionCheck", "Android current version is empty")
        return null
    }

    val requiredVersion = runCatching {
        val snapshot = database.child("minimumRequiredVersionAndroid").get().await()
        val normalized = versionString(snapshot.value)
        Log.d("DoorTreeVersionCheck", "Android rawRequired=${snapshot.value} normalizedRequired=$normalized")
        normalized
    }.onFailure { error ->
        Log.d("DoorTreeVersionCheck", "Android read failed for minimumRequiredVersionAndroid: ${error.localizedMessage}")
    }.getOrNull() ?: return null

    if (requiredVersion.isEmpty()) {
        Log.d("DoorTreeVersionCheck", "Android root key minimumRequiredVersionAndroid is missing or empty")
        return false
    }

    val needsUpdate = isVersionOlder(currentVersion, requiredVersion)
    Log.d(
        "DoorTreeVersionCheck",
        "Android current=$currentVersion required=$requiredVersion key=minimumRequiredVersionAndroid needsUpdate=$needsUpdate"
    )
    return needsUpdate
}

private fun versionString(value: Any?): String {
    return when (value) {
        is String -> value
        is Number -> numericVersionString(value)
        else -> ""
    }.trim()
}

private fun numericVersionString(value: Number): String {
    return when (value) {
        is Byte, is Short, is Int, is Long -> value.toLong().toString()
        is Float, is Double -> BigDecimal.valueOf(value.toDouble()).stripTrailingZeros().toPlainString()
        else -> value.toString()
    }
}

private fun isVersionOlder(current: String, required: String): Boolean {
    val currentComponents = current.split(".").map { it.toIntOrNull() ?: 0 }
    val requiredComponents = required.split(".").map { it.toIntOrNull() ?: 0 }
    val maxCount = maxOf(currentComponents.size, requiredComponents.size)

    repeat(maxCount) { index ->
        val currentValue = currentComponents.getOrElse(index) { 0 }
        val requiredValue = requiredComponents.getOrElse(index) { 0 }

        when {
            currentValue < requiredValue -> return true
            currentValue > requiredValue -> return false
        }
    }

    return false
}

private fun openPlayStorePage(context: android.content.Context) {
    val playStorePackageId = "codewhale.doortreeandroid"
    val playStoreWebUrl = "https://play.google.com/store/apps/details?id=codewhale.doortreeandroid"
    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$playStorePackageId")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val webIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(playStoreWebUrl)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        context.startActivity(marketIntent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(webIntent)
    }
}

@Composable
private fun ForceUpdateOverlay(onUpdate: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f))
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.82f))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = L("Update Required"),
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = L("Update to the latest version of DoorTree to continue using the app."),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                fontSize = 16.sp
            )
            Text(
                text = L("Update Now"),
                color = androidx.compose.ui.graphics.Color.Black,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(androidx.compose.ui.graphics.Color.White)
                    .clickable(onClick = onUpdate)
                    .padding(horizontal = 32.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun TenantLoadFailureView(
    title: String = "Tenant data unavailable",
    message: String,
    onRetry: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .topSafeAreaPadding()
            .padding(DoorTreeTheme.screenHorizontalPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .glassCard(cornerRadius = 24.dp)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = L(title), color = DoorTreeTheme.textPrimary)
            Text(text = message, color = DoorTreeTheme.textSecondary)
            GradientButton(title = L("Try again"), onClick = onRetry)
            Text(
                text = L("profile.sign_out"),
                color = DoorTreeTheme.destructive,
                modifier = Modifier.clickable(onClick = onSignOut)
            )
        }
    }
}
