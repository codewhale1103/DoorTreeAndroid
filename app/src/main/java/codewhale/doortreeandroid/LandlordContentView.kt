package codewhale.doortreeandroid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import codewhale.doortreeandroid.ui.theme.DoorTreeTheme
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

private enum class LandlordRoute(val title: String, val subtitle: String, val icon: String, val accent: Color) {
    Rent("Rent", "Payments and balances", "dollarsign.circle.fill", DoorTreeTheme.gradientStart),
    Tenants("Tenants", "People and leases", "person.crop.rectangle.stack.fill", DoorTreeTheme.leaseAccent),
    Messages("Messages", "Conversations", "bubble.left.and.bubble.right.fill", DoorTreeTheme.chatAccent),
    Inbox("Inbox", "Requests needing attention", "checklist", Color(0xFFFFA940)),
    Calendar("Calendar", "Upcoming schedule", "calendar", Color(0xFF5C98E8)),
    Properties("Properties", "Your portfolio", "building.2.fill", Color(0xFF22B6A5))
}

private data class LandlordDetail(val type: LandlordRoute, val id: String)

@Composable
fun LandlordContentView(landlordDataStore: LandlordDataStore, onSignOut: () -> Unit) {
    var route by remember { mutableStateOf<LandlordRoute?>(null) }
    var detailStack by remember { mutableStateOf<List<LandlordDetail>>(emptyList()) }
    val detail = detailStack.lastOrNull()
    var confirmSignOut by remember { mutableStateOf(false) }

    BackHandler(route != null || detail != null) {
        if (detail != null) detailStack = detailStack.dropLast(1) else route = null
    }

    Column(Modifier.fillMaxSize().background(DoorTreeTheme.backgroundPrimary)) {
        if (route == null) {
            LandlordDashboard(
                store = landlordDataStore,
                onOpen = { route = it; detailStack = emptyList() },
                onSignOut = { confirmSignOut = true }
            )
        } else {
            LandlordTopBar(
                title = detail?.let { detailTitle(it, landlordDataStore) } ?: route!!.title,
                onBack = { if (detail != null) detailStack = detailStack.dropLast(1) else route = null }
            )
            if (detail == null) {
                if (route == LandlordRoute.Rent) {
                    LandlordRentDashboardScreen(landlordDataStore)
                } else {
                    LandlordSection(
                        route = route!!,
                        store = landlordDataStore,
                        onOpen = { detailStack = detailStack + LandlordDetail(route!!, it) }
                    )
                }
            } else {
                LandlordDetailScreen(detail, landlordDataStore, onOpen = { detailStack = detailStack + it })
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of DoorTree?") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun LandlordDashboard(store: LandlordDataStore, onOpen: (LandlordRoute) -> Unit, onSignOut: () -> Unit) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("LANDLORD WORKSPACE", color = DoorTreeTheme.gradientStart, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(if (store.firstName.isBlank()) "Welcome back" else "Hello, ${store.firstName}", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = DoorTreeTheme.textPrimary)
                    Text("Everything you manage, in one place.", fontSize = 14.sp, color = DoorTreeTheme.textSecondary)
                }
                IconButton(onClick = onSignOut) {
                    Icon(systemIcon("person.crop.circle.fill"), contentDescription = "Account and sign out", tint = DoorTreeTheme.textPrimary)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().glassCard(cornerRadius = 22.dp).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("At a glance", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = DoorTreeTheme.textPrimary, modifier = Modifier.weight(1f))
                    if (store.isLoading) Text("Loading…", fontSize = 12.sp, color = DoorTreeTheme.textSecondary)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OverviewMetric(if (store.isLoading) "—" else store.properties.size.toString(), "Properties", Modifier.weight(1f))
                    OverviewMetric(if (store.isLoading) "—" else store.activeTenantCount.toString(), "Active tenants", Modifier.weight(1f))
                    OverviewMetric(if (store.isLoading) "—" else store.openRequestCount.toString(), "Open requests", Modifier.weight(1f))
                }
                if (store.unreadMessageCount > 0) {
                    Spacer(Modifier.height(12.dp))
                    Text("${store.unreadMessageCount} unread messages", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DoorTreeTheme.gradientStart)
                }
            }
        }
        if (store.loadError != null) {
            item { Text(store.loadError.orEmpty(), color = DoorTreeTheme.destructive, fontSize = 13.sp) }
        }
        item { Text("Workspace", color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
        items(LandlordRoute.entries.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { section ->
                    LandlordDashboardCard(section, Modifier.weight(1f), onClick = { onOpen(section) })
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun OverviewMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = DoorTreeTheme.textPrimary)
        Text(label, fontSize = 12.sp, lineHeight = 15.sp, color = DoorTreeTheme.textSecondary)
    }
}

@Composable
private fun LandlordDashboardCard(section: LandlordRoute, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(154.dp)
            .glassCard(cornerRadius = 20.dp)
            .clickable(onClick = onClick)
            .padding(15.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(Modifier.size(44.dp).background(section.accent.copy(alpha = 0.14f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Icon(systemIcon(section.icon), contentDescription = null, tint = section.accent, modifier = Modifier.size(23.dp))
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(section.title, color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Icon(systemIcon("chevron.right"), contentDescription = null, tint = DoorTreeTheme.textSecondary, modifier = Modifier.size(14.dp))
            }
            Text(section.subtitle, color = DoorTreeTheme.textSecondary, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2)
        }
    }
}

@Composable
private fun LandlordTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(systemIcon("chevron.left"), contentDescription = "Back", tint = DoorTreeTheme.textPrimary) }
        Text(title, color = DoorTreeTheme.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LandlordSection(route: LandlordRoute, store: LandlordDataStore, onOpen: (String) -> Unit) {
    var showingAddProperty by remember { mutableStateOf(false) }
    var search by remember(route) { mutableStateOf("") }
    var showAll by remember(route) { mutableStateOf(false) }
    var selectedMonth by remember(route) { mutableStateOf("") }
    val months = remember(store.rentCharges) { store.rentCharges.map { it.dueDate.take(7) }.distinct().sortedDescending() }
    val currentMonth = LocalDate.now().toString().take(7)
    val month = selectedMonth.ifBlank { if (currentMonth in months) currentMonth else months.firstOrNull().orEmpty() }
    val query = search.trim()
    val charges = store.rentCharges.filter { it.dueDate.startsWith(month) && !it.status.equals("void", true) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(11.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 26.dp)
    ) {
        item { SectionHeader(route) }
        if (route == LandlordRoute.Properties) {
            item { Button(onClick = { showingAddProperty = true }, modifier = Modifier.fillMaxWidth()) { Text("Add property") } }
        }
        if (route == LandlordRoute.Tenants || route == LandlordRoute.Messages || route == LandlordRoute.Inbox || route == LandlordRoute.Properties) {
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search ${route.title.lowercase()}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }
        when (route) {
            LandlordRoute.Rent -> {
                if (months.isNotEmpty()) {
                    item {
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(months) { item ->
                                FilterChip(selected = item == month, onClick = { selectedMonth = item }, label = { Text(item) })
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MoneyTile("Expected", charges.sumOf { it.amount }, Modifier.weight(1f))
                        MoneyTile("Outstanding", charges.sumOf { it.balance }, Modifier.weight(1f))
                    }
                }
                item { FilterChip(selected = showAll, onClick = { showAll = !showAll }, label = { Text("Show paid charges") }) }
                val visible = if (showAll) charges else charges.filter { it.balance > 0 }
                if (visible.isEmpty()) item { EmptySection("No rent charges for this view", "dollarsign.circle.fill") }
                items(visible, key = { it.id }) { charge ->
                    LandlordListCard(charge.tenantName.ifBlank { "Tenant" }, "Due ${dateLabel(charge.dueDate)} · ${charge.status.replaceFirstChar(Char::uppercase)}", money(if (charge.balance > 0) charge.balance else charge.amount))
                }
            }
            LandlordRoute.Tenants -> {
                item { FilterChip(selected = showAll, onClick = { showAll = !showAll }, label = { Text("Include unassigned and former tenants") }) }
                val visible = store.tenants.filter { (showAll || it.active) && (query.isBlank() || "${it.name} ${it.propertyName} ${it.unit}".contains(query, true)) }
                if (visible.isEmpty()) item { EmptySection("No tenants found", "person.crop.rectangle.stack.fill") }
                items(visible, key = { it.id }) { tenant ->
                    LandlordListCard(tenant.name, location(tenant.propertyName, tenant.unit), if (tenant.monthlyRent > 0) money(tenant.monthlyRent) else null) { onOpen(tenant.id) }
                }
            }
            LandlordRoute.Messages -> {
                val visible = store.conversations.filter { query.isBlank() || "${it.name} ${it.propertyName}".contains(query, true) }
                if (visible.isEmpty()) item { EmptySection("No conversations yet", "bubble.left.fill") }
                items(visible, key = { it.id }) { conversation ->
                    LandlordListCard(conversation.name, conversation.preview.ifBlank { location(conversation.propertyName, conversation.unit) }, if (conversation.unreadCount > 0) "${conversation.unreadCount} new" else null) { onOpen(conversation.id) }
                }
            }
            LandlordRoute.Inbox -> {
                item { FilterChip(selected = showAll, onClick = { showAll = !showAll }, label = { Text("Show resolved requests") }) }
                val visible = store.requests.filter { (showAll || !it.status.equals("resolved", true)) && (query.isBlank() || "${it.title} ${it.tenant} ${it.property}".contains(query, true)) }
                if (visible.isEmpty()) item { EmptySection("Inbox is clear", "checklist") }
                items(visible, key = { it.id }) { request ->
                    LandlordListCard(request.title, "${request.tenant} · ${location(request.property, request.unit)}", request.status.replaceFirstChar(Char::uppercase)) { onOpen(request.id) }
                }
            }
            LandlordRoute.Calendar -> {
                item { FilterChip(selected = showAll, onClick = { showAll = !showAll }, label = { Text("Show past events") }) }
                val today = LocalDate.now().toString()
                val visible = store.events.filter { showAll || it.startDate >= today }.take(if (showAll) 100 else 50)
                if (visible.isEmpty()) item { EmptySection("Nothing on the calendar", "calendar") }
                items(visible, key = { it.id }) { event ->
                    LandlordListCard(event.title, location(event.property, event.unit), dateLabel(event.startDate)) { onOpen(event.id) }
                }
            }
            LandlordRoute.Properties -> {
                val visible = store.properties.filter { query.isBlank() || "${it.name} ${it.address}".contains(query, true) }
                if (visible.isEmpty()) item { EmptySection("No properties found", "building.2.fill") }
                items(visible, key = { it.id }) { property ->
                    LandlordListCard(property.name, property.address, "${property.occupiedCount}/${property.unitCount} units") { onOpen(property.id) }
                }
            }
        }
    }
    if (showingAddProperty) AddLandlordPropertyDialog(store, onDismiss = { showingAddProperty = false })
}

@Composable
private fun SectionHeader(route: LandlordRoute) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).background(route.accent.copy(alpha = 0.14f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
            Icon(systemIcon(route.icon), contentDescription = null, tint = route.accent)
        }
        Column {
            Text(route.title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = DoorTreeTheme.textPrimary)
            Text(route.subtitle, fontSize = 14.sp, color = DoorTreeTheme.textSecondary)
        }
    }
}

@Composable
private fun MoneyTile(title: String, amount: Double, modifier: Modifier = Modifier) {
    Column(modifier.glassCard(cornerRadius = 17.dp).padding(14.dp)) {
        Text(title, color = DoorTreeTheme.textSecondary, fontSize = 12.sp)
        Text(money(amount), color = DoorTreeTheme.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LandlordListCard(title: String, subtitle: String, trailing: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().glassCard(cornerRadius = 18.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = DoorTreeTheme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle.ifBlank { "—" }, color = DoorTreeTheme.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, color = DoorTreeTheme.gradientStart, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        if (onClick != null) Icon(systemIcon("chevron.right"), contentDescription = null, tint = DoorTreeTheme.textSecondary, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun EmptySection(title: String, icon: String) {
    Column(Modifier.fillMaxWidth().height(170.dp).glassCard(cornerRadius = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(systemIcon(icon), contentDescription = null, tint = DoorTreeTheme.textSecondary)
        Spacer(Modifier.height(9.dp))
        Text(title, color = DoorTreeTheme.textSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun LandlordDetailScreen(detail: LandlordDetail, store: LandlordDataStore, onOpen: (LandlordDetail) -> Unit) {
    when (detail.type) {
        LandlordRoute.Messages -> LandlordConversationScreen(detail.id, store)
        LandlordRoute.Properties -> LandlordPropertyWorkspaceScreen(detail.id, store, onOpenTenant = { onOpen(LandlordDetail(LandlordRoute.Tenants, it)) })
        LandlordRoute.Tenants -> TenantProfileScreen(
            tenantId = detail.id,
            cachedTenant = store.tenantRecords[detail.id],
            onOpenConversation = { onOpen(LandlordDetail(LandlordRoute.Messages, detail.id)) },
            hasConversation = store.conversations.any { it.id == detail.id }
        )
        else -> {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(13.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp)
            ) {
                when (detail.type) {
                    LandlordRoute.Properties -> store.properties.firstOrNull { it.id == detail.id }?.let { property ->
                        item { DetailHeader(property.name, "building.2.fill") }
                        item { DetailCard {
                            DetailLine("Address", property.address)
                            DetailLine("Occupied units", "${property.occupiedCount} of ${property.unitCount}")
                        } }
                        item { Text("Tenants", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DoorTreeTheme.textPrimary) }
                        items(store.tenants.filter { it.propertyName == property.name && it.active }, key = { it.id }) { tenant ->
                            LandlordListCard(tenant.name, location(tenant.propertyName, tenant.unit)) { onOpen(LandlordDetail(LandlordRoute.Tenants, tenant.id)) }
                        }
                    }
                    LandlordRoute.Inbox -> store.requests.firstOrNull { it.id == detail.id }?.let { request ->
                        item { DetailHeader(request.title, "checklist") }
                        item { DetailCard {
                            DetailLine("Status", request.status)
                            DetailLine("Priority", request.priority)
                            DetailLine("Tenant", request.tenant)
                            DetailLine("Property", location(request.property, request.unit))
                            DetailLine("Received", dateLabel(request.date))
                            if (request.description.isNotBlank()) {
                                Text("Description", fontWeight = FontWeight.SemiBold, color = DoorTreeTheme.textPrimary)
                                Text(request.description, color = DoorTreeTheme.textPrimary)
                            }
                        } }
                    }
                    LandlordRoute.Calendar -> store.events.firstOrNull { it.id == detail.id }?.let { event ->
                        item { DetailHeader(event.title, "calendar") }
                        item { DetailCard {
                            DetailLine("When", dateLabel(event.startDate))
                            DetailLine("Status", event.status)
                            DetailLine("Property", location(event.property, event.unit))
                            DetailLine("Tenant", event.tenant)
                            if (event.notes.isNotBlank()) {
                                Text("Notes", fontWeight = FontWeight.SemiBold, color = DoorTreeTheme.textPrimary)
                                Text(event.notes, color = DoorTreeTheme.textPrimary)
                            }
                        } }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun LandlordConversationScreen(id: String, store: LandlordDataStore) {
    val conversation = store.conversations.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by remember(id) { mutableStateOf("") }
    var isSending by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }

    LaunchedEffect(id, conversation?.unreadCount) {
        if ((conversation?.unreadCount ?: 0) > 0) conversation?.let(store::markConversationRead)
    }
    LaunchedEffect(conversation?.messages?.size) {
        val count = conversation?.messages?.size ?: 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
        ) {
            items(conversation?.messages.orEmpty(), key = { it.id }) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.isIncoming) Arrangement.Start else Arrangement.End) {
                    Text(
                        message.text,
                        color = DoorTreeTheme.textPrimary,
                        modifier = Modifier.fillMaxWidth(0.82f)
                            .background(if (message.isIncoming) DoorTreeTheme.backgroundSecondary else DoorTreeTheme.gradientStart.copy(alpha = 0.22f), RoundedCornerShape(17.dp))
                            .padding(12.dp)
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, placeholder = { Text("Message") }, modifier = Modifier.weight(1f), maxLines = 4, shape = RoundedCornerShape(14.dp))
            IconButton(enabled = !isSending && draft.isNotBlank(), onClick = {
                val text = draft
                isSending = true
                scope.launch {
                    runCatching { store.sendMessage(id, text) }
                        .onSuccess { draft = "" }
                        .onFailure { error = it.localizedMessage ?: "Please try again." }
                    isSending = false
                }
            }) {
                Icon(systemIcon("paperplane.fill"), contentDescription = "Send message", tint = DoorTreeTheme.gradientStart)
            }
        }
    }
    if (error != null) {
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Message not sent") }, text = { Text(error.orEmpty()) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } })
    }
}

@Composable
private fun DetailHeader(title: String, icon: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(systemIcon(icon), contentDescription = null, tint = DoorTreeTheme.gradientStart, modifier = Modifier.size(36.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DoorTreeTheme.textPrimary)
    }
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glassCard(cornerRadius = 20.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) { content() }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = DoorTreeTheme.textSecondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value.ifBlank { "—" }, color = DoorTreeTheme.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

private fun detailTitle(detail: LandlordDetail, store: LandlordDataStore): String = when (detail.type) {
    LandlordRoute.Tenants -> store.tenants.firstOrNull { it.id == detail.id }?.name ?: "Tenant"
    LandlordRoute.Messages -> store.conversations.firstOrNull { it.id == detail.id }?.name ?: "Messages"
    LandlordRoute.Inbox -> store.requests.firstOrNull { it.id == detail.id }?.title ?: "Request"
    LandlordRoute.Calendar -> store.events.firstOrNull { it.id == detail.id }?.title ?: "Event"
    LandlordRoute.Properties -> store.properties.firstOrNull { it.id == detail.id }?.name ?: "Property"
    LandlordRoute.Rent -> "Rent"
}

private fun location(property: String, unit: String): String = listOf(property, if (unit.isBlank()) "" else "Unit $unit").filter(String::isNotBlank).joinToString(" · ").ifBlank { "Unassigned" }

private fun money(amount: Double): String = NumberFormat.getCurrencyInstance(Locale.CANADA).apply { currency = Currency.getInstance("CAD") }.format(amount)

private fun dateLabel(raw: String): String {
    if (raw.isBlank()) return "—"
    val date = runCatching { Instant.parse(raw).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")) }.getOrNull()
    if (date != null) return date
    return runCatching { LocalDate.parse(raw.take(10)).format(DateTimeFormatter.ofPattern("MMM d, yyyy")) }.getOrDefault(raw)
}
