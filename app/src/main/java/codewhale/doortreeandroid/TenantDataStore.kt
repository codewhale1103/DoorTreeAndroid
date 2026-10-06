package codewhale.doortreeandroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import codewhale.doortreeandroid.ui.theme.DoorTreeTheme
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.ParsePosition
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

data class MaintenancePhotoUpload(
    val bytes: ByteArray,
    val contentType: String = "image/jpeg",
    val fileExtension: String = "jpg"
)

data class TenantRecord(
    val uid: String,
    val bathrooms: Double?,
    val bedrooms: Double?,
    val city: String,
    val email: String,
    val firstName: String,
    val landlordUID: String,
    val lastName: String,
    val lateFeeAmount: Double?,
    val leaseEnd: String,
    val leaseEnded: Boolean,
    val leaseStart: String,
    val postalCode: String,
    val propertyId: String,
    val propertyManager: String,
    val propertyName: String,
    val province: String,
    val rentAmount: Double?,
    val securityDeposit: Double?,
    val squareFootage: Double?,
    val streetAddress: String,
    val unitNumber: String,
    val userType: String,
    val parkingInfo: TenantParkingInfo?,
    val rentPayment: TenantRentPaymentState,
    val stripeConnectAssociation: TenantStripeConnectAssociationState
) {
    val tenantProfile: TenantProfile
        get() = TenantProfile(
            name = displayName,
            email = email,
            phone = "",
            initials = initials(displayName)
        )

    val propertyInfo: PropertyInfo
        get() = PropertyInfo(
            name = propertyDisplayName,
            unit = unitDisplayName,
            address = streetAddressLine,
            city = cityLine
        )

    val leaseDetails: LeaseDetails
        get() = LeaseDetails(
            startDate = formattedDate(leaseStart),
            endDate = formattedDate(leaseEnd),
            monthlyRent = formattedCurrency(rentAmount),
            unitLabel = propertyInfo.subtitle,
            renewalNotice = renewalSummary
        )

    val propertyManagerDisplayName: String
        get() = propertyManager.trim().ifBlank { "Property Manager" }

    val propertyManagerInitials: String
        get() = initials(propertyManagerDisplayName)

    private val displayName: String
        get() = listOf(firstName.trim(), lastName.trim())
            .filter(String::isNotBlank)
            .joinToString(" ")
            .ifBlank { email.ifBlank { "Tenant" } }

    private val propertyDisplayName: String
        get() = propertyName.trim().ifBlank { "Property" }

    private val unitDisplayName: String
        get() = unitNumber.trim().let { if (it.isBlank()) "Unit -" else "Unit $it" }

    private val streetAddressLine: String
        get() = streetAddress.trim().ifBlank { unitDisplayName }

    private val cityLine: String
        get() {
            val location = listOf(city.trim(), province.trim()).filter(String::isNotBlank).joinToString(", ")
            val postal = postalCode.trim()
            return when {
                location.isBlank() -> postal
                postal.isBlank() -> location
                else -> "$location $postal"
            }
        }

    private val renewalSummary: String
        get() {
            val formattedEnd = formattedDate(leaseEnd)
            return when {
                leaseEnded && formattedEnd == "-" -> "Lease has ended."
                leaseEnded -> "Lease ended on $formattedEnd."
                formattedEnd == "-" -> "Lease is active."
                else -> "Lease active until $formattedEnd."
            }
        }

    companion object {
        private val currencyFormatter = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        private val outputFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

        fun fromSnapshot(uid: String, snapshot: JsonObject): TenantRecord {
            return TenantRecord(
                uid = uid,
                bathrooms = snapshot["bathrooms"].doubleValue(),
                bedrooms = snapshot["bedrooms"].doubleValue(),
                city = snapshot["city"].stringValue(),
                email = snapshot["email"].stringValue(),
                firstName = snapshot["firstName"].stringValue(),
                landlordUID = snapshot["landlordUID"].stringValue(),
                lastName = snapshot["lastName"].stringValue(),
                lateFeeAmount = snapshot["lateFeeAmount"].doubleValue(),
                leaseEnd = snapshot["leaseEnd"].stringValue(),
                leaseEnded = snapshot["leaseEnded"]?.jsonPrimitive?.booleanOrNull ?: false,
                leaseStart = snapshot["leaseStart"].stringValue(),
                postalCode = snapshot["postalCode"].stringValue(),
                propertyId = snapshot["propertyId"].stringValue(),
                propertyManager = snapshot["propertyManager"].stringValue(),
                propertyName = snapshot["propertyName"].stringValue(),
                province = snapshot["province"].stringValue(),
                rentAmount = snapshot["rentAmount"].doubleValue(),
                securityDeposit = snapshot["securityDeposit"].doubleValue(),
                squareFootage = snapshot["squarefootage"].doubleValue(),
                streetAddress = snapshot["streetAddress"].stringValue(),
                unitNumber = snapshot["unitNumber"].stringValue(),
                userType = snapshot["userType"].stringValue(),
                parkingInfo = parkingInfo(snapshot["parking"]),
                rentPayment = rentPaymentState(snapshot["rentPayment"]),
                stripeConnectAssociation = stripeConnectAssociation(snapshot["stripeConnect"])
            )
        }

        private fun parkingInfo(value: JsonElement?): TenantParkingInfo? {
            val snapshot = value as? JsonObject ?: return null
            val price = snapshot["price"].doubleValue()
            return TenantParkingInfo(
                unit = snapshot["unit"].stringValue(),
                priceValue = price,
                price = formattedCurrency(price),
                type = snapshot["type"].stringValue(),
                startDate = formattedDate(snapshot["startDate"].stringValue()),
                endDate = formattedDate(snapshot["endDate"].stringValue())
            )
        }

        private fun rentPaymentState(value: JsonElement?): TenantRentPaymentState {
            val snapshot = value as? JsonObject ?: return TenantRentPaymentState.Empty
            val paymentMethodType = snapshot["paymentMethodType"].stringValue().ifBlank { null }
            val pendingSetupMethodType = snapshot["pendingSetupMethodType"].stringValue().ifBlank { null }
            val selectedMethodType = snapshot["selectedMethodType"].stringValue().ifBlank { "manual" }
            val status = snapshot["status"].stringValue().ifBlank { "manual" }

            return TenantRentPaymentState(
                createdAt = snapshot["createdAt"].stringValue(),
                lastAutopayAt = snapshot["lastAutopayAt"].stringValue().ifBlank { null },
                lastAutopayChargeId = snapshot["lastAutopayChargeId"].stringValue().ifBlank { null },
                lastAutopayPaymentIntentId = snapshot["lastAutopayPaymentIntentId"].stringValue().ifBlank { null },
                lastAutopayStatus = snapshot["lastAutopayStatus"].stringValue().ifBlank { null },
                lastAutopaySucceededAt = snapshot["lastAutopaySucceededAt"].stringValue().ifBlank { null },
                lastError = snapshot["lastError"].stringValue().ifBlank { null },
                lastSetupAt = snapshot["lastSetupAt"].stringValue().ifBlank { null },
                lastSetupError = snapshot["lastSetupError"].stringValue().ifBlank { null },
                padAuthorizationEmail = snapshot["padAuthorizationEmail"].stringValue().ifBlank { null },
                padAuthorizationExpiresAt = snapshot["padAuthorizationExpiresAt"].stringValue().ifBlank { null },
                padAuthorizationId = snapshot["padAuthorizationId"].stringValue().ifBlank { null },
                padAuthorizationSentAt = snapshot["padAuthorizationSentAt"].stringValue().ifBlank { null },
                paymentMethodBrand = snapshot["paymentMethodBrand"].stringValue().ifBlank { null },
                paymentMethodLabel = snapshot["paymentMethodLabel"].stringValue().ifBlank { null },
                paymentMethodLast4 = snapshot["paymentMethodLast4"].stringValue().ifBlank { null },
                paymentMethodType = paymentMethodType,
                pendingSetupCheckoutSessionId = snapshot["pendingSetupCheckoutSessionId"].stringValue().ifBlank { null },
                pendingSetupMethodType = pendingSetupMethodType,
                selectedMethodType = selectedMethodType,
                status = status,
                stripeCustomerId = snapshot["stripeCustomerId"].stringValue().ifBlank { null },
                stripeMandateId = snapshot["stripeMandateId"].stringValue().ifBlank { null },
                stripePaymentMethodId = snapshot["stripePaymentMethodId"].stringValue().ifBlank { null },
                stripeSetupIntentId = snapshot["stripeSetupIntentId"].stringValue().ifBlank { null },
                updatedAt = snapshot["updatedAt"].stringValue()
            )
        }

        private data class StripeConnectMethodNodeState(
            val accountId: String?,
            val associated: Boolean,
            val isActive: Boolean,
            val landlordUserId: String?,
            val linkedAt: String?,
            val paymentMethodBrand: String?,
            val paymentMethodLabel: String?,
            val paymentMethodLast4: String?,
            val status: String?,
            val stripeCustomerId: String?,
            val stripeMandateId: String?,
            val stripePaymentMethodId: String?,
            val stripeSetupIntentId: String?,
            val tenantUserId: String?,
            val type: String?,
            val updatedAt: String?
        )

        private fun normalizedStripeConnectStatus(value: JsonElement?): String? {
            return value.stringValue()
                .ifBlank { "" }
                .let { normalized ->
                    when (normalized) {
                        "", "manual" -> null
                        else -> normalized
                    }
                }
        }

        private fun stripeConnectMethodNodeState(
            value: JsonObject?,
            methodType: String
        ): StripeConnectMethodNodeState? {
            val snapshot = value ?: return null
            val status = normalizedStripeConnectStatus(
                snapshot[if (methodType == "card") "cardStatus" else "bankStatus"] ?: snapshot["status"]
            )
            val stripeCustomerId = snapshot["stripeCustomerId"].stringValue().ifBlank { null }
            val stripePaymentMethodId =
                (snapshot[if (methodType == "card") "cardStripePaymentMethodId" else "bankStripePaymentMethodId"]
                    ?: snapshot["stripePaymentMethodId"])
                    .stringValue()
                    .ifBlank { null }
            val stripeSetupIntentId =
                (snapshot[if (methodType == "card") "cardStripeSetupIntentId" else "bankStripeSetupIntentId"]
                    ?: snapshot["stripeSetupIntentId"])
                    .stringValue()
                    .ifBlank { null }
            val stripeMandateId =
                if (methodType == "acss_debit") {
                    (snapshot["bankStripeMandateId"] ?: snapshot["stripeMandateId"])
                        .stringValue()
                        .ifBlank { null }
                } else {
                    null
                }
            val isActive =
                snapshot["isActive"]?.jsonPrimitive?.booleanOrNull
                    ?: if (methodType == "card") {
                        snapshot["creditCardActive"]?.jsonPrimitive?.booleanOrNull
                    } else {
                        snapshot["debitActive"]?.jsonPrimitive?.booleanOrNull
                    }
                    ?: (status != null)
            val associated =
                (snapshot["associated"]?.jsonPrimitive?.booleanOrNull ?: false) ||
                    !stripeCustomerId.isNullOrBlank() ||
                    !stripePaymentMethodId.isNullOrBlank() ||
                    !stripeSetupIntentId.isNullOrBlank() ||
                    !stripeMandateId.isNullOrBlank()

            return StripeConnectMethodNodeState(
                accountId = snapshot["accountId"].stringValue().ifBlank { null },
                associated = associated,
                isActive = isActive,
                landlordUserId = snapshot["landlordUserId"].stringValue().ifBlank { null },
                linkedAt = snapshot["linkedAt"].stringValue().ifBlank { null },
                paymentMethodBrand =
                    (snapshot[if (methodType == "card") "cardPaymentMethodBrand" else "bankPaymentMethodBrand"]
                        ?: snapshot["paymentMethodBrand"])
                        .stringValue()
                        .ifBlank { null },
                paymentMethodLabel =
                    (snapshot[if (methodType == "card") "cardPaymentMethodLabel" else "bankPaymentMethodLabel"]
                        ?: snapshot["paymentMethodLabel"])
                        .stringValue()
                        .ifBlank { null },
                paymentMethodLast4 =
                    (snapshot[if (methodType == "card") "cardPaymentMethodLast4" else "bankPaymentMethodLast4"]
                        ?: snapshot["paymentMethodLast4"])
                        .stringValue()
                        .ifBlank { null },
                status = status,
                stripeCustomerId = stripeCustomerId,
                stripeMandateId = stripeMandateId,
                stripePaymentMethodId = stripePaymentMethodId,
                stripeSetupIntentId = stripeSetupIntentId,
                tenantUserId = snapshot["tenantUserId"].stringValue().ifBlank { null },
                type = snapshot["type"].stringValue().ifBlank { null },
                updatedAt = snapshot["updatedAt"].stringValue().ifBlank { null }
            )
        }

        private fun hasStripeConnectMethodNodePayload(value: JsonObject?, methodType: String): Boolean {
            val state = stripeConnectMethodNodeState(value, methodType) ?: return false

            return state.associated ||
                state.isActive ||
                !state.linkedAt.isNullOrBlank() ||
                !state.paymentMethodBrand.isNullOrBlank() ||
                !state.paymentMethodLabel.isNullOrBlank() ||
                !state.paymentMethodLast4.isNullOrBlank() ||
                !state.status.isNullOrBlank() ||
                !state.stripeCustomerId.isNullOrBlank() ||
                !state.stripeMandateId.isNullOrBlank() ||
                !state.stripePaymentMethodId.isNullOrBlank() ||
                !state.stripeSetupIntentId.isNullOrBlank()
        }

        private fun stripeConnectAssociation(value: JsonElement?): TenantStripeConnectAssociationState {
            val root = value as? JsonObject ?: return TenantStripeConnectAssociationState.Empty
            val associationNode = root["association"] as? JsonObject
            val creditCardNode = root["creditCard"] as? JsonObject
            val oneTimeCreditCardNode = root["oneTimeCreditCard"] as? JsonObject
            val oneTimeBankTransferNode = root["oneTimeBankTransfer"] as? JsonObject
            val hasAssociationNode = associationNode != null
            val hasCreditCardNode = creditCardNode != null
            val hasOneTimeCreditCardNode = oneTimeCreditCardNode != null
            val hasOneTimeBankTransferNode = oneTimeBankTransferNode != null
            val legacyStripePaymentMethodId = root["stripePaymentMethodId"].stringValue().ifBlank { null }
            val legacyStripeMandateId = root["stripeMandateId"].stringValue().ifBlank { null }
            val legacyMethodType = when {
                legacyStripePaymentMethodId.isNullOrBlank() -> null
                !legacyStripeMandateId.isNullOrBlank() -> "acss_debit"
                else -> "card"
            }
            val strippedLegacyKeys = setOf("stripeMandateId", "stripePaymentMethodId", "stripeSetupIntentId")
            val legacyBankSource =
                if (!hasAssociationNode && !hasCreditCardNode && legacyMethodType == "card") {
                    JsonObject(root.filterKeys { it !in strippedLegacyKeys })
                } else {
                    root
                }
            val legacyCardSource =
                if (!hasAssociationNode && !hasCreditCardNode && legacyMethodType == "acss_debit") {
                    JsonObject(root.filterKeys { it !in strippedLegacyKeys })
                } else {
                    root
                }
            val bankSource = associationNode ?: if (!hasCreditCardNode) legacyBankSource else null
            val cardSource = creditCardNode ?: if (!hasAssociationNode) legacyCardSource else null
            val bankState = stripeConnectMethodNodeState(bankSource, "acss_debit")
            val cardState = stripeConnectMethodNodeState(cardSource, "card")
            val creditCardActive = cardState?.isActive == true
            val debitActive = bankState?.isActive == true
            val oneTimeCreditCardActive = oneTimeCreditCardNode?.get("isActive")?.jsonPrimitive?.booleanOrNull ?: false
            val oneTimeBankTransferActive = oneTimeBankTransferNode?.get("isActive")?.jsonPrimitive?.booleanOrNull ?: false
            val activeMethodType = when {
                oneTimeCreditCardActive || oneTimeBankTransferActive -> null
                creditCardActive &&
                    !cardState?.stripeCustomerId.isNullOrBlank() &&
                    !cardState?.stripePaymentMethodId.isNullOrBlank() -> "card"
                debitActive &&
                    !bankState?.stripeCustomerId.isNullOrBlank() &&
                    !bankState?.stripePaymentMethodId.isNullOrBlank() &&
                    (!bankState?.stripeMandateId.isNullOrBlank() || !bankState?.stripeSetupIntentId.isNullOrBlank()) -> "acss_debit"
                else -> null
            }
            val hasAssociationPayload =
                hasStripeConnectMethodNodePayload(bankSource, "acss_debit") ||
                    hasStripeConnectMethodNodePayload(cardSource, "card") ||
                    oneTimeCreditCardActive ||
                    oneTimeBankTransferActive

            if (!hasAssociationNode && !hasCreditCardNode && !hasOneTimeCreditCardNode && !hasOneTimeBankTransferNode && !hasAssociationPayload) {
                return TenantStripeConnectAssociationState.Empty
            }

            val associated = bankState?.associated == true || cardState?.associated == true
            val stripeCustomerId = when (activeMethodType) {
                "card" -> cardState?.stripeCustomerId
                "acss_debit" -> bankState?.stripeCustomerId
                else -> cardState?.stripeCustomerId ?: bankState?.stripeCustomerId
            }
            val stripeMandateId = if (activeMethodType == "acss_debit") bankState?.stripeMandateId else null
            val stripePaymentMethodId = when (activeMethodType) {
                "card" -> cardState?.stripePaymentMethodId
                "acss_debit" -> bankState?.stripePaymentMethodId
                else -> null
            }
            val stripeSetupIntentId = when (activeMethodType) {
                "card" -> cardState?.stripeSetupIntentId
                "acss_debit" -> bankState?.stripeSetupIntentId
                else -> null
            }
            val status = when (activeMethodType) {
                "card" -> cardState?.status ?: "manual"
                "acss_debit" -> bankState?.status ?: "manual"
                else -> "manual"
            }

            return TenantStripeConnectAssociationState(
                accountId = bankState?.accountId ?: cardState?.accountId,
                bankPaymentMethodBrand = bankState?.paymentMethodBrand,
                bankPaymentMethodLabel = bankState?.paymentMethodLabel,
                bankPaymentMethodLast4 = bankState?.paymentMethodLast4,
                bankStatus = bankState?.status,
                bankStripeMandateId = bankState?.stripeMandateId,
                bankStripePaymentMethodId = bankState?.stripePaymentMethodId,
                bankStripeSetupIntentId = bankState?.stripeSetupIntentId,
                associated = associated,
                cardPaymentMethodBrand = cardState?.paymentMethodBrand,
                cardPaymentMethodLabel = cardState?.paymentMethodLabel,
                cardPaymentMethodLast4 = cardState?.paymentMethodLast4,
                cardStatus = cardState?.status,
                cardStripePaymentMethodId = cardState?.stripePaymentMethodId,
                cardStripeSetupIntentId = cardState?.stripeSetupIntentId,
                creditCardActive = creditCardActive,
                debitActive = debitActive,
                landlordUserId = bankState?.landlordUserId ?: cardState?.landlordUserId,
                linkedAt = bankState?.linkedAt ?: cardState?.linkedAt,
                oneTimeBankTransferActive = oneTimeBankTransferActive,
                oneTimeCreditCardActive = oneTimeCreditCardActive,
                status = status,
                stripeCustomerId = stripeCustomerId,
                stripeMandateId = stripeMandateId,
                stripePaymentMethodId = stripePaymentMethodId,
                stripeSetupIntentId = stripeSetupIntentId,
                tenantUserId = bankState?.tenantUserId ?: cardState?.tenantUserId,
                type = bankState?.type ?: cardState?.type,
                updatedAt = bankState?.updatedAt ?: cardState?.updatedAt
            )
        }

        private fun formattedDate(raw: String): String {
            return runCatching {
                LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE).format(outputFormatter)
            }.getOrElse {
                raw.ifBlank { "-" }
            }
        }

        private fun formattedCurrency(value: Double?): String {
            return value?.let { currencyFormatter.format(it) } ?: "-"
        }

        private fun initials(value: String): String {
            return value.split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .map { it.first().uppercaseChar() }
                .joinToString("")
                .ifBlank { "?" }
        }

        private fun JsonElement?.stringValue(): String {
            return this?.jsonPrimitive?.content?.trim().orEmpty()
        }

        private fun JsonElement?.doubleValue(): Double? {
            return this?.jsonPrimitive?.doubleOrNull
                ?: this?.jsonPrimitive?.content?.toDoubleOrNull()
        }
    }
}

class TenantDataStore(
    private val authSession: AuthSessionStore,
    private val context: Context
) {
    private val restClient = FirebaseRestClient()
    private val realtimeDatabase = FirebaseDatabase.getInstance(FirebaseConfig.databaseUrl).reference
    private val storage = FirebaseStorage.getInstance("gs://${FirebaseConfig.storageBucket}")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var activeUid: String? = null
    private var chatConversationReference: DatabaseReference? = null
    private var chatConversationListener: ValueEventListener? = null
    private var unreadChatMessageIds: List<String> = emptyList()
    private var isChatOpen = false

    var tenantRecord by mutableStateOf<TenantRecord?>(null)
        private set
    var rentEntries by mutableStateOf<List<RentLedgerEntry>>(emptyList())
        private set
    var parkingEntries by mutableStateOf<List<ParkingLedgerEntry>>(emptyList())
        private set
    var notificationPreferences by mutableStateOf(DoorTreeSampleData.notificationPreferences)
        private set
    var pendingInvoices by mutableStateOf<List<PendingInvoiceItem>>(emptyList())
        private set
    var invoices by mutableStateOf<List<PendingInvoiceItem>>(emptyList())
        private set
    var documents by mutableStateOf<List<DocumentItem>>(emptyList())
        private set
    var notices by mutableStateOf<List<NoticeItem>>(emptyList())
        private set
    var maintenanceRequests by mutableStateOf<List<MaintenanceRequestItem>>(emptyList())
        private set
    var chatSections by mutableStateOf<List<ChatSection>>(emptyList())
        private set
    var interacTransferDetails by mutableStateOf<InteracTransferDetails?>(null)
        private set
    var landlordInteracSettings by mutableStateOf<InteracRecipientSettings?>(null)
        private set
    var landlordRentCollectionSettings by mutableStateOf<LandlordRentCollectionSettings?>(null)
        private set
    var landlordCompanyName by mutableStateOf<String?>(null)
        private set
    var unreadChatCount by mutableStateOf(0)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var chatParticipantNameOverride by mutableStateOf<String?>(null)
        private set

    val tenantProfile: TenantProfile
        get() = tenantRecord?.tenantProfile ?: TenantProfile("Tenant", "", "", "?")

    val propertyInfo: PropertyInfo
        get() = tenantRecord?.propertyInfo ?: PropertyInfo("Property", "Unit -", "", "")

    val leaseDetails: LeaseDetails
        get() {
            val record = tenantRecord ?: return LeaseDetails("-", "-", "-", "-", "Lease information is unavailable.")
            val base = record.leaseDetails
            val rentEntry = nextRentEntry ?: currentRentEntry
            return LeaseDetails(
                startDate = activeLeaseStartDate ?: base.startDate,
                endDate = activeLeaseEndDate ?: base.endDate,
                monthlyRent = rentEntry?.amount ?: base.monthlyRent,
                unitLabel = base.unitLabel,
                renewalNotice = base.renewalNotice
            )
        }

    val propertyManagerName: String
        get() = chatParticipantNameOverride?.trim().takeUnless { it.isNullOrBlank() }
            ?: tenantRecord?.propertyManagerDisplayName
            ?: "Property Manager"

    val propertyManagerInitials: String
        get() = propertyManagerName.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "PM" }

    val quickActions: List<QuickActionItem>
        get() {
            val actions = DoorTreeSampleData.quickActions.map { action ->
                if (action.route == QuickActionRoute.Chat) {
                    action.copy(subtitle = chatQuickActionSubtitle)
                } else {
                    action
                }
            }.toMutableList()

            if (tenantRecord?.parkingInfo?.hasPrice == true) {
                actions.add(
                    1.coerceAtMost(actions.size),
                    QuickActionItem(
                        title = "Pay Parking",
                        subtitle = upcomingParkingDescription,
                        icon = "car.fill",
                        iconColor = DoorTreeTheme.dueText,
                        iconBackground = DoorTreeTheme.dueBackground.copy(alpha = 0.75f),
                        route = QuickActionRoute.Parking
                    )
                )
            }
            return actions
        }

    private val chatQuickActionSubtitle: String
        get() = when (unreadChatCount) {
            0 -> L("home.quick.chat.no_unread")
            1 -> L("home.quick.chat.one_unread")
            else -> LF("home.quick.chat.unread_count", unreadChatCount)
        }

    val paymentMethods: List<PaymentMethodItem>
        get() {
            val rentPayment = currentRentPayment
            val hasSavedCardProfile = tenantRecord?.let(::hasSavedCardRentPaymentProfileForRecord) ?: rentPayment.hasSavedCardStripeProfile
            val hasSavedBankProfile = tenantRecord?.let(::hasSavedBankRentPaymentProfileForRecord) ?: rentPayment.hasSavedBankStripeProfile
            val cardLabel = tenantRecord?.let(::savedCardPaymentMethodLabelForRecord)
            val bankLabel = tenantRecord?.let(::savedBankPaymentMethodLabelForRecord)
            val isCardActive = isCreditCardRentCollectionEnabled &&
                (tenantRecord?.let(::isCardAutopayActiveForRecord) ?: rentPayment.isCardAutopayActive)
            val isBankActive = isBankDebitsRentCollectionEnabled &&
                (tenantRecord?.let(::isBankAutopayActiveForRecord) ?: rentPayment.isBankAutopayActive)
            val isBankVerificationPending = isBankDebitsRentCollectionEnabled &&
                (tenantRecord?.let(::isBankAutopayVerificationPendingForRecord) ?: rentPayment.isBankAutopayVerificationPending)
            val isPendingCardSetup = rentPayment.pendingSetupMethodType == "card" && !hasSavedCardProfile
            val isPendingBankSetup = rentPayment.pendingSetupMethodType == "acss_debit" && !hasSavedBankProfile
            val manualSubtitle = if (currentRentEntry?.isAutopayProcessing == true) {
                L("payments.method.manual.subtitle.autopay_processing")
            } else {
                L("payments.method.manual.subtitle")
            }
            val cardSubtitle = when {
                isPendingCardSetup ->
                    L("payments.method.card.subtitle.setup_pending")
                isCardActive && !cardLabel.isNullOrBlank() ->
                    LF("payments.method.card.subtitle.active_with_label", cardLabel)
                hasSavedCardProfile && !cardLabel.isNullOrBlank() ->
                    LF("payments.method.card.subtitle.saved_with_label", cardLabel)
                hasCardRentPaymentManagement ->
                    L("payments.method.card.subtitle.management_available")
                else ->
                    L("payments.method.card.subtitle.setup_default")
            }
            val bankSubtitle = when {
                isPendingBankSetup ->
                    L("payments.method.bank.subtitle.setup_pending")
                isBankVerificationPending ->
                    L("payments.method.bank.subtitle.verification_pending")
                isBankActive && !bankLabel.isNullOrBlank() ->
                    LF("payments.method.bank.subtitle.active_with_label", bankLabel)
                hasSavedBankProfile && !bankLabel.isNullOrBlank() ->
                    LF("payments.method.bank.subtitle.saved_with_label", bankLabel)
                hasBankRentPaymentManagement ->
                    L("payments.method.bank.subtitle.management_available")
                else ->
                    L("payments.method.bank.subtitle.setup_default")
            }

            return bankTransferPaymentMethods() + listOf(
                PaymentMethodItem(
                    id = "manual-monthly",
                    title = L("payments.method.manual.title"),
                    subtitle = manualSubtitle,
                    icon = "creditcard.fill",
                    kind = PaymentMethodItem.Kind.ManualMonthly
                ),
                PaymentMethodItem(
                    id = "autopay-card",
                    title = L("payments.method.card.title"),
                    subtitle = cardSubtitle,
                    icon = "creditcard.circle.fill",
                    kind = PaymentMethodItem.Kind.AutopayCard
                ),
                PaymentMethodItem(
                    id = "autopay-bank",
                    title = L("payments.method.bank.title"),
                    subtitle = bankSubtitle,
                    icon = "building.columns.fill",
                    kind = PaymentMethodItem.Kind.AutopayBank
                )
            )
        }

    private fun bankTransferPaymentMethods(): List<PaymentMethodItem> {
        val transferDetails = interacTransferDetails ?: return emptyList()
        if (!isBankTransferRentCollectionEnabled || transferDetails.recipientEmail.isBlank()) {
            return emptyList()
        }

        return listOf(
            PaymentMethodItem(
                id = "one-time-bank-transfer",
                title = L("payments.method.bank_transfer.title"),
                subtitle = LF("payments.method.bank_transfer.subtitle.recipient", transferDetails.recipientEmail),
                icon = "arrow.left.arrow.right.circle.fill",
                kind = PaymentMethodItem.Kind.OneTimeBankTransfer
            )
        )
    }

    val currentRentPayment: TenantRentPaymentState
        get() = tenantRecord?.rentPayment ?: TenantRentPaymentState.Empty

    val stripeConnectAssociation: TenantStripeConnectAssociationState
        get() = tenantRecord?.stripeConnectAssociation ?: TenantStripeConnectAssociationState.Empty

    val syncedPreferredPaymentSelectionKind: PaymentMethodItem.Kind?
        get() = stripeConnectAssociation.syncedSelectedPaymentKind

    val hasStripeRentPaymentManagement: Boolean
        get() = tenantRecord?.let(::hasStripeRentPaymentManagement) ?: false

    val hasCardRentPaymentManagement: Boolean
        get() = isCreditCardRentCollectionEnabled && (tenantRecord?.let(::hasCardRentPaymentManagement) ?: false)

    val hasBankRentPaymentManagement: Boolean
        get() = isBankDebitsRentCollectionEnabled && (tenantRecord?.let(::hasBankRentPaymentManagement) ?: false)

    val hasSavedCardRentPaymentProfile: Boolean
        get() = tenantRecord?.let(::hasSavedCardRentPaymentProfileForRecord) ?: false

    val hasSavedBankRentPaymentProfile: Boolean
        get() = tenantRecord?.let(::hasSavedBankRentPaymentProfileForRecord) ?: false

    val savedCardPaymentMethodLabel: String?
        get() = tenantRecord?.let(::savedCardPaymentMethodLabelForRecord)

    val savedBankPaymentMethodLabel: String?
        get() = tenantRecord?.let(::savedBankPaymentMethodLabelForRecord)

    val isCardRentPaymentActive: Boolean
        get() = isCreditCardRentCollectionEnabled &&
            (tenantRecord?.let(::isCardAutopayActiveForRecord) ?: currentRentPayment.isCardAutopayActive)

    val isBankRentPaymentActive: Boolean
        get() = isBankDebitsRentCollectionEnabled &&
            (tenantRecord?.let(::isBankAutopayActiveForRecord) ?: currentRentPayment.isBankAutopayActive)

    val isBankRentPaymentVerificationPending: Boolean
        get() = isBankDebitsRentCollectionEnabled &&
            (tenantRecord?.let(::isBankAutopayVerificationPendingForRecord) ?: currentRentPayment.isBankAutopayVerificationPending)

    val isCreditCardRentCollectionEnabled: Boolean
        get() = landlordRentCollectionSettings?.creditCardActive ?: true

    val isBankDebitsRentCollectionEnabled: Boolean
        get() = landlordRentCollectionSettings?.bankDebitsActive ?: true

    val isBankTransferRentCollectionEnabled: Boolean
        get() = landlordRentCollectionSettings?.bankTransferActive ?: true

    val paymentHistory: List<PaymentItem>
        get() = rentEntries
            .filter { it.isPaid }
            .sortedByDescending { it.sortDate ?: LocalDate.MIN }
            .map { entry ->
                PaymentItem(
                    month = monthFormatter.format(entry.sortDate ?: LocalDate.now()),
                    date = entry.dueDateDisplay,
                    amount = entry.amount,
                    status = StatusBadgeStyle.Paid
                )
            }

    val dashboardPaymentHistory: List<DashboardPaymentHistoryItem>
        get() {
            val rentPayments = rentEntries
                .filter { it.isPaid }
                .map { entry ->
                    (entry.sortDate ?: LocalDate.MIN) to DashboardPaymentHistoryItem(
                        id = "rent-${entry.dueDateDisplay}-${entry.amount}",
                        title = "Rent",
                        date = entry.dueDateDisplay,
                        amount = entry.amount,
                        status = StatusBadgeStyle.Paid,
                        source = DashboardPaymentHistorySource.Rent
                    )
                }
            val parkingPayments = parkingEntries
                .filter { it.isPaid }
                .map { entry ->
                    (entry.sortDate ?: LocalDate.MIN) to DashboardPaymentHistoryItem(
                        id = "parking-${entry.dueDateDisplay}-${entry.amount}",
                        title = "Parking",
                        date = entry.dueDateDisplay,
                        amount = entry.amount,
                        status = StatusBadgeStyle.Paid,
                        source = DashboardPaymentHistorySource.Parking
                    )
                }
            val invoicePayments = invoices
                .filter { it.isPaid }
                .map { invoice ->
                    (invoice.sortDate ?: LocalDate.MIN) to DashboardPaymentHistoryItem(
                        id = "invoice-${invoice.id}",
                        title = invoice.invoiceNumber.ifBlank { L("maintenance.invoice.title") },
                        date = invoice.dueDate,
                        amount = invoice.total,
                        status = StatusBadgeStyle.Paid,
                        source = DashboardPaymentHistorySource.Invoice(invoice)
                    )
                }

            return (rentPayments + parkingPayments + invoicePayments)
                .sortedByDescending { it.first }
                .map { it.second }
        }

    val completedPayments: List<PaymentItem>
        get() = paymentHistory.filter { it.status == StatusBadgeStyle.Paid }

    val unreadDocumentCount: Int
        get() = documents.count { it.shouldShowNotificationBadge }

    val unreadNoticeCount: Int
        get() = notices.count { it.isUnread }

    val dueRentPaymentCount: Int
        get() = rentEntries.count { !it.isPaid && it.statusStyle == StatusBadgeStyle.Due }

    val dueParkingPaymentCount: Int
        get() = parkingEntries.count { !it.isPaid && it.statusStyle == StatusBadgeStyle.Due }

    val duePaymentCount: Int
        get() = dueRentPaymentCount + dueParkingPaymentCount

    val rentScheduleEntries: List<RentScheduleEntry>
        get() = RentScheduleBuilder.entries(
            rentEntries = rentEntries,
            tenantRecord = tenantRecord,
            leaseDetails = leaseDetails
        )

    val parkingScheduleEntries: List<RentScheduleEntry>
        get() = parkingEntries
            .sortedBy { it.sortDate ?: LocalDate.MIN }
            .mapNotNull { entry ->
                val dueDate = entry.sortDate ?: return@mapNotNull null
                val status = scheduleStatusFor(entry, dueDate)
                RentScheduleEntry(
                    dueDate = dueDate,
                    amount = entry.amount,
                    statusLabel = status.label,
                    accentColor = status.accentColor,
                    accentBackground = status.accentBackground
                )
            }

    val currentRentEntry: RentLedgerEntry?
        get() {
            val sortedEntries = rentEntries.sortedBy { it.sortDate ?: LocalDate.MAX }
            val today = LocalDate.now()
            val nextDue = sortedEntries.firstOrNull { entry ->
                !entry.isPaid && ((entry.sortDate ?: LocalDate.MIN) >= today)
            }
            if (nextDue != null) {
                return nextDue
            }

            return sortedEntries.lastOrNull { !it.isPaid } ?: sortedEntries.lastOrNull()
        }

    val nextRentEntry: RentLedgerEntry?
        get() {
            val sortedEntries = rentEntries.sortedBy { it.sortDate ?: LocalDate.MAX }
            val today = LocalDate.now()
            val nextDue = sortedEntries.firstOrNull { entry ->
                !entry.isPaid && ((entry.sortDate ?: LocalDate.MIN) >= today)
            }
            return nextDue ?: sortedEntries.firstOrNull { !it.isPaid } ?: sortedEntries.lastOrNull()
        }

    val currentParkingEntry: ParkingLedgerEntry?
        get() {
            val sortedEntries = parkingEntries.sortedBy { it.sortDate ?: LocalDate.MAX }
            val today = LocalDate.now()
            val nextDue = sortedEntries.firstOrNull { entry ->
                !entry.isPaid && ((entry.sortDate ?: LocalDate.MIN) >= today)
            }
            if (nextDue != null) {
                return nextDue
            }

            return sortedEntries.lastOrNull { !it.isPaid } ?: sortedEntries.lastOrNull()
        }

    val nextParkingEntry: ParkingLedgerEntry?
        get() {
            val sortedEntries = parkingEntries.sortedBy { it.sortDate ?: LocalDate.MAX }
            val today = LocalDate.now()
            val nextDue = sortedEntries.firstOrNull { entry ->
                !entry.isPaid && ((entry.sortDate ?: LocalDate.MIN) >= today)
            }
            return nextDue ?: sortedEntries.firstOrNull { !it.isPaid } ?: sortedEntries.lastOrNull()
        }

    val payableParkingEntry: ParkingLedgerEntry?
        get() {
            val next = nextParkingEntry
            if (next != null && !next.isPaid && !next.hostedCheckoutUrl.isNullOrBlank()) {
                return next
            }

            return parkingEntries
                .filter { !it.isPaid && !it.hostedCheckoutUrl.isNullOrBlank() }
                .sortedBy { it.sortDate ?: LocalDate.MAX }
                .firstOrNull()
        }

    val upcomingParkingDescription: String
        get() {
            val entry = nextParkingEntry ?: currentParkingEntry
            if (entry != null) {
                return "Spot ${entry.unit} • Due ${entry.dueDateDisplay}"
            }

            val unit = tenantRecord?.parkingInfo?.unit.orEmpty()
            return if (unit.isBlank()) "Parking payment" else "Spot $unit"
        }

    fun payableRentEntry(kind: PaymentMethodItem.Kind): RentLedgerEntry? {
        if (kind == PaymentMethodItem.Kind.ManualMonthly && !isCreditCardRentCollectionEnabled) {
            return null
        }

        val next = nextRentEntry
        if (next != null && !next.isPaid && next.hostedCheckoutUrl(kind) != null) {
            return next
        }

        return rentEntries
            .filter { !it.isPaid && it.hostedCheckoutUrl(kind) != null }
            .sortedBy { it.sortDate ?: LocalDate.MAX }
            .firstOrNull()
    }

    val payableRentEntry: RentLedgerEntry?
        get() = payableRentEntry(PaymentMethodItem.Kind.ManualMonthly)

    fun hostedCheckoutUrl(kind: PaymentMethodItem.Kind): String? =
        payableRentEntry(kind)?.hostedCheckoutUrl(kind)

    val notificationCenterItems: List<NotificationCenterItem>
        get() = notices.map { notice ->
            NotificationCenterItem(
                id = "notice-${notice.id}",
                title = notice.displayTitle,
                message = "A tenant notice is available for ${notice.unitLabel}.",
                timestamp = notice.displayDate,
                category = NotificationCenterCategory.Reminder,
                isUnread = notice.isUnread
            )
        }

    val unreadNotificationCount: Int
        get() = unreadNoticeCount

    fun handleAuthState(uid: String?) {
        if (uid.isNullOrBlank()) {
            debugMaintenanceRequestLog("handleAuthState received empty uid, resetting store")
            reset()
            return
        }
        if (activeUid == uid && (tenantRecord != null || isLoading)) {
            debugMaintenanceRequestLog("handleAuthState ignoring duplicate uid $uid")
            return
        }
        activeUid = uid
        debugMaintenanceRequestLog("handleAuthState loading uid $uid")
        scope.launch {
            loadTenantRecord(uid)
        }
    }

    fun reload() {
        val uid = activeUid ?: return
        debugMaintenanceRequestLog("reload requested for uid $uid")
        scope.launch {
            loadTenantRecord(uid)
        }
    }

    suspend fun refresh() {
        val uid = activeUid ?: return
        debugMaintenanceRequestLog("refresh requested for uid $uid")
        loadTenantRecord(uid)
    }

    suspend fun startRentPaymentFlow(
        kind: PaymentMethodItem.Kind,
        managementMode: Boolean = false
    ): String? {
        val uid = activeUid ?: throw IllegalStateException(L("payments.error.sign_in_again"))

        return when (kind) {
            PaymentMethodItem.Kind.ManualMonthly -> {
                if (!isCreditCardRentCollectionEnabled) {
                    throw IllegalStateException(L("payments.disabled.credit_card"))
                }

                val manualCheckoutUrl = hostedCheckoutUrl(PaymentMethodItem.Kind.ManualMonthly)
                    ?: throw IllegalStateException(L("payments.error.link_preparing"))

                if (currentRentEntry?.isAutopayProcessing == true) {
                    throw IllegalStateException(L("payments.error.autopay_processing"))
                }

                submitRentPaymentPreferenceJob(uid, "switch-manual")
                refresh()
                manualCheckoutUrl
            }

            PaymentMethodItem.Kind.AutopayCard -> {
                if (!isCreditCardRentCollectionEnabled) {
                    throw IllegalStateException(L("payments.disabled.credit_card"))
                }

                val action = if (managementMode) "manage-card" else "setup-card"
                val result = submitRentPaymentPreferenceJob(uid, action)
                refresh()
                result.url
            }

            PaymentMethodItem.Kind.AutopayBank -> {
                if (!isBankDebitsRentCollectionEnabled) {
                    throw IllegalStateException(L("payments.disabled.bank_debit"))
                }

                val action = if (managementMode) "manage-pad" else "setup-pad"
                val result = submitRentPaymentPreferenceJob(uid, action)
                refresh()
                result.url
            }

            PaymentMethodItem.Kind.OneTimeBankTransfer -> null
        }
    }

    suspend fun prepareInteracTransfer(): InteracTransferDetails {
        val uid = activeUid ?: throw IllegalStateException(L("payments.error.sign_in_again"))
        val result = submitTenantInteracPaymentJob(uid = uid, action = "prepare")
        val details = result.details
            ?: throw IllegalStateException("Interac payment details are unavailable. Please try again.")
        interacTransferDetails = details
        return details
    }

    suspend fun markInteracTransferSent(intentId: String) {
        val uid = activeUid ?: throw IllegalStateException(L("payments.error.sign_in_again"))
        submitTenantInteracPaymentJob(uid = uid, action = "mark-sent", intentId = intentId)
    }

    suspend fun deactivateAutopayForOneTimePaymentIfNeeded() {
        val uid = activeUid ?: throw IllegalStateException(L("payments.error.sign_in_again"))
        val rentPayment = currentRentPayment
        val shouldSwitchToManual =
            rentPayment.selectedMethodType == "card" ||
                rentPayment.selectedMethodType == "acss_debit" ||
                rentPayment.pendingSetupMethodType == "card" ||
                rentPayment.pendingSetupMethodType == "acss_debit"

        if (!shouldSwitchToManual) {
            return
        }

        submitRentPaymentPreferenceJob(uid, "switch-manual")
        refresh()
    }

    suspend fun persistSharedRentPaymentSelection(kind: PaymentMethodItem.Kind) {
        val uid = activeUid ?: throw IllegalStateException(L("payments.error.sign_in_again"))
        val idToken = authSession.ensureValidIdToken()
            ?: throw IllegalStateException(L("payments.error.sign_in_again"))
        val updated = restClient.patchDatabaseRoot(
            idToken = idToken,
            body = buildJsonObject {
                put("users/$uid/stripeConnect/association/isActive", JsonPrimitive(kind == PaymentMethodItem.Kind.AutopayBank))
                put("users/$uid/stripeConnect/creditCard/isActive", JsonPrimitive(kind == PaymentMethodItem.Kind.AutopayCard))
                put("users/$uid/stripeConnect/oneTimeCreditCard/isActive", JsonPrimitive(kind == PaymentMethodItem.Kind.ManualMonthly))
                put("users/$uid/stripeConnect/oneTimeBankTransfer/isActive", JsonPrimitive(kind == PaymentMethodItem.Kind.OneTimeBankTransfer))
            }
        )

        if (!updated) {
            throw IllegalStateException(L("payments.error.selection_sync_failed"))
        }
    }

    fun startInvoicePaymentFlow(invoice: PendingInvoiceItem): String {
        if (activeUid.isNullOrBlank()) {
            throw IllegalStateException(L("payments.error.sign_in_again"))
        }

        return invoice.hostedCheckoutUrl
            ?: throw IllegalStateException("Stripe is still preparing this invoice payment link.")
    }

    fun markDocumentRead(document: DocumentItem) {
        val uid = activeUid ?: return
        val databasePath = document.databasePath?.trim().orEmpty()
        if (databasePath.isBlank() || document.read) {
            return
        }

        val previousDocuments = documents
        documents = documents.map { item ->
            if (item.id == document.id) item.markingRead else item
        }

        scope.launch {
            val idToken = authSession.ensureValidIdToken()
            val openedAt = Instant.now().toString()
            val readEventMetadata = documentReadEventMetadata(document)
            val updated = idToken != null && restClient.patchDatabaseRoot(
                idToken = idToken,
                body = buildJsonObject {
                    put("users/$uid/$databasePath/read", JsonPrimitive(true))
                    put("users/$uid/$databasePath/readAt", JsonPrimitive(openedAt))

                    if (readEventMetadata != null) {
                        val eventId = "${readEventMetadata.type}-${openedAt.replace(Regex("""[.#$/\[\]]"""), "-")}-${UUID.randomUUID().toString().take(8)}"
                        val event = buildJsonObject {
                            put("id", JsonPrimitive(eventId))
                            put("type", JsonPrimitive(readEventMetadata.type))
                            put("title", JsonPrimitive(readEventMetadata.title))
                            put("category", JsonPrimitive(readEventMetadata.category))
                            put("source", JsonPrimitive("android"))
                            put("status", JsonPrimitive("read"))
                            put("occurredAt", JsonPrimitive(openedAt))
                            put("details", buildJsonObject {
                                put("databasePath", JsonPrimitive(databasePath))
                                put("filename", JsonPrimitive(document.filename))
                                put("storageReference", JsonPrimitive(document.storageReference))
                            })
                        }
                        val landlordUid = tenantRecord?.landlordUID?.trim().orEmpty()

                        put("users/$uid/events/${readEventMetadata.type}", JsonPrimitive(openedAt))
                        put("users/$uid/events/timeline/$eventId", event)

                        if (landlordUid.isNotBlank()) {
                            if (document.isRenewalNotice) {
                                put("users/$landlordUid/renewalNotices/sent/$uid/read", JsonPrimitive(true))
                                put("users/$landlordUid/renewalNotices/sent/$uid/readAt", JsonPrimitive(openedAt))
                            }
                            put("users/$landlordUid/tenants/$uid/events/${readEventMetadata.type}", JsonPrimitive(openedAt))
                            put("users/$landlordUid/tenants/$uid/events/timeline/$eventId", event)
                        }
                    }
                }
            )

            if (!updated) {
                documents = previousDocuments
                debugMaintenanceRequestLog("Failed to mark document as read at $databasePath")
            }
        }
    }

    fun markNoticeRead(notice: NoticeItem) {
        val uid = activeUid ?: return
        if (!notice.isUnread) {
            return
        }

        val previousNotices = notices
        notices = notices.map { item ->
            if (item.id == notice.id) item.markingRead else item
        }

        scope.launch {
            val idToken = authSession.ensureValidIdToken()
            val openedAt = Instant.now().toString()
            val updated = idToken != null && restClient.patchDatabaseRoot(
                idToken = idToken,
                body = buildJsonObject {
                    put("users/$uid/${notice.databasePath}/read", JsonPrimitive(true))
                    put("users/$uid/${notice.databasePath}/readAt", JsonPrimitive(openedAt))
                }
            )

            if (!updated) {
                notices = previousNotices
                debugMaintenanceRequestLog("Failed to mark notice as read at ${notice.databasePath}")
            }
        }
    }

    fun markNoticeUnread(notice: NoticeItem) {
        val uid = activeUid ?: return
        if (notice.isUnread) {
            return
        }

        val previousNotices = notices
        notices = notices.map { item ->
            if (item.id == notice.id) item.markingUnread else item
        }

        scope.launch {
            val idToken = authSession.ensureValidIdToken()
            val updated = idToken != null && restClient.patchDatabaseRoot(
                idToken = idToken,
                body = buildJsonObject {
                    put("users/$uid/${notice.databasePath}/read", JsonPrimitive(false))
                    put("users/$uid/${notice.databasePath}/readAt", JsonNull)
                }
            )

            if (!updated) {
                notices = previousNotices
                debugMaintenanceRequestLog("Failed to mark notice as unread at ${notice.databasePath}")
            }
        }
    }

    fun deleteNotice(notice: NoticeItem) {
        val uid = activeUid ?: return
        val previousNotices = notices
        notices = notices.filterNot { it.id == notice.id }

        scope.launch {
            val idToken = authSession.ensureValidIdToken()
            val updated = idToken != null && restClient.patchDatabaseRoot(
                idToken = idToken,
                body = buildJsonObject {
                    put("users/$uid/${notice.databasePath}", JsonNull)
                }
            )

            if (!updated) {
                notices = previousNotices
                debugMaintenanceRequestLog("Failed to delete notice at ${notice.databasePath}")
            }
        }
    }

    private data class DocumentReadEventMetadata(
        val type: String,
        val title: String,
        val category: String
    )

    private fun documentReadEventMetadata(document: DocumentItem): DocumentReadEventMetadata? {
        if (document.isRenewalNotice) {
            return DocumentReadEventMetadata(
                type = "rentIncreaseRead",
                title = "Rent increase notice read",
                category = "Lease"
            )
        }

        val searchableText = listOf(
            document.filename,
            document.databasePath.orEmpty(),
            document.storageReference
        ).joinToString(" ").lowercase()

        if ("rl31" in searchableText || "rl-31" in searchableText) {
            return DocumentReadEventMetadata(
                type = "rl31Read",
                title = "RL-31 form read",
                category = "Tax"
            )
        }

        return null
    }

    suspend fun recordRenewalDecision(
        document: DocumentItem,
        status: String,
        signatureBitmap: Bitmap? = null
    ) {
        val signedBitmap = signatureBitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            ?: throw IllegalStateException("The signature could not be saved. Please sign again and retry.")
        val uid = activeUid ?: throw IllegalStateException("Unable to update this renewal notice right now.")
        val databasePath = document.databasePath?.trim().orEmpty()
        if (databasePath.isBlank() || !document.isRenewalNotice) {
            throw IllegalStateException("Unable to update this renewal notice right now.")
        }

        val previousDocuments = documents
        documents = documents.map { item ->
            if (item.id == document.id) item.markingRenewalActionTaken(status) else item
        }

        try {
            var signatureStoragePath: String? = null
            var refreshedDocumentUrl: String? = null
            val tenantReplyPath = uploadRenewalReplyPdf(status, signedBitmap, document)
            val tenantReplyUrl = storage.reference.child(tenantReplyPath).downloadUrl.await().toString()

            signatureStoragePath = uploadRenewalSignature(signedBitmap, document)

            if (status == "accept") {
                refreshedDocumentUrl = uploadSignedRenewalPdf(signedBitmap, document)
            }

            val idToken = authSession.ensureValidIdToken()
                ?: throw IllegalStateException(L("payments.error.sign_in_again"))
            val tenantReplyCreatedAt = Instant.now().toString()
            val updated = restClient.patchDatabaseRoot(
                idToken = idToken,
                body = buildJsonObject {
                    put("users/$uid/$databasePath/status", JsonPrimitive(status))
                    put("users/$uid/$databasePath/isActionTaken", JsonPrimitive(true))
                    put("users/$uid/$databasePath/read", JsonPrimitive(true))
                    put("users/$uid/$databasePath/tenantReplyPDFfile", JsonPrimitive(tenantReplyPath))
                    put("users/$uid/$databasePath/tenantReplyPDFDownloadURL", JsonPrimitive(tenantReplyUrl))
                    put("users/$uid/$databasePath/tenantReplyCreatedAt", JsonPrimitive(tenantReplyCreatedAt))
                    signatureStoragePath?.let { path ->
                        put("users/$uid/$databasePath/signatureStoragePath", JsonPrimitive(path))
                    }
                }
            )

            if (!updated) {
                throw IllegalStateException("Unable to update this renewal notice right now.")
            }

            val eventType = when (status.trim().lowercase()) {
                "accept", "accepted" -> "rentIncreaseAccepted"
                "refuse", "refused", "reject", "rejected" -> "rentIncreaseRefused"
                else -> null
            }
            if (eventType != null) {
                val eventId = "$eventType-${tenantReplyCreatedAt.replace(Regex("""[.#$/\[\]]"""), "-")}-${System.currentTimeMillis()}"
                val event = buildJsonObject {
                    put("id", JsonPrimitive(eventId))
                    put("type", JsonPrimitive(eventType))
                    put("title", JsonPrimitive(if (eventType == "rentIncreaseAccepted") "Rent increase accepted" else "Rent increase refused"))
                    put("category", JsonPrimitive("Lease"))
                    put("source", JsonPrimitive("android"))
                    put("status", JsonPrimitive(if (eventType == "rentIncreaseAccepted") "accepted" else "refused"))
                    put("occurredAt", JsonPrimitive(tenantReplyCreatedAt))
                    put("details", buildJsonObject {
                        put("databasePath", JsonPrimitive(databasePath))
                        put("tenantReplyPDFfile", JsonPrimitive(tenantReplyPath))
                        signatureStoragePath?.let { path -> put("signatureStoragePath", JsonPrimitive(path)) }
                    })
                }
                val landlordUid = tenantRecord?.landlordUID?.trim().orEmpty()
                runCatching {
                    restClient.patchDatabaseRoot(
                        idToken = idToken,
                        body = buildJsonObject {
                            put("users/$uid/events/$eventType", JsonPrimitive(tenantReplyCreatedAt))
                            put("users/$uid/events/timeline/$eventId", event)
                            if (landlordUid.isNotBlank()) {
                                put("users/$landlordUid/renewalNotices/sent/$uid/status", JsonPrimitive(status))
                                put("users/$landlordUid/renewalNotices/sent/$uid/isActionTaken", JsonPrimitive(true))
                                put("users/$landlordUid/renewalNotices/sent/$uid/read", JsonPrimitive(true))
                                put("users/$landlordUid/renewalNotices/sent/$uid/tenantReplyPDFfile", JsonPrimitive(tenantReplyPath))
                                put("users/$landlordUid/renewalNotices/sent/$uid/tenantReplyPDFDownloadURL", JsonPrimitive(tenantReplyUrl))
                                put("users/$landlordUid/renewalNotices/sent/$uid/tenantReplyCreatedAt", JsonPrimitive(tenantReplyCreatedAt))
                                put("users/$landlordUid/tenants/$uid/documents/renewalReply-${tenantReplyCreatedAt.replace(Regex("""[.#$/\[\]]"""), "-" )}", buildJsonObject {
                                    put("name", JsonPrimitive("Tenant Renewal Reply"))
                                    put("type", JsonPrimitive("application/pdf"))
                                    put("documentType", JsonPrimitive("Renewal Notice"))
                                    put("date", JsonPrimitive(tenantReplyCreatedAt))
                                    put("uploadedBy", JsonPrimitive(tenantProfile.name.ifBlank { "Tenant" }))
                                    put("url", JsonPrimitive(tenantReplyUrl))
                                    put("storagePath", JsonPrimitive(tenantReplyPath))
                                })
                                signatureStoragePath?.let { path ->
                                    put("users/$landlordUid/renewalNotices/sent/$uid/signatureStoragePath", JsonPrimitive(path))
                                }
                                put("users/$landlordUid/tenants/$uid/events/$eventType", JsonPrimitive(tenantReplyCreatedAt))
                                put("users/$landlordUid/tenants/$uid/events/timeline/$eventId", event)
                            }
                        }
                    )
                }
            }

            if (!signatureStoragePath.isNullOrBlank()) {
                val eventType = "rentIncreaseSigned"
                val eventId = "$eventType-${tenantReplyCreatedAt.replace(Regex("""[.#$/\[\]]"""), "-")}-${System.currentTimeMillis()}"
                val event = buildJsonObject {
                    put("id", JsonPrimitive(eventId))
                    put("type", JsonPrimitive(eventType))
                    put("title", JsonPrimitive("Rent increase notice signed"))
                    put("category", JsonPrimitive("Lease"))
                    put("source", JsonPrimitive("android"))
                    put("status", JsonPrimitive("signed"))
                    put("occurredAt", JsonPrimitive(tenantReplyCreatedAt))
                    put("details", buildJsonObject {
                        put("databasePath", JsonPrimitive(databasePath))
                        put("decisionStatus", JsonPrimitive(status.trim().lowercase()))
                        put("tenantReplyPDFfile", JsonPrimitive(tenantReplyPath))
                        put("signatureStoragePath", JsonPrimitive(signatureStoragePath))
                    })
                }
                val landlordUid = tenantRecord?.landlordUID?.trim().orEmpty()
                runCatching {
                    restClient.patchDatabaseRoot(
                        idToken = idToken,
                        body = buildJsonObject {
                            put("users/$uid/events/$eventType", JsonPrimitive(tenantReplyCreatedAt))
                            put("users/$uid/events/timeline/$eventId", event)
                            if (landlordUid.isNotBlank()) {
                                put("users/$landlordUid/renewalNotices/sent/$uid/signatureStoragePath", JsonPrimitive(signatureStoragePath))
                                put("users/$landlordUid/tenants/$uid/events/$eventType", JsonPrimitive(tenantReplyCreatedAt))
                                put("users/$landlordUid/tenants/$uid/events/timeline/$eventId", event)
                            }
                        }
                    )
                }
            }

            refreshedDocumentUrl?.let { url ->
                documents = documents.map { item ->
                    if (item.id == document.id) item.markingRenewalActionTaken(status, url) else item
                }
            }
        } catch (error: Exception) {
            documents = previousDocuments
            throw error
        }
    }

    fun setChatOpen(isOpen: Boolean) {
        isChatOpen = isOpen
        if (isOpen && unreadChatMessageIds.isNotEmpty()) {
            unreadChatCount = 0
            val messageIds = unreadChatMessageIds
            scope.launch {
                runCatching { markChatMessagesRead(messageIds) }
            }
        }
    }

    suspend fun submitMaintenanceRequest(
        category: MaintenanceCategory,
        customCategoryName: String,
        description: String,
        priority: MaintenancePriority,
        refundAmount: String? = null,
        isRefundRequest: Boolean = false,
        photos: List<MaintenancePhotoUpload> = emptyList()
    ) {
        val uid = activeUid ?: throw IllegalStateException(L("maintenance.submit_error.sign_in"))
        val record = tenantRecord ?: throw IllegalStateException(L("maintenance.submit_error.tenant_unavailable"))
        val trimmedDescription = description.trim()
        if (trimmedDescription.isBlank()) {
            throw IllegalStateException(L("maintenance.submit_error.describe_issue"))
        }
        val validatedRefundAmount = validatedRefundAmount(refundAmount, isRefundRequest)

        val idToken = authSession.ensureValidIdToken()
            ?: throw IllegalStateException(L("maintenance.submit_error.sign_in"))
        val landlordUid = resolveLandlordUid(record)
        val now = Instant.now()
        val today = LocalDate.now()
        val requestId = UUID.randomUUID().toString()
        val requestDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val timestamp = now.toString()
        val requestPath = maintenanceRequestPath(uid = uid, date = today, requestId = requestId)
        val landlordPath = maintenanceRequestPath(uid = landlordUid, date = today, requestId = requestId)
        val photoUrls = uploadMaintenancePhotos(
            photos = photos,
            tenantUid = uid,
            landlordUid = landlordUid,
            requestDate = requestDate,
            requestId = requestId
        )
        val payload = buildJsonObject {
            put(
                "category",
                JsonPrimitive(
                    if (isRefundRequest) "Refund Request"
                    else resolvedMaintenanceCategoryValue(category, customCategoryName)
                )
            )
            put("createdAt", JsonPrimitive(timestamp))
            put("date", JsonPrimitive(requestDate))
            put("description", JsonPrimitive(trimmedDescription))
            put("internalNotes", JsonPrimitive(""))
            put("issue", JsonPrimitive(maintenanceIssueSummary(trimmedDescription)))
            put("photos", buildJsonArray {
                photoUrls.forEach { url ->
                    add(JsonPrimitive(url))
                }
            })
            put("preferredDate", JsonPrimitive(requestDate))
            put("priority", JsonPrimitive(priority.defaultTitle))
            put("property", JsonPrimitive(record.propertyName))
            put("status", JsonPrimitive("pending"))
            put("tenant", JsonPrimitive(record.tenantProfile.name))
            put("unit", JsonPrimitive(record.unitNumber))
            put("updatedAt", JsonPrimitive(timestamp))
            validatedRefundAmount?.let { put("amount", JsonPrimitive(it)) }
        }

        val tenantUpdated = restClient.putDatabaseValue(
            path = requestPath,
            idToken = idToken,
            value = payload
        )
        val landlordUpdated = restClient.putDatabaseValue(
            path = landlordPath,
            idToken = idToken,
            value = payload
        )
        if (!tenantUpdated || !landlordUpdated) {
            throw IllegalStateException("Unable to submit maintenance request.")
        }

        refresh()
    }

    suspend fun deleteMaintenanceRequest(request: MaintenanceRequestItem) {
        if (request.status != StatusBadgeStyle.Pending) {
            throw IllegalStateException("Only pending requests can be deleted.")
        }

        val uid = activeUid ?: throw IllegalStateException(L("maintenance.submit_error.sign_in"))
        val record = tenantRecord ?: throw IllegalStateException(L("maintenance.submit_error.tenant_unavailable"))
        val requestDate = request.sortDate ?: throw IllegalStateException("Unable to determine this request date.")
        val idToken = authSession.ensureValidIdToken()
            ?: throw IllegalStateException(L("maintenance.submit_error.sign_in"))
        val landlordUid = resolveLandlordUid(record)
        val tenantPath = maintenanceRequestPath(uid = uid, date = requestDate, requestId = request.id)
        val landlordPath = maintenanceRequestPath(uid = landlordUid, date = requestDate, requestId = request.id)

        val tenantUpdated = restClient.putDatabaseValue(
            path = tenantPath,
            idToken = idToken,
            value = JsonNull
        )
        val landlordUpdated = restClient.putDatabaseValue(
            path = landlordPath,
            idToken = idToken,
            value = JsonNull
        )
        if (!tenantUpdated || !landlordUpdated) {
            throw IllegalStateException("Unable to delete request.")
        }

        refresh()
    }

    suspend fun sendChatMessage(rawText: String) {
        val uid = activeUid ?: throw IllegalStateException(L("maintenance.submit_error.sign_in"))
        val record = tenantRecord ?: throw IllegalStateException(L("maintenance.submit_error.tenant_unavailable"))
        val text = rawText.trim()
        if (text.isBlank()) {
            return
        }

        val moderationResult = ObjectionableContentFilter.evaluate(text)
        if (!moderationResult.allowed) {
            throw IllegalStateException(ObjectionableContentFilter.warningMessage(moderationResult.hits))
        }

        val idToken = authSession.ensureValidIdToken()
            ?: throw IllegalStateException(L("maintenance.submit_error.sign_in"))
        val landlordUid = resolveLandlordUid(record)
        val now = Instant.now()
        val timestamp = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val sentAt = now.toString()
        val participantName = propertyManagerName
        val tenantName = tenantProfile.name

        val tenantConversationPath = "users/$uid/messages/$landlordUid"
        val landlordConversationPath = "users/$landlordUid/messages/$uid"
        val tenantMessagePayload = buildJsonObject {
            put("read", JsonPrimitive(true))
            put("senderRole", JsonPrimitive("tenant"))
            put("senderUserId", JsonPrimitive(uid))
            put("sentAt", JsonPrimitive(sentAt))
            put("text", JsonPrimitive(text))
            put("timestamp", JsonPrimitive(timestamp))
        }
        val landlordMessagePayload = buildJsonObject {
            put("read", JsonPrimitive(false))
            put("senderRole", JsonPrimitive("tenant"))
            put("senderUserId", JsonPrimitive(uid))
            put("sentAt", JsonPrimitive(sentAt))
            put("text", JsonPrimitive(text))
            put("timestamp", JsonPrimitive(timestamp))
        }

        val updated = restClient.patchDatabaseRoot(
            idToken = idToken,
            body = buildJsonObject {
                put("$tenantConversationPath/lastMessage", JsonPrimitive(text))
                put("$tenantConversationPath/lastMessageTimestamp", JsonPrimitive(timestamp))
                put("$tenantConversationPath/participantId", JsonPrimitive(landlordUid))
                put("$tenantConversationPath/participantName", JsonPrimitive(participantName))
                put("$tenantConversationPath/propertyName", JsonPrimitive(record.propertyName))
                put("$tenantConversationPath/unitNumber", JsonPrimitive(record.unitNumber))
                put("$tenantConversationPath/updatedAt", JsonPrimitive(timestamp))
                put("$tenantConversationPath/messages/$messageId", tenantMessagePayload)
                put("$landlordConversationPath/lastMessage", JsonPrimitive(text))
                put("$landlordConversationPath/lastMessageTimestamp", JsonPrimitive(timestamp))
                put("$landlordConversationPath/participantId", JsonPrimitive(uid))
                put("$landlordConversationPath/participantName", JsonPrimitive(tenantName))
                put("$landlordConversationPath/propertyName", JsonPrimitive(record.propertyName))
                put("$landlordConversationPath/unitNumber", JsonPrimitive(record.unitNumber))
                put("$landlordConversationPath/updatedAt", JsonPrimitive(timestamp))
                put("$landlordConversationPath/messages/$messageId", landlordMessagePayload)
            }
        )
        if (!updated) {
            throw IllegalStateException("Unable to send message.")
        }

        refresh()
    }

    suspend fun reportChatMessage(message: ChatMessageItem) {
        if (message.sender != ChatParticipant.Landlord) {
            return
        }

        val uid = activeUid ?: throw IllegalStateException(L("maintenance.submit_error.sign_in"))
        val record = tenantRecord ?: throw IllegalStateException(L("maintenance.submit_error.tenant_unavailable"))
        val idToken = authSession.ensureValidIdToken()
            ?: throw IllegalStateException(L("maintenance.submit_error.sign_in"))
        val landlordUid = resolveLandlordUid(record)
        val now = Instant.now()
        val reportId = UUID.randomUUID().toString()
        val reportedTimestamp = System.currentTimeMillis()
        val messageTimestamp = if (message.sentTimestamp > 0L) message.sentTimestamp else reportedTimestamp
        val messageSentAt = message.sentAtIso8601.ifBlank { now.toString() }
        val offenderUid = message.senderUserId.trim().ifBlank { landlordUid }

        val updated = restClient.patchDatabaseRoot(
            idToken = idToken,
            body = buildJsonObject {
                put("ChatReports/$reportId/messageContent", JsonPrimitive(message.text))
                put("ChatReports/$reportId/messageId", JsonPrimitive(message.id))
                put("ChatReports/$reportId/messageTime", JsonPrimitive(messageTimestamp))
                put("ChatReports/$reportId/messageTimeISO8601", JsonPrimitive(messageSentAt))
                put("ChatReports/$reportId/offenderName", JsonPrimitive(propertyManagerName))
                put("ChatReports/$reportId/offenderUid", JsonPrimitive(offenderUid))
                put("ChatReports/$reportId/participantId", JsonPrimitive(landlordUid))
                put("ChatReports/$reportId/participantName", JsonPrimitive(propertyManagerName))
                put("ChatReports/$reportId/propertyName", JsonPrimitive(record.propertyName))
                put("ChatReports/$reportId/reportedTime", JsonPrimitive(reportedTimestamp))
                put("ChatReports/$reportId/reportedTimeISO8601Local", JsonPrimitive(now.toString()))
                put("ChatReports/$reportId/reporterName", JsonPrimitive(record.tenantProfile.name))
                put("ChatReports/$reportId/reporterUid", JsonPrimitive(uid))
                put("ChatReports/$reportId/source", JsonPrimitive("DoorTreeAndroid"))
                put("ChatReports/$reportId/status", JsonPrimitive("Needs Review"))
                put("ChatReports/$reportId/unitNumber", JsonPrimitive(record.unitNumber))
            }
        )
        if (!updated) {
            throw IllegalStateException("Unable to report message.")
        }
    }

    private suspend fun markChatMessagesRead(messageIds: List<String>) {
        if (messageIds.isEmpty()) {
            return
        }

        val uid = activeUid ?: return
        val record = tenantRecord ?: return
        val idToken = authSession.ensureValidIdToken() ?: return
        val landlordUid = resolveLandlordUid(record)
        val updated = restClient.patchDatabaseRoot(
            idToken = idToken,
            body = buildJsonObject {
                messageIds.forEach { messageId ->
                    put("users/$uid/messages/$landlordUid/messages/$messageId/read", JsonPrimitive(true))
                }
            }
        )
        if (!updated) {
            throw IllegalStateException("Unable to mark messages as read.")
        }
    }

    private data class RentPaymentPreferenceJobResult(
        val url: String?
    )

    private data class TenantInteracPaymentJobResult(
        val details: InteracTransferDetails?
    )

    private suspend fun loadTenantInteracCheckout() {
        runCatching { prepareInteracTransfer() }
            .onFailure { error ->
                interacTransferDetails = null
                debugMaintenanceRequestLog("Interac checkout unavailable: ${error.localizedMessage ?: "Unknown error"}")
            }
    }

    private suspend fun submitTenantInteracPaymentJob(
        uid: String,
        action: String,
        intentId: String? = null
    ): TenantInteracPaymentJobResult {
        return withTimeout(30_000) {
            suspendCancellableCoroutine { continuation ->
                val queueRef = realtimeDatabase
                    .child("users")
                    .child(uid)
                    .child("interacPaymentQueue")
                    .push()
                val payload = mutableMapOf<String, Any>(
                    "action" to action,
                    "createdAt" to Instant.now().toString(),
                    "source" to "android",
                    "status" to "pending"
                )
                if (!intentId.isNullOrBlank()) {
                    payload["intentId"] = intentId
                }
                var listener: ValueEventListener? = null

                fun finish(result: Result<TenantInteracPaymentJobResult>) {
                    if (!continuation.isActive) return
                    listener?.let(queueRef::removeEventListener)
                    result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) }
                    )
                }

                listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val values = snapshot.value as? Map<*, *> ?: return
                        when ((values["status"] as? String)?.trim().orEmpty()) {
                            "completed" -> {
                                val details = (values["details"] as? Map<*, *>)?.let(::interacTransferDetailsFromJob)
                                finish(Result.success(TenantInteracPaymentJobResult(details)))
                            }

                            "failed" -> {
                                val message = (values["error"] as? String)?.trim().orEmpty()
                                finish(Result.failure(IllegalStateException(
                                    message.ifBlank { "Interac payment details could not be prepared." }
                                )))
                            }
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        finish(Result.failure(IllegalStateException(error.message)))
                    }
                }

                queueRef.addValueEventListener(listener)
                queueRef.setValue(payload).addOnFailureListener { error ->
                    finish(Result.failure(error))
                }
                continuation.invokeOnCancellation {
                    listener?.let(queueRef::removeEventListener)
                }
            }
        }
    }

    private fun interacTransferDetailsFromJob(values: Map<*, *>): InteracTransferDetails? {
        fun text(key: String): String = (values[key] as? String)?.trim().orEmpty()
        val intentId = text("intentId")
        val recipientEmail = text("recipientEmail")
        val amountValue = (values["amount"] as? Number)?.toDouble()
            ?: text("amount").toDoubleOrNull()
        if (intentId.isBlank() || recipientEmail.isBlank() || amountValue == null || amountValue <= 0) {
            return null
        }
        val monthKey = text("monthKey")
        val rentMonth = runCatching {
            monthFormatter.format(LocalDate.parse("$monthKey-01", DateTimeFormatter.ISO_LOCAL_DATE))
        }.getOrElse { monthKey }

        return InteracTransferDetails(
            id = intentId,
            chargeId = text("chargeId"),
            invoiceNumber = text("invoiceNumber"),
            rentMonth = rentMonth,
            recipientEmail = recipientEmail,
            recipientName = text("recipientName"),
            amount = formatCurrency(amountValue),
            amountEntry = String.format(Locale.CANADA, "%.2f", amountValue),
            dueDate = formatDate(text("dueDate")),
            reference = text("reference"),
            propertyName = text("propertyName"),
            unitNumber = text("unitNumber"),
            autodepositEnabled = values["autodepositEnabled"] as? Boolean ?: false,
            status = text("status")
        )
    }

    private suspend fun submitRentPaymentPreferenceJob(
        uid: String,
        action: String
    ): RentPaymentPreferenceJobResult {
        return withTimeout(30_000) {
            suspendCancellableCoroutine { continuation ->
                val queueRef = realtimeDatabase
                    .child("users")
                    .child(uid)
                    .child("rentPaymentQueue")
                    .push()

                val payload = mapOf(
                    "action" to action,
                    "createdAt" to Instant.now().toString(),
                    "source" to "android",
                    "status" to "pending"
                )

                var listener: ValueEventListener? = null

                fun finish(result: Result<RentPaymentPreferenceJobResult>) {
                    if (!continuation.isActive) {
                        return
                    }

                    listener?.let(queueRef::removeEventListener)
                    result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) }
                    )
                }

                listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val values = snapshot.value as? Map<*, *> ?: return
                        when ((values["status"] as? String)?.trim().orEmpty()) {
                            "completed" -> finish(
                                Result.success(
                                    RentPaymentPreferenceJobResult(
                                        url = (values["url"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                                    )
                                )
                            )

                            "failed" -> {
                                val message = (values["error"] as? String)?.trim().orEmpty()
                                finish(
                                    Result.failure(
                                        IllegalStateException(
                                            if (message.isBlank()) {
                                                "Rent payment setup could not be completed."
                                            } else {
                                                message
                                            }
                                        )
                                    )
                                )
                            }
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        finish(Result.failure(IllegalStateException(error.message)))
                    }
                }

                queueRef.addValueEventListener(listener)
                queueRef.setValue(payload).addOnFailureListener { error ->
                    finish(Result.failure(error))
                }

                continuation.invokeOnCancellation {
                    listener?.let(queueRef::removeEventListener)
                }
            }
        }
    }

    fun updateNotificationSetting(key: NotificationSettingKey, isEnabled: Boolean) {
        val uid = activeUid ?: return
        val previous = notificationPreferences
        notificationPreferences = notificationPreferences.updating(key, isEnabled)

        scope.launch {
            val idToken = authSession.ensureValidIdToken()
            val updated = idToken != null && restClient.updateNotificationSetting(uid, idToken, key.firebaseKey, isEnabled)
            if (!updated) {
                notificationPreferences = previous
            }
        }
    }

    private suspend fun loadTenantRecord(uid: String, allowPendingSetupReconciliation: Boolean = true) {
        isLoading = true
        loadError = null
        debugMaintenanceRequestLog("loadTenantRecord start uid=$uid")

        val idToken = authSession.ensureValidIdToken()
        if (idToken.isNullOrBlank()) {
            stopObservingChatConversation()
            tenantRecord = null
            rentEntries = emptyList()
            parkingEntries = emptyList()
            notificationPreferences = DoorTreeSampleData.notificationPreferences
            pendingInvoices = emptyList()
            invoices = emptyList()
            documents = emptyList()
            notices = emptyList()
            maintenanceRequests = emptyList()
            chatSections = emptyList()
            interacTransferDetails = null
            landlordInteracSettings = null
            landlordRentCollectionSettings = null
            landlordCompanyName = null
            chatParticipantNameOverride = null
            unreadChatCount = 0
            unreadChatMessageIds = emptyList()
            loadError = L("auth.error.sign_in_again")
            isLoading = false
            debugMaintenanceRequestLog("loadTenantRecord missing auth token for uid $uid")
            return
        }

        val snapshot = runCatching { restClient.fetchUser(uid, idToken) }.getOrNull()
        val objectValue = snapshot.asJsonObjectOrNull()
        debugMaintenanceRequestLog("loadTenantRecord user snapshot keys=${objectValue?.keys?.sorted()?.joinToString(",").orEmpty()} raw=${snapshot?.toString() ?: "null"}")
        if (objectValue == null || objectValue.isEmpty()) {
            stopObservingChatConversation()
            tenantRecord = null
            rentEntries = emptyList()
            parkingEntries = emptyList()
            notificationPreferences = DoorTreeSampleData.notificationPreferences
            pendingInvoices = emptyList()
            invoices = emptyList()
            documents = emptyList()
            notices = emptyList()
            maintenanceRequests = emptyList()
            chatSections = emptyList()
            interacTransferDetails = null
            landlordInteracSettings = null
            landlordRentCollectionSettings = null
            landlordCompanyName = null
            chatParticipantNameOverride = null
            unreadChatCount = 0
            unreadChatMessageIds = emptyList()
            loadError = "We couldn't find tenant data for this account."
            isLoading = false
            debugMaintenanceRequestLog("loadTenantRecord failed to decode tenant record for uid $uid")
            return
        }

        val parsedTenantRecord = TenantRecord.fromSnapshot(uid, objectValue)
        if (allowPendingSetupReconciliation && shouldReconcilePendingRentPaymentSetup(parsedTenantRecord)) {
            debugMaintenanceRequestLog("loadTenantRecord found pending Stripe setup session for uid $uid, reconciling before applying state")
            runCatching {
                submitRentPaymentPreferenceJob(uid, "reconcile-pending-setup")
            }.onFailure { error ->
                debugMaintenanceRequestLog("loadTenantRecord reconciliation failed for uid $uid: ${error.localizedMessage ?: "Unknown error"}")
            }
            loadTenantRecord(uid, allowPendingSetupReconciliation = false)
            return
        }

        tenantRecord = parsedTenantRecord
        rentEntries = parseRentEntries(objectValue)
        interacTransferDetails = null
        scope.launch { loadTenantInteracCheckout() }
        parkingEntries = parseParkingEntries(objectValue)
        invoices = parseInvoices(objectValue)
        pendingInvoices = invoices.filter { it.isPending }
        documents = parseLeaseDocuments(
            renewalNoticesValue = objectValue["renewalNotices"],
            rl31NoticesValue = objectValue["RL31Notices"],
            rl31Value = objectValue["RL31"]
        )
        notices = parseNotices(objectValue["notices"])
        debugMaintenanceRequestLog("loadTenantRecord pendingInvoices count=${pendingInvoices.size}")
        val chatConversation = parseChatConversation(
            messagesRoot = objectValue["messages"] as? JsonObject,
            landlordUid = tenantRecord?.landlordUID.orEmpty()
        )
        applyChatConversation(chatConversation)
        observeChatConversation(uid = uid, landlordUid = tenantRecord?.landlordUID.orEmpty())
        val landlordSnapshot = tenantRecord
            ?.landlordUID
            ?.takeIf { it.isNotBlank() }
            ?.let { landlordUid ->
                runCatching { restClient.fetchUser(landlordUid, idToken) }
                    .getOrNull()
                    .asJsonObjectOrNull()
            }
        landlordInteracSettings = landlordSnapshot
            ?.get("interacSettings")
            ?.asJsonObjectOrNull()
            ?.let(::parseInteracRecipientSettings)
        landlordRentCollectionSettings = landlordSnapshot?.let(::parseLandlordRentCollectionSettings)
        landlordCompanyName = landlordSnapshot?.let(::parseLandlordCompanyName)
        val maintenanceRequestsSnapshot = runCatching {
            restClient.fetchMaintenanceRequests(uid, idToken)
        }.getOrNull()
        debugMaintenanceRequestLog("loadMaintenanceRequests raw=${maintenanceRequestsSnapshot?.toString() ?: "null"}")
        maintenanceRequests = MaintenanceRequestParser.parseRequestsRoot(maintenanceRequestsSnapshot.asJsonObjectOrNull()) { message ->
            debugMaintenanceRequestLog(message)
        }
        debugMaintenanceRequestLog("loadMaintenanceRequests parsed count=${maintenanceRequests.size} ids=${maintenanceRequests.joinToString(",") { it.id }}")
        val notificationSettings = objectValue["notificationSettings"].asJsonObjectOrNull()
        notificationPreferences = NotificationPreferences(
            paymentReminders = notificationSettings?.get("paymentReminders")?.jsonPrimitive?.booleanOrNull
                ?: DoorTreeSampleData.notificationPreferences.paymentReminders,
            maintenanceUpdates = notificationSettings?.get("maintenanceUpdates")?.jsonPrimitive?.booleanOrNull
                ?: DoorTreeSampleData.notificationPreferences.maintenanceUpdates,
            messages = notificationSettings?.get("messages")?.jsonPrimitive?.booleanOrNull
                ?: DoorTreeSampleData.notificationPreferences.messages,
            faceID = DoorTreeSampleData.notificationPreferences.faceID
        )
        isLoading = false
    }

    private fun shouldReconcilePendingRentPaymentSetup(record: TenantRecord): Boolean {
        return !record.rentPayment.pendingSetupCheckoutSessionId.isNullOrBlank()
    }

    private fun hasStripeRentPaymentManagement(record: TenantRecord): Boolean {
        return hasCardRentPaymentManagement(record) || hasBankRentPaymentManagement(record)
    }

    private fun hasCardRentPaymentManagement(record: TenantRecord): Boolean {
        val rentPayment = record.rentPayment

        if (hasSavedCardRentPaymentProfileForRecord(record)) {
            return true
        }

        return record.stripeConnectAssociation.hasConnectedCustomerProfile &&
            (rentPayment.selectedMethodType == "card" || rentPayment.pendingSetupMethodType == "card")
    }

    private fun hasBankRentPaymentManagement(record: TenantRecord): Boolean {
        val rentPayment = record.rentPayment

        if (hasSavedBankRentPaymentProfileForRecord(record)) {
            return true
        }

        return record.stripeConnectAssociation.hasConnectedCustomerProfile &&
            (rentPayment.selectedMethodType == "acss_debit" || rentPayment.pendingSetupMethodType == "acss_debit")
    }

    private fun hasSavedCardRentPaymentProfileForRecord(record: TenantRecord): Boolean {
        return record.stripeConnectAssociation.hasSavedCardStripeProfile || record.rentPayment.hasSavedCardStripeProfile
    }

    private fun hasSavedBankRentPaymentProfileForRecord(record: TenantRecord): Boolean {
        return record.stripeConnectAssociation.hasSavedBankStripeProfile || record.rentPayment.hasSavedBankStripeProfile
    }

    private fun savedCardPaymentMethodLabelForRecord(record: TenantRecord): String? {
        val associationLabel = record.stripeConnectAssociation.cardPaymentMethodLabel?.trim().orEmpty()
        if (associationLabel.isNotBlank()) {
            return associationLabel
        }

        return record.rentPayment.paymentMethodLabel?.takeIf { record.rentPayment.paymentMethodType == "card" }
    }

    private fun savedBankPaymentMethodLabelForRecord(record: TenantRecord): String? {
        val associationLabel = record.stripeConnectAssociation.bankPaymentMethodLabel?.trim().orEmpty()
        if (associationLabel.isNotBlank()) {
            return associationLabel
        }

        return record.rentPayment.paymentMethodLabel?.takeIf { record.rentPayment.paymentMethodType == "acss_debit" }
    }

    private fun isCardAutopayActiveForRecord(record: TenantRecord): Boolean {
        return record.stripeConnectAssociation.isCardAutopayActive || record.rentPayment.isCardAutopayActive
    }

    private fun isBankAutopayActiveForRecord(record: TenantRecord): Boolean {
        return record.stripeConnectAssociation.isBankAutopayActive || record.rentPayment.isBankAutopayActive
    }

    private fun isBankAutopayVerificationPendingForRecord(record: TenantRecord): Boolean {
        return record.stripeConnectAssociation.isBankAutopayVerificationPending || record.rentPayment.isBankAutopayVerificationPending
    }

    private fun reset() {
        stopObservingChatConversation()
        activeUid = null
        tenantRecord = null
        rentEntries = emptyList()
        parkingEntries = emptyList()
        notificationPreferences = DoorTreeSampleData.notificationPreferences
        pendingInvoices = emptyList()
        invoices = emptyList()
        documents = emptyList()
        notices = emptyList()
        maintenanceRequests = emptyList()
        chatSections = emptyList()
        interacTransferDetails = null
        landlordInteracSettings = null
        landlordRentCollectionSettings = null
        landlordCompanyName = null
        chatParticipantNameOverride = null
        unreadChatCount = 0
        unreadChatMessageIds = emptyList()
        isChatOpen = false
        isLoading = false
        loadError = null
    }

    private fun debugMaintenanceRequestLog(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d("TenantDataStore", message)
        }
    }

    private fun JsonElement?.asJsonObjectOrNull(): JsonObject? {
        return this as? JsonObject
    }

    private fun currentMonthLabel(): String {
        return java.time.LocalDate.now()
            .format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
    }

    private fun observeChatConversation(uid: String, landlordUid: String) {
        val trimmedLandlordUid = landlordUid.trim()
        if (trimmedLandlordUid.isEmpty()) {
            stopObservingChatConversation()
            chatSections = emptyList()
            chatParticipantNameOverride = null
            return
        }

        val reference = realtimeDatabase
            .child("users")
            .child(uid)
            .child("messages")
            .child(trimmedLandlordUid)

        if (chatConversationReference?.path.toString() == reference.path.toString() && chatConversationListener != null) {
            return
        }

        stopObservingChatConversation()
        chatConversationReference = reference
        chatConversationListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val conversation = parseRealtimeChatConversation(snapshot.value)
                applyChatConversation(conversation)
            }

            override fun onCancelled(error: DatabaseError) {
                if (BuildConfig.DEBUG) {
                    Log.d("TenantDataStore", "chat listener cancelled: ${error.message}")
                }
            }
        }.also { listener ->
            reference.addValueEventListener(listener)
        }
    }

    private fun stopObservingChatConversation() {
        val listener = chatConversationListener
        val reference = chatConversationReference
        if (listener != null && reference != null) {
            reference.removeEventListener(listener)
        }
        chatConversationListener = null
        chatConversationReference = null
    }

    private fun applyChatConversation(conversation: ParsedChatConversation) {
        chatParticipantNameOverride = conversation.participantName
        chatSections = conversation.sections
        unreadChatMessageIds = conversation.unreadMessageIds
        unreadChatCount = if (isChatOpen) 0 else conversation.unreadMessageIds.size

        if (isChatOpen && conversation.unreadMessageIds.isNotEmpty()) {
            val messageIds = conversation.unreadMessageIds
            scope.launch {
                runCatching { markChatMessagesRead(messageIds) }
            }
        }
    }

    private fun parseChatConversation(
        messagesRoot: JsonObject?,
        landlordUid: String
    ): ParsedChatConversation {
        if (messagesRoot == null || messagesRoot.isEmpty()) {
            return ParsedChatConversation(null, emptyList(), emptyList())
        }

        val conversation = messagesRoot[landlordUid] as? JsonObject
            ?: messagesRoot.entries
                .mapNotNull { (_, value) -> value as? JsonObject }
                .maxByOrNull { conversationObject ->
                    conversationObject["updatedAt"].longValue()
                        ?: conversationObject["lastMessageTimestamp"].longValue()
                        ?: 0L
                }
            ?: return ParsedChatConversation(null, emptyList(), emptyList())

        val participantName = conversation["participantName"].stringValue()
            .ifBlank { tenantRecord?.propertyManagerDisplayName.orEmpty() }
            .ifBlank { null }
        val messagesNode = conversation["messages"] as? JsonObject ?: return ParsedChatConversation(participantName, emptyList(), emptyList())
        val parsedMessages = messagesNode.entries.mapNotNull { (messageId, value) ->
            val message = value as? JsonObject ?: return@mapNotNull null
            val text = message["text"].stringValue()
            if (text.isBlank()) {
                return@mapNotNull null
            }

            val sortInstant = parseChatInstant(message["sentAt"].stringValue(), message["timestamp"].longValue())
                ?: Instant.EPOCH
            val sender = if (message["senderRole"].stringValue().trim().equals("tenant", ignoreCase = true)) {
                ChatParticipant.Tenant
            } else {
                ChatParticipant.Landlord
            }
            ParsedChatMessage(
                item = ChatMessageItem(
                    id = messageId,
                    sender = sender,
                    text = text,
                    timestamp = chatTimeFormatter.format(sortInstant.atZone(ZoneId.systemDefault())),
                    senderUserId = message["senderUserId"].stringValue(),
                    sentAtIso8601 = message["sentAt"].stringValue(),
                    sentTimestamp = message["timestamp"].longValue() ?: 0L,
                    isRead = message["read"]?.jsonPrimitive?.booleanOrNull ?: true
                ),
                sortInstant = sortInstant,
                isUnreadIncoming = sender == ChatParticipant.Landlord &&
                    message["read"]?.jsonPrimitive?.booleanOrNull != true
            )
        }.sortedBy { it.sortInstant }

        val sections = parsedMessages
            .groupBy { chatSectionFormatter.format(it.sortInstant.atZone(ZoneId.systemDefault())) }
            .map { (title, messages) ->
                ParsedChatSection(
                    title = title,
                    sortInstant = messages.firstOrNull()?.sortInstant ?: Instant.EPOCH,
                    messages = messages.map { it.item }
                )
            }
            .sortedBy { it.sortInstant }
            .map { section ->
                ChatSection(
                    id = section.title,
                    title = section.title,
                    messages = section.messages
                )
            }

        val unreadMessageIds = parsedMessages
            .filter { it.isUnreadIncoming }
            .map { it.item.id }

        return ParsedChatConversation(participantName, sections, unreadMessageIds)
    }

    private fun parseRealtimeChatConversation(snapshotValue: Any?): ParsedChatConversation {
        val conversation = snapshotValue as? Map<*, *> ?: return ParsedChatConversation(null, emptyList(), emptyList())
        val participantName = (conversation["participantName"] as? String)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: tenantRecord?.propertyManagerDisplayName
        val messageNodes = conversation["messages"] as? Map<*, *> ?: return ParsedChatConversation(participantName, emptyList(), emptyList())
        val parsedMessages = messageNodes.mapNotNull { (messageId, value) ->
            val id = (messageId as? String)?.trim().orEmpty()
            val message = value as? Map<*, *> ?: return@mapNotNull null
            val text = (message["text"] as? String)?.trim().orEmpty()
            if (id.isEmpty() || text.isEmpty()) {
                return@mapNotNull null
            }

            val sortInstant = parseChatInstant(
                sentAt = (message["sentAt"] as? String).orEmpty(),
                timestamp = anyLongValue(message["timestamp"])
            ) ?: Instant.EPOCH
            val sender = if ((message["senderRole"] as? String)?.trim()?.equals("tenant", ignoreCase = true) == true) {
                ChatParticipant.Tenant
            } else {
                ChatParticipant.Landlord
            }

            ParsedChatMessage(
                item = ChatMessageItem(
                    id = id,
                    sender = sender,
                    text = text,
                    timestamp = chatTimeFormatter.format(sortInstant.atZone(ZoneId.systemDefault())),
                    senderUserId = (message["senderUserId"] as? String).orEmpty(),
                    sentAtIso8601 = (message["sentAt"] as? String).orEmpty(),
                    sentTimestamp = anyLongValue(message["timestamp"]) ?: 0L,
                    isRead = anyBooleanValue(message["read"]) ?: true
                ),
                sortInstant = sortInstant,
                isUnreadIncoming = sender == ChatParticipant.Landlord &&
                    anyBooleanValue(message["read"]) != true
            )
        }.sortedBy { it.sortInstant }

        val sections = parsedMessages
            .groupBy { chatSectionFormatter.format(it.sortInstant.atZone(ZoneId.systemDefault())) }
            .map { (title, messages) ->
                ParsedChatSection(
                    title = title,
                    sortInstant = messages.firstOrNull()?.sortInstant ?: Instant.EPOCH,
                    messages = messages.map { it.item }
                )
            }
            .sortedBy { it.sortInstant }
            .map { section ->
                ChatSection(
                    id = section.title,
                    title = section.title,
                    messages = section.messages
                )
            }

        val unreadMessageIds = parsedMessages
            .filter { it.isUnreadIncoming }
            .map { it.item.id }

        return ParsedChatConversation(participantName, sections, unreadMessageIds)
    }

    private fun parseRentEntries(snapshot: JsonObject): List<RentLedgerEntry> {
        val rentRoot = snapshot["rent"] as? JsonObject ?: return emptyList()
        val entriesRoot = activeRentEntriesRoot(rentRoot)
        return entriesRoot.entries
            .mapNotNull { (dueDate, value) ->
                val rentObject = value as? JsonObject ?: return@mapNotNull null
                if (!isRentEntryNode(dueDate, rentObject)) {
                    return@mapNotNull null
                }

                rentEntryFromSnapshot(dueDate, rentObject)
            }
            .sortedBy { it.sortDate ?: LocalDate.MIN }
    }

    private fun parseParkingEntries(snapshot: JsonObject): List<ParkingLedgerEntry> {
        val parkingRoot = snapshot["parking"] as? JsonObject ?: return emptyList()
        val scheduleRoot = parkingRoot["1"] as? JsonObject ?: return emptyList()
        return scheduleRoot.entries
            .mapNotNull { (dueDate, value) ->
                val parkingObject = value as? JsonObject ?: return@mapNotNull null
                parkingEntryFromSnapshot(dueDate, parkingRoot, parkingObject)
            }
            .sortedBy { it.sortDate ?: LocalDate.MIN }
    }

    private fun parkingEntryFromSnapshot(
        fallbackDate: String,
        fallbackSnapshot: JsonObject,
        snapshot: JsonObject
    ): ParkingLedgerEntry {
        val dueDate = firstNonBlank(
            snapshot["dueDate"].stringValue(),
            fallbackDate
        )
        val parsedDueDate = parseLocalDate(dueDate) ?: parseMaintenanceDate(dueDate)
        val amountValue = snapshot["amount"].doubleValue() ?: fallbackSnapshot["price"].doubleValue()
        val balanceValue = snapshot["balance"].doubleValue() ?: amountValue
        val statusStyle = rentStatusStyle(
            snapshot = snapshot,
            dueDate = parsedDueDate,
            amountValue = amountValue,
            balanceValue = balanceValue
        )
        val stripeSnapshot = snapshot["stripe"] as? JsonObject
        val paymentLinkId = stripeSnapshot?.get("paymentLinkId").stringValue()
        val paymentLinkUrl = stripeSnapshot?.get("paymentLinkUrl").stringValue()
        val autopaySnapshot = stripeSnapshot?.get("autopay") as? JsonObject
        val stripe = if (stripeSnapshot == null && paymentLinkId.isBlank() && paymentLinkUrl.isBlank()) {
            null
        } else {
            RentStripeDetails(
                autopay = autopaySnapshot?.let { autopay ->
                    RentStripeAutopayDetails(
                        lastAttemptAt = autopay["lastAttemptAt"].stringValue().ifBlank { null },
                        lastChargeId = autopay["lastChargeId"].stringValue().ifBlank { null },
                        lastError = autopay["lastError"].stringValue().ifBlank { null },
                        lastPaymentIntentId = autopay["lastPaymentIntentId"].stringValue().ifBlank { null },
                        lastProcessedAt = autopay["lastProcessedAt"].stringValue().ifBlank { null },
                        lastStatus = autopay["lastStatus"].stringValue().ifBlank { null },
                        methodType = autopay["methodType"].stringValue().ifBlank { null }
                    )
                },
                isActive = stripeSnapshot?.get("active")?.jsonPrimitive?.booleanOrNull ?: false,
                paymentLinkId = paymentLinkId,
                paymentLinkUrl = paymentLinkUrl
            )
        }

        return ParkingLedgerEntry(
            id = fallbackDate,
            dueDate = dueDate,
            dueDateDisplay = formatDate(dueDate),
            amountValue = amountValue,
            amount = formatCurrency(amountValue),
            balanceValue = balanceValue,
            balance = formatCurrency(balanceValue),
            statusLabel = localizedRentStatus(statusStyle, snapshot["status"].stringValue(), parsedDueDate),
            statusStyle = statusStyle,
            startDate = formatDate(firstNonBlank(snapshot["startDate"].stringValue(), fallbackSnapshot["startDate"].stringValue())),
            endDate = formatDate(firstNonBlank(snapshot["endDate"].stringValue(), fallbackSnapshot["endDate"].stringValue())),
            type = firstNonBlank(snapshot["type"].stringValue(), fallbackSnapshot["type"].stringValue()),
            unit = firstNonBlank(snapshot["unit"].stringValue(), fallbackSnapshot["unit"].stringValue()),
            stripe = stripe,
            sortDate = parsedDueDate ?: parseMaintenanceDate(snapshot["updatedAt"].stringValue())
        )
    }

    private val activeLeaseStartDate: String?
        get() {
            val sortedEntries = rentEntries.sortedBy { it.sortDate ?: LocalDate.MAX }
            return sortedEntries
                .map { it.leaseStart }
                .firstOrNull { it.isNotBlank() && it != "-" }
                ?: sortedEntries.firstOrNull()?.dueDateDisplay
        }

    private val activeLeaseEndDate: String?
        get() {
            val sortedEntries = rentEntries.sortedBy { it.sortDate ?: LocalDate.MAX }
            return sortedEntries
                .asReversed()
                .map { it.leaseEnd }
                .firstOrNull { it.isNotBlank() && it != "-" }
                ?: sortedEntries.lastOrNull()?.dueDateDisplay
        }

    private fun activeRentEntriesRoot(root: JsonObject): JsonObject {
        if (containsRentEntryNodes(root)) {
            return root
        }

        val activeLeaseKey = root["activeLease"].stringValue()
        if (activeLeaseKey.isNotBlank()) {
            (root[activeLeaseKey] as? JsonObject)?.let { return it }
        }

        root.entries.forEach { (key, value) ->
            val leaseObject = value as? JsonObject ?: return@forEach
            val nestedActiveLeaseKey = leaseObject["activeLease"].stringValue()
            if (nestedActiveLeaseKey.isBlank()) {
                return@forEach
            }

            (root[nestedActiveLeaseKey] as? JsonObject)?.let { return it }
            if (key == nestedActiveLeaseKey) {
                return leaseObject
            }
        }

        val leaseContainers = root.entries.mapNotNull { (key, value) ->
            val leaseObject = value as? JsonObject ?: return@mapNotNull null
            if (key == "activeLease" || isRentEntryNode(key, leaseObject)) {
                null
            } else {
                leaseObject
            }
        }

        return leaseContainers.singleOrNull() ?: root
    }

    private fun containsRentEntryNodes(root: JsonObject): Boolean {
        return root.entries.any { (key, value) ->
            val rentObject = value as? JsonObject ?: return@any false
            isRentEntryNode(key, rentObject)
        }
    }

    private fun isRentEntryNode(fallbackDate: String, snapshot: JsonObject): Boolean {
        if (fallbackDate == "activeLease") {
            return false
        }

        if (parseLocalDate(fallbackDate) != null || parseMaintenanceDate(fallbackDate) != null) {
            return true
        }

        return snapshot.containsKey("dueDate") ||
            snapshot.containsKey("date") ||
            snapshot.containsKey("amount") ||
            snapshot.containsKey("balance") ||
            snapshot.containsKey("status") ||
            snapshot.containsKey("interac") ||
            snapshot.containsKey("stripe")
    }

    private fun rentEntryFromSnapshot(
        fallbackDate: String,
        snapshot: JsonObject
    ): RentLedgerEntry {
        val dueDate = firstNonBlank(
            snapshot["dueDate"].stringValue(),
            snapshot["date"].stringValue(),
            fallbackDate
        )
        val parsedDueDate = parseLocalDate(dueDate) ?: parseMaintenanceDate(dueDate)
        val amountValue = snapshot["amount"].doubleValue()
        val balanceValue = snapshot["balance"].doubleValue()
        val statusStyle = rentStatusStyle(
            snapshot = snapshot,
            dueDate = parsedDueDate,
            amountValue = amountValue,
            balanceValue = balanceValue
        )
        val interacSnapshot = snapshot["interac"] as? JsonObject
        val requestId = interacSnapshot?.get("requestId").stringValue()
        val requestUrl = interacSnapshot?.get("requestUrl").stringValue()
        val interac = if (interacSnapshot == null) {
            null
        } else {
            RentInteracDetails(
                isActive = interacSnapshot["active"]?.jsonPrimitive?.booleanOrNull ?: false,
                requestId = requestId,
                requestUrl = requestUrl,
                currency = interacSnapshot["currency"].stringValue(),
                collectibleAmount = interacSnapshot["collectibleAmount"].doubleValue(),
                status = interacSnapshot["status"].stringValue(),
                completedAt = interacSnapshot["completedAt"].stringValue().ifBlank { null }
            )
        }
        val stripeSnapshot = snapshot["stripe"] as? JsonObject
        val paymentLinkId = stripeSnapshot?.get("paymentLinkId").stringValue()
        val paymentLinkUrl = stripeSnapshot?.get("paymentLinkUrl").stringValue()
        val autopaySnapshot = stripeSnapshot?.get("autopay") as? JsonObject
        val stripe = if (stripeSnapshot == null && paymentLinkId.isBlank() && paymentLinkUrl.isBlank()) {
            null
        } else {
            RentStripeDetails(
                autopay = autopaySnapshot?.let { autopay ->
                    RentStripeAutopayDetails(
                        lastAttemptAt = autopay["lastAttemptAt"].stringValue().ifBlank { null },
                        lastChargeId = autopay["lastChargeId"].stringValue().ifBlank { null },
                        lastError = autopay["lastError"].stringValue().ifBlank { null },
                        lastPaymentIntentId = autopay["lastPaymentIntentId"].stringValue().ifBlank { null },
                        lastProcessedAt = autopay["lastProcessedAt"].stringValue().ifBlank { null },
                        lastStatus = autopay["lastStatus"].stringValue().ifBlank { null },
                        methodType = autopay["methodType"].stringValue().ifBlank { null }
                    )
                },
                isActive = stripeSnapshot?.get("active")?.jsonPrimitive?.booleanOrNull ?: false,
                paymentLinkId = paymentLinkId,
                paymentLinkUrl = paymentLinkUrl
            )
        }

        return RentLedgerEntry(
            id = fallbackDate,
            dueDate = dueDate,
            dueDateDisplay = formatDate(dueDate),
            amountValue = amountValue,
            amount = formatCurrency(amountValue),
            balanceValue = balanceValue,
            balance = formatCurrency(balanceValue),
            statusLabel = localizedRentStatus(statusStyle, snapshot["status"].stringValue(), parsedDueDate),
            statusStyle = statusStyle,
            propertyName = snapshot["propertyName"].stringValue(),
            propertyManager = snapshot["propertyManager"].stringValue(),
            leaseStart = formatDate(snapshot["leaseStart"].stringValue()),
            leaseEnd = formatDate(snapshot["leaseEnd"].stringValue()),
            tenantName = snapshot["tenantName"].stringValue(),
            tenantEmail = snapshot["tenantEmail"].stringValue(),
            tenantUid = snapshot["tenantUid"].stringValue(),
            unitNumber = snapshot["unitNumber"].stringValue(),
            interac = interac,
            stripe = stripe,
            sortDate = parsedDueDate
                ?: parseMaintenanceDate(snapshot["createdAt"].stringValue())
                ?: parseMaintenanceDate(snapshot["updatedAt"].stringValue())
        )
    }

    private data class LeaseDocumentDraft(
        val id: String,
        val title: String,
        val subtitle: String,
        val storageReference: String,
        val databasePath: String,
        val read: Boolean,
        val isRenewalNotice: Boolean,
        val isActionTaken: Boolean,
        val status: String,
        val sortDate: LocalDate?
    )

    private data class LeaseDocumentSource(
        val value: JsonElement?,
        val keyPath: List<String>,
        val idPrefix: String,
        val title: String,
        val fileKeys: List<String>,
        val isRenewalNotice: Boolean
    )

    private suspend fun parseNotices(snapshotValue: JsonElement?): List<NoticeItem> {
        val root = snapshotValue.asJsonObjectOrNull() ?: return emptyList()
        if (root.isEmpty()) {
            return emptyList()
        }

        return root.entries.mapNotNull { (noticeId, value) ->
            val snapshot = value.asJsonObjectOrNull() ?: return@mapNotNull null
            val downloadUrl = firstNonBlank(
                snapshot["downloadUrl"].stringValue(),
                snapshot["downloadURL"].stringValue()
            )
            val storagePath = snapshot["storagePath"].stringValue()
            val noticePath = snapshot["noticePath"].stringValue()
            val dateRaw = snapshot["date"].stringValue()
            val sentAtRaw = snapshot["sentAt"].stringValue()
            val fileName = firstNonBlank(
                snapshot["fileName"].stringValue(),
                snapshot["filename"].stringValue()
            )
            val sortDate = parseMaintenanceDate(sentAtRaw)
                ?: parseLocalDate(dateRaw)
                ?: parseMaintenanceDate(dateRaw)

            NoticeItem(
                id = noticeId,
                title = snapshot["title"].stringValue(),
                date = formatDate(dateRaw),
                fileName = fileName.ifBlank { "Tenant Notice.pdf" },
                language = snapshot["language"].stringValue(),
                noticePath = noticePath,
                propertyId = snapshot["propertyId"].stringValue(),
                propertyName = snapshot["propertyName"].stringValue(),
                read = snapshot["read"]?.jsonPrimitive?.booleanOrNull ?: false,
                recipientName = snapshot["recipientName"].stringValue(),
                sentAt = formatDateTime(sentAtRaw),
                status = snapshot["status"].stringValue(),
                storagePath = storagePath,
                tenantDirectoryId = snapshot["tenantDirectoryId"].stringValue(),
                tenantUid = snapshot["tenantUid"].stringValue(),
                unitId = snapshot["unitId"].stringValue(),
                unitNumber = snapshot["unitNumber"].stringValue(),
                url = resolveLeaseDocumentUrl(firstNonBlank(downloadUrl, storagePath, noticePath)),
                databasePath = "notices/$noticeId",
                sortDate = sortDate
            )
        }.sortedByDescending { it.sortDate ?: LocalDate.MIN }
    }

    private suspend fun parseLeaseDocuments(
        renewalNoticesValue: JsonElement?,
        rl31NoticesValue: JsonElement?,
        rl31Value: JsonElement?
    ): List<DocumentItem> {
        val sources = listOf(
            LeaseDocumentSource(
                value = renewalNoticesValue,
                keyPath = listOf("renewalNotices"),
                idPrefix = "renewal-notice",
                title = "Renewal Notice",
                fileKeys = listOf(
                    "renewalPDFfile",
                    "renewalPDFFile",
                    "renewalPdfFile",
                    "renewalPDF",
                    "downloadURL",
                    "downloadUrl",
                    "url",
                    "storagePath",
                    "file"
                ),
                isRenewalNotice = true
            ),
            LeaseDocumentSource(
                value = renewalNoticesValue,
                keyPath = listOf("renewalNotices"),
                idPrefix = "renewal-reply",
                title = "Tenant Reply",
                fileKeys = listOf(
                    "tenantReplyPDFfile",
                    "tenantReplyPDFFile",
                    "tenantReplyPdfFile",
                    "tenantReplyPDF",
                    "replyPDFfile",
                    "replyPDFFile",
                    "replyPDF"
                ),
                isRenewalNotice = false
            ),
            LeaseDocumentSource(
                value = rl31NoticesValue,
                keyPath = listOf("RL31Notices"),
                idPrefix = "rl31-notice",
                title = "RL-31",
                fileKeys = listOf(
                    "RL31PDFfile",
                    "RL31PDFFile",
                    "RL31PDF",
                    "RL-31PDFfile",
                    "RL-31PDFFile",
                    "rl31PDFfile",
                    "downloadURL",
                    "downloadUrl",
                    "url",
                    "storagePath",
                    "file"
                ),
                isRenewalNotice = false
            ),
            LeaseDocumentSource(
                value = rl31Value,
                keyPath = listOf("RL31"),
                idPrefix = "rl31",
                title = "RL-31",
                fileKeys = listOf(
                    "RL-31PDFfile",
                    "RL-31PDFFile",
                    "RL31PDFfile",
                    "RL31PDFFile",
                    "rl31PDFfile",
                    "downloadURL",
                    "downloadUrl",
                    "url",
                    "storagePath",
                    "file"
                ),
                isRenewalNotice = false
            )
        )

        return sources
            .flatMap { source ->
                collectLeaseDocumentDrafts(
                    value = source.value,
                    keyPath = source.keyPath,
                    idPrefix = source.idPrefix,
                    title = source.title,
                    fileKeys = source.fileKeys,
                    isRenewalNotice = source.isRenewalNotice
                )
            }
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<LeaseDocumentDraft> { it.sortDate ?: LocalDate.MIN }
                    .thenBy { it.title }
            )
            .map { draft ->
                DocumentItem(
                    id = draft.id,
                    filename = draft.title,
                    subtitle = draft.subtitle,
                    url = resolveLeaseDocumentUrl(draft.storageReference),
                    storageReference = draft.storageReference,
                    databasePath = draft.databasePath,
                    read = draft.read,
                    isRenewalNotice = draft.isRenewalNotice,
                    isActionTaken = draft.isActionTaken,
                    status = draft.status
                )
            }
    }

    private fun collectLeaseDocumentDrafts(
        value: JsonElement?,
        keyPath: List<String>,
        idPrefix: String,
        title: String,
        fileKeys: List<String>,
        isRenewalNotice: Boolean
    ): List<LeaseDocumentDraft> {
        return when (value) {
            is JsonObject -> {
                val fileReference = documentFileReference(value, fileKeys)
                val drafts = mutableListOf<LeaseDocumentDraft>()

                if (fileReference.isNotBlank()) {
                    val rawDate = leaseDocumentDateValue(value, keyPath)
                    val subtitle = formatLeaseDocumentDate(rawDate)
                    val id = (listOf(idPrefix) + keyPath + fileReference)
                        .joinToString("-")
                        .replace("/", "-")

                    drafts += LeaseDocumentDraft(
                        id = id,
                        title = title,
                        subtitle = subtitle,
                        storageReference = fileReference,
                        databasePath = keyPath.joinToString("/"),
                        read = value["read"]?.jsonPrimitive?.booleanOrNull ?: false,
                        isRenewalNotice = isRenewalNotice,
                        isActionTaken = !isRenewalNotice || (value["isActionTaken"]?.jsonPrimitive?.booleanOrNull ?: false),
                        status = value["status"].stringValue(),
                        sortDate = parseLocalDate(rawDate) ?: parseMaintenanceDate(rawDate)
                    )
                }

                value.entries
                    .sortedBy { it.key }
                    .flatMapTo(drafts) { (key, nestedValue) ->
                        collectLeaseDocumentDrafts(
                            value = nestedValue,
                            keyPath = keyPath + key,
                            idPrefix = idPrefix,
                            title = title,
                            fileKeys = fileKeys,
                            isRenewalNotice = isRenewalNotice
                        )
                    }

                drafts
            }
            is JsonArray -> value.flatMapIndexed { index, nestedValue ->
                collectLeaseDocumentDrafts(
                    value = nestedValue,
                    keyPath = keyPath + index.toString(),
                    idPrefix = idPrefix,
                    title = title,
                    fileKeys = fileKeys,
                    isRenewalNotice = isRenewalNotice
                )
            }
            else -> emptyList()
        }
    }

    private fun documentFileReference(snapshot: JsonObject, fileKeys: List<String>): String {
        return fileKeys
            .firstNotNullOfOrNull { key -> documentFileReference(snapshot[key]).takeIf { it.isNotBlank() } }
            .orEmpty()
    }

    private fun documentFileReference(value: JsonElement?): String {
        return when (value) {
            is JsonPrimitive -> value.content.trim()
            is JsonObject -> firstNonBlank(
                value["downloadURL"].stringValue(),
                value["downloadUrl"].stringValue(),
                value["url"].stringValue(),
                value["storagePath"].stringValue(),
                value["path"].stringValue(),
                value["fullPath"].stringValue(),
                value["name"].stringValue(),
                value["filename"].stringValue()
            )
            else -> ""
        }
    }

    private fun leaseDocumentDateValue(snapshot: JsonObject, keyPath: List<String>): String {
        val fieldDate = firstNonBlank(
            snapshot["date"].stringValue(),
            snapshot["sentAt"].stringValue(),
            snapshot["issueDate"].stringValue(),
            snapshot["createdAt"].stringValue(),
            snapshot["updatedAt"].stringValue(),
            snapshot["leaseEnd"].stringValue()
        )

        if (fieldDate.isNotBlank()) {
            return fieldDate
        }

        return keyPath
            .asReversed()
            .firstOrNull { parseLocalDate(it) != null || parseMaintenanceDate(it) != null }
            .orEmpty()
    }

    private fun formatLeaseDocumentDate(raw: String): String {
        val date = parseLocalDate(raw) ?: parseMaintenanceDate(raw) ?: return raw
        return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
    }

    private suspend fun resolveLeaseDocumentUrl(rawValue: String): String? {
        val trimmed = rawValue.trim()
        if (trimmed.isBlank()) {
            return null
        }

        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }

        return runCatching {
            val reference = if (trimmed.startsWith("gs://", ignoreCase = true)) {
                storage.getReferenceFromUrl(trimmed)
            } else {
                storage.reference.child(trimmed.trim('/'))
            }

            reference.downloadUrl.await().toString()
        }.onFailure { error ->
            debugMaintenanceRequestLog("Failed to resolve lease document URL for $trimmed: ${error.localizedMessage ?: "Unknown error"}")
        }.getOrNull()
    }

    private suspend fun uploadRenewalSignature(image: Bitmap, document: DocumentItem): String {
        val signaturePath = renewalSignatureStoragePath(document)
        val bytes = ByteArrayOutputStream().use { output ->
            if (!image.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IllegalStateException("Unable to save the signature image.")
            }
            output.toByteArray()
        }
        val metadata = StorageMetadata.Builder()
            .setContentType("image/png")
            .build()

        storage.reference.child(signaturePath).putBytes(bytes, metadata).await()
        return signaturePath
    }

    private suspend fun uploadSignedRenewalPdf(signatureBitmap: Bitmap, document: DocumentItem): String {
        val documentPath = renewalDocumentStoragePath(document)
        val originalPdfBytes = storage.reference.child(documentPath).getBytes(50L * 1024L * 1024L).await()
        val signedPdfBytes = signedRenewalPdfBytes(
            originalPdfBytes = originalPdfBytes,
            signatureBitmap = signatureBitmap,
            tenantName = tenantProfile.name
        )
        val metadata = StorageMetadata.Builder()
            .setContentType("application/pdf")
            .build()
        val reference = storage.reference.child(documentPath)
        reference.putBytes(signedPdfBytes, metadata).await()
        return reference.downloadUrl.await().toString()
    }

    private suspend fun uploadRenewalReplyPdf(
        status: String,
        signatureBitmap: Bitmap?,
        document: DocumentItem
    ): String {
        val replyPath = renewalReplyStoragePath(document)
        val replyPdfBytes = renewalReplyPdfBytes(
            status = status,
            signatureBitmap = signatureBitmap,
            landlordName = renewalReplyLandlordName(),
            tenantName = tenantProfile.name,
            dwellingAddress = renewalReplyDwellingAddress(),
            replyDate = LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
        )
        val metadata = StorageMetadata.Builder()
            .setContentType("application/pdf")
            .build()

        storage.reference.child(replyPath).putBytes(replyPdfBytes, metadata).await()
        return replyPath
    }

    private suspend fun renewalReplyPdfBytes(
        status: String,
        signatureBitmap: Bitmap?,
        landlordName: String,
        tenantName: String,
        dwellingAddress: String,
        replyDate: String
    ): ByteArray = withContext(Dispatchers.IO) {
        val inputFile = File.createTempFile("doortree-renewal-reply-template", ".pdf")
        try {
            context.assets.open("TAL_810_E.pdf").use { input ->
                FileOutputStream(inputFile).use { output ->
                    input.copyTo(output)
                }
            }

            val descriptor = ParcelFileDescriptor.open(inputFile, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(descriptor)
                try {
                    val outputDocument = PdfDocument()
                    try {
                        for (index in 0 until renderer.pageCount) {
                            val page = renderer.openPage(index)
                            try {
                                val width = page.width
                                val height = page.height
                                val pageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                try {
                                    pageBitmap.eraseColor(Color.WHITE)
                                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                                    val pageInfo = PdfDocument.PageInfo.Builder(width, height, index + 1).create()
                                    val outputPage = outputDocument.startPage(pageInfo)
                                    outputPage.canvas.drawBitmap(pageBitmap, 0f, 0f, null)

                                    if (index == 0) {
                                        drawRenewalReplyFields(
                                            canvas = outputPage.canvas,
                                            pageWidth = width.toFloat(),
                                            pageHeight = height.toFloat(),
                                            status = status,
                                            landlordName = landlordName,
                                            tenantName = tenantName,
                                            dwellingAddress = dwellingAddress,
                                            replyDate = replyDate,
                                            signatureBitmap = signatureBitmap
                                        )
                                    }

                                    outputDocument.finishPage(outputPage)
                                } finally {
                                    pageBitmap.recycle()
                                }
                            } finally {
                                page.close()
                            }
                        }

                        ByteArrayOutputStream().use { output ->
                            outputDocument.writeTo(output)
                            output.toByteArray()
                        }
                    } finally {
                        outputDocument.close()
                    }
                } finally {
                    renderer.close()
                }
            } finally {
                descriptor.close()
            }
        } finally {
            inputFile.delete()
        }
    }

    private fun drawRenewalReplyFields(
        canvas: Canvas,
        pageWidth: Float,
        pageHeight: Float,
        status: String,
        landlordName: String,
        tenantName: String,
        dwellingAddress: String,
        replyDate: String,
        signatureBitmap: Bitmap?
    ) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = max(10f, pageHeight * 0.015f)
        }
        val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = max(11f, pageHeight * 0.017f)
        }

        canvas.drawPdfFieldText(landlordName, PdfFieldRect(36.55f, 630.06f, 519.724f, 21.799f), pageWidth, pageHeight, textPaint)
        canvas.drawPdfFieldText(dwellingAddress, PdfFieldRect(36.713f, 566.791f, 519.397f, 21.636f), pageWidth, pageHeight, textPaint)
        canvas.drawPdfFieldText(replyDate, PdfFieldRect(38.844f, 230.454f, 101.126f, 21.635f), pageWidth, pageHeight, textPaint)
        canvas.drawPdfFieldText(tenantName, PdfFieldRect(160.457f, 229.798f, 202.744f, 21.8f), pageWidth, pageHeight, textPaint)

        val normalizedStatus = status.trim().lowercase(Locale.getDefault())
        val checkbox = when (normalizedStatus) {
            "accept" -> PdfFieldRect(47.367f, 491.066f, 8.687f, 8.687f)
            "refuse" -> PdfFieldRect(47.367f, 472.873f, 8.687f, 8.687f)
            "notrenewing", "not_renewing", "not-renewing" -> PdfFieldRect(47.367f, 454.843f, 8.687f, 8.851f)
            else -> null
        }
        checkbox?.let { rect ->
            val bounds = rect.toAndroidRect(pageWidth, pageHeight)
            canvas.drawText("X", bounds.left, bounds.bottom, checkPaint)
        }

        if (signatureBitmap != null) {
            val signatureBounds = PdfFieldRect(363.201f, 229.798f, 186.909f, 21.8f)
                .toAndroidRect(pageWidth, pageHeight)
                .let { field ->
                    RectF(field.left, field.top - 16f * pageHeight / 792f, field.right, field.top + 18f * pageHeight / 792f)
                }
            val signatureRect = aspectFitRect(
                imageWidth = signatureBitmap.width.toFloat(),
                imageHeight = signatureBitmap.height.toFloat(),
                bounds = signatureBounds
            )
            canvas.drawBitmap(signatureBitmap, null, signatureRect, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
            })
        }
    }

    private data class PdfFieldRect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    ) {
        fun toAndroidRect(pageWidth: Float, pageHeight: Float): RectF {
            val scaleX = pageWidth / 612f
            val scaleY = pageHeight / 792f
            return RectF(
                x * scaleX,
                (792f - y - height) * scaleY,
                (x + width) * scaleX,
                (792f - y) * scaleY
            )
        }
    }

    private fun Canvas.drawPdfFieldText(
        text: String,
        field: PdfFieldRect,
        pageWidth: Float,
        pageHeight: Float,
        paint: Paint
    ) {
        if (text.isBlank()) return

        val bounds = field.toAndroidRect(pageWidth, pageHeight)
        val baseline = bounds.centerY() - (paint.descent() + paint.ascent()) / 2f
        drawText(text, bounds.left, baseline, paint)
    }

    private suspend fun renewalReplyLandlordName(): String {
        val cachedCompanyName = landlordCompanyName?.trim().orEmpty()
        if (cachedCompanyName.isNotBlank()) {
            return cachedCompanyName
        }

        val landlordUid = tenantRecord?.landlordUID?.trim().orEmpty()
        val idToken = authSession.ensureValidIdToken()
        if (landlordUid.isNotBlank() && !idToken.isNullOrBlank()) {
            val companyName = runCatching {
                restClient.fetchUser(landlordUid, idToken)
                    .asJsonObjectOrNull()
                    ?.let(::parseLandlordCompanyName)
                    .orEmpty()
            }.getOrDefault("")

            if (companyName.isNotBlank()) {
                landlordCompanyName = companyName
                return companyName
            }
        }

        val record = tenantRecord
        val propertyName = record?.propertyName?.trim().orEmpty()
        return propertyName.ifBlank { record?.propertyManagerDisplayName ?: "Property Manager" }
    }

    private fun renewalReplyDwellingAddress(): String {
        val record = tenantRecord ?: return ""
        return listOf(
            record.unitNumber.trim().takeIf { it.isNotBlank() }?.let { "Unit $it" }.orEmpty(),
            record.streetAddress,
            record.city,
            record.province,
            record.postalCode
        )
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(", ")
    }

    private suspend fun signedRenewalPdfBytes(
        originalPdfBytes: ByteArray,
        signatureBitmap: Bitmap,
        tenantName: String
    ): ByteArray = withContext(Dispatchers.IO) {
        val inputFile = File.createTempFile("doortree-renewal", ".pdf")
        try {
            FileOutputStream(inputFile).use { output ->
                output.write(originalPdfBytes)
            }

            val descriptor = ParcelFileDescriptor.open(inputFile, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(descriptor)
                try {
                    val outputDocument = PdfDocument()
                    try {
                        for (index in 0 until renderer.pageCount) {
                            val page = renderer.openPage(index)
                            try {
                                val width = page.width
                                val height = page.height
                                val pageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                try {
                                    pageBitmap.eraseColor(Color.WHITE)
                                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                                    val pageInfo = PdfDocument.PageInfo.Builder(width, height, index + 1).create()
                                    val outputPage = outputDocument.startPage(pageInfo)
                                    outputPage.canvas.drawBitmap(pageBitmap, 0f, 0f, null)

                                    if (index == 0) {
                                        drawRenewalSignatureBlock(
                                            canvas = outputPage.canvas,
                                            pageWidth = width.toFloat(),
                                            pageHeight = height.toFloat(),
                                            signatureBitmap = signatureBitmap,
                                            tenantName = tenantName
                                        )
                                    }

                                    outputDocument.finishPage(outputPage)
                                } finally {
                                    pageBitmap.recycle()
                                }
                            } finally {
                                page.close()
                            }
                        }

                        ByteArrayOutputStream().use { output ->
                            outputDocument.writeTo(output)
                            output.toByteArray()
                        }
                    } finally {
                        outputDocument.close()
                    }
                } finally {
                    renderer.close()
                }
            } finally {
                descriptor.close()
            }
        } finally {
            inputFile.delete()
        }
    }

    private fun drawRenewalSignatureBlock(
        canvas: Canvas,
        pageWidth: Float,
        pageHeight: Float,
        signatureBitmap: Bitmap,
        tenantName: String
    ) {
        val today = LocalDate.now()
        val year = "%04d".format(today.year)
        val month = "%02d".format(today.monthValue)
        val day = "%02d".format(today.dayOfMonth)
        val displayName = tenantName.trim().uppercase(Locale.getDefault())
        val pageAspectRatio = max(pageWidth, pageHeight) / max(1f, min(pageWidth, pageHeight))
        val isLegalPage = pageAspectRatio > 1.55f
        val fieldHeight = max(13f, pageHeight * 0.022f)
        val textTop = pageHeight * if (isLegalPage) 0.882f else 0.830f
        val signatureHeight = max(26f, pageHeight * 0.05f)
        val signatureTop = textTop - signatureHeight * 0.42f
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = max(9f, pageHeight * 0.014f)
        }
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = max(8f, pageHeight * 0.013f)
        }

        canvas.drawCenteredText(year, RectF(pageWidth * 0.075f, textTop, pageWidth * 0.150f, textTop + fieldHeight), datePaint)
        canvas.drawCenteredText(month, RectF(pageWidth * 0.154f, textTop, pageWidth * 0.194f, textTop + fieldHeight), datePaint)
        canvas.drawCenteredText(day, RectF(pageWidth * 0.196f, textTop, pageWidth * 0.236f, textTop + fieldHeight), datePaint)
        canvas.drawLeftAlignedText(displayName, RectF(pageWidth * 0.248f, textTop, pageWidth * 0.613f, textTop + fieldHeight), namePaint)

        val signatureBounds = RectF(
            pageWidth * 0.635f,
            signatureTop,
            pageWidth * 0.890f,
            signatureTop + signatureHeight
        )
        val signatureRect = aspectFitRect(
            imageWidth = signatureBitmap.width.toFloat(),
            imageHeight = signatureBitmap.height.toFloat(),
            bounds = signatureBounds
        )
        canvas.drawBitmap(signatureBitmap, null, signatureRect, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        })
    }

    private fun Canvas.drawCenteredText(text: String, rect: RectF, paint: Paint) {
        val baseline = rect.centerY() - (paint.descent() + paint.ascent()) / 2f
        drawText(text, rect.centerX() - paint.measureText(text) / 2f, baseline, paint)
    }

    private fun Canvas.drawLeftAlignedText(text: String, rect: RectF, paint: Paint) {
        val baseline = rect.centerY() - (paint.descent() + paint.ascent()) / 2f
        drawText(text, rect.left, baseline, paint)
    }

    private fun aspectFitRect(imageWidth: Float, imageHeight: Float, bounds: RectF): RectF {
        if (imageWidth <= 0f || imageHeight <= 0f) {
            return bounds
        }

        val scale = min(bounds.width() / imageWidth, bounds.height() / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        return RectF(
            bounds.centerX() - width / 2f,
            bounds.centerY() - height / 2f,
            bounds.centerX() + width / 2f,
            bounds.centerY() + height / 2f
        )
    }

    private fun renewalSignatureStoragePath(document: DocumentItem): String {
        val documentPath = renewalDocumentStoragePath(document)
        val directory = documentPath.substringBeforeLast("/", missingDelimiterValue = "")
        if (directory.isBlank()) {
            throw IllegalStateException("Unable to save the signature beside the renewal notice.")
        }
        return "$directory/tenantSignature.png"
    }

    private fun renewalReplyStoragePath(document: DocumentItem): String {
        val documentPath = renewalDocumentStoragePath(document)
        val directory = documentPath.substringBeforeLast("/", missingDelimiterValue = "")
        if (directory.isBlank()) {
            throw IllegalStateException("Unable to save the tenant reply beside the renewal notice.")
        }
        return "$directory/tenantRenewalReply.pdf"
    }

    private fun renewalDocumentStoragePath(document: DocumentItem): String {
        val documentPath = firebaseStoragePath(document.storageReference)
        if (documentPath.isBlank()) {
            throw IllegalStateException("Unable to find the renewal notice storage path.")
        }
        return documentPath
    }

    private fun firebaseStoragePath(rawValue: String): String {
        val trimmed = rawValue.trim()
        if (trimmed.isBlank()) {
            return ""
        }

        if (trimmed.startsWith("gs://", ignoreCase = true)) {
            val withoutScheme = trimmed.removePrefix("gs://")
            return withoutScheme.substringAfter("/", missingDelimiterValue = "").trim('/')
        }

        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return ""
        }

        return trimmed.trim('/')
    }

    private fun parseInteracRecipientSettings(snapshot: JsonObject): InteracRecipientSettings? {
        val email = snapshot["email"].stringValue()
        if (email.isBlank()) {
            return null
        }

        return InteracRecipientSettings(
            email = email,
            displayName = snapshot["displayName"].stringValue(),
            autodepositEnabled = snapshot["autodepositEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
            isEnabled = snapshot["isEnabled"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    private fun parseLandlordRentCollectionSettings(snapshot: JsonObject): LandlordRentCollectionSettings {
        return LandlordRentCollectionSettings(
            bankDebitsActive = snapshot["bankDebitsActive"]?.jsonPrimitive?.booleanOrNull ?: true,
            bankTransferActive = snapshot["bankTransferActive"]?.jsonPrimitive?.booleanOrNull ?: true,
            bankTransferEmail = snapshot["bankTransferEmail"].stringValue(),
            creditCardActive = snapshot["creditCardActive"]?.jsonPrimitive?.booleanOrNull ?: true
        )
    }

    private fun parseLandlordCompanyName(snapshot: JsonObject): String {
        return firstNonBlank(
            snapshot["companyName"].stringValue(),
            snapshot["company"].stringValue(),
            snapshot["businessName"].stringValue(),
            snapshot["legalName"].stringValue()
        )
    }

    private fun parseInvoices(snapshot: JsonObject): List<PendingInvoiceItem> {
        val invoicesRoot = snapshot["invoices"] as? JsonObject ?: return emptyList()

        return invoiceSnapshots(invoicesRoot)
            .mapNotNull { (fallbackId, invoiceObject) ->
                runCatching { invoiceFromSnapshot(fallbackId, invoiceObject) }.getOrNull()
            }
            .sortedWith(
                compareBy<PendingInvoiceItem> { it.sortDate ?: LocalDate.MAX }
                    .thenBy { it.invoiceNumber }
            )
    }

    private fun parseMaintenanceRequests(snapshot: JsonObject): List<MaintenanceRequestItem> {
        return MaintenanceRequestParser.parse(snapshot) { message ->
            if (BuildConfig.DEBUG) {
                Log.d("TenantDataStore", message)
            }
        }
    }

    private fun invoiceSnapshots(root: JsonObject): List<Pair<String, JsonObject>> {
        val invoices = mutableListOf<Pair<String, JsonObject>>()

        fun walk(path: List<String>, node: JsonObject) {
            if (looksLikeInvoice(node)) {
                invoices += (path.lastOrNull().orEmpty() to node)
                return
            }

            node.forEach { (key, value) ->
                val objectValue = value as? JsonObject ?: return@forEach
                walk(path + key, objectValue)
            }
        }

        walk(emptyList(), root)
        return invoices
    }

    private fun maintenanceRequestSnapshots(root: JsonObject): List<Pair<String, JsonObject>> {
        val requests = mutableListOf<Pair<String, JsonObject>>()

        fun walk(path: List<String>, node: JsonObject) {
            if (looksLikeMaintenanceRequest(node)) {
                requests += (path.lastOrNull().orEmpty() to node)
                return
            }

            node.forEach { (key, value) ->
                val objectValue = value as? JsonObject ?: return@forEach
                walk(path + key, objectValue)
            }
        }

        walk(emptyList(), root)
        return requests
    }

    private fun looksLikeInvoice(node: JsonObject): Boolean {
        return node.containsKey("invoiceNumber")
            || node.containsKey("dueDate")
            || node.containsKey("lineItems")
            || (node.containsKey("balance") && node.containsKey("total"))
    }

    private fun looksLikeMaintenanceRequest(node: JsonObject): Boolean {
        return node.containsKey("issue")
            || node.containsKey("description")
            || node.containsKey("assign")
            || node.containsKey("priority")
    }

    private fun invoiceFromSnapshot(fallbackId: String, snapshot: JsonObject): PendingInvoiceItem {
        val invoiceNumber = snapshot["invoiceNumber"].stringValue().ifBlank { fallbackId.ifBlank { L("maintenance.invoice.title") } }
        val propertyName = snapshot["propertyName"].stringValue()
        val recipientName = snapshot["recipientName"].stringValue()
        val recipientAddress = snapshot["recipientAddress"].stringValue()
        val recipientEmail = snapshot["recipientEmail"].stringValue()
        val recipientNumber = snapshot["recipientNumber"].stringValue()
        val issueDate = formatDate(snapshot["issueDate"].stringValue())
        val dueDate = formatDate(snapshot["dueDate"].stringValue())
        val createdAt = formatDateTime(snapshot["createdAt"].stringValue())
        val updatedAt = formatDateTime(snapshot["updatedAt"].stringValue())
        val statusRaw = snapshot["status"].stringValue()
        val statusLabel = localizedInvoiceStatus(statusRaw)
        val balanceValue = snapshot["balance"].doubleValue()
        val stripeSnapshot = snapshot["stripe"] as? JsonObject
        val paymentLinkUrl = stripeSnapshot?.get("paymentLinkUrl").stringValue()
        val stripe = if (stripeSnapshot == null && paymentLinkUrl.isBlank()) {
            null
        } else {
            PendingInvoiceStripeDetails(
                isActive = stripeSnapshot?.get("active")?.jsonPrimitive?.booleanOrNull ?: false,
                checkoutSessionId = stripeSnapshot?.get("checkoutSessionId").stringValue(),
                currency = stripeSnapshot?.get("currency").stringValue(),
                paymentLinkUrl = paymentLinkUrl
            )
        }

        return PendingInvoiceItem(
            id = invoiceNumber,
            invoiceNumber = invoiceNumber,
            propertyName = propertyName,
            recipientName = recipientName,
            recipientAddress = recipientAddress,
            recipientEmail = recipientEmail,
            recipientNumber = recipientNumber,
            issueDate = issueDate,
            dueDate = dueDate,
            createdAt = createdAt,
            updatedAt = updatedAt,
            statusRaw = statusRaw,
            statusLabel = statusLabel,
            notes = snapshot["notes"].stringValue(),
            terms = snapshot["terms"].stringValue(),
            subtotal = formatCurrency(snapshot["subtotal"].doubleValue()),
            tpsAmount = formatCurrency(snapshot["tpsAmount"].doubleValue()),
            tvqAmount = formatCurrency(snapshot["tvqAmount"].doubleValue()),
            total = formatCurrency(snapshot["total"].doubleValue()),
            balanceValue = balanceValue,
            balance = formatCurrency(balanceValue),
            lineItems = parseInvoiceLineItems(snapshot["lineItems"]),
            stripe = stripe,
            sortDate = parseLocalDate(snapshot["dueDate"].stringValue()) ?: parseLocalDate(snapshot["issueDate"].stringValue())
        )
    }

    private fun maintenanceRequestFromSnapshot(
        fallbackId: String,
        snapshot: JsonObject
    ): MaintenanceRequestItem {
        val category = localizedMaintenanceCategory(snapshot["category"].stringValue())
        val issue = firstNonBlank(
            snapshot["issue"].stringValue(),
            snapshot["description"].stringValue(),
            category,
            L("maintenance.title")
        )
        val details = snapshot["description"].stringValue().ifBlank { issue }
        val requestDate = firstNonBlank(
            snapshot["date"].stringValue(),
            snapshot["preferredDate"].stringValue(),
            snapshot["createdAt"].stringValue(),
            snapshot["updatedAt"].stringValue()
        )
        val preferredDate = firstNonBlank(
            snapshot["preferredDate"].stringValue(),
            snapshot["date"].stringValue()
        )
        val title = firstNonBlank(
            category,
            issue,
            snapshot["description"].stringValue(),
            L("maintenance.title")
        )

        return MaintenanceRequestItem(
            id = fallbackId,
            title = title,
            category = category,
            submittedDate = formatMaintenanceDate(requestDate),
            submittedDateShort = formatMaintenanceDate(requestDate, short = true),
            status = maintenanceRequestStatus(snapshot),
            issue = issue,
            details = details,
            priority = localizedMaintenancePriority(snapshot["priority"].stringValue()),
            preferredDate = formatMaintenanceDate(preferredDate),
            assignedTo = snapshot["assign"].stringValue(),
            propertyName = snapshot["property"].stringValue(),
            unit = snapshot["unit"].stringValue(),
            tenantName = snapshot["tenant"].stringValue(),
            internalNotes = snapshot["internalNotes"].stringValue(),
            costEstimate = formatCurrency(snapshot["costEstimate"].doubleValue()),
            createdAt = formatDateTime(snapshot["createdAt"].stringValue()),
            updatedAt = formatDateTime(snapshot["updatedAt"].stringValue()),
            photos = parsePhotoUrls(snapshot["photos"]),
            sortDate = parseMaintenanceDate(requestDate)
        )
    }

    private fun parseInvoiceLineItems(snapshot: JsonElement?): List<InvoiceLineItem> {
        return when (snapshot) {
            is JsonObject -> snapshot.entries
                .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
                .mapNotNull { (key, value) ->
                    val item = value as? JsonObject ?: return@mapNotNull null
                    invoiceLineItemFromSnapshot(key, item)
                }
            is JsonArray -> snapshot.mapIndexedNotNull { index, value ->
                val item = value as? JsonObject ?: return@mapIndexedNotNull null
                invoiceLineItemFromSnapshot(index.toString(), item)
            }
            else -> emptyList()
        }
    }

    private fun invoiceLineItemFromSnapshot(
        fallbackId: String,
        snapshot: JsonObject
    ): InvoiceLineItem {
        return InvoiceLineItem(
            id = fallbackId,
            description = snapshot["description"].stringValue().ifBlank {
                snapshot["category"].stringValue().ifBlank { L("maintenance.invoice.line_items") }
            },
            category = snapshot["category"].stringValue(),
            quantity = formatQuantity(snapshot["qty"].doubleValue()),
            unitPrice = formatCurrency(snapshot["price"].doubleValue()),
            amount = formatCurrency(snapshot["amount"].doubleValue()),
            taxable = snapshot["taxable"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    private fun isPendingInvoice(snapshot: JsonObject): Boolean {
        val normalizedStatus = snapshot["status"].stringValue().trim().lowercase(Locale.ROOT)
        val resolvedStatuses = setOf("paid", "completed", "cancelled", "canceled", "void")
        val balance = snapshot["balance"].doubleValue()
        return normalizedStatus !in resolvedStatuses && (balance == null || balance > 0.0)
    }

    private fun localizedInvoiceStatus(rawStatus: String): String {
        return when (rawStatus.trim().lowercase(Locale.ROOT)) {
            "paid" -> L("status.paid")
            "completed", "complete" -> L("status.completed")
            "in_progress", "in-progress", "in progress" -> L("status.in_progress")
            "overdue", "due" -> L("status.due")
            "pending", "sent", "open" -> L("status.pending")
            else -> rawStatus.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                .ifBlank { L("status.pending") }
        }
    }

    private fun localizedRentStatus(
        statusStyle: StatusBadgeStyle,
        rawStatus: String,
        dueDate: LocalDate?
    ): String {
        return when (statusStyle) {
            StatusBadgeStyle.Paid -> L("status.paid")
            StatusBadgeStyle.Completed -> L("status.completed")
            StatusBadgeStyle.InProgress -> L("status.in_progress")
            StatusBadgeStyle.Pending, StatusBadgeStyle.Due -> {
                if (dueDate != null) {
                    val today = LocalDate.now()
                    when {
                        dueDate.isAfter(today) -> L("payments.schedule.status.upcoming")
                        dueDate.isEqual(today) -> L("status.due")
                        else -> L("status.overdue")
                    }
                } else {
                    rawStatus.trim()
                        .replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                        }
                        .ifBlank { L("status.pending") }
                }
            }
        }
    }

    private fun scheduleStatusFor(entry: ParkingLedgerEntry, dueDate: LocalDate): ScheduleStatus {
        val today = LocalDate.now()
        return when {
            entry.isPaid -> ScheduleStatus(
                label = L("status.paid"),
                accentColor = DoorTreeTheme.paidText,
                accentBackground = DoorTreeTheme.paidBackground
            )
            dueDate.isAfter(today) -> ScheduleStatus(
                label = L("payments.schedule.status.upcoming"),
                accentColor = DoorTreeTheme.dueText,
                accentBackground = DoorTreeTheme.dueBackground
            )
            dueDate.isEqual(today) -> ScheduleStatus(
                label = L("status.due"),
                accentColor = DoorTreeTheme.pendingText,
                accentBackground = DoorTreeTheme.pendingBackground
            )
            else -> ScheduleStatus(
                label = L("status.overdue"),
                accentColor = DoorTreeTheme.destructive,
                accentBackground = DoorTreeTheme.destructive.copy(alpha = 0.14f)
            )
        }
    }

    private data class ScheduleStatus(
        val label: String,
        val accentColor: androidx.compose.ui.graphics.Color,
        val accentBackground: androidx.compose.ui.graphics.Color
    )

    private fun localizedMaintenanceCategory(rawCategory: String): String {
        return when (rawCategory.trim().lowercase(Locale.ROOT)) {
            "plumbing" -> MaintenanceCategory.Plumbing.localizedTitle
            "electrical" -> MaintenanceCategory.Electrical.localizedTitle
            "hvac" -> MaintenanceCategory.Hvac.localizedTitle
            "heating" -> MaintenanceCategory.Heating.localizedTitle
            "cooling" -> MaintenanceCategory.Cooling.localizedTitle
            "appliance repair", "appliance_repair", "appliances" -> MaintenanceCategory.ApplianceRepair.localizedTitle
            "general repair", "general_repair" -> MaintenanceCategory.GeneralRepair.localizedTitle
            "carpentry" -> MaintenanceCategory.Carpentry.localizedTitle
            "painting" -> MaintenanceCategory.Painting.localizedTitle
            "flooring" -> MaintenanceCategory.Flooring.localizedTitle
            "locksmith" -> MaintenanceCategory.Locksmith.localizedTitle
            "pest control", "pest_control" -> MaintenanceCategory.PestControl.localizedTitle
            "cleaning" -> MaintenanceCategory.Cleaning.localizedTitle
            "roofing" -> MaintenanceCategory.Roofing.localizedTitle
            "exterior" -> MaintenanceCategory.Exterior.localizedTitle
            "landscaping" -> MaintenanceCategory.Landscaping.localizedTitle
            "snow removal", "snow_removal" -> MaintenanceCategory.SnowRemoval.localizedTitle
            "drywall" -> MaintenanceCategory.Drywall.localizedTitle
            "water damage", "water_damage" -> MaintenanceCategory.WaterDamage.localizedTitle
            "mold" -> MaintenanceCategory.Mold.localizedTitle
            "inspection" -> MaintenanceCategory.Inspection.localizedTitle
            "preventive maintenance", "preventive_maintenance" -> MaintenanceCategory.PreventiveMaintenance.localizedTitle
            "emergency" -> MaintenanceCategory.Emergency.localizedTitle
            "refund request", "refund_request" -> "Refund Request"
            "other" -> MaintenanceCategory.Other.localizedTitle
            else -> rawCategory.trim().ifBlank { MaintenanceCategory.Other.localizedTitle }
        }
    }

    private fun localizedMaintenancePriority(rawPriority: String): String {
        return when (rawPriority.trim().lowercase(Locale.ROOT)) {
            "low" -> MaintenancePriority.Low.localizedTitle
            "medium" -> MaintenancePriority.Medium.localizedTitle
            "high" -> MaintenancePriority.High.localizedTitle
            "urgent" -> MaintenancePriority.Urgent.localizedTitle
            "emergency" -> MaintenancePriority.Emergency.localizedTitle
            else -> rawPriority.trim().replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }.ifBlank { MaintenancePriority.Low.localizedTitle }
        }
    }

    private fun maintenanceRequestStatus(snapshot: JsonObject): StatusBadgeStyle {
        return when (snapshot["status"].stringValue().trim().lowercase(Locale.ROOT)) {
            "completed", "complete", "resolved", "closed" -> StatusBadgeStyle.Completed
            "in progress", "in_progress", "in-progress", "assigned" -> StatusBadgeStyle.InProgress
            "due", "overdue" -> StatusBadgeStyle.Due
            "paid" -> StatusBadgeStyle.Paid
            "pending", "submitted", "sent", "open", "" -> {
                if (snapshot["assign"].stringValue().isNotBlank()) StatusBadgeStyle.InProgress else StatusBadgeStyle.Pending
            }
            else -> {
                if (snapshot["assign"].stringValue().isNotBlank()) StatusBadgeStyle.InProgress else StatusBadgeStyle.Pending
            }
        }
    }

    private fun rentStatusStyle(
        snapshot: JsonObject,
        dueDate: LocalDate?,
        amountValue: Double?,
        balanceValue: Double?
    ): StatusBadgeStyle {
        val normalizedStatus = snapshot["status"].stringValue().trim().lowercase(Locale.ROOT)
        if (normalizedStatus in setOf("paid", "completed", "complete")) {
            return StatusBadgeStyle.Paid
        }

        if (balanceValue != null && amountValue != null && balanceValue <= 0.0) {
            return StatusBadgeStyle.Paid
        }

        return if (dueDate != null && !dueDate.isAfter(LocalDate.now())) {
            StatusBadgeStyle.Due
        } else {
            StatusBadgeStyle.Pending
        }
    }

    private fun formatCurrency(value: Double?): String {
        return value?.let {
            NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }.format(it)
        } ?: "-"
    }

    private fun formatQuantity(value: Double?): String {
        val quantity = value ?: return "-"
        return if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
    }

    private fun formatDate(raw: String): String {
        return parseLocalDate(raw)
            ?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
            ?: raw.ifBlank { "-" }
    }

    private fun formatMaintenanceDate(raw: String, short: Boolean = false): String {
        if (raw.isBlank()) return "-"

        parseLocalDate(raw)?.let { date ->
            val formatter = if (short) {
                DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
            } else {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
            }
            return date.format(formatter)
        }

        return runCatching {
            Instant.parse(raw)
                .atZone(ZoneId.systemDefault())
                .format(
                    if (short) {
                        DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
                    } else {
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
                    }
                )
        }.getOrElse {
            raw
        }
    }

    private fun formatDateTime(raw: String): String {
        if (raw.isBlank()) return "-"
        return runCatching {
            Instant.parse(raw)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
        }.getOrElse {
            raw
        }
    }

    private fun parseLocalDate(raw: String): LocalDate? {
        if (raw.isBlank()) return null
        return runCatching {
            LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)
        }.getOrNull()
    }

    private fun parseMaintenanceDate(raw: String): LocalDate? {
        return parseLocalDate(raw)
            ?: runCatching {
                Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDate()
            }.getOrNull()
    }

    private fun parsePhotoUrls(snapshot: JsonElement?): List<String> {
        return when (snapshot) {
            is JsonObject -> snapshot.entries
                .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
                .mapNotNull { (_, value) -> value.stringValue().ifBlank { null } }
            is JsonArray -> snapshot.mapNotNull { value -> value.stringValue().ifBlank { null } }
            else -> emptyList()
        }
    }

    private fun resolveLandlordUid(tenantRecord: TenantRecord): String {
        return tenantRecord.landlordUID.trim()
            .ifBlank { throw IllegalStateException(L("maintenance.submit_error.landlord_missing")) }
    }

    private suspend fun uploadMaintenancePhotos(
        photos: List<MaintenancePhotoUpload>,
        tenantUid: String,
        landlordUid: String,
        requestDate: String,
        requestId: String
    ): List<String> {
        if (photos.isEmpty()) {
            return emptyList()
        }

        val candidateOwnerUids = listOf(landlordUid, tenantUid).distinct().filter { it.isNotBlank() }
        return photos.mapIndexed { index, photo ->
            val extension = photo.fileExtension.trim().ifBlank { "jpg" }
            val filename = "$requestId-${index + 1}.$extension"
            val metadata = StorageMetadata.Builder()
                .setContentType(photo.contentType.ifBlank { "image/jpeg" })
                .build()
            var lastError: Exception? = null

            for (ownerUid in candidateOwnerUids) {
                val path = "Users/$ownerUid/requests/$requestDate/photos/$filename"
                val reference = storage.reference.child(path)

                try {
                    reference.putBytes(photo.bytes, metadata).await()
                    return@mapIndexed reference.downloadUrl.await().toString()
                } catch (error: Exception) {
                    lastError = error
                    val storageError = error as? StorageException
                    val isPermissionIssue = storageError?.errorCode == StorageException.ERROR_NOT_AUTHORIZED ||
                        storageError?.errorCode == StorageException.ERROR_NOT_AUTHENTICATED
                    if (!isPermissionIssue || ownerUid == candidateOwnerUids.last()) {
                        break
                    }
                }
            }

            throw lastError ?: IllegalStateException("Unable to upload maintenance photo.")
        }
    }

    private fun maintenanceRequestPath(uid: String, date: LocalDate, requestId: String): String {
        val month = date.format(DateTimeFormatter.ofPattern("LLLL", Locale.ENGLISH))
        return "users/$uid/maintenance/requests/${date.year}/$month/${date.dayOfMonth}/$requestId"
    }

    private fun maintenanceIssueSummary(description: String): String {
        val trimmed = description.trim()
        return if (trimmed.length <= 80) {
            trimmed
        } else {
            trimmed.take(80).trim()
        }
    }

    private fun resolvedMaintenanceCategoryValue(category: MaintenanceCategory, customCategoryName: String): String {
        if (category == MaintenanceCategory.Other) {
            val trimmed = customCategoryName.trim()
            return if (trimmed.isBlank()) category.defaultTitle else trimmed
        }

        return category.defaultTitle
    }

    private fun validatedRefundAmount(rawValue: String?, isRefundRequest: Boolean): Double? {
        if (!isRefundRequest) {
            return null
        }

        val trimmed = rawValue?.trim().orEmpty()
        if (trimmed.isBlank()) {
            throw IllegalStateException("Enter a refund amount.")
        }

        val sanitized = trimmed
            .replace("$", "")
            .replace(" ", "")

        val formatter = NumberFormat.getNumberInstance(Locale.getDefault())
        val parsePosition = ParsePosition(0)
        val parsed = formatter.parse(sanitized, parsePosition)?.toDouble()
        if (parsed != null && parsed > 0 && parsePosition.index == sanitized.length) {
            return parsed
        }

        val normalized = sanitized
            .replace(",", ".")
            .replace(Regex("[^0-9.]"), "")
        val normalizedValue = normalized.toDoubleOrNull()
        if (normalizedValue != null && normalizedValue > 0) {
            return normalizedValue
        }

        throw IllegalStateException("Enter a valid refund amount.")
    }


    private fun firstNonBlank(vararg values: String): String {
        return values.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun JsonElement?.stringValue(): String {
        return this?.jsonPrimitive?.content?.trim().orEmpty()
    }

    private fun JsonElement?.doubleValue(): Double? {
        return this?.jsonPrimitive?.doubleOrNull
            ?: this?.jsonPrimitive?.content?.toDoubleOrNull()
    }

    private fun JsonElement?.longValue(): Long? {
        return this?.jsonPrimitive?.content?.toLongOrNull()
    }

    private fun anyLongValue(value: Any?): Long? {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            is Float -> value.toLong()
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private fun anyBooleanValue(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.trim().lowercase(Locale.ROOT).let {
                when (it) {
                    "true", "1" -> true
                    "false", "0" -> false
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun parseChatInstant(sentAt: String, timestamp: Long?): Instant? {
        if (sentAt.isNotBlank()) {
            runCatching { Instant.parse(sentAt) }.getOrNull()?.let { return it }
        }
        return timestamp?.let { Instant.ofEpochMilli(it) }
    }

    private val chatTimeFormatter: DateTimeFormatter
        get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

    private val chatSectionFormatter: DateTimeFormatter
        get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

    private val monthFormatter: DateTimeFormatter
        get() = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
}

private data class ParsedChatConversation(
    val participantName: String?,
    val sections: List<ChatSection>,
    val unreadMessageIds: List<String>
)

private data class ParsedChatMessage(
    val item: ChatMessageItem,
    val sortInstant: Instant,
    val isUnreadIncoming: Boolean
)

private data class ParsedChatSection(
    val title: String,
    val sortInstant: Instant,
    val messages: List<ChatMessageItem>
)

internal object MaintenanceRequestParser {
    fun parse(
        snapshot: JsonObject,
        logger: ((String) -> Unit)? = null
    ): List<MaintenanceRequestItem> {
        val requestsRoot = (snapshot["maintenance"] as? JsonObject)?.get("requests") as? JsonObject
        return parseRequestsRoot(requestsRoot, logger)
    }

    fun parseRequestsRoot(
        requestsRoot: JsonObject?,
        logger: ((String) -> Unit)? = null
    ): List<MaintenanceRequestItem> {
        if (requestsRoot == null) {
            logger?.invoke("maintenance requests root missing")
            return emptyList()
        }

        val snapshots = requestLeafSnapshots(requestsRoot, logger)
        logger?.invoke("maintenance requests discovered: ${snapshots.size}")

        val parsed = snapshots.mapNotNull { snapshot ->
            runCatching { maintenanceRequestFromSnapshot(snapshot) }.getOrNull()
        }
        logger?.invoke("maintenance requests mapped: ${parsed.size}")

        return parsed
            .sortedWith(
                compareByDescending<ParsedMaintenanceRequest> { it.primarySortDate ?: LocalDate.MIN }
                    .thenByDescending { it.secondarySortInstant ?: Instant.EPOCH }
                    .thenBy { it.item.id }
            )
            .map { it.item }
    }

    private fun requestLeafSnapshots(
        root: JsonObject,
        logger: ((String) -> Unit)? = null
    ): List<MaintenanceRequestLeaf> {
        val requests = mutableListOf<MaintenanceRequestLeaf>()

        root.forEach { (yearKey, yearValue) ->
            val months = yearValue as? JsonObject ?: return@forEach

            months.forEach { (monthKey, monthValue) ->
                dayEntries(monthValue).forEach { (dayKey, dayValue) ->
                    val requestNodes = dayValue as? JsonObject ?: return@forEach
                    val fallbackDate = pathDate(yearKey, monthKey, dayKey)

                    requestNodes.forEach { (requestId, requestValue) ->
                        val requestObject = requestValue as? JsonObject ?: return@forEach
                        requests += MaintenanceRequestLeaf(
                            id = requestId,
                            snapshot = requestObject,
                            fallbackDate = fallbackDate
                        )
                        logger?.invoke(
                            "discovered request leaf id=$requestId fallbackDate=${fallbackDate ?: "null"} keys=${requestObject.keys.sorted().joinToString(",")}"
                        )
                    }
                }
            }
        }

        return requests
    }

    private fun dayEntries(monthValue: JsonElement): List<Pair<String, JsonElement>> {
        val daysObject = monthValue as? JsonObject
        if (daysObject != null) {
            return daysObject.entries.map { it.key to it.value }
        }

        val daysArray = monthValue as? JsonArray ?: return emptyList()
        return daysArray.mapIndexedNotNull { index, dayValue ->
            if (index == 0 || dayValue is JsonNull) {
                null
            } else {
                index.toString() to dayValue
            }
        }
    }

    private fun maintenanceRequestFromSnapshot(snapshot: MaintenanceRequestLeaf): ParsedMaintenanceRequest {
        val requestObject = snapshot.snapshot
        val category = localizedMaintenanceCategory(requestObject["category"].stringValue())
        val issue = firstNonBlank(
            requestObject["issue"].stringValue(),
            requestObject["description"].stringValue(),
            category,
            L("maintenance.title")
        )
        val details = requestObject["description"].stringValue().ifBlank { issue }
        val requestDate = firstNonBlank(
            requestObject["date"].stringValue(),
            requestObject["preferredDate"].stringValue(),
            snapshot.fallbackDate.orEmpty(),
            requestObject["createdAt"].stringValue(),
            requestObject["updatedAt"].stringValue()
        )
        val preferredDate = firstNonBlank(
            requestObject["preferredDate"].stringValue(),
            requestObject["date"].stringValue(),
            snapshot.fallbackDate.orEmpty()
        )
        val primarySortDate = parseMaintenanceDate(requestObject["date"].stringValue())
            ?: parseMaintenanceDate(requestObject["preferredDate"].stringValue())
            ?: parseMaintenanceDate(snapshot.fallbackDate.orEmpty())
            ?: parseMaintenanceDate(requestObject["createdAt"].stringValue())
            ?: parseMaintenanceDate(requestObject["updatedAt"].stringValue())
        val secondarySortInstant = parseInstant(requestObject["updatedAt"].stringValue())
            ?: parseInstant(requestObject["createdAt"].stringValue())
        val title = firstNonBlank(
            category,
            issue,
            requestObject["description"].stringValue(),
            L("maintenance.title")
        )

        val item = MaintenanceRequestItem(
            id = snapshot.id,
            title = title,
            category = category,
            submittedDate = formatMaintenanceDate(requestDate),
            submittedDateShort = formatMaintenanceDate(requestDate, short = true),
            status = maintenanceRequestStatus(requestObject),
            issue = issue,
            details = details,
            priority = localizedMaintenancePriority(requestObject["priority"].stringValue()),
            preferredDate = formatMaintenanceDate(preferredDate),
            assignedTo = requestObject["assign"].stringValue(),
            propertyName = requestObject["property"].stringValue(),
            unit = requestObject["unit"].stringValue(),
            tenantName = requestObject["tenant"].stringValue(),
            internalNotes = requestObject["internalNotes"].stringValue(),
            costEstimate = formatCurrency(requestObject["costEstimate"].doubleValue()),
            createdAt = formatDateTime(requestObject["createdAt"].stringValue()),
            updatedAt = formatDateTime(requestObject["updatedAt"].stringValue()),
            photos = parsePhotoUrls(requestObject["photos"]),
            sortDate = primarySortDate
        )

        return ParsedMaintenanceRequest(
            item = item,
            primarySortDate = primarySortDate,
            secondarySortInstant = secondarySortInstant
        )
    }

    private fun pathDate(year: String, month: String, day: String): String? {
        val yearValue = year.trim().toIntOrNull() ?: return null
        val dayValue = day.trim().toIntOrNull() ?: return null
        val monthValue = monthNames.indexOfFirst { it.equals(month.trim(), ignoreCase = true) }
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: return null

        return "%04d-%02d-%02d".format(Locale.ENGLISH, yearValue, monthValue, dayValue)
    }

    private fun parsePhotoUrls(snapshot: JsonElement?): List<String> {
        return when (snapshot) {
            is JsonObject -> snapshot.entries
                .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
                .mapNotNull { (_, value) -> value.stringValue().ifBlank { null } }
            is JsonArray -> snapshot.mapNotNull { value -> value.stringValue().ifBlank { null } }
            else -> emptyList()
        }
    }

    private fun formatMaintenanceDate(raw: String, short: Boolean = false): String {
        if (raw.isBlank()) return "-"

        parseMaintenanceDate(raw)?.let { date ->
            val formatter = if (short) {
                DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
            } else {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
            }
            return date.format(formatter)
        }

        return runCatching {
            Instant.parse(raw)
                .atZone(ZoneId.systemDefault())
                .format(
                    if (short) {
                        DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
                    } else {
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
                    }
                )
        }.getOrElse {
            raw
        }
    }

    private fun formatDateTime(raw: String): String {
        if (raw.isBlank()) return "-"
        return runCatching {
            Instant.parse(raw)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
        }.getOrElse {
            raw
        }
    }

    private fun parseMaintenanceDate(raw: String): LocalDate? {
        if (raw.isBlank()) return null
        return runCatching {
            LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)
        }.getOrNull() ?: runCatching {
            Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDate()
        }.getOrNull()
    }

    private fun parseInstant(raw: String): Instant? {
        if (raw.isBlank()) return null
        return runCatching { Instant.parse(raw) }.getOrNull()
    }

    private fun maintenanceRequestStatus(snapshot: JsonObject): StatusBadgeStyle {
        return when (snapshot["status"].stringValue().trim().lowercase(Locale.ROOT)) {
            "completed", "complete", "resolved", "closed" -> StatusBadgeStyle.Completed
            "in progress", "in_progress", "in-progress", "assigned" -> StatusBadgeStyle.InProgress
            "due", "overdue" -> StatusBadgeStyle.Due
            "paid" -> StatusBadgeStyle.Paid
            "pending", "submitted", "sent", "open", "" -> {
                if (snapshot["assign"].stringValue().isNotBlank()) StatusBadgeStyle.InProgress else StatusBadgeStyle.Pending
            }
            else -> {
                if (snapshot["assign"].stringValue().isNotBlank()) StatusBadgeStyle.InProgress else StatusBadgeStyle.Pending
            }
        }
    }

    private fun localizedMaintenanceCategory(rawCategory: String): String {
        return when (rawCategory.trim().lowercase(Locale.ROOT)) {
            "plumbing" -> MaintenanceCategory.Plumbing.localizedTitle
            "electrical" -> MaintenanceCategory.Electrical.localizedTitle
            "hvac" -> MaintenanceCategory.Hvac.localizedTitle
            "heating" -> MaintenanceCategory.Heating.localizedTitle
            "cooling" -> MaintenanceCategory.Cooling.localizedTitle
            "appliance repair", "appliance_repair", "appliances" -> MaintenanceCategory.ApplianceRepair.localizedTitle
            "general repair", "general_repair" -> MaintenanceCategory.GeneralRepair.localizedTitle
            "carpentry" -> MaintenanceCategory.Carpentry.localizedTitle
            "painting" -> MaintenanceCategory.Painting.localizedTitle
            "flooring" -> MaintenanceCategory.Flooring.localizedTitle
            "locksmith" -> MaintenanceCategory.Locksmith.localizedTitle
            "pest control", "pest_control" -> MaintenanceCategory.PestControl.localizedTitle
            "cleaning" -> MaintenanceCategory.Cleaning.localizedTitle
            "roofing" -> MaintenanceCategory.Roofing.localizedTitle
            "exterior" -> MaintenanceCategory.Exterior.localizedTitle
            "landscaping" -> MaintenanceCategory.Landscaping.localizedTitle
            "snow removal", "snow_removal" -> MaintenanceCategory.SnowRemoval.localizedTitle
            "drywall" -> MaintenanceCategory.Drywall.localizedTitle
            "water damage", "water_damage" -> MaintenanceCategory.WaterDamage.localizedTitle
            "mold" -> MaintenanceCategory.Mold.localizedTitle
            "inspection" -> MaintenanceCategory.Inspection.localizedTitle
            "preventive maintenance", "preventive_maintenance" -> MaintenanceCategory.PreventiveMaintenance.localizedTitle
            "emergency" -> MaintenanceCategory.Emergency.localizedTitle
            "refund request", "refund_request" -> "Refund Request"
            "other" -> MaintenanceCategory.Other.localizedTitle
            else -> rawCategory.trim().ifBlank { MaintenanceCategory.Other.localizedTitle }
        }
    }

    private fun localizedMaintenancePriority(rawPriority: String): String {
        return when (rawPriority.trim().lowercase(Locale.ROOT)) {
            "low" -> MaintenancePriority.Low.localizedTitle
            "medium" -> MaintenancePriority.Medium.localizedTitle
            "high" -> MaintenancePriority.High.localizedTitle
            "urgent" -> MaintenancePriority.Urgent.localizedTitle
            "emergency" -> MaintenancePriority.Emergency.localizedTitle
            else -> rawPriority.trim().replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }.ifBlank { MaintenancePriority.Low.localizedTitle }
        }
    }

    private fun formatCurrency(value: Double?): String {
        return value?.let {
            NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }.format(it)
        } ?: "-"
    }

    private fun firstNonBlank(vararg values: String): String {
        return values.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun JsonElement?.stringValue(): String {
        return this?.jsonPrimitive?.content?.trim().orEmpty()
    }

    private fun JsonElement?.doubleValue(): Double? {
        return this?.jsonPrimitive?.doubleOrNull
            ?: this?.jsonPrimitive?.content?.toDoubleOrNull()
    }

    private data class MaintenanceRequestLeaf(
        val id: String,
        val snapshot: JsonObject,
        val fallbackDate: String?
    )

    private data class ParsedMaintenanceRequest(
        val item: MaintenanceRequestItem,
        val primarySortDate: LocalDate?,
        val secondarySortInstant: Instant?
    )

    private val monthNames = listOf(
        "January",
        "February",
        "March",
        "April",
        "May",
        "June",
        "July",
        "August",
        "September",
        "October",
        "November",
        "December"
    )
}
