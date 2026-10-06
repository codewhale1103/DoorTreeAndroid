package codewhale.doortreeandroid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.tasks.await
import java.time.Instant

data class LandlordProperty(
    val id: String,
    val name: String,
    val address: String,
    val unitCount: Int,
    val occupiedCount: Int
)

data class LandlordTenant(
    val id: String,
    val name: String,
    val propertyName: String,
    val unit: String,
    val email: String,
    val phone: String,
    val leaseEnd: String,
    val monthlyRent: Double,
    val active: Boolean
)

data class LandlordMessage(
    val id: String,
    val text: String,
    val sentAt: String,
    val isIncoming: Boolean,
    val isRead: Boolean
)

data class LandlordConversation(
    val id: String,
    val name: String,
    val propertyName: String,
    val unit: String,
    val preview: String,
    val updatedAt: Long,
    val unreadCount: Int,
    val messages: List<LandlordMessage>
)

data class LandlordRequest(
    val id: String,
    val title: String,
    val description: String,
    val tenant: String,
    val property: String,
    val unit: String,
    val date: String,
    val status: String,
    val priority: String
)

data class LandlordEvent(
    val id: String,
    val title: String,
    val startDate: String,
    val property: String,
    val unit: String,
    val tenant: String,
    val status: String,
    val notes: String
)

data class LandlordRentCharge(
    val id: String,
    val tenantName: String,
    val tenantUid: String,
    val dueDate: String,
    val amount: Double,
    val balance: Double,
    val status: String,
    val propertyId: String,
    val propertyName: String,
    val unitNumber: String,
    val paidAt: String,
    val paymentMethod: String,
    val paymentHistory: List<LandlordRentPayment>
)

data class LandlordRentPayment(val id: String, val date: String, val amount: Double, val method: String)

class LandlordDataStore {
    private val database = FirebaseDatabase.getInstance(FirebaseConfig.databaseUrl).reference
    private val observers = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private val received = mutableSetOf<String>()
    private var activeUid: String? = null

    var firstName by mutableStateOf("")
        private set
    var properties by mutableStateOf<List<LandlordProperty>>(emptyList())
        private set
    var propertyRecords by mutableStateOf<Map<String, Map<String, Any?>>>(emptyMap())
        private set
    var tenants by mutableStateOf<List<LandlordTenant>>(emptyList())
        private set
    var tenantRecords by mutableStateOf<Map<String, Map<String, Any?>>>(emptyMap())
        private set
    var conversations by mutableStateOf<List<LandlordConversation>>(emptyList())
        private set
    var requests by mutableStateOf<List<LandlordRequest>>(emptyList())
        private set
    var events by mutableStateOf<List<LandlordEvent>>(emptyList())
        private set
    var rentCharges by mutableStateOf<List<LandlordRentCharge>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    val unreadMessageCount: Int get() = conversations.sumOf { it.unreadCount }
    val openRequestCount: Int get() = requests.count { !it.status.equals("resolved", true) }
    val activeTenantCount: Int get() = tenants.count { it.active }

    fun start(uid: String) {
        if (activeUid == uid) return
        stop()
        activeUid = uid
        isLoading = true
        loadError = null
        observe("firstName") { firstName = it as? String ?: "" }
        observe("properties") {
            properties = parseProperties(it)
            propertyRecords = records(it).toMap()
        }
        observe("tenants") {
            tenants = parseTenants(it)
            tenantRecords = records(it).toMap()
        }
        observe("messages") { conversations = parseConversations(it, uid) }
        observe("tenantRequests") { requests = parseRequests(it) }
        observe("calendar") { events = parseEvents(it) }
        observe("rent/ledger") { rentCharges = parseRentCharges(it) }
    }

    fun stop() {
        observers.forEach { (reference, listener) -> reference.removeEventListener(listener) }
        observers.clear()
        received.clear()
        activeUid = null
        firstName = ""
        properties = emptyList()
        propertyRecords = emptyMap()
        tenants = emptyList()
        tenantRecords = emptyMap()
        conversations = emptyList()
        requests = emptyList()
        events = emptyList()
        rentCharges = emptyList()
        isLoading = false
        loadError = null
    }

    fun reload() {
        val uid = activeUid ?: return
        stop()
        start(uid)
    }

    suspend fun sendMessage(participantUid: String, rawText: String) {
        val uid = activeUid ?: return
        val text = rawText.trim()
        if (text.isEmpty()) return
        val timestamp = System.currentTimeMillis()
        val sentAt = Instant.ofEpochMilli(timestamp).toString()
        val messageId = database.push().key ?: return
        val tenantMessage = mapOf(
            "text" to text,
            "senderUserId" to uid,
            "senderRole" to "landlord",
            "timestamp" to timestamp,
            "sentAt" to sentAt,
            "read" to false
        )
        val landlordPath = "users/$uid/messages/$participantUid"
        val tenantPath = "users/$participantUid/messages/$uid"
        database.updateChildren(mapOf(
            "$landlordPath/messages/$messageId" to tenantMessage.plus("read" to true),
            "$landlordPath/lastMessage" to text,
            "$landlordPath/lastMessageTimestamp" to timestamp,
            "$landlordPath/updatedAt" to timestamp,
            "$landlordPath/participantId" to participantUid,
            "$tenantPath/messages/$messageId" to tenantMessage,
            "$tenantPath/lastMessage" to text,
            "$tenantPath/lastMessageTimestamp" to timestamp,
            "$tenantPath/updatedAt" to timestamp,
            "$tenantPath/participantId" to uid
        )).await()
    }

    fun markConversationRead(conversation: LandlordConversation) {
        val uid = activeUid ?: return
        val updates = conversation.messages
            .filter { it.isIncoming && !it.isRead }
            .associate { "users/$uid/messages/${conversation.id}/messages/${it.id}/read" to true }
        if (updates.isNotEmpty()) database.updateChildren(updates)
    }

    private fun observe(path: String, update: (Any?) -> Unit) {
        val uid = activeUid ?: return
        val reference = database.child("users").child(uid).child(path)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (activeUid != uid) return
                update(snapshot.value)
                received.add(path)
                isLoading = received.size < 7
            }

            override fun onCancelled(error: DatabaseError) {
                if (activeUid != uid) return
                loadError = error.message
                received.add(path)
                isLoading = received.size < 7
            }
        }
        reference.addValueEventListener(listener)
        observers.add(reference to listener)
    }
}

private fun records(value: Any?): List<Pair<String, Map<String, Any?>>> = when (value) {
    is Map<*, *> -> value.mapNotNull { (key, item) ->
        val record = item as? Map<*, *> ?: return@mapNotNull null
        key.toString() to record.entries.associate { it.key.toString() to it.value }
    }
    is List<*> -> value.mapIndexedNotNull { index, item ->
        val record = item as? Map<*, *> ?: return@mapIndexedNotNull null
        index.toString() to record.entries.associate { it.key.toString() to it.value }
    }
    else -> emptyList()
}

private fun children(value: Any?): List<Pair<String, Any?>> = when (value) {
    is Map<*, *> -> value.map { it.key.toString() to it.value }
    is List<*> -> value.mapIndexed { index, item -> index.toString() to item }
    else -> emptyList()
}

private fun Map<String, Any?>.string(key: String): String = (this[key] as? String)?.trim().orEmpty()
private fun Map<String, Any?>.number(key: String): Double = when (val value = this[key]) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull() ?: 0.0
    else -> 0.0
}

private fun parseProperties(value: Any?): List<LandlordProperty> = records(value).map { (id, record) ->
    val units = records(record["units"])
    LandlordProperty(
        id = id,
        name = record.string("propertyName").ifBlank { "Property" },
        address = listOf(record.string("streetAddress"), record.string("city")).filter(String::isNotBlank).joinToString(", "),
        unitCount = maxOf(record.number("numberOfUnits").toInt(), units.size),
        occupiedCount = units.count { (_, unit) ->
            unit.string("status").equals("occupied", true) || records(unit["tenants"]).isNotEmpty()
        }
    )
}.sortedBy { it.name.lowercase() }

private fun parseTenants(value: Any?): List<LandlordTenant> = records(value).map { (id, record) ->
    LandlordTenant(
        id = id,
        name = listOf(record.string("firstName"), record.string("lastName")).filter(String::isNotBlank).joinToString(" ").ifBlank { "Tenant" },
        propertyName = record.string("propertyName"),
        unit = record.string("unitNumber").ifBlank { record.string("unit") },
        email = record.string("email"),
        phone = record.string("phoneNumber"),
        leaseEnd = record.string("leaseEnd"),
        monthlyRent = record.number("rentAmount"),
        active = record["leaseEnded"] != true && record.string("property").isNotBlank()
    )
}.sortedBy { it.name.lowercase() }

private fun parseConversations(value: Any?, ownerUid: String): List<LandlordConversation> = records(value).map { (id, record) ->
    val messages = records(record["messages"]).map { (messageId, message) ->
        LandlordMessage(
            id = messageId,
            text = message.string("text"),
            sentAt = message.string("sentAt"),
            isIncoming = message.string("senderUserId") != ownerUid,
            isRead = message["read"] == true
        )
    }.sortedBy { it.sentAt }
    LandlordConversation(
        id = id,
        name = record.string("participantName").ifBlank { "Tenant" },
        propertyName = record.string("propertyName"),
        unit = record.string("unitNumber"),
        preview = record.string("lastMessage"),
        updatedAt = record.number("updatedAt").toLong(),
        unreadCount = messages.count { it.isIncoming && !it.isRead },
        messages = messages
    )
}.sortedByDescending { it.updatedAt }

private fun parseRequests(value: Any?): List<LandlordRequest> = buildList {
    records(value).forEach { (_, year) ->
        records(year).forEach { (_, month) ->
            records(month).forEach { (_, day) ->
                records(day).forEach { (id, request) ->
                    add(LandlordRequest(
                        id = id,
                        title = request.string("title").ifBlank { request.string("category").ifBlank { "Tenant request" } },
                        description = request.string("description"),
                        tenant = request.string("tenant"),
                        property = request.string("property"),
                        unit = request.string("unit"),
                        date = request.string("createdAt").ifBlank { request.string("date") },
                        status = request.string("status"),
                        priority = request.string("priority")
                    ))
                }
            }
        }
    }
}.sortedByDescending { it.date }

private fun parseEvents(value: Any?): List<LandlordEvent> = buildList {
    records(value).filter { it.first.toIntOrNull() != null }.forEach { (year, days) ->
        children(days).forEach { (day, events) ->
            records(events).forEach { (id, event) ->
                add(LandlordEvent(
                    id = "$year-$day-$id",
                    title = event.string("title").ifBlank { "Event" },
                    startDate = event.string("startDate").ifBlank { event.string("occurrenceDate") },
                    property = event.string("property"),
                    unit = event.string("unit"),
                    tenant = event.string("tenant"),
                    status = event.string("status"),
                    notes = event.string("notes")
                ))
            }
        }
    }
}.sortedBy { it.startDate }

private fun parseRentCharges(value: Any?): List<LandlordRentCharge> = buildList {
    records(value).forEach { (_, month) ->
        records(month).forEach { (id, charge) ->
            add(LandlordRentCharge(
                id = id,
                tenantName = charge.string("tenantName"),
                tenantUid = charge.string("tenantUid"),
                dueDate = charge.string("dueDate"),
                amount = charge.number("amount"),
                balance = charge.number("balance"),
                status = charge.string("status"),
                propertyId = charge.string("propertyId"),
                propertyName = charge.string("propertyName"),
                unitNumber = charge.string("unitNumber"),
                paidAt = charge.string("paidAt"),
                paymentMethod = charge.string("paymentMethod"),
                paymentHistory = records(charge["paymentHistory"]).map { (paymentId, payment) ->
                    LandlordRentPayment(paymentId, payment.string("date"), payment.number("amount"), payment.string("method"))
                }.sortedBy { it.date }
            ))
        }
    }
}.sortedByDescending { it.dueDate }
