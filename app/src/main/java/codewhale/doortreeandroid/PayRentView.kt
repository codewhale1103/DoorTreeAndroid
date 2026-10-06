package codewhale.doortreeandroid

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import codewhale.doortreeandroid.ui.theme.DoorTreeTheme
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun PayRentView(
    tenantDataStore: TenantDataStore,
    mode: PaymentViewMode = PaymentViewMode.Rent,
    onModeChange: (PaymentViewMode) -> Unit = {}
) {
    var activeMode by remember { mutableStateOf(mode) }
    val hasParking = tenantDataStore.tenantRecord?.parkingInfo != null || tenantDataStore.parkingEntries.isNotEmpty()
    val isParkingMode = activeMode == PaymentViewMode.Parking
    val paymentMethods = if (isParkingMode) parkingPaymentMethods() else tenantDataStore.paymentMethods
    val scope = rememberCoroutineScope()
    var selectedMethodId by remember { mutableStateOf<String?>(null) }
    var hasUserSelectedMethod by remember { mutableStateOf(false) }
    var lastSyncedPreferredMethodId by remember { mutableStateOf<String?>(null) }
    var checkoutRequest by remember { mutableStateOf<HostedCheckoutRequest?>(null) }
    var transferDetails by remember { mutableStateOf<InteracTransferDetails?>(null) }
    var isStartingPaymentFlow by remember { mutableStateOf(false) }
    var paymentFlowMessage by remember { mutableStateOf<String?>(null) }
    var managementPrompt by remember { mutableStateOf<SavedPaymentManagementPrompt?>(null) }
    val preferredSelectedMethodId =
        paymentMethodIdForKind(
            paymentMethods = paymentMethods,
            kind = if (isParkingMode) {
                PaymentMethodItem.Kind.ManualMonthly
            } else {
                preferredSelectedPaymentKind(tenantDataStore)
            }
        ) ?: paymentMethods.firstOrNull { !isPaymentMethodDisabled(tenantDataStore, it.kind, activeMode) }?.id
            ?: paymentMethods.firstOrNull()?.id

    fun resetPaymentSelection() {
        selectedMethodId = null
        hasUserSelectedMethod = false
        lastSyncedPreferredMethodId = null
        paymentFlowMessage = null
    }

    suspend fun continuePaymentFlow(
        kind: PaymentMethodItem.Kind,
        managementMode: Boolean = false
    ) {
        paymentFlowMessage = null
        isStartingPaymentFlow = true

        try {
            if (isParkingMode) {
                if (kind != PaymentMethodItem.Kind.ManualMonthly) {
                    isStartingPaymentFlow = false
                    paymentFlowMessage = "Automatic parking payments are not available yet."
                    return
                }

                val url = tenantDataStore.payableParkingEntry?.hostedCheckoutUrl
                if (url.isNullOrBlank()) {
                    isStartingPaymentFlow = false
                    paymentFlowMessage = "Stripe is still preparing the parking payment link."
                    return
                }

                isStartingPaymentFlow = false
                checkoutRequest = HostedCheckoutRequest(url = url, title = "Pay Parking")
                return
            }

            if (kind == PaymentMethodItem.Kind.OneTimeBankTransfer) {
                val details = tenantDataStore.prepareInteracTransfer()
                isStartingPaymentFlow = false
                transferDetails = details
                return
            }

            val url = tenantDataStore.startRentPaymentFlow(kind, managementMode)
            isStartingPaymentFlow = false

            if (!url.isNullOrBlank()) {
                val selectedMethod = paymentMethods.firstOrNull { it.kind == kind }
                checkoutRequest = HostedCheckoutRequest(
                    url = url,
                    title = selectedMethod?.title ?: L("tab.payments")
                )
            } else {
                val refreshedRentPayment = tenantDataStore.currentRentPayment
                paymentFlowMessage = when (kind) {
                    PaymentMethodItem.Kind.ManualMonthly -> null
                    PaymentMethodItem.Kind.AutopayCard -> L("payments.message.saved_card_activated")
                    PaymentMethodItem.Kind.AutopayBank ->
                        if (refreshedRentPayment.isBankAutopayVerificationPending) {
                            L("payments.message.saved_bank_verification_pending")
                        } else {
                            L("payments.message.saved_bank_activated")
                        }
                    PaymentMethodItem.Kind.OneTimeBankTransfer -> null
                }
            }
        } catch (error: Throwable) {
            isStartingPaymentFlow = false
            paymentFlowMessage = error.message
        }
    }

    transferDetails?.let { details ->
        InteracTransferSheetView(
            details = details,
            onMarkSent = {
                tenantDataStore.markInteracTransferSent(details.id)
            },
            onDismiss = { transferDetails = null }
        )
    }

    LaunchedEffect(mode, hasParking) {
        activeMode = if (hasParking) mode else PaymentViewMode.Rent
        resetPaymentSelection()
    }

    LaunchedEffect(activeMode) {
        if (!hasParking && activeMode == PaymentViewMode.Parking) {
            activeMode = PaymentViewMode.Rent
            return@LaunchedEffect
        }

        onModeChange(activeMode)
        resetPaymentSelection()
    }

    LaunchedEffect(preferredSelectedMethodId, paymentMethods) {
        val hasValidSelection = selectedMethodId != null && paymentMethods.any {
            it.id == selectedMethodId && !isPaymentMethodDisabled(tenantDataStore, it.kind, activeMode)
        }
        val preferredSelectionChanged = preferredSelectedMethodId != lastSyncedPreferredMethodId

        if (preferredSelectionChanged || !hasUserSelectedMethod || !hasValidSelection) {
            selectedMethodId = preferredSelectedMethodId
            hasUserSelectedMethod = false
            lastSyncedPreferredMethodId = preferredSelectedMethodId
        }
    }

    RefreshableScreen(
        onRefresh = { tenantDataStore.refresh() },
        modifier = Modifier
            .fillMaxSize()
            .background(DoorTreeTheme.backgroundPrimary)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .topSafeAreaPadding()
                .padding(horizontal = DoorTreeTheme.screenHorizontalPadding, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = L("tab.payments"),
                color = DoorTreeTheme.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp
            )

            if (hasParking) {
                PaymentModeSegmentedControl(
                    activeMode = activeMode,
                    rentBadgeCount = tenantDataStore.dueRentPaymentCount,
                    parkingBadgeCount = tenantDataStore.dueParkingPaymentCount,
                    onModeSelected = { selectedMode ->
                        activeMode = selectedMode
                        onModeChange(selectedMode)
                    }
                )
            }

            PayRentHeroCard(tenantDataStore = tenantDataStore, mode = activeMode)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = L("payments.method_section"),
                    color = DoorTreeTheme.textSecondary,
                    fontWeight = FontWeight.SemiBold
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlassSurface(
                            cornerRadius = 16.dp,
                            tint = DoorTreeTheme.gradientStart.copy(alpha = 0.12f)
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = paymentPreferenceSummaryTitle(
                            tenantDataStore = tenantDataStore,
                            selectedMethodId = selectedMethodId,
                            mode = activeMode
                        ),
                        color = DoorTreeTheme.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = paymentPreferenceSummarySubtitle(
                            tenantDataStore = tenantDataStore,
                            selectedMethodId = selectedMethodId,
                            mode = activeMode
                        ),
                        color = DoorTreeTheme.textSecondary
                    )
                }

                paymentMethods.forEach { method ->
                    val disabledReason = disabledReason(tenantDataStore, method.kind, activeMode)

                    PaymentMethodRow(
                        method = method,
                        isSelected = selectedMethodId == method.id,
                        isDisabled = disabledReason != null,
                        disabledMessage = disabledReason,
                        onClick = {
                            if (disabledReason != null) {
                                return@PaymentMethodRow
                            }

                            hasUserSelectedMethod = true
                            selectedMethodId = method.id
                            paymentFlowMessage = null

                            scope.launch {
                                runCatching {
                                    if (!isParkingMode && method.kind != PaymentMethodItem.Kind.OneTimeBankTransfer) {
                                        tenantDataStore.persistSharedRentPaymentSelection(method.kind)
                                    }
                                }.onFailure { error ->
                                    paymentFlowMessage = error.message
                                }

                                runCatching {
                                    if (!isParkingMode && shouldDeactivateAutopayOnSelection(tenantDataStore, method.kind)) {
                                        tenantDataStore.deactivateAutopayForOneTimePaymentIfNeeded()
                                    }

                                    if (shouldApplySelectedPaymentMethodImmediately(tenantDataStore, method.kind, isStartingPaymentFlow, activeMode)) {
                                        continuePaymentFlow(method.kind)
                                    }
                                }.onFailure { error ->
                                    paymentFlowMessage = error.message
                                }
                            }
                        }
                    )
                }
            }

            if (showsPrimaryActionButton(tenantDataStore, selectedMethodId, paymentMethods, activeMode)) {
                GradientButton(
                    title = primaryActionTitle(
                        tenantDataStore = tenantDataStore,
                        selectedMethodId = selectedMethodId,
                        paymentMethods = paymentMethods,
                        mode = activeMode,
                        isStartingPaymentFlow = isStartingPaymentFlow
                    ),
                    enabled = canStartPaymentFlow(tenantDataStore, selectedMethodId, paymentMethods, activeMode) && !isStartingPaymentFlow,
                    onClick = {
                        val selectedMethod = paymentMethods.firstOrNull { it.id == selectedMethodId } ?: return@GradientButton
                        val prompt = managementPromptFor(tenantDataStore, selectedMethod.kind, activeMode)
                        if (prompt != null) {
                            paymentFlowMessage = null
                            managementPrompt = prompt
                        } else {
                            scope.launch {
                                continuePaymentFlow(selectedMethod.kind)
                            }
                        }
                    }
                )
            }

            paymentStatusMessage(
                tenantDataStore = tenantDataStore,
                selectedMethodId = selectedMethodId,
                paymentMethods = paymentMethods,
                mode = activeMode,
                paymentFlowMessage = paymentFlowMessage
            )?.let { message ->
                Text(
                    text = message,
                    color = DoorTreeTheme.textSecondary
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = L("payments.schedule_section"),
                    color = DoorTreeTheme.textSecondary,
                    fontWeight = FontWeight.SemiBold
                )
                val entries = if (isParkingMode) tenantDataStore.parkingScheduleEntries else tenantDataStore.rentScheduleEntries
                if (entries.isEmpty()) {
                    SectionPlaceholder(
                        systemName = "calendar.badge.clock",
                        title = L("payments.schedule.empty_title"),
                        message = L("payments.schedule.empty_message")
                    )
                } else {
                    entries.forEach { entry ->
                        RentScheduleRow(entry = entry)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = L("payments.history_section"),
                    color = DoorTreeTheme.textSecondary,
                    fontWeight = FontWeight.SemiBold
                )
                val completedPayments = if (isParkingMode) parkingCompletedPayments(tenantDataStore) else tenantDataStore.completedPayments
                if (completedPayments.isEmpty()) {
                    SectionPlaceholder(
                        systemName = "clock.arrow.circlepath",
                        title = "No completed payments yet",
                        message = if (isParkingMode) "Past parking payments will appear here after they are synced." else "Past rent payments will appear here after they are synced."
                    )
                } else {
                    completedPayments.forEach { payment ->
                        PaymentRow(
                            payment = payment,
                            badgeForegroundOverride = if (payment.status == StatusBadgeStyle.Paid) DoorTreeTheme.paidText else null,
                            badgeBackgroundOverride = if (payment.status == StatusBadgeStyle.Paid) DoorTreeTheme.paidBackground else null
                        )
                    }
                }
            }
        }
    }

    checkoutRequest?.let { request ->
        HostedCheckoutSheetView(
            url = request.url,
            title = request.title,
            onDismiss = {
                checkoutRequest = null
                scope.launch {
                    tenantDataStore.refresh()
                }
            }
        )
    }

    managementPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { managementPrompt = null },
            title = {
                Text(text = prompt.title)
            },
            text = {
                Text(text = prompt.message)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        managementPrompt = null
                        scope.launch {
                            continuePaymentFlow(prompt.kind, managementMode = true)
                        }
                    }
                ) {
                    Text(text = prompt.confirmTitle)
                }
            },
            dismissButton = {
                TextButton(onClick = { managementPrompt = null }) {
                    Text(text = prompt.cancelTitle)
                }
            }
        )
    }
}

@Composable
private fun PaymentModeSegmentedControl(
    activeMode: PaymentViewMode,
    rentBadgeCount: Int,
    parkingBadgeCount: Int,
    onModeSelected: (PaymentViewMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassSurface(cornerRadius = 16.dp, tint = DoorTreeTheme.barGlassTint)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        PaymentModeSegment(
            title = "Rent",
            isSelected = activeMode == PaymentViewMode.Rent,
            badgeCount = rentBadgeCount,
            modifier = Modifier.weight(1f),
            onClick = { onModeSelected(PaymentViewMode.Rent) }
        )
        PaymentModeSegment(
            title = "Parking",
            isSelected = activeMode == PaymentViewMode.Parking,
            badgeCount = parkingBadgeCount,
            modifier = Modifier.weight(1f),
            onClick = { onModeSelected(PaymentViewMode.Parking) }
        )
    }
}

@Composable
private fun PaymentModeSegment(
    title: String,
    isSelected: Boolean,
    badgeCount: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .background(
                color = if (isSelected) DoorTreeTheme.gradientStart.copy(alpha = 0.22f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = DoorTreeTheme.textPrimary,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
        )
        if (badgeCount > 0) {
            NotificationCountBadge(
                count = badgeCount,
                modifier = Modifier.padding(start = 7.dp)
            )
        }
    }
}

@Composable
private fun PayRentHeroCard(
    tenantDataStore: TenantDataStore,
    mode: PaymentViewMode
) {
    val lease = tenantDataStore.leaseDetails
    val currentRentEntry = tenantDataStore.nextRentEntry ?: tenantDataStore.currentRentEntry
    val currentParkingEntry = tenantDataStore.nextParkingEntry ?: tenantDataStore.currentParkingEntry
    val leaseEnded = tenantDataStore.tenantRecord?.leaseEnded == true
    val isParkingMode = mode == PaymentViewMode.Parking

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassSurface(cornerRadius = 18.dp, tint = DoorTreeTheme.gradientStart.copy(alpha = 0.18f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (isParkingMode) {
                currentParkingEntry?.sortDate?.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())) ?: "Parking"
            } else {
                currentRentEntry?.sortDate?.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
                    ?: L("payments.hero.title")
            },
            color = DoorTreeTheme.textPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = if (isParkingMode) {
                currentParkingEntry?.balance ?: currentParkingEntry?.amount ?: tenantDataStore.tenantRecord?.parkingInfo?.price ?: "-"
            } else {
                lease.monthlyRent
            },
            color = DoorTreeTheme.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isParkingMode) {
                    currentParkingEntry?.dueDateDisplay ?: "Current parking"
            } else {
                currentRentEntry?.dueDateDisplay
                    ?: if (lease.endDate == "-") "Current monthly rent" else "Lease ends ${lease.endDate}"
            },
                color = DoorTreeTheme.textSecondary
            )
            Spacer(modifier = Modifier.weight(1f))
            StatusBadge(
                status = if (isParkingMode) {
                    currentParkingEntry?.statusStyle ?: StatusBadgeStyle.Due
                } else {
                    currentRentEntry?.statusStyle ?: if (leaseEnded) StatusBadgeStyle.Completed else StatusBadgeStyle.Due
                },
                label = if (isParkingMode) {
                    currentParkingEntry?.statusLabel ?: "Current parking"
                } else {
                    currentRentEntry?.statusLabel ?: if (leaseEnded) "Lease ended" else "Current rent"
                }
            )
        }
    }
}

private data class HostedCheckoutRequest(
    val url: String,
    val title: String
)

private data class SavedPaymentManagementPrompt(
    val cancelTitle: String,
    val confirmTitle: String,
    val kind: PaymentMethodItem.Kind,
    val message: String,
    val title: String
)

private fun parkingPaymentMethods(): List<PaymentMethodItem> = listOf(
    PaymentMethodItem(
        id = "manual-parking",
        title = L("payments.method.manual.title"),
        subtitle = "Open the payment portal and pay parking manually.",
        icon = "creditcard.fill",
        kind = PaymentMethodItem.Kind.ManualMonthly
    ),
    PaymentMethodItem(
        id = "parking-autopay-card",
        title = L("payments.method.card.title"),
        subtitle = "Automatic card payments for parking are not available yet.",
        icon = "creditcard.circle.fill",
        kind = PaymentMethodItem.Kind.AutopayCard
    ),
    PaymentMethodItem(
        id = "parking-autopay-bank",
        title = L("payments.method.bank.title"),
        subtitle = "Automatic bank debit for parking is not available yet.",
        icon = "building.columns.fill",
        kind = PaymentMethodItem.Kind.AutopayBank
    )
)

private fun parkingCompletedPayments(tenantDataStore: TenantDataStore): List<PaymentItem> =
    tenantDataStore.parkingEntries
        .filter { it.isPaid }
        .sortedByDescending { it.sortDate ?: java.time.LocalDate.MIN }
        .map { entry ->
            PaymentItem(
                month = entry.sortDate?.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())) ?: entry.dueDateDisplay,
                date = entry.dueDateDisplay,
                amount = entry.amount,
                status = StatusBadgeStyle.Paid
            )
        }

private fun selectedHostedCheckoutUrl(
    tenantDataStore: TenantDataStore,
    selectedMethodId: String?,
    paymentMethods: List<PaymentMethodItem> = tenantDataStore.paymentMethods,
    mode: PaymentViewMode = PaymentViewMode.Rent
): String? {
    if (mode == PaymentViewMode.Parking) {
        val selectedMethod = paymentMethods.firstOrNull { it.id == selectedMethodId } ?: return null
        return if (selectedMethod.kind == PaymentMethodItem.Kind.ManualMonthly) {
            tenantDataStore.payableParkingEntry?.hostedCheckoutUrl?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    val selectedMethod = paymentMethods.firstOrNull { it.id == selectedMethodId } ?: return null
    if (selectedMethod.kind != PaymentMethodItem.Kind.ManualMonthly) {
        return null
    }
    return tenantDataStore.hostedCheckoutUrl(PaymentMethodItem.Kind.ManualMonthly)?.takeIf { it.isNotBlank() }
}

private fun preferredSelectedPaymentKind(tenantDataStore: TenantDataStore): PaymentMethodItem.Kind {
    return when {
        tenantDataStore.syncedPreferredPaymentSelectionKind == PaymentMethodItem.Kind.ManualMonthly &&
            !isPaymentMethodDisabled(tenantDataStore, PaymentMethodItem.Kind.ManualMonthly) ->
            PaymentMethodItem.Kind.ManualMonthly
        tenantDataStore.syncedPreferredPaymentSelectionKind == PaymentMethodItem.Kind.OneTimeBankTransfer &&
            !isPaymentMethodDisabled(tenantDataStore, PaymentMethodItem.Kind.OneTimeBankTransfer) &&
            tenantDataStore.interacTransferDetails != null ->
            PaymentMethodItem.Kind.OneTimeBankTransfer
        tenantDataStore.syncedPreferredPaymentSelectionKind == PaymentMethodItem.Kind.AutopayCard &&
            !isPaymentMethodDisabled(tenantDataStore, PaymentMethodItem.Kind.AutopayCard) ->
            PaymentMethodItem.Kind.AutopayCard
        tenantDataStore.syncedPreferredPaymentSelectionKind == PaymentMethodItem.Kind.AutopayBank &&
            !isPaymentMethodDisabled(tenantDataStore, PaymentMethodItem.Kind.AutopayBank) ->
            PaymentMethodItem.Kind.AutopayBank
        tenantDataStore.isCardRentPaymentActive -> PaymentMethodItem.Kind.AutopayCard
        tenantDataStore.isBankRentPaymentActive || tenantDataStore.isBankRentPaymentVerificationPending ->
            PaymentMethodItem.Kind.AutopayBank
        !isPaymentMethodDisabled(tenantDataStore, PaymentMethodItem.Kind.ManualMonthly) ->
            PaymentMethodItem.Kind.ManualMonthly
        !isPaymentMethodDisabled(tenantDataStore, PaymentMethodItem.Kind.OneTimeBankTransfer) &&
            tenantDataStore.interacTransferDetails != null ->
            PaymentMethodItem.Kind.OneTimeBankTransfer
        !isPaymentMethodDisabled(tenantDataStore, PaymentMethodItem.Kind.AutopayBank) ->
            PaymentMethodItem.Kind.AutopayBank
        else -> PaymentMethodItem.Kind.AutopayCard
    }
}

private fun paymentMethodIdForKind(
    paymentMethods: List<PaymentMethodItem>,
    kind: PaymentMethodItem.Kind
): String? {
    return paymentMethods.firstOrNull { it.kind == kind }?.id
}

private fun canStartPaymentFlow(
    tenantDataStore: TenantDataStore,
    selectedMethodId: String?,
    paymentMethods: List<PaymentMethodItem> = tenantDataStore.paymentMethods,
    mode: PaymentViewMode = PaymentViewMode.Rent
): Boolean {
    val selectedMethod = paymentMethods.firstOrNull { it.id == selectedMethodId } ?: return false
    if (isPaymentMethodDisabled(tenantDataStore, selectedMethod.kind, mode)) {
        return false
    }
    if (mode == PaymentViewMode.Parking) {
        return selectedMethod.kind == PaymentMethodItem.Kind.ManualMonthly &&
            selectedHostedCheckoutUrl(tenantDataStore, selectedMethodId, paymentMethods, mode) != null
    }

    return when (selectedMethod.kind) {
        PaymentMethodItem.Kind.ManualMonthly ->
            selectedHostedCheckoutUrl(tenantDataStore, selectedMethodId) != null &&
                tenantDataStore.currentRentEntry?.isAutopayProcessing != true

        PaymentMethodItem.Kind.AutopayCard -> tenantDataStore.isCardRentPaymentActive
        PaymentMethodItem.Kind.AutopayBank ->
            tenantDataStore.isBankRentPaymentActive || tenantDataStore.isBankRentPaymentVerificationPending
        PaymentMethodItem.Kind.OneTimeBankTransfer -> tenantDataStore.interacTransferDetails != null
    }
}

private fun showsPrimaryActionButton(
    tenantDataStore: TenantDataStore,
    selectedMethodId: String?,
    paymentMethods: List<PaymentMethodItem> = tenantDataStore.paymentMethods,
    mode: PaymentViewMode = PaymentViewMode.Rent
): Boolean {
    val selectedMethod = paymentMethods.firstOrNull { it.id == selectedMethodId } ?: return false
    if (isPaymentMethodDisabled(tenantDataStore, selectedMethod.kind, mode)) {
        return false
    }
    if (mode == PaymentViewMode.Parking) {
        return selectedMethod.kind == PaymentMethodItem.Kind.ManualMonthly
    }

    return when (selectedMethod.kind) {
        PaymentMethodItem.Kind.ManualMonthly -> true
        PaymentMethodItem.Kind.AutopayCard -> tenantDataStore.isCardRentPaymentActive
        PaymentMethodItem.Kind.AutopayBank ->
            tenantDataStore.isBankRentPaymentActive || tenantDataStore.isBankRentPaymentVerificationPending
        PaymentMethodItem.Kind.OneTimeBankTransfer -> true
    }
}

private fun shouldApplySelectedPaymentMethodImmediately(
    tenantDataStore: TenantDataStore,
    kind: PaymentMethodItem.Kind,
    isStartingPaymentFlow: Boolean,
    mode: PaymentViewMode = PaymentViewMode.Rent
): Boolean {
    if (mode == PaymentViewMode.Parking) {
        return false
    }

    if (isStartingPaymentFlow) {
        return false
    }

    if (isPaymentMethodDisabled(tenantDataStore, kind, mode)) {
        return false
    }

    return when (kind) {
        PaymentMethodItem.Kind.ManualMonthly -> false
        PaymentMethodItem.Kind.AutopayCard -> !tenantDataStore.isCardRentPaymentActive
        PaymentMethodItem.Kind.AutopayBank ->
            !tenantDataStore.isBankRentPaymentActive && !tenantDataStore.isBankRentPaymentVerificationPending
        PaymentMethodItem.Kind.OneTimeBankTransfer -> true
    }
}

private fun shouldDeactivateAutopayOnSelection(
    tenantDataStore: TenantDataStore,
    kind: PaymentMethodItem.Kind
): Boolean {
    return when (kind) {
        PaymentMethodItem.Kind.ManualMonthly ->
            tenantDataStore.isCardRentPaymentActive ||
                tenantDataStore.isBankRentPaymentActive ||
                tenantDataStore.isBankRentPaymentVerificationPending ||
                (tenantDataStore.currentRentPayment.pendingSetupMethodType == "card" && !tenantDataStore.hasSavedCardRentPaymentProfile) ||
                (tenantDataStore.currentRentPayment.pendingSetupMethodType == "acss_debit" && !tenantDataStore.hasSavedBankRentPaymentProfile)

        PaymentMethodItem.Kind.AutopayCard,
        PaymentMethodItem.Kind.AutopayBank,
        PaymentMethodItem.Kind.OneTimeBankTransfer -> false
    }
}

private fun primaryActionTitle(
    tenantDataStore: TenantDataStore,
    selectedMethodId: String?,
    isStartingPaymentFlow: Boolean,
    paymentMethods: List<PaymentMethodItem> = tenantDataStore.paymentMethods,
    mode: PaymentViewMode = PaymentViewMode.Rent
): String {
    if (isStartingPaymentFlow) {
        return L("payments.action.working")
    }

    val selectedMethod = paymentMethods.firstOrNull { it.id == selectedMethodId }
    if (mode == PaymentViewMode.Parking) {
        disabledReason(tenantDataStore, selectedMethod?.kind, mode)?.let { return it }
        return if (selectedMethod?.kind == PaymentMethodItem.Kind.ManualMonthly) {
            L("payments.action.continue_to_stripe")
        } else {
            L("payments.pay_now")
        }
    }

    val currentRentPayment = tenantDataStore.currentRentPayment
    val hasSavedCardProfile = tenantDataStore.hasSavedCardRentPaymentProfile
    val hasSavedBankProfile = tenantDataStore.hasSavedBankRentPaymentProfile
    val isPendingCardSetup = currentRentPayment.pendingSetupMethodType == "card" && !hasSavedCardProfile
    val isPendingBankSetup = currentRentPayment.pendingSetupMethodType == "acss_debit" && !hasSavedBankProfile

    disabledReason(tenantDataStore, selectedMethod?.kind, mode)?.let { return it }

    return when (selectedMethod?.kind) {
        PaymentMethodItem.Kind.ManualMonthly -> L("payments.action.continue_to_stripe")
        PaymentMethodItem.Kind.AutopayCard -> {
            if (isPendingCardSetup) {
                L("payments.action.continue_card_setup")
            } else if (tenantDataStore.isCardRentPaymentActive) {
                L("payments.action.manage_saved_card")
            } else if (hasSavedCardProfile) {
                L("payments.action.use_saved_card")
            } else {
                L("payments.action.setup_card")
            }
        }

        PaymentMethodItem.Kind.AutopayBank -> {
            if (isPendingBankSetup) {
                L("payments.action.continue_bank_setup")
            } else if (tenantDataStore.isBankRentPaymentActive || tenantDataStore.isBankRentPaymentVerificationPending) {
                L("payments.action.manage_saved_bank")
            } else if (hasSavedBankProfile) {
                L("payments.action.use_saved_bank")
            } else {
                L("payments.action.setup_bank")
            }
        }

        PaymentMethodItem.Kind.OneTimeBankTransfer -> L("payments.action.view_transfer_details")

        null -> L("payments.pay_now")
    }
}

private fun paymentStatusMessage(
    tenantDataStore: TenantDataStore,
    selectedMethodId: String?,
    paymentMethods: List<PaymentMethodItem> = tenantDataStore.paymentMethods,
    mode: PaymentViewMode = PaymentViewMode.Rent,
    paymentFlowMessage: String?
): String? {
    if (!paymentFlowMessage.isNullOrBlank()) {
        return paymentFlowMessage
    }

    val currentRentPayment = tenantDataStore.currentRentPayment
    currentRentPayment.lastSetupError?.takeIf { it.isNotBlank() }?.let { return it }
    currentRentPayment.lastError?.takeIf { it.isNotBlank() }?.let { return it }

    val selectedMethod = paymentMethods.firstOrNull { it.id == selectedMethodId } ?: return null
    if (mode == PaymentViewMode.Parking) {
        disabledReason(tenantDataStore, selectedMethod.kind, mode)?.let { return it }
        return if (
            selectedMethod.kind == PaymentMethodItem.Kind.ManualMonthly &&
            selectedHostedCheckoutUrl(tenantDataStore, selectedMethodId, paymentMethods, mode).isNullOrBlank()
        ) {
            "Stripe is still preparing the parking payment link."
        } else {
            null
        }
    }

    val hasSavedCardProfile = tenantDataStore.hasSavedCardRentPaymentProfile
    val hasSavedBankProfile = tenantDataStore.hasSavedBankRentPaymentProfile
    val cardLabel = tenantDataStore.savedCardPaymentMethodLabel
    val bankLabel = tenantDataStore.savedBankPaymentMethodLabel
    val isPendingCardSetup = currentRentPayment.pendingSetupMethodType == "card" && !hasSavedCardProfile
    val isPendingBankSetup = currentRentPayment.pendingSetupMethodType == "acss_debit" && !hasSavedBankProfile
    return when (selectedMethod.kind) {
        PaymentMethodItem.Kind.ManualMonthly -> when {
            tenantDataStore.currentRentEntry?.isAutopayProcessing == true ->
                L("payments.error.autopay_processing")

            selectedHostedCheckoutUrl(tenantDataStore, selectedMethodId).isNullOrBlank() ->
                L("payments.error.link_preparing")

            else -> null
        }

        PaymentMethodItem.Kind.AutopayCard -> {
            if (isPendingCardSetup) {
                L("payments.method.card.subtitle.setup_pending")
            } else if (tenantDataStore.isCardRentPaymentActive) {
                if (!cardLabel.isNullOrBlank()) {
                    LF("payments.message.card.active_with_label", cardLabel)
                } else {
                    L("payments.message.card.active_default")
                }
            } else if (hasSavedCardProfile) {
                if (!cardLabel.isNullOrBlank()) {
                    LF("payments.message.card.saved_with_label", cardLabel)
                } else {
                    L("payments.message.card.saved_default")
                }
            } else if (tenantDataStore.hasCardRentPaymentManagement) {
                L("payments.message.card.management_available")
            } else {
                null
            }
        }

        PaymentMethodItem.Kind.AutopayBank -> when {
            isPendingBankSetup ->
                L("payments.method.bank.subtitle.setup_pending")

            tenantDataStore.isBankRentPaymentVerificationPending ->
                L("payments.method.bank.subtitle.verification_pending")

            tenantDataStore.isBankRentPaymentActive ->
                if (!bankLabel.isNullOrBlank()) {
                    LF("payments.message.bank.active_with_label", bankLabel)
                } else {
                    L("payments.message.bank.active_default")
                }

            hasSavedBankProfile ->
                if (!bankLabel.isNullOrBlank()) {
                    LF("payments.message.bank.saved_with_label", bankLabel)
                } else {
                    L("payments.message.bank.saved_default")
                }

            tenantDataStore.hasBankRentPaymentManagement ->
                L("payments.message.bank.management_available")

            else -> null
        }

        PaymentMethodItem.Kind.OneTimeBankTransfer ->
            tenantDataStore.interacTransferDetails?.let {
                LF("payments.message.bank_transfer.recipient", it.recipientEmail)
            }
    }
}

private fun managementPromptFor(
    tenantDataStore: TenantDataStore,
    kind: PaymentMethodItem.Kind,
    mode: PaymentViewMode = PaymentViewMode.Rent
): SavedPaymentManagementPrompt? {
    if (mode == PaymentViewMode.Parking || isPaymentMethodDisabled(tenantDataStore, kind, mode)) {
        return null
    }

    return when (kind) {
        PaymentMethodItem.Kind.ManualMonthly -> null
        PaymentMethodItem.Kind.AutopayCard -> {
            if (!tenantDataStore.isCardRentPaymentActive) {
                return null
            }

            val message = if (!tenantDataStore.savedCardPaymentMethodLabel.isNullOrBlank()) {
                LF("payments.alert.saved_card.message_with_label", tenantDataStore.savedCardPaymentMethodLabel)
            } else {
                L("payments.alert.saved_card.message_default")
            }

            SavedPaymentManagementPrompt(
                cancelTitle = L("payments.alert.saved_card.cancel"),
                confirmTitle = L("payments.alert.saved_card.confirm"),
                kind = kind,
                message = message,
                title = L("payments.alert.saved_card.title")
            )
        }

        PaymentMethodItem.Kind.AutopayBank -> {
            if (!tenantDataStore.isBankRentPaymentActive && !tenantDataStore.isBankRentPaymentVerificationPending) {
                return null
            }

            val message = if (!tenantDataStore.savedBankPaymentMethodLabel.isNullOrBlank()) {
                LF("payments.alert.saved_bank.message_with_label", tenantDataStore.savedBankPaymentMethodLabel)
            } else {
                L("payments.alert.saved_bank.message_default")
            }

            SavedPaymentManagementPrompt(
                cancelTitle = L("payments.alert.saved_bank.cancel"),
                confirmTitle = L("payments.alert.saved_bank.confirm"),
                kind = kind,
                message = message,
                title = L("payments.alert.saved_bank.title")
            )
        }
        PaymentMethodItem.Kind.OneTimeBankTransfer -> null
    }
}

private fun isPaymentMethodDisabled(
    tenantDataStore: TenantDataStore,
    kind: PaymentMethodItem.Kind,
    mode: PaymentViewMode = PaymentViewMode.Rent
): Boolean = disabledReason(tenantDataStore, kind, mode) != null

private fun paymentPreferenceSummaryTitle(
    tenantDataStore: TenantDataStore,
    selectedMethodId: String?,
    mode: PaymentViewMode = PaymentViewMode.Rent
): String {
    if (mode == PaymentViewMode.Parking) {
        return "Manual parking payment"
    }

    val selectedMethod = tenantDataStore.paymentMethods.firstOrNull { it.id == selectedMethodId }
    if (selectedMethod != null && !isPaymentMethodDisabled(tenantDataStore, selectedMethod.kind)) {
        return selectedMethod.title
    }

    return when (disabledCurrentPreferenceKind(tenantDataStore)) {
        PaymentMethodItem.Kind.ManualMonthly,
        PaymentMethodItem.Kind.AutopayCard -> L("payments.preference.title.card_disabled")
        PaymentMethodItem.Kind.AutopayBank -> L("payments.preference.title.bank_disabled")
        PaymentMethodItem.Kind.OneTimeBankTransfer,
        null -> tenantDataStore.currentRentPayment.currentPreferenceTitle
    }
}

private fun paymentPreferenceSummarySubtitle(
    tenantDataStore: TenantDataStore,
    selectedMethodId: String?,
    mode: PaymentViewMode = PaymentViewMode.Rent
): String {
    if (mode == PaymentViewMode.Parking) {
        return "Parking payments use the parking schedule connected to your account."
    }

    val selectedMethod = tenantDataStore.paymentMethods.firstOrNull { it.id == selectedMethodId }
    if (selectedMethod != null) {
        disabledReason(tenantDataStore, selectedMethod.kind)?.let { return it }
        return selectedMethod.subtitle
    }

    val disabledKind = disabledCurrentPreferenceKind(tenantDataStore)
    return disabledReason(tenantDataStore, disabledKind)
        ?: tenantDataStore.currentRentPayment.currentPreferenceSubtitle
}

private fun disabledCurrentPreferenceKind(tenantDataStore: TenantDataStore): PaymentMethodItem.Kind? {
    val rentPayment = tenantDataStore.currentRentPayment

    if ((rentPayment.pendingSetupMethodType == "card" ||
            rentPayment.selectedMethodType == "card" ||
            rentPayment.paymentMethodType == "card") &&
        !tenantDataStore.isCreditCardRentCollectionEnabled
    ) {
        return PaymentMethodItem.Kind.AutopayCard
    }

    if ((rentPayment.pendingSetupMethodType == "acss_debit" ||
            rentPayment.selectedMethodType == "acss_debit" ||
            rentPayment.paymentMethodType == "acss_debit") &&
        !tenantDataStore.isBankDebitsRentCollectionEnabled
    ) {
        return PaymentMethodItem.Kind.AutopayBank
    }

    return null
}

private fun disabledReason(
    tenantDataStore: TenantDataStore,
    kind: PaymentMethodItem.Kind?,
    mode: PaymentViewMode = PaymentViewMode.Rent
): String? {
    if (mode == PaymentViewMode.Parking) {
        return when (kind) {
            PaymentMethodItem.Kind.AutopayCard,
            PaymentMethodItem.Kind.AutopayBank -> "Parking autopay is not available yet."
            PaymentMethodItem.Kind.ManualMonthly,
            PaymentMethodItem.Kind.OneTimeBankTransfer,
            null -> null
        }
    }

    return when (kind) {
        PaymentMethodItem.Kind.ManualMonthly,
        PaymentMethodItem.Kind.AutopayCard ->
            if (tenantDataStore.isCreditCardRentCollectionEnabled) null else L("payments.disabled.credit_card")

        PaymentMethodItem.Kind.AutopayBank ->
            if (tenantDataStore.isBankDebitsRentCollectionEnabled) null else L("payments.disabled.bank_debit")

        PaymentMethodItem.Kind.OneTimeBankTransfer -> null

        null -> null
    }
}

@Composable
private fun PaymentMethodRow(
    method: PaymentMethodItem,
    isSelected: Boolean,
    isDisabled: Boolean,
    disabledMessage: String?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassSurface(
                cornerRadius = 16.dp,
                interactive = !isDisabled,
                tint = if (isSelected) DoorTreeTheme.gradientStart.copy(alpha = 0.18f) else Color.Unspecified
            )
            .clickable(enabled = !isDisabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(DoorTreeTheme.paidBackground.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Icon(systemIcon(method.icon), contentDescription = null, tint = DoorTreeTheme.gradientStart)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = method.title, color = DoorTreeTheme.textPrimary)
                    Text(text = method.subtitle, color = DoorTreeTheme.textSecondary)
                }
            }

            Box(
                modifier = Modifier
                    .background(
                        color = if (isSelected) DoorTreeTheme.gradientStart else Color.Transparent,
                        shape = RoundedCornerShape(999.dp)
                    )
                    .size(18.dp)
                    .padding(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            color = if (isSelected) DoorTreeTheme.gradientStart else Color.Transparent,
                            shape = RoundedCornerShape(999.dp)
                        )
                        .padding(6.dp)
                )
            }
        }

        if (isDisabled && disabledMessage != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(DoorTreeTheme.backgroundPrimary.copy(alpha = 0.82f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = disabledMessage,
                    color = DoorTreeTheme.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
