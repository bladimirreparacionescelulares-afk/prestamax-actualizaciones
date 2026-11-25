package com.vitrinapos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.Screen
import com.vitrinapos.data.Money
import com.vitrinapos.data.SaleStatus
import com.vitrinapos.data.Time
import com.vitrinapos.db.Client
import com.vitrinapos.db.Sale
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.Avatar
import com.vitrinapos.ui.components.ConfirmDialog
import com.vitrinapos.ui.components.Divider
import com.vitrinapos.ui.components.EmptyState
import com.vitrinapos.ui.components.KeyValue
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SearchBox
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.SquareIconButton
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch

// ================= Clientes =================

@Composable
fun ClientsScreen() {
    val app = LocalAppState.current
    val clients by app.repo.clients.collectAsState()
    val recent by app.repo.recentSales.collectAsState()
    var query by remember { mutableStateOf("") }
    val list = clients.filter { c -> query.isBlank() || listOf(c.name, c.phone, c.docId, c.email).any { it.contains(query.trim(), true) } }
    // Resumen de compras por cliente (ventas recientes)
    val byClient = recent.filter { it.status == SaleStatus.OK && it.clientId != null }.groupBy { it.clientId }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Clientes", subtitle = "${clients.size} registrados")
            SearchBox(query, { query = it }, "Buscar por nombre, teléfono o documento", Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            if (clients.isEmpty()) {
                EmptyState(Icons.Rounded.People, "Sin clientes todavía", "Registra a tus clientes para ver lo que compran y enviarles cotizaciones.")
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { c ->
                    val sales = byClient[c.id].orEmpty()
                    PersonRow(
                        name = c.name,
                        sub = listOf(c.phone, when (sales.size) { 0 -> ""; 1 -> "1 compra"; else -> "${sales.size} compras" }).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Sin teléfono" },
                        trailing = if (sales.isNotEmpty()) Money.format(sales.sumOf { it.total }, app.settings.currencySymbol) else null,
                    ) { app.open(Screen.ClientDetail(c.id)) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { app.open(Screen.ClientForm()) },
            containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Cliente", fontWeight = FontWeight.Bold) },
        )
    }
}

@Composable
fun PersonRow(name: String, sub: String, trailing: String? = null, square: Boolean = false, onClick: () -> Unit) {
    AppCard(onClick = onClick, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, 44.dp, square)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, fontSize = 12.sp, color = AppTheme.extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) Text(trailing, fontWeight = FontWeight.SemiBold, color = AppTheme.extra.muted, fontSize = 13.sp)
            else Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, tint = AppTheme.extra.line, modifier = Modifier.padding(start = 6.dp).width(14.dp))
        }
    }
}

@Composable
fun ClientFormScreen(id: Long?) {
    val app = LocalAppState.current
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var docId by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var showErrors by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (id != null) app.repo.clientById(id)?.let { c ->
            name = c.name; phone = c.phone; email = c.email; docId = c.docId; address = c.address; notes = c.notes
        }
    }
    fun save() {
        showErrors = true
        if (name.isBlank()) return
        scope.launch {
            val newId = app.repo.saveClient(id, name, phone, email, docId, address, notes)
            // Si venía del cobro sin cliente, lo deja elegido.
            if (id == null && app.cart.isNotEmpty() && app.cartClient == null) app.cartClient = app.repo.clientById(newId)
            app.toast("Cliente guardado")
            app.back()
        }
    }
    FormScaffold(if (id == null) "Nuevo cliente" else "Editar cliente", onSave = ::save, onDelete = if (id != null) ({ confirmDelete = true }) else null) {
        TextInput(name, { name = it }, "Nombre *", error = if (showErrors && name.isBlank()) "Escribe el nombre" else null)
        TextInput(phone, { phone = it }, "Teléfono", keyboard = KeyboardType.Phone)
        TextInput(docId, { docId = it }, "Cédula o RNC")
        TextInput(email, { email = it }, "Correo", keyboard = KeyboardType.Email)
        TextInput(address, { address = it }, "Dirección")
        TextInput(notes, { notes = it }, "Notas", singleLine = false, minLines = 2)
    }
    if (confirmDelete && id != null) {
        ConfirmDialog("Eliminar cliente", "Se borra \"$name\". Sus ventas anteriores se conservan con su nombre.", "Eliminar",
            onConfirm = { scope.launch { app.repo.deleteClient(id); app.toast("Cliente eliminado"); app.selectTab(Screen.Clients) } },
            onDismiss = { confirmDelete = false }, destructive = true)
    }
}

@Composable
fun FormScaffold(title: String, onSave: () -> Unit, onDelete: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val app = LocalAppState.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, onBack = { app.back() }) {
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Eliminar", tint = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onSave) { Text("Guardar", fontWeight = FontWeight.Bold) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Guardar", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ClientDetailScreen(id: Long) {
    val app = LocalAppState.current
    val clients by app.repo.clients.collectAsState()
    val quotes by app.repo.quotes.collectAsState()
    val client = clients.firstOrNull { it.id == id }
    val sales by produceState(emptyList<Sale>(), id, client) { value = app.repo.salesByClient(id) }
    if (client == null) {
        Column { ScreenHeader("Cliente", onBack = { app.back() }) }
        return
    }
    val ok = sales.filter { it.status == SaleStatus.OK }
    val s = app.settings
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(client.name, subtitle = client.phone.ifBlank { null }, onBack = { app.back() }) {
            SquareIconButton(Icons.Rounded.Edit, "Editar") { app.open(Screen.ClientForm(id)) }
        }
        LazyColumn(
            Modifier.widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("Compras", ok.size.toString(), Modifier.weight(1f))
                    StatCard("Total comprado", Money.format(ok.sumOf { it.total }, s.currencySymbol), Modifier.weight(1f))
                }
            }
            item {
                AppCard {
                    if (client.docId.isNotBlank()) KeyValue("Cédula / RNC", client.docId)
                    if (client.email.isNotBlank()) KeyValue("Correo", client.email)
                    if (client.address.isNotBlank()) KeyValue("Dirección", client.address)
                    KeyValue("Cliente desde", Time.date(client.createdAt))
                    if (client.notes.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(client.notes, color = AppTheme.extra.muted) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { app.cartClient = client; app.selectTab(Screen.Pos) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.ShoppingCart, null); Spacer(Modifier.width(6.dp)); Text("Venderle")
                    }
                    OutlinedButton(onClick = { app.open(Screen.QuoteEditor(clientId = id)) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Text("Cotizar")
                    }
                }
            }
            val clientQuotes = quotes.filter { it.clientId == id }
            if (clientQuotes.isNotEmpty()) {
                item { SectionTitle("Cotizaciones") }
                items(clientQuotes, key = { "q${it.id}" }) { q -> QuoteRow(q) { app.open(Screen.QuoteEditor(q.id)) } }
            }
            item { SectionTitle("Historial de compras") }
            if (sales.isEmpty()) item { Text("Todavía no tiene compras.", color = AppTheme.extra.muted) }
            else item {
                AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    sales.forEachIndexed { i, sale ->
                        if (i > 0) Divider()
                        SaleRow(sale) { app.open(Screen.SaleDetail(sale.id)) }
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    AppCard(modifier, padding = PaddingValues(14.dp), color = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Text(label, fontSize = 12.sp, color = AppTheme.extra.muted)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ================= Proveedores =================

@Composable
fun SuppliersScreen() {
    val app = LocalAppState.current
    val suppliers by app.repo.suppliers.collectAsState()
    val products by app.repo.products.collectAsState()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Long?>(null) }
    val list = suppliers.filter { s -> query.isBlank() || listOf(s.name, s.contact, s.phone).any { it.contains(query.trim(), true) } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Proveedores", subtitle = "${suppliers.size} registrados", onBack = if (app.stack.isNotEmpty()) ({ app.back() }) else null) {
            SquareIconButton(Icons.Rounded.Add, "Nuevo proveedor", primary = true) { app.open(Screen.SupplierForm()) }
        }
        SearchBox(query, { query = it }, "Buscar proveedor", Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        if (suppliers.isEmpty()) {
            EmptyState(Icons.Rounded.LocalShipping, "Sin proveedores", "Registra a quién le compras para saber qué te surte cada uno.") {
                Button(onClick = { app.open(Screen.SupplierForm()) }) { Text("Nuevo proveedor") }
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { sup ->
                val count = products.count { it.supplierId == sup.id }
                if (selected == sup.id) {
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(sup.name, 44.dp, square = true)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(sup.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(listOf(sup.contact, sup.phone).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Sin contacto" }, fontSize = 12.sp, color = AppTheme.extra.muted)
                            }
                            IconButton(onClick = { app.open(Screen.SupplierForm(sup.id)) }) { Icon(Icons.Rounded.Edit, "Editar") }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatCard("Productos", count.toString(), Modifier.weight(1f))
                            StatCard("Documento", sup.docId.ifBlank { "—" }, Modifier.weight(1f))
                        }
                        if (sup.email.isNotBlank() || sup.address.isNotBlank() || sup.notes.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            if (sup.email.isNotBlank()) KeyValue("Correo", sup.email)
                            if (sup.address.isNotBlank()) KeyValue("Dirección", sup.address)
                            if (sup.notes.isNotBlank()) Text(sup.notes, color = AppTheme.extra.muted)
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { app.open(Screen.StockEntry(sup.id)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Rounded.MoveToInbox, null); Spacer(Modifier.width(6.dp)); Text("Registrar entrada de mercancía")
                        }
                    }
                } else {
                    PersonRow(sup.name, listOf(sup.contact, "$count productos").filter { it.isNotBlank() }.joinToString(" · "), square = true) { selected = sup.id }
                }
            }
        }
    }
}

@Composable
fun SupplierFormScreen(id: Long?) {
    val app = LocalAppState.current
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    var name by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var docId by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var showErrors by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val suppliers by app.repo.suppliers.collectAsState()
    LaunchedEffect(id) {
        suppliers.firstOrNull { it.id == id }?.let { s ->
            name = s.name; contact = s.contact; phone = s.phone; email = s.email; docId = s.docId; address = s.address; notes = s.notes
        }
    }
    fun save() {
        showErrors = true
        if (name.isBlank()) return
        scope.launch { app.repo.saveSupplier(id, name, contact, phone, email, docId, address, notes); app.toast("Proveedor guardado"); app.back() }
    }
    FormScaffold(if (id == null) "Nuevo proveedor" else "Editar proveedor", onSave = ::save, onDelete = if (id != null) ({ confirmDelete = true }) else null) {
        TextInput(name, { name = it }, "Nombre de la empresa *", error = if (showErrors && name.isBlank()) "Escribe el nombre" else null)
        TextInput(contact, { contact = it }, "Persona de contacto")
        TextInput(phone, { phone = it }, "Teléfono", keyboard = KeyboardType.Phone)
        TextInput(docId, { docId = it }, "RNC o documento")
        TextInput(email, { email = it }, "Correo", keyboard = KeyboardType.Email)
        TextInput(address, { address = it }, "Dirección")
        TextInput(notes, { notes = it }, "Notas (días de visita, condiciones de pago…)", singleLine = false, minLines = 2)
    }
    if (confirmDelete && id != null) {
        ConfirmDialog("Eliminar proveedor", "Los productos de \"$name\" quedarán sin proveedor.", "Eliminar",
            onConfirm = { scope.launch { app.repo.deleteSupplier(id); app.toast("Proveedor eliminado"); app.back() } },
            onDismiss = { confirmDelete = false }, destructive = true)
    }
}
