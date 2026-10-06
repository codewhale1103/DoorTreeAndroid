package codewhale.doortreeandroid

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import codewhale.doortreeandroid.ui.theme.DoorTreeTheme
import kotlinx.coroutines.launch

@Composable
fun InteracTransferSheetView(
    details: InteracTransferDetails,
    onMarkSent: suspend () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copiedMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isConfirmingSent by remember { mutableStateOf(false) }
    var isMarkingSent by remember { mutableStateOf(false) }
    var hasReportedSent by remember { mutableStateOf(false) }

    fun copy(value: String, message: String) {
        clipboard.setText(AnnotatedString(value))
        copiedMessage = message
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DoorTreeTheme.backgroundPrimary)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .topSafeAreaPadding()
                    .padding(horizontal = DoorTreeTheme.screenHorizontalPadding)
                    .padding(top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeaderIconButton(systemName = "xmark", onClick = onDismiss)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Pay by Interac e-Transfer",
                            color = DoorTreeTheme.textPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() }
                        )
                        Text(text = "DoorTree will guide you", color = DoorTreeTheme.textSecondary)
                    }
                }

                Text(
                    text = "You will send the rent from your own banking app. DoorTree never signs in to your bank and cannot withdraw money.",
                    color = DoorTreeTheme.textSecondary,
                    fontSize = 16.sp,
                    lineHeight = 23.sp
                )

                InvoiceSummaryCard(details)
                AutodepositCard()

                Text(
                    text = "3 simple steps",
                    color = DoorTreeTheme.textPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )

                CopyStepCard(
                    number = 1,
                    title = "Copy the payment email",
                    explanation = "Your bank will ask who you want to send money to.",
                    value = details.recipientEmail,
                    buttonTitle = "Copy payment email",
                    accessibilityValue = "Payment email, ${details.recipientEmail}",
                    onCopy = { copy(details.recipientEmail, "Payment email copied") }
                )

                CopyStepCard(
                    number = 2,
                    title = "Copy the exact amount",
                    explanation = "Enter this amount in your banking app.",
                    value = details.amount,
                    buttonTitle = "Copy amount",
                    accessibilityValue = "Payment amount, ${details.amount}",
                    emphasizeValue = true,
                    onCopy = { copy(details.amountEntry, "Amount copied") }
                )

                BankingStepCard(details)

                SecondaryActionButton(
                    title = "Copy all payment details",
                    icon = "doc.on.doc",
                    onClick = {
                        val unit = if (details.unitNumber.isBlank()) "" else "\nUnit: ${details.unitNumber}"
                        copy(
                            "Interac e-Transfer rent payment\nSend to: ${details.recipientEmail}\nAmount: ${details.amountEntry} CAD\nProperty: ${details.propertyName}$unit\nReference: ${details.reference}",
                            "All payment details copied"
                        )
                    }
                )

                copiedMessage?.let { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = systemIcon("checkmark.circle.fill"),
                            contentDescription = null,
                            tint = DoorTreeTheme.paidText,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = message,
                            color = DoorTreeTheme.paidText,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlassSurface(
                            cornerRadius = 20.dp,
                            tint = if (hasReportedSent) {
                                DoorTreeTheme.paidBackground.copy(alpha = 0.45f)
                            } else {
                                DoorTreeTheme.gradientStart.copy(alpha = 0.12f)
                            }
                        )
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (hasReportedSent) {
                        Text(
                            text = "✓  DoorTree is watching for your payment",
                            color = DoorTreeTheme.paidText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Your rent will be marked paid or partially paid only after DoorTree receives the bank's Autodeposit notification. This can take a few minutes.",
                            color = DoorTreeTheme.textSecondary,
                            fontSize = 16.sp,
                            lineHeight = 23.sp
                        )
                        PrimaryActionButton(title = "Done", onClick = onDismiss)
                    } else {
                        Text(
                            text = "Finished in your banking app?",
                            color = DoorTreeTheme.textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Come back here only after your bank confirms the e-Transfer was sent.",
                            color = DoorTreeTheme.textSecondary,
                            fontSize = 16.sp,
                            lineHeight = 23.sp
                        )
                        PrimaryActionButton(
                            title = if (isMarkingSent) "Saving..." else "I sent the e-Transfer",
                            enabled = !isMarkingSent,
                            showProgress = isMarkingSent,
                            onClick = { isConfirmingSent = true }
                        )
                    }

                    errorMessage?.let { message ->
                        Text(
                            text = message,
                            color = DoorTreeTheme.destructive,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    if (isConfirmingSent) {
        AlertDialog(
            onDismissRequest = { isConfirmingSent = false },
            title = { Text("Did you send the e-Transfer?") },
            text = {
                Text("Only confirm after your banking app says the Interac e-Transfer was sent. This does not mark your rent paid—DoorTree will wait for the bank notification.")
            },
            dismissButton = {
                TextButton(onClick = { isConfirmingSent = false }) { Text("Not yet") }
            },
            confirmButton = {
                TextButton(onClick = {
                    isConfirmingSent = false
                    isMarkingSent = true
                    errorMessage = null
                    scope.launch {
                        runCatching { onMarkSent() }
                            .onSuccess {
                                isMarkingSent = false
                                hasReportedSent = true
                                Toast.makeText(context, "DoorTree is watching for your payment", Toast.LENGTH_LONG).show()
                            }
                            .onFailure { error ->
                                isMarkingSent = false
                                errorMessage = error.localizedMessage ?: "Your confirmation could not be saved. Please try again."
                            }
                    }
                }) { Text("Yes, I sent it") }
            }
        )
    }
}

@Composable
private fun InvoiceSummaryCard(details: InteracTransferDetails) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassSurface(cornerRadius = 20.dp, tint = DoorTreeTheme.gradientStart.copy(alpha = 0.16f))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = "RENT PAYMENT", color = DoorTreeTheme.gradientStart, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(text = details.rentMonth, color = DoorTreeTheme.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            text = details.amount,
            color = DoorTreeTheme.textPrimary,
            fontSize = 38.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = "Amount due, ${details.amount}" }
        )
        Text(
            text = buildString {
                append(details.propertyName)
                if (details.unitNumber.isNotBlank()) append(" • Unit ${details.unitNumber}")
            },
            color = DoorTreeTheme.textSecondary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Due ${details.dueDate} · Invoice ${details.invoiceNumber}",
            color = DoorTreeTheme.textSecondary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun AutodepositCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassSurface(cornerRadius = 18.dp, tint = DoorTreeTheme.paidBackground.copy(alpha = 0.40f))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = systemIcon("checkmark.shield.fill"),
            contentDescription = null,
            tint = DoorTreeTheme.paidText,
            modifier = Modifier.size(24.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(text = "Autodeposit is ready", color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.Bold)
            Text(
                text = "You will not need a security question. Double-check the email and amount before sending.",
                color = DoorTreeTheme.textSecondary,
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
        }
    }
}

@Composable
private fun CopyStepCard(
    number: Int,
    title: String,
    explanation: String,
    value: String,
    buttonTitle: String,
    accessibilityValue: String,
    emphasizeValue: Boolean = false,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassSurface(cornerRadius = 20.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StepHeader(number, title, explanation)
        Text(
            text = value,
            color = DoorTreeTheme.textPrimary,
            fontSize = if (emphasizeValue) 25.sp else 17.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = if (emphasizeValue) 30.sp else 24.sp,
            modifier = Modifier.semantics { contentDescription = accessibilityValue }
        )
        PrimaryActionButton(title = buttonTitle, icon = "doc.on.doc", onClick = onCopy)
    }
}

@Composable
private fun BankingStepCard(details: InteracTransferDetails) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassSurface(cornerRadius = 20.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StepHeader(3, "Send it from your bank", "Keep DoorTree open, then switch to your bank app.")
        InstructionRow(1, "Open your bank or credit union app.")
        InstructionRow(2, "Choose Interac e-Transfer, then choose Send money.")
        InstructionRow(3, "Paste the payment email and enter ${details.amountEntry}.")
        InstructionRow(4, "Review everything, then send the e-Transfer.")
        Spacer(modifier = Modifier.size(2.dp))
        Text(text = "OPTIONAL MESSAGE OR REFERENCE", color = DoorTreeTheme.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(text = details.reference, color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StepHeader(number: Int, title: String, explanation: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.semantics { contentDescription = "Step $number. $title. $explanation" }
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(DoorTreeTheme.primaryGradient),
            contentAlignment = Alignment.Center
        ) {
            Text(text = number.toString(), color = DoorTreeTheme.accentForeground, fontWeight = FontWeight.Bold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, color = DoorTreeTheme.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(text = explanation, color = DoorTreeTheme.textSecondary, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}

@Composable
private fun InstructionRow(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
        Text(text = "$number.", color = DoorTreeTheme.gradientStart, fontWeight = FontWeight.Bold)
        Text(text = text, color = DoorTreeTheme.textPrimary, fontSize = 16.sp, lineHeight = 23.sp)
    }
}

@Composable
private fun PrimaryActionButton(
    title: String,
    icon: String? = null,
    enabled: Boolean = true,
    showProgress: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(DoorTreeTheme.primaryGradient)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                color = DoorTreeTheme.accentForeground,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
            )
        } else if (icon != null) {
            Icon(
                imageVector = systemIcon(icon),
                contentDescription = null,
                tint = DoorTreeTheme.accentForeground,
                modifier = Modifier.size(21.dp)
            )
        }
        Text(
            text = title,
            color = DoorTreeTheme.accentForeground.copy(alpha = if (enabled) 1f else 0.6f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = if (showProgress || icon != null) 10.dp else 0.dp)
        )
    }
}

@Composable
private fun SecondaryActionButton(title: String, icon: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .liquidGlassSurface(cornerRadius = 18.dp, interactive = true)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = systemIcon(icon),
            contentDescription = null,
            tint = DoorTreeTheme.gradientStart,
            modifier = Modifier.size(21.dp)
        )
        Text(
            text = title,
            color = DoorTreeTheme.gradientStart,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}
