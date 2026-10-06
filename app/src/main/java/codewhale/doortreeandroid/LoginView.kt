package codewhale.doortreeandroid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import codewhale.doortreeandroid.ui.theme.DoorTreeTheme
import android.content.Intent
import android.net.Uri

@Composable
fun LoginView(authSession: AuthSessionStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showEmailAuth by remember { mutableStateOf(false) }
    var authAlertMessage by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        AuthBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .topSafeAreaPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.size(1.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    DoorTreeLogoLockup(width = 220.dp)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = L("auth.welcome.title"), color = DoorTreeTheme.textPrimary)
                        Text(text = L("auth.welcome.subtitle"), color = DoorTreeTheme.textSecondary)
                    }
                }

                if (!authSession.pendingVerificationEmail.isNullOrBlank()) {
                    VerificationReminder(
                        email = authSession.pendingVerificationEmail.orEmpty(),
                        onClick = { showEmailAuth = true }
                    )
                }

                GradientButton(
                    title = L("auth.google.continue"),
                    onClick = {
                        scope.launch {
                            try {
                                val option = GetSignInWithGoogleOption.Builder(
                                    context.getString(R.string.default_web_client_id)
                                ).build()
                                val request = GetCredentialRequest.Builder()
                                    .addCredentialOption(option)
                                    .build()
                                val credential = CredentialManager.create(context)
                                    .getCredential(context, request).credential
                                if (credential !is CustomCredential || credential.type != TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                    authAlertMessage = L("auth.google.unavailable")
                                } else {
                                    val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                                    authSession.signInWithGoogle(idToken) { error ->
                                        if (error != null) authAlertMessage = error
                                    }
                                }
                            } catch (_: GetCredentialCancellationException) {
                                // The user dismissed the Google account picker.
                            } catch (_: Exception) {
                                authAlertMessage = L("auth.google.unavailable")
                            }
                        }
                    },
                    content = {
                        Image(
                            painter = painterResource(R.drawable.google_g),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )

                GradientButton(
                    title = L("auth.email.continue"),
                    icon = "envelope.fill",
                    onClick = { showEmailAuth = true }
                )

                LoginDisclaimer(
                    onOpenTerms = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://doortree.co/terms-and-conditions")))
                    },
                    onOpenPrivacy = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://doortree.co/privacy-policy")))
                    }
                )
            }

            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
                PoweredByCodeWhaleFooter()
            }
        }

        if (showEmailAuth) {
            EmailAuthView(
                authSession = authSession,
                initialEmail = authSession.pendingVerificationEmail
                    ?: authSession.pendingFirstLoginResetEmail
                    ?: "",
                onDismiss = { showEmailAuth = false }
            )
        }

        if (authSession.isAuthenticating) {
            AuthLoadingOverlay(
                title = L("auth.signing_in.title"),
                subtitle = L("auth.signing_in.subtitle")
            )
        }
    }

    if (authAlertMessage.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { authAlertMessage = "" },
            confirmButton = {
                TextButton(onClick = { authAlertMessage = "" }) {
                    Text(L("common.ok"))
                }
            },
            title = { Text(L("auth.alert.title")) },
            text = { Text(authAlertMessage) }
        )
    }
}

@Composable
private fun LoginDisclaimer(
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = L("login.disclaimer.prefix"),
            color = DoorTreeTheme.textSecondary
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = L("policy.terms_of_use.title"),
                color = DoorTreeTheme.textSecondary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(onClick = onOpenTerms)
            )
            Text(
                text = L("login.disclaimer.and"),
                color = DoorTreeTheme.textSecondary
            )
            Text(
                text = L("policy.privacy_policy.title"),
                color = DoorTreeTheme.textSecondary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(onClick = onOpenPrivacy)
            )
        }
    }
}

@Composable
private fun VerificationReminder(email: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassSurface(cornerRadius = 20.dp, interactive = true, tint = DoorTreeTheme.gradientStart.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        androidx.compose.material3.Icon(
            imageVector = systemIcon("envelope.badge.fill"),
            contentDescription = null,
            tint = DoorTreeTheme.gradientStart
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(text = L("auth.finish_verifying"), color = DoorTreeTheme.textPrimary)
            Text(text = email, color = DoorTreeTheme.textSecondary)
        }
        androidx.compose.material3.Icon(
            imageVector = systemIcon("chevron.right"),
            contentDescription = null,
            tint = DoorTreeTheme.textSecondary
        )
    }
}
