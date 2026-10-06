package codewhale.doortreeandroid

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import codewhale.doortreeandroid.ui.theme.DoorTreeTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONObject

private fun pText(record: Map<String, Any?>, key: String): String = record[key]?.toString()?.trim().orEmpty()
private fun pRecords(value: Any?): List<Pair<String, Map<String, Any?>>> = when (value) {
    is Map<*, *> -> value.mapNotNull { (key, item) ->
        if (item is Map<*, *>) key.toString() to item.entries.associate { it.key.toString() to it.value } else null
    }
    is List<*> -> value.mapIndexedNotNull { index, item ->
        if (item is Map<*, *>) index.toString() to item.entries.associate { it.key.toString() to it.value } else null
    }
    else -> emptyList()
}

private fun occupantNames(value: Any?): List<String> = when (value) {
    is String -> listOf(value).filter(String::isNotBlank)
    is Map<*, *> -> {
        val record = value.entries.associate { it.key.toString() to it.value }
        if (record.containsKey("firstName") || record.containsKey("lastName") || record.containsKey("name")) {
            listOf(listOf(pText(record, "firstName"), pText(record, "lastName")).filter(String::isNotBlank).joinToString(" ").ifBlank { pText(record, "name") }).filter(String::isNotBlank)
        } else record.values.flatMap(::occupantNames)
    }
    is List<*> -> value.flatMap(::occupantNames)
    else -> emptyList()
}

private data class PropertyRequest(val id: String, val path: String, val data: Map<String, Any?>) {
    val issue: String get() = pText(data, "issue").ifBlank { pText(data, "title") }
    val date: String get() = pText(data, "date").ifBlank { pText(data, "createdAt").take(10) }
}

private fun flattenRequests(uid: String, collection: String, value: Any?): List<PropertyRequest> = buildList {
    pRecords(value).forEach { (year, months) ->
        pRecords(months).forEach { (month, days) ->
            pRecords(days).forEach { (day, requests) ->
                pRecords(requests).forEach { (id, data) ->
                    add(PropertyRequest(id, "users/$uid/maintenance/$collection/$year/$month/$day/$id", data))
                }
            }
        }
    }
}

private fun legacyPropertyRequests(value: Any?): List<PropertyRequest> = buildList {
    fun walk(node: Any?, id: String) {
        val data = pRecords(mapOf("item" to node)).firstOrNull()?.second ?: return
        if (pText(data, "issue").isNotBlank() || pText(data, "title").isNotBlank()) {
            add(PropertyRequest(id, "legacy/$id", data))
        } else pRecords(data).forEach { (key, child) -> walk(child, key) }
    }
    pRecords(value).forEach { (id, data) -> walk(data, id) }
}

private suspend fun sendConciergeJob(propertyId: String, requestId: String, requestDate: String, conciergeId: String): String = withContext(Dispatchers.IO) {
    val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in again to send this request.")
    val idToken = user.getIdToken(false).await().token ?: error("Could not verify your sign-in.")
    val payload = JSONObject().put("data", JSONObject()
        .put("propertyId", propertyId)
        .put("requestId", requestId)
        .put("requestDate", requestDate)
        .put("conciergeId", conciergeId))
    val connection = URL("https://us-central1-doortree-44647.cloudfunctions.net/sendMaintenanceRequestToConciergeFromMobile")
        .openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $idToken")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
        if (response.has("error")) error(response.getJSONObject("error").optString("message", "Could not send this request."))
        response.optJSONObject("result")?.optString("jobId")?.takeIf(String::isNotBlank)
            ?: error("The server did not create an email job.")
    } finally { connection.disconnect() }
}

@Composable
fun AddLandlordPropertyDialog(store: LandlordDataStore, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("House") }
    var street by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var province by remember { mutableStateOf("QC") }
    var postal by remember { mutableStateOf("") }
    var unitCount by remember { mutableStateOf("1") }
    var firstUnit by remember { mutableStateOf("1") }
    var lot by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var manager by remember { mutableStateOf("") }
    var rentDue by remember { mutableStateOf("") }
    var lateFee by remember { mutableStateOf("") }
    var purchasePrice by remember { mutableStateOf("") }
    var purchaseDate by remember { mutableStateOf("") }
    var mortgageAmount by remember { mutableStateOf("") }
    var mortgageTerm by remember { mutableStateOf("") }
    var mortgageInterest by remember { mutableStateOf("") }
    var institution by remember { mutableStateOf("") }
    var tax by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var photoDataURL by remember { mutableStateOf("") }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                val original = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                    ?: error("Could not open photo")
                val scale = minOf(1f, 1200f / maxOf(original.width, original.height))
                val image = if (scale < 1f) Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true) else original
                var bytes = ByteArray(0)
                for (quality in listOf(85, 70, 55, 40, 25)) {
                    val output = ByteArrayOutputStream()
                    image.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    bytes = output.toByteArray()
                    if (bytes.size <= 1_000_000) break
                }
                require(bytes.size <= 1_000_000) { "Choose a smaller photo. The web property limit is 1 MB." }
                photoDataURL = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }.onFailure { error = it.localizedMessage ?: "Could not load photo" }
        }
    }
    val types = listOf("House", "Duplex", "Triplex", "Apartment Building", "Condo", "Multi-unit", "Industrial", "Commercial")
    val count = unitCount.toIntOrNull() ?: 0
    val first = firstUnit.toIntOrNull()
    val valid = name.isNotBlank() && street.isNotBlank() && city.isNotBlank() && province.isNotBlank() && postal.isNotBlank() && count in 1..500 && first != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add property") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                item { Text("Property", fontWeight = FontWeight.Bold) }
                item { OutlinedTextField(name, { name = it }, label = { Text("Property name") }, modifier = Modifier.fillMaxWidth()) }
                item { Text("Type: $type", color = DoorTreeTheme.textSecondary) }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(types) { choice -> FilterChip(type == choice, onClick = { type = choice }, label = { Text(choice) }) } } }
                item { OutlinedTextField(lot, { lot = it }, label = { Text("Lot number") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(unitCount, { unitCount = it.filter(Char::isDigit) }, label = { Text("Number of units") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(firstUnit, { firstUnit = it.filter(Char::isDigit) }, label = { Text("First unit number") }, modifier = Modifier.fillMaxWidth()) }
                if (count > 0 && first != null) item { Text("Units $first–${first + count - 1}", color = DoorTreeTheme.textSecondary) }
                if (type == "Commercial" || type == "Industrial") item { FilterChip(tax, onClick = { tax = !tax }, label = { Text("Apply tax on rent") }) }
                item { TextButton(onClick = { photoPicker.launch("image/*") }) { Text(if (photoDataURL.isBlank()) "Add property photo" else "Change property photo") } }
                item { Text("Address", fontWeight = FontWeight.Bold) }
                item { OutlinedTextField(street, { street = it }, label = { Text("Street address") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(city, { city = it }, label = { Text("City") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(province, { province = it }, label = { Text("Province") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(postal, { postal = it }, label = { Text("Postal code") }, modifier = Modifier.fillMaxWidth()) }
                item { Text("Management", fontWeight = FontWeight.Bold) }
                item { OutlinedTextField(company, { company = it }, label = { Text("Company") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(manager, { manager = it }, label = { Text("Property manager") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(rentDue, { rentDue = it.filter(Char::isDigit) }, label = { Text("Rent due day") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(lateFee, { lateFee = it }, label = { Text("Late fee") }, modifier = Modifier.fillMaxWidth()) }
                item { Text("Purchase and mortgage · optional", fontWeight = FontWeight.Bold) }
                item { OutlinedTextField(purchasePrice, { purchasePrice = it }, label = { Text("Purchase price") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(purchaseDate, { purchaseDate = it }, label = { Text("Purchase date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(mortgageAmount, { mortgageAmount = it }, label = { Text("Mortgage amount") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(mortgageTerm, { mortgageTerm = it }, label = { Text("Mortgage term (years)") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(mortgageInterest, { mortgageInterest = it }, label = { Text("Mortgage interest") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(institution, { institution = it }, label = { Text("Institution") }, modifier = Modifier.fillMaxWidth()) }
                if (error != null) item { Text(error.orEmpty(), color = DoorTreeTheme.destructive) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !saving, onClick = {
                scope.launch {
                    saving = true
                    try {
                        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Sign in again to add a property.")
                        val now = Instant.now().toString()
                        val units = (0 until count).associate { index ->
                            index.toString() to mapOf<String, Any?>(
                                "unitNumber" to (first!! + index).toString(), "bedrooms" to null, "bathrooms" to null,
                                "squarefootage" to null, "rentAmount" to 0, "securityDeposit" to null,
                                "amenities" to mapOf("heatingIncluded" to false, "electricityIncluded" to false,
                                    "washerDryerIncluded" to false, "fridgeIncluded" to false, "stoveIncluded" to false,
                                    "dishwasherIncluded" to false), "notes" to "", "status" to "Vacant",
                                "tenants" to emptyMap<String, Any>(), "tenantHistory" to emptyList<Any>(),
                                "rentHistory" to emptyList<Any>(), "document" to null, "photos" to emptyList<Any>(), "updatedAt" to now
                            )
                        }
                        val payload = mapOf<String, Any?>(
                            "propertyName" to name.trim(), "lotNumber" to lot.trim(), "propertyType" to type,
                            "applyTaxOnRent" to ((type == "Commercial" || type == "Industrial") && tax),
                            "numberOfUnits" to count, "streetAddress" to street.trim(), "city" to city.trim(),
                            "province" to province.trim(), "postalCode" to postal.trim(),
                            "purchasePrice" to purchasePrice.toDoubleOrNull(), "purchaseDate" to purchaseDate.trim(),
                            "mortgageAmount" to mortgageAmount.toDoubleOrNull(), "mortgageTerm" to mortgageTerm.toDoubleOrNull(),
                            "mortgageInterest" to mortgageInterest.toDoubleOrNull(), "institution" to institution.trim(),
                            "propertyManager" to manager.trim(), "company" to company.trim().ifBlank {
                                store.propertyRecords.values.mapNotNull { it["company"] as? String }.firstOrNull(String::isNotBlank).orEmpty()
                            },
                            "rentDueDate" to rentDue.toIntOrNull(), "lateFeeAmount" to lateFee.toDoubleOrNull(),
                            "photo" to photoDataURL, "photoStoragePath" to "", "units" to units, "unitIndexCounter" to count - 1,
                            "leases" to emptyList<Any>(), "maintenance" to emptyList<Any>(), "concierge" to emptyMap<String, Any>(),
                            "notes" to emptyList<Any>(), "documents" to emptyList<Any>(), "photos" to emptyList<Any>(),
                            "createdAt" to now, "updatedAt" to now
                        )
                        FirebaseDatabase.getInstance(FirebaseConfig.databaseUrl).reference.child("users/$uid/properties").push().setValue(payload).await()
                        onDismiss()
                    } catch (exception: Exception) { error = exception.localizedMessage ?: "Could not save property" }
                    saving = false
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun LandlordPropertyWorkspaceScreen(propertyId: String, store: LandlordDataStore, onOpenTenant: (String) -> Unit) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val property = store.properties.firstOrNull { it.id == propertyId }
    val record = store.propertyRecords[propertyId].orEmpty()
    val name = property?.name ?: pText(record, "propertyName")
    var tab by remember(propertyId) { mutableStateOf("Units") }
    var selectedRequest by remember(propertyId) { mutableStateOf<PropertyRequest?>(null) }
    var active by remember(propertyId) { mutableStateOf<List<PropertyRequest>>(emptyList()) }
    var completed by remember(propertyId) { mutableStateOf<List<PropertyRequest>>(emptyList()) }
    var loadError by remember(propertyId) { mutableStateOf<String?>(null) }

    DisposableEffect(uid, propertyId) {
        if (uid == null) return@DisposableEffect onDispose { }
        val database = FirebaseDatabase.getInstance(FirebaseConfig.databaseUrl).reference
        val subscriptions = listOf("requests", "completed").map { collection ->
            val ref = database.child("users/$uid/maintenance/$collection")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val items = flattenRequests(uid, collection, snapshot.value)
                    if (collection == "requests") active = items else completed = items
                }
                override fun onCancelled(error: DatabaseError) { loadError = error.message }
            }
            ref.addValueEventListener(listener)
            ref to listener
        }
        onDispose { subscriptions.forEach { (ref, listener) -> ref.removeEventListener(listener) } }
    }

    Column(Modifier.fillMaxSize()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            items(listOf("Units", "Tenants", "Maintenance", "Renewals")) { choice ->
                FilterChip(tab == choice, onClick = { tab = choice }, label = { Text(choice) })
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth().glassCard(cornerRadius = 17.dp).padding(16.dp)) {
                    Text(name, color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 23.sp)
                    Text(property?.address.orEmpty(), color = DoorTreeTheme.textSecondary)
                }
            }
            when (tab) {
                "Units" -> {
                    val units = pRecords(record["units"]).sortedBy { it.first.toIntOrNull() ?: 0 }
                    if (units.isEmpty()) item { PropertyEmpty("No units yet") }
                    items(units, key = { it.first }) { (_, unit) ->
                        val number = pText(unit, "unitNumber")
                        val nested = occupantNames(unit["tenants"] ?: unit["tenant"])
                        val fallback = store.tenants.filter { tenant ->
                            val raw = store.tenantRecords[tenant.id].orEmpty()
                            (pText(raw, "property") == propertyId || tenant.propertyName.equals(name, true)) && tenant.unit == number && tenant.active
                        }.map { it.name }
                        val occupants = (nested.ifEmpty { fallback }).distinct().sorted()
                        val status = pText(unit, "status").ifBlank { if (occupants.isEmpty()) "Vacant" else "Occupied" }
                        val occupantText = if (occupants.isEmpty()) {
                            if (status.equals("occupied", true)) "Occupant details unavailable" else ""
                        } else "Occupied by ${occupants.joinToString(", ")}"
                        PropertyLine("Unit $number", listOf(status, occupantText).filter(String::isNotBlank).joinToString(" · "))
                    }
                }
                "Tenants" -> {
                    val tenants = store.tenants.filter { tenant ->
                        pText(store.tenantRecords[tenant.id].orEmpty(), "property") == propertyId || tenant.propertyName.equals(name, true)
                    }
                    if (tenants.isEmpty()) item { PropertyEmpty("No tenants for this property") }
                    items(tenants, key = { it.id }) { tenant ->
                        PropertyLine(tenant.name, if (tenant.unit.isBlank()) "" else "Unit ${tenant.unit}") { onOpenTenant(tenant.id) }
                    }
                }
                "Maintenance" -> {
                    val current = (active + completed).filter { request ->
                        pText(request.data, "propertyId") == propertyId ||
                            (pText(request.data, "propertyId").isBlank() && pText(request.data, "property").equals(name, true))
                    }
                    val currentIds = current.mapTo(mutableSetOf()) { it.id }
                    val requests = (current + legacyPropertyRequests(record["maintenance"]).filter { it.id !in currentIds }).sortedByDescending { it.date }
                    if (loadError != null) item { Text(loadError.orEmpty(), color = DoorTreeTheme.destructive) }
                    if (requests.isEmpty()) item { PropertyEmpty("No maintenance requests for this property") }
                    items(requests, key = { it.path }) { request ->
                        PropertyLine(request.issue, listOf(pText(request.data, "tenant"), pText(request.data, "unit"), pText(request.data, "status")).filter(String::isNotBlank).joinToString(" · ")) { selectedRequest = request }
                    }
                }
                "Renewals" -> {
                    val renewals = pRecords(record["renewals"])
                    if (renewals.isEmpty()) item { PropertyEmpty("No renewals for this property") }
                    items(renewals, key = { it.first }) { (_, renewal) ->
                        PropertyLine(pText(renewal, "type").ifBlank { "Renewal" }, listOf(pText(renewal, "renewalDate"), pText(renewal, "frequency"), pText(renewal, "amount")).filter(String::isNotBlank).joinToString(" · "))
                    }
                }
            }
        }
    }
    selectedRequest?.let { request ->
        PropertyMaintenanceDialog(uid.orEmpty(), propertyId, request, pRecords(record["concierge"]), onDismiss = { selectedRequest = null })
    }
}

@Composable
private fun PropertyLine(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().glassCard(cornerRadius = 16.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp)) {
        Text(title, color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotBlank()) Text(subtitle, color = DoorTreeTheme.textSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun PropertyEmpty(message: String) { Text(message, color = DoorTreeTheme.textSecondary, modifier = Modifier.padding(16.dp)) }

@Composable
private fun PropertyMaintenanceDialog(uid: String, propertyId: String, request: PropertyRequest, concierge: List<Pair<String, Map<String, Any?>>>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by remember(request.path) { mutableStateOf("Overview") }
    var choosing by remember(request.path) { mutableStateOf(false) }
    var workerId by remember(request.path) { mutableStateOf(concierge.firstOrNull()?.first.orEmpty()) }
    var sending by remember(request.path) { mutableStateOf(false) }
    var message by remember(request.path) { mutableStateOf<String?>(null) }
    val data = request.data

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (choosing) "Send to Concierge" else request.issue) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (choosing) {
                    Text("Email this request and completion link to an assigned concierge.", color = DoorTreeTheme.textSecondary)
                    concierge.forEach { (id, worker) ->
                        FilterChip(workerId == id, onClick = { workerId = id }, label = { Text(pText(worker, "label").ifBlank { id }) })
                    }
                    concierge.firstOrNull { it.first == workerId }?.second?.let { Text(pText(it, "email"), color = DoorTreeTheme.textSecondary) }
                } else {
                    Text(listOf(pText(data, "category"), pText(data, "property"), pText(data, "unit")).filter(String::isNotBlank).joinToString(" · "), color = DoorTreeTheme.textSecondary)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf("Overview", "Attachments", "Notes")) { choice -> FilterChip(tab == choice, onClick = { tab = choice }, label = { Text(choice) }) }
                    }
                    when (tab) {
                        "Overview" -> {
                            Text("Tenant: ${pText(data, "tenant")}")
                            Text("Assigned to: ${pText(data, "assign")}")
                            Text("Status: ${pText(data, "status")} · ${pText(data, "priority")}")
                            Text("Created: ${request.date}")
                            Text("Scheduled: ${pText(data, "preferredDate")}")
                            Text(pText(data, "description"))
                        }
                        "Attachments" -> {
                            val photos = (data["photos"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                            if (photos.isEmpty()) Text("No attachments yet")
                            photos.forEachIndexed { index, photo ->
                                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(photo))) }) { Text("Open attachment ${index + 1}") }
                            }
                        }
                        else -> Text(pText(data, "internalNotes").ifBlank { "No internal notes" })
                    }
                }
                if (message != null) Text(message.orEmpty(), color = DoorTreeTheme.destructive)
                if (tab == "Overview" && !choosing) {
                    if (concierge.isEmpty()) Text("Assign a concierge worker on the web before sending.", color = DoorTreeTheme.textSecondary)
                    if (!request.path.startsWith("users/")) Text("This older request has no matching maintenance record to send.", color = DoorTreeTheme.textSecondary)
                }
            }
        },
        confirmButton = {
            if (choosing || tab == "Overview") {
            TextButton(
                enabled = !sending && if (choosing) workerId.isNotBlank() else concierge.isNotEmpty() && request.path.startsWith("users/") && !pText(data, "status").equals("completed", true),
                onClick = {
                    if (!choosing) { choosing = true; message = null }
                    else scope.launch {
                        sending = true
                        try {
                            val worker = concierge.firstOrNull { it.first == workerId }?.second ?: error("Choose a concierge")
                            val email = pText(worker, "email")
                            require(email.contains('@')) { "The selected concierge needs a valid email address." }
                            require(uid.isNotBlank()) { "Sign in again to send this request." }
                            val root = FirebaseDatabase.getInstance(FirebaseConfig.databaseUrl).reference
                            val jobId = sendConciergeJob(propertyId, request.id, request.date, workerId)
                            val queue = root.child("users/$uid/mailQueue/$jobId")
                            var result = "The concierge email is still pending. Check mail queue status."
                            for (attempt in 0 until 25) {
                                delay(1000)
                                val job = queue.get().await().value as? Map<*, *>
                                when (job?.get("status")?.toString()) {
                                    "sent" -> { result = "Sent to concierge"; break }
                                    "failed" -> error(job["error"]?.toString() ?: "The email could not be sent")
                                }
                            }
                            message = result
                            if (result == "Sent to concierge") choosing = false
                        } catch (exception: Exception) { message = exception.localizedMessage ?: "Could not send request" }
                        sending = false
                    }
                }
            ) { Text(if (choosing) { if (sending) "Sending…" else "Send" } else "Send to Concierge") }
            }
        },
        dismissButton = { TextButton(onClick = { if (choosing) choosing = false else onDismiss() }) { Text(if (choosing) "Back" else "Close") } }
    )
}
