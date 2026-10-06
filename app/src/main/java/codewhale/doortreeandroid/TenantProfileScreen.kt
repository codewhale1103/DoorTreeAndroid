package codewhale.doortreeandroid

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

private enum class TenantTab(val label: String) {
    Details("Details"), Events("Events"), Assignment("Assignment"), RentHistory("Rent history"),
    RentPayments("Rent payments"), Documents("Documents")
}

@Composable
fun TenantProfileScreen(tenantId: String, cachedTenant: Map<String, Any?>?, onOpenConversation: () -> Unit, hasConversation: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember(tenantId) { mutableStateOf<TenantProfileData?>(null) }
    var error by remember(tenantId) { mutableStateOf<String?>(null) }
    var selected by remember(tenantId) { mutableStateOf(TenantTab.Details) }
    var reload by remember(tenantId) { mutableIntStateOf(0) }
    var openingDocument by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tenantId, reload, cachedTenant) {
        error = null
        runCatching { loadTenantProfile(tenantId, cachedTenant) { profile = it } }
            .onSuccess { profile = it }
            .onFailure { error = it.localizedMessage ?: "Unable to load tenant profile" }
    }

    Column(Modifier.fillMaxSize()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp)) {
            items(TenantTab.entries) { tab ->
                FilterChip(selected = selected == tab, onClick = { selected = tab }, label = { Text(tab.label) })
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp)
        ) {
            val data = profile
            if (data == null) {
                item { ProfileCard { Text(error ?: "Loading tenant profile…", color = DoorTreeTheme.textSecondary) } }
                if (error != null) item { TextButton(onClick = { reload++ }) { Text("Try again") } }
            } else {
                val sectionKey = when (selected) {
                    TenantTab.Events -> "events"
                    TenantTab.Assignment -> "assignment"
                    TenantTab.RentHistory -> "rent history"
                    TenantTab.RentPayments -> "rent payments"
                    else -> ""
                }
                data.sectionErrors[sectionKey]?.let { message ->
                    item { ProfileCard {
                        Text(message, color = DoorTreeTheme.destructive)
                        TextButton(onClick = { reload++ }) { Text("Try again") }
                    } }
                }
                if (selected == TenantTab.Details) item {
                    ProfileCard {
                        Text(data.name, color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        Text(listOf(data.propertyName, data.unit.takeIf { it.isNotBlank() }?.let { "Unit $it" }).filterNotNull().filter(String::isNotBlank).joinToString(" · "), color = DoorTreeTheme.textSecondary)
                        ProfileLine("Tenant score", "${data.onTimeStreak} month streak")
                        ProfileLine("Pre-authorized payments", if (data.paymentText("status") == "active") "Active" else "Not active")
                        TextButton(onClick = { selected = TenantTab.Documents }) { Text("View documents") }
                        if (error != null) Text(error.orEmpty(), color = DoorTreeTheme.destructive, fontSize = 12.sp)
                        TextButton(onClick = { reload++ }) { Text("Refresh") }
                    }
                }
                when (selected) {
                    TenantTab.Details -> {
                        item { ProfileCard {
                            ProfileHeading("Personal information")
                            ProfileLine("First name", data.tenantText("firstName"))
                            ProfileLine("Last name", data.tenantText("lastName"))
                            ProfileLine("Date of birth", data.tenantText("dateOfBirth"))
                            ProfileLine("Email", data.tenantText("email"))
                            ProfileLine("Phone", data.tenantText("phoneNumber"))
                            ProfileLine("Emergency contact", data.tenantText("emergencyContact"))
                            ProfileLine("Preferred language", data.tenantText("language").uppercase())
                        } }
                        item { ProfileCard {
                            ProfileHeading("Employment & communication")
                            ProfileLine("Employment", data.tenantText("employmentStatus"))
                            ProfileLine("Monthly income", data.tenantMoney("monthlyIncome").takeIf { it > 0 }?.let(::profileMoney) ?: "—")
                            ProfileLine("Email updates", if (data.tenant["sendEmail"] == true) "Enabled" else "Disabled")
                            ProfileLine("Email ready", yesNo(data.tenantText("email").isNotBlank()))
                            ProfileLine("Phone ready", yesNo(data.tenantText("phoneNumber").isNotBlank()))
                        } }
                        item { ProfileCard {
                            ProfileHeading("Data completeness")
                            ProfileLine("Email on file", yesNo(data.tenantText("email").isNotBlank()))
                            ProfileLine("Phone on file", yesNo(data.tenantText("phoneNumber").isNotBlank()))
                            ProfileLine("Emergency contact", yesNo(data.tenantText("emergencyContact").isNotBlank()))
                            ProfileLine("Property linked", yesNo(data.tenantText("property").isNotBlank()))
                        } }
                        if (hasConversation) item { TextButton(onClick = onOpenConversation) { Text("Open conversation") } }
                    }
                    TenantTab.Events -> {
                        item { ProfileCard {
                            ProfileHeading("Events history")
                            ProfileLine("Sent", data.events.count { it.status == "sent" }.toString())
                            ProfileLine("Read", data.events.count { it.status == "read" }.toString())
                            ProfileLine("Accepted", data.events.count { it.status == "accepted" }.toString())
                            ProfileLine("Refused", data.events.count { it.status == "refused" || it.status == "rejected" }.toString())
                        } }
                        if (data.events.isEmpty()) item { ProfileEmpty("No events yet") }
                        items(data.events, key = { "${it.id}-${it.date}" }) { event ->
                            ProfileCard {
                                Text(event.title, color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                                Text(profileDate(event.date), color = DoorTreeTheme.textSecondary, fontSize = 12.sp)
                                Text(listOf(event.status.replaceFirstChar(Char::uppercase), event.category).filter(String::isNotBlank).joinToString(" · "), color = DoorTreeTheme.gradientStart, fontSize = 12.sp)
                            }
                        }
                    }
                    TenantTab.Assignment -> {
                        item { ProfileCard {
                            ProfileHeading("Assignment")
                            ProfileLine("Property", data.propertyName)
                            ProfileLine("Unit", data.unit)
                            ProfileLine("Monthly payment credit", data.tenantMoney("monthlyPayment").takeIf { it > 0 }?.let(::profileMoney) ?: "N/A")
                            ProfileLine("Address", listOf(data.tenantText("streetAddress").ifBlank { data.propertyText("streetAddress") }, data.tenantText("city").ifBlank { data.propertyText("city") }, data.tenantText("province").ifBlank { data.propertyText("province") }).filter(String::isNotBlank).joinToString(", "))
                            ProfileLine("Property type", data.propertyText("propertyType"))
                            ProfileLine("Property manager", data.tenantText("propertyManager").ifBlank { data.propertyText("propertyManager") })
                            ProfileLine("Lease start", profileDate(data.tenantText("leaseStart")))
                            ProfileLine("Lease end", profileDate(data.tenantText("leaseEnd")))
                            ProfileLine("Rent due day", data.tenantText("dueDate").ifBlank { data.propertyText("rentDueDate") })
                            ProfileLine("Guarantor", yesNo(data.tenant["hasGarantor"] == true))
                            ProfileLine("Departure date", profileDate(data.tenantText("departureDate")))
                        } }
                        if (data.parking.isNotEmpty()) item { ProfileCard {
                            ProfileHeading("Parking")
                            ProfileLine("Spot", data.parkingText("unit"))
                            ProfileLine("Monthly payment", data.parkingMoney("price").takeIf { it > 0 }?.let(::profileMoney) ?: "—")
                        } }
                    }
                    TenantTab.RentHistory -> {
                        item { ProfileCard {
                            ProfileHeading("Upcoming rent")
                            ProfileLine("Expected rent", data.tenantMoney("rentAmount").takeIf { it > 0 }?.let(::profileMoney) ?: "—")
                            ProfileLine("Due schedule", data.tenantText("dueDate").takeIf { it.isNotBlank() }?.let { "Day $it each month" } ?: "—")
                        } }
                        if (data.charges.isEmpty()) item { ProfileEmpty("No rent history yet") }
                        items(data.charges, key = { "${it.id}-${it.dueDate}" }) { charge ->
                            ProfileCard {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(listOf(charge.property.ifBlank { data.propertyName }, charge.unit.ifBlank { data.unit }.let { "Unit $it" }).joinToString(" · "), color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text(charge.status.replaceFirstChar(Char::uppercase), color = DoorTreeTheme.gradientStart, fontSize = 12.sp)
                                }
                                Text("Due ${profileDate(charge.dueDate)}", color = DoorTreeTheme.textSecondary, fontSize = 12.sp)
                                ProfileLine("Total", profileMoney(charge.amount))
                                ProfileLine("Paid", profileMoney((charge.amount - charge.balance).coerceAtLeast(0.0)))
                                ProfileLine("Balance", profileMoney(charge.balance))
                                charge.payments.forEach { payment ->
                                    ProfileLine("${profileDate(payment.date)} ${payment.method}", profileMoney(payment.amount))
                                }
                            }
                        }
                    }
                    TenantTab.RentPayments -> {
                        item { ProfileCard {
                            ProfileHeading("Rent payments")
                            Text(when (data.paymentText("status")) {
                                "active" -> "Automatic payment active"
                                "authorization_pending" -> "Signature pending"
                                "verification_pending" -> "Verification pending"
                                else -> "Manual payments"
                            }, color = DoorTreeTheme.gradientStart, fontWeight = FontWeight.SemiBold)
                            ProfileLine("Current method", data.paymentText("paymentMethodLabel"))
                            ProfileLine("Saved type", when (data.paymentText("selectedMethodType")) { "acss_debit" -> "Bank autopay"; "card" -> "Card autopay"; else -> "Manual pay" })
                            ProfileLine("Last setup", profileDate(data.paymentText("lastSetupAt")))
                            ProfileLine("Last autopay success", profileDate(data.paymentText("lastAutopaySucceededAt")))
                        } }
                    }
                    TenantTab.Documents -> {
                        if (data.documents.isEmpty()) item { ProfileEmpty("No documents yet") }
                        items(data.documents, key = { it.id }) { document ->
                            ProfileCard {
                                Column(Modifier.fillMaxWidth().clickable {
                                    scope.launch {
                                        runCatching {
                                            val link = if (document.url.isNotBlank()) document.url else FirebaseStorage.getInstance().getReference(document.storagePath).downloadUrl.await().toString()
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                                        }.onFailure { openingDocument = it.localizedMessage ?: "Could not open document" }
                                    }
                                }) {
                                    Text(document.name, color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                                    Text(listOf(document.type, profileDate(document.date)).filter { it.isNotBlank() && it != "—" }.joinToString(" · "), color = DoorTreeTheme.textSecondary, fontSize = 12.sp)
                                    Text("Open document", color = DoorTreeTheme.gradientStart, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (openingDocument != null) AlertDialog(onDismissRequest = { openingDocument = null },
        confirmButton = { TextButton(onClick = { openingDocument = null }) { Text("OK") } },
        title = { Text("Document unavailable") }, text = { Text(openingDocument.orEmpty()) })
}

@Composable private fun ProfileCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glassCard(cornerRadius = 20.dp).padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) { content() }
}
@Composable private fun ProfileHeading(title: String) { Text(title, color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
@Composable private fun ProfileLine(label: String, value: String) {
    Column {
        Text(label.uppercase(), color = DoorTreeTheme.textSecondary, fontSize = 11.sp)
        Text(value.ifBlank { "—" }, color = DoorTreeTheme.textPrimary, fontSize = 15.sp)
    }
}
@Composable private fun ProfileEmpty(text: String) { ProfileCard { Text(text, color = DoorTreeTheme.textSecondary) } }
private fun yesNo(value: Boolean) = if (value) "Yes" else "No"
private fun profileMoney(value: Double): String = NumberFormat.getCurrencyInstance(Locale.CANADA).apply { currency = Currency.getInstance("CAD") }.format(value)
private fun profileDate(value: String): String = if (value.isBlank()) "—" else value.take(10)
