package codewhale.doortreeandroid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import codewhale.doortreeandroid.ui.theme.DoorTreeTheme
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Currency
import java.util.Locale

private enum class RentTab(val title: String) {
    Overview("Overview"), Overdue("Overdue"), Upcoming("Upcoming"), Recent("Recent payments"), Analytics("Analytics")
}

private val LandlordRentCharge.displayStatus: String get() {
    val stored = status.lowercase()
    if (stored == "void" || stored == "paid") return stored
    val pastDue = dueDate.isNotBlank() && dueDate < LocalDate.now().toString()
    if (stored == "partial") return if (pastDue) "partial" else "upcoming"
    return if (pastDue) "overdue" else "upcoming"
}
private val LandlordRentCharge.amountPaid: Double get() = (amount - balance.coerceAtLeast(0.0)).coerceAtLeast(0.0)
private val LandlordRentCharge.outstanding: Double get() = balance.coerceAtLeast(0.0)
private val LandlordRentCharge.place: String get() = listOf(propertyName, unitNumber.takeIf(String::isNotBlank)?.let { "Unit $it" }).filterNotNull().filter(String::isNotBlank).joinToString(" · ")
private fun rentMoney(value: Double): String = NumberFormat.getCurrencyInstance(Locale.CANADA).apply { currency = Currency.getInstance("CAD") }.format(value)

@Composable
fun LandlordRentDashboardScreen(store: LandlordDataStore) {
    var tab by remember { mutableStateOf(RentTab.Overview) }
    var month by remember { mutableStateOf("") }
    var propertyId by remember { mutableStateOf("all") }
    var search by remember { mutableStateOf("") }
    var collectedYtd by remember { mutableStateOf(false) }
    val currentMonth = LocalDate.now().toString().take(7)
    val selectedMonth = month.ifBlank { currentMonth }
    val months = (store.rentCharges.map { it.dueDate.take(7) }.filter { it.length == 7 } + currentMonth).distinct().sortedDescending()
    val thisMonth = store.rentCharges.filter { it.dueDate.startsWith(currentMonth) && it.displayStatus != "void" }
    val totalDue = thisMonth.sumOf { it.amount }
    val collectedProgress = thisMonth.sumOf { it.amountPaid }
    val collectedPeriod = if (collectedYtd) currentMonth.take(4) else currentMonth
    val collectedMetric = store.rentCharges.filter { it.displayStatus != "void" }.sumOf { charge ->
        if (charge.paymentHistory.isNotEmpty()) charge.paymentHistory.filter { it.date.startsWith(collectedPeriod) }.sumOf { it.amount }
        else if (charge.paidAt.startsWith(collectedPeriod)) charge.amountPaid else 0.0
    }
    val filtered = store.rentCharges.filter { charge ->
        charge.displayStatus != "void" &&
            (selectedMonth == "all" || charge.dueDate.startsWith(selectedMonth)) &&
            (propertyId == "all" || charge.propertyId == propertyId) &&
            (search.isBlank() || "${charge.tenantName} ${charge.propertyName} ${charge.unitNumber}".contains(search, true))
    }
    val overdue = filtered.filter { it.displayStatus == "overdue" || it.displayStatus == "partial" }.sortedBy { it.dueDate }
    val upcoming = filtered.filter { it.displayStatus == "upcoming" }.sortedBy { it.dueDate }
    val paid = filtered.filter { it.displayStatus == "paid" }.sortedByDescending { it.paidAt }
    val progress = if (totalDue <= 0) 0f else (collectedProgress / totalDue).coerceIn(0.0, 1.0).toFloat()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RentMetric("Total due", totalDue, Modifier.weight(1f))
                    RentMetric("Collected", collectedMetric, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RentMetric("Overdue", overdue.sumOf { it.outstanding }, Modifier.weight(1f))
                    RentMetric("Upcoming", upcoming.sumOf { it.outstanding }, Modifier.weight(1f))
                }
            }
        }
        item { FilterChip(selected = collectedYtd, onClick = { collectedYtd = !collectedYtd }, label = { Text("Collected year to date") }) }
        item {
            Column(Modifier.fillMaxWidth().glassCard(cornerRadius = 17.dp).padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Collection progress", fontWeight = FontWeight.SemiBold, color = DoorTreeTheme.textPrimary)
                    Text("${(progress * 100).toInt()}%", color = DoorTreeTheme.gradientStart)
                }
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = DoorTreeTheme.gradientStart)
                Text("${rentMoney(collectedProgress)} of ${rentMoney(totalDue)} collected this month", fontSize = 12.sp, color = DoorTreeTheme.textSecondary)
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item { FilterChip(selected = month.isBlank(), onClick = { month = "" }, label = { Text("Current month") }) }
                item { FilterChip(selected = month == "all", onClick = { month = "all" }, label = { Text("All months") }) }
                items(months.filter { it != currentMonth }) { value -> FilterChip(selected = month == value, onClick = { month = value }, label = { Text(value) }) }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item { FilterChip(selected = propertyId == "all", onClick = { propertyId = "all" }, label = { Text("All properties") }) }
                items(store.properties, key = { it.id }) { property ->
                    FilterChip(selected = propertyId == property.id, onClick = { propertyId = property.id }, label = { Text(property.name) })
                }
            }
        }
        item { OutlinedTextField(search, { search = it }, label = { Text("Search tenant, property, or unit") }, modifier = Modifier.fillMaxWidth()) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(RentTab.entries) { choice -> FilterChip(selected = tab == choice, onClick = { tab = choice }, label = { Text(choice.title) }) }
            }
        }
        when (tab) {
            RentTab.Overview -> {
                item { RentHeading("Overdue payments") }
                if (overdue.isEmpty()) item { RentEmpty("No overdue payments") }
                items(overdue.take(3), key = { it.id }) { RentChargeCard(it) }
                item { RentHeading("Upcoming payments") }
                if (upcoming.isEmpty()) item { RentEmpty("No upcoming payments") }
                items(upcoming.take(3), key = { it.id }) { RentChargeCard(it) }
            }
            RentTab.Overdue -> {
                item { RentHeading("Overdue · ${overdue.size}") }
                if (overdue.isEmpty()) item { RentEmpty("No overdue payments") }
                items(overdue, key = { it.id }) { RentChargeCard(it) }
            }
            RentTab.Upcoming -> {
                item { RentHeading("Upcoming · ${upcoming.size}") }
                if (upcoming.isEmpty()) item { RentEmpty("No upcoming payments") }
                items(upcoming, key = { it.id }) { RentChargeCard(it) }
            }
            RentTab.Recent -> {
                item { RentHeading("Recent payments") }
                if (paid.isEmpty()) item { RentEmpty("No recent payments for this month") }
                items(paid, key = { it.id }) { RentChargeCard(it) }
            }
            RentTab.Analytics -> {
                val due = filtered.sumOf { it.amount }
                item { RentHeading("Rent breakdown") }
                item { RentBreakdown("Total due", due, due, DoorTreeTheme.gradientStart) }
                item { RentBreakdown("Collected", filtered.sumOf { it.amountPaid }, due, Color(0xFF17B883)) }
                item { RentBreakdown("Overdue", overdue.sumOf { it.outstanding }, due, Color(0xFFE85B62)) }
                item { RentBreakdown("Upcoming", upcoming.sumOf { it.outstanding }, due, Color(0xFFFFB645)) }
                item { RentHeading("By property") }
                val groups = filtered.groupBy { it.propertyName.ifBlank { "Other" } }
                items(groups.keys.sorted()) { name ->
                    val charges = groups[name].orEmpty()
                    Column(Modifier.fillMaxWidth().glassCard(cornerRadius = 15.dp).padding(15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(name, color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text("Due ${rentMoney(charges.sumOf { it.amount })} · Collected ${rentMoney(charges.sumOf { it.amountPaid })}", color = DoorTreeTheme.textSecondary, fontSize = 12.sp)
                        Text("Overdue ${rentMoney(charges.filter { it.displayStatus == "overdue" || it.displayStatus == "partial" }.sumOf { it.outstanding })}", color = DoorTreeTheme.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RentMetric(label: String, amount: Double, modifier: Modifier = Modifier) {
    Column(modifier.glassCard(cornerRadius = 17.dp).padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, color = DoorTreeTheme.textSecondary, fontSize = 12.sp)
        Text(rentMoney(amount), color = DoorTreeTheme.textPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RentHeading(title: String) { Text(title, color = DoorTreeTheme.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold) }

@Composable
private fun RentEmpty(message: String) { Text(message, color = DoorTreeTheme.textSecondary, modifier = Modifier.padding(12.dp)) }

@Composable
private fun RentChargeCard(charge: LandlordRentCharge) {
    Row(Modifier.fillMaxWidth().glassCard(cornerRadius = 15.dp).padding(15.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(charge.tenantName.ifBlank { "Tenant" }, color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.SemiBold)
            Text(charge.place, color = DoorTreeTheme.textSecondary, fontSize = 12.sp)
            Text(if (charge.displayStatus == "paid") "Paid ${charge.paidAt.take(10)} · ${charge.paymentMethod}" else "Due ${charge.dueDate} · ${charge.displayStatus.replaceFirstChar(Char::uppercase)}", color = DoorTreeTheme.textSecondary, fontSize = 12.sp)
        }
        Text(rentMoney(if (charge.displayStatus == "paid") charge.amountPaid else charge.outstanding), color = DoorTreeTheme.gradientStart, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RentBreakdown(label: String, value: Double, total: Double, color: Color) {
    Column(Modifier.fillMaxWidth().glassCard(cornerRadius = 14.dp).padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = DoorTreeTheme.textPrimary)
            Text(rentMoney(value), color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(progress = { if (total <= 0) 0f else (value / total).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth(), color = color)
    }
}
