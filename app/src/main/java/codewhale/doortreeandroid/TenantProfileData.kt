package codewhale.doortreeandroid

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

data class TenantProfileEvent(val id: String, val title: String, val category: String, val status: String, val date: String)
data class TenantProfileDocument(val id: String, val name: String, val type: String, val date: String, val url: String, val storagePath: String)
data class TenantProfilePayment(val amount: Double, val date: String, val method: String)
data class TenantProfileCharge(
    val id: String, val property: String, val unit: String, val dueDate: String,
    val amount: Double, val balance: Double, val status: String, val paidAt: String,
    val payments: List<TenantProfilePayment>
)
data class TenantProfileData(
    val id: String,
    val tenant: Map<String, Any?>,
    val property: Map<String, Any?>,
    val events: List<TenantProfileEvent>,
    val documents: List<TenantProfileDocument>,
    val charges: List<TenantProfileCharge>,
    val rentPayment: Map<String, Any?>,
    val sectionErrors: Map<String, String> = emptyMap()
) {
    fun tenantText(key: String) = tenant.text(key)
    fun tenantMoney(key: String) = tenant.amount(key)
    fun propertyText(key: String) = property.text(key)
    fun propertyMoney(key: String) = property.amount(key)
    fun paymentText(key: String) = rentPayment.text(key)
    val parking: Map<String, Any?> get() = tenant["parking"].asRecord().ifEmpty {
        property["parking"].asRecords().firstOrNull { (_, spot) ->
            spot.text("assignmentType") == "tenant" &&
                (spot.text("tenantId") == id ||
                    (tenantText("directoryId").isNotBlank() && spot.text("tenantId") == tenantText("directoryId")) ||
                    (authUid.isNotBlank() && spot.text("tenantUid") == authUid))
        }?.second ?: emptyMap()
    }
    fun parkingText(key: String) = parking.text(key)
    fun parkingMoney(key: String) = parking.amount(key)
    val name get() = listOf(tenantText("firstName"), tenantText("lastName")).filter(String::isNotBlank).joinToString(" ").ifBlank { "Tenant" }
    val propertyName get() = tenantText("propertyName").ifBlank { property.text("propertyName") }
    val unit get() = tenantText("unitNumber").ifBlank { tenantText("unit") }
    val authUid get() = tenantText("authUserId")
    val onTimeStreak: Int get() {
        var count = 0
        for (charge in charges.filter { it.dueDate.take(10) <= java.time.LocalDate.now().toString() }) {
            if (charge.status != "paid" || charge.balance > 0) break
            var paid = 0.0
            val paidDate = charge.payments.firstOrNull {
                paid += it.amount
                paid >= charge.amount
            }?.date ?: charge.paidAt
            if (paidDate.isBlank() || paidDate.take(10) > charge.dueDate.take(10)) break
            count++
        }
        return count
    }
}

private fun Any?.asRecord(): Map<String, Any?> = (this as? Map<*, *>)?.entries
    ?.associate { it.key.toString() to it.value } ?: emptyMap()
private fun Any?.asRecords(): List<Pair<String, Map<String, Any?>>> = when (this) {
    is Map<*, *> -> entries.mapNotNull { (key, value) ->
        if (value is Map<*, *>) key.toString() to value.asRecord() else null
    }
    is List<*> -> mapIndexedNotNull { index, value ->
        if (value is Map<*, *>) index.toString() to value.asRecord() else null
    }
    else -> emptyList()
}
private fun Map<String, Any?>.text(key: String): String = when (val value = this[key]) {
    is String -> value.trim()
    is Number -> value.toString()
    else -> ""
}
private fun Map<String, Any?>.amount(key: String): Double = when (val value = this[key]) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull() ?: 0.0
    else -> 0.0
}

internal suspend fun loadTenantProfile(
    tenantId: String,
    cachedTenant: Map<String, Any?>? = null,
    onInitial: (TenantProfileData) -> Unit = {}
): TenantProfileData {
    val landlordUid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Sign in again to view this tenant")
    val root = FirebaseDatabase.getInstance(FirebaseConfig.databaseUrl).reference
    val tenant = cachedTenant?.takeIf { it.isNotEmpty() }
        ?: root.child("users/$landlordUid/tenants/$tenantId").get().await().value.asRecord()
    check(tenant.isNotEmpty()) { "This tenant is no longer available" }
    val authUid = tenant.text("authUserId")
    val propertyId = tenant.text("property").ifBlank { tenant.text("propertyId") }
    val documents = tenant["documents"].asRecords().map { (id, record) ->
        TenantProfileDocument(id, record.text("name").ifBlank { "Document" }, record.text("documentType"),
            record.text("date"), record.text("url"), record.text("storagePath"))
    }.sortedByDescending { it.date }
    val localEvents = parseProfileEvents(tenant["events"])
    onInitial(TenantProfileData(tenantId, tenant, emptyMap(), localEvents, documents, emptyList(), emptyMap()))
    return coroutineScope {
        val errors = mutableMapOf<String, String>()
        suspend fun readOptional(path: String, section: String): Any? = try {
            root.child(path).get().await().value
        } catch (exception: Exception) {
            Log.w("TenantProfile", "Could not load $section", exception)
            errors[section] = "Could not load $section. Pull to refresh and try again."
            null
        }
        val propertyTask = async { if (propertyId.isBlank()) emptyMap() else readOptional("users/$landlordUid/properties/$propertyId", "assignment").asRecord() }
        val ledgerTask = async { readOptional("users/$landlordUid/rent/ledger", "rent history") }
        val paymentTask = async { if (authUid.isBlank()) emptyMap() else readOptional("users/$authUid/rentPayment", "rent payments").asRecord() }
        val tenantEventsTask = async { if (authUid.isBlank()) null else readOptional("users/$authUid/events", "events") }

        val events = (localEvents + parseProfileEvents(tenantEventsTask.await()))
            .associateBy { "${it.title}|${it.date}|${it.status}" }.values.sortedByDescending { it.date }
        val charges = ledgerTask.await().asRecords().flatMap { (_, month) ->
            month.asRecords().mapNotNull { (id, record) ->
                if (authUid.isBlank() || record.text("tenantUid") != authUid || record.text("status") == "void") return@mapNotNull null
                val payments = record["paymentHistory"].asRecords().map { (_, payment) ->
                    TenantProfilePayment(payment.amount("amount"), payment.text("date"), payment.text("method"))
                }.sortedBy { it.date }
                TenantProfileCharge(id, record.text("propertyName"), record.text("unitNumber"), record.text("dueDate"),
                    record.amount("amount"), record.amount("balance"), record.text("status"), record.text("paidAt"), payments)
            }
        }.sortedByDescending { it.dueDate }
        TenantProfileData(tenantId, tenant, propertyTask.await(), events,
            documents, charges, paymentTask.await(), errors.toMap())
    }
}

private fun parseProfileEvents(raw: Any?): List<TenantProfileEvent> {
    val events = linkedMapOf<String, TenantProfileEvent>()
    val record = raw.asRecord()
    record["timeline"].asRecords().forEach { (id, event) ->
        val date = event.text("occurredAt").ifBlank { event.text("timestamp") }
        if (date.isNotBlank()) {
            val type = event.text("type")
            events["$type|$date"] = TenantProfileEvent(id, event.text("title").ifBlank { eventTitle(type) },
                event.text("category"), event.text("status"), date)
        }
    }
    record.forEach { (type, value) ->
        if (type != "timeline" && value is String && value.isNotBlank()) {
            events.putIfAbsent("$type|$value", TenantProfileEvent(type, eventTitle(type), "", eventStatus(type), value))
        }
    }
    return events.values.sortedByDescending { it.date }
}

private fun eventTitle(type: String): String = when (type) {
    "rl31Sent" -> "RL-31 form sent"
    "rl31Read" -> "RL-31 form read"
    "rentIncreaseAccepted" -> "Rent increase accepted"
    "rentIncreaseRefused" -> "Rent increase refused"
    "rentIncreaseSent" -> "Rent increase notice sent"
    "rentIncreaseRead" -> "Rent increase notice read"
    "rentIncreaseSigned" -> "Rent increase notice signed"
    "tenantNoticeSent" -> "Notice sent"
    "tenantNoticeRead" -> "Notice read"
    else -> type.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar(Char::uppercase)
}
private fun eventStatus(type: String): String = when {
    type.endsWith("Sent") -> "sent"
    type.endsWith("Read") -> "read"
    type.endsWith("Accepted") -> "accepted"
    type.endsWith("Refused") -> "refused"
    type.endsWith("Signed") -> "signed"
    else -> "recorded"
}
