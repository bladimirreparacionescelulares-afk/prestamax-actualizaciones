package com.vitrinapos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.Screen
import com.vitrinapos.data.AppSettings
import com.vitrinapos.data.QuoteStatus
import com.vitrinapos.data.SampleData
import com.vitrinapos.data.Time
import com.vitrinapos.platform.PrinterDevice
import com.vitrinapos.platform.rememberFileExchange
import com.vitrinapos.platform.rememberImagePicker
import com.vitrinapos.print.Ticket
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.Avatar
import com.vitrinapos.ui.components.ConfirmDialog
import com.vitrinapos.ui.components.Divider
import com.vitrinapos.ui.components.ImageCache
import com.vitrinapos.ui.components.ProductImage
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ================= Más =================

private data class MoreItem(val icon: ImageVector, val title: String, val sub: String, val screen: Screen)

@Composable
fun MoreScreen() {
    val app = LocalAppState.current
    val s = app.settings
    val quotes by app.repo.quotes.collectAsState()
    val suppliers by app.repo.suppliers.collectAsState()
    val categories by app.repo.categories.collectAsState()
    val cash by app.repo.openCash.collectAsState()
    val admin = app.can(com.vitrinapos.data.Permission.SETTINGS)
    val role by app.repo.meta.collectAsState()
    val netText = when (role["role"]) {
        com.vitrinapos.data.NetRole.SERVER -> "Equipo principal"
        com.vitrinapos.data.NetRole.CLIENT -> "Conectado"
        else -> "Este equipo trabaja solo"
    }
    val clients by app.repo.clients.collectAsState()
    val users by app.repo.users.collectAsState()
    val items = buildList {
        add(MoreItem(Icons.Rounded.Description, "Cotizaciones", "${quotes.count { it.status != QuoteStatus.SOLD }} pendientes", Screen.Quotes))
        add(MoreItem(Icons.Rounded.People, "Clientes", "${clients.size} registrados", Screen.Clients))
        add(MoreItem(Icons.Rounded.LocalShipping, "Proveedores", "${suppliers.size} registrados", Screen.Suppliers))
        add(MoreItem(Icons.AutoMirrored.Rounded.ReceiptLong, "Ventas", "Historial y anulaciones", Screen.SalesHistory))
        add(MoreItem(Icons.Rounded.BarChart, "Reportes", "Ventas y ganancias", Screen.Reports))
        add(MoreItem(Icons.Rounded.PointOfSale, "Caja", if (cash != null) "Abierta" else "Apertura y cierre", Screen.Cash))
        add(MoreItem(Icons.Rounded.QrCode2, "Etiquetas", "QR y precio para tus productos", Screen.Labels))
        add(MoreItem(Icons.Rounded.Category, "Categorías", "${categories.size} categorías", Screen.Categories))
        add(MoreItem(Icons.Rounded.Inventory2, "Inventario", "Compras, conteo, mermas, IMEI y kardex", Screen.Inventory))
        val fiscalScreen = if (app.allows(Screen.Fiscal)) Screen.Fiscal else Screen.FiscalDocs
        if (fiscalScreen == Screen.Fiscal || s.fiscalEnabled) add(MoreItem(Icons.Rounded.Receipt, "Facturación electrónica", if (s.fiscalEnabled) "Activa · ${com.vitrinapos.fiscal.DgiiEnv.of(s.fiscalEnv).title}" else "e-CF de la DGII", fiscalScreen))
        add(MoreItem(Icons.Rounded.Print, "Impresora", if (s.printerName.isNotBlank()) "${s.paperWidthMm} mm · ${s.printerName}" else "Sin configurar", Screen.Settings))
        add(MoreItem(Icons.Rounded.Lan, "Trabajo en red", netText, Screen.Network))
        add(MoreItem(Icons.Rounded.Badge, "Usuarios", if (users.isEmpty()) "Sin usuarios" else "${users.count { it.active == 1L }} activos", Screen.Users))
        if (admin && app.graph.client == null) add(MoreItem(Icons.Rounded.CloudUpload, "Respaldo", if (s.lastBackupAt > 0) "Último: ${Time.shortDate(s.lastBackupAt)}" else "Nunca", Screen.Settings))
        add(MoreItem(Icons.Rounded.Settings, "Configuración", if (admin) "Negocio, impuestos" else "Impresora de este equipo", Screen.Settings))
    }.filter { app.allows(it.screen) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Más")
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val me = app.repo.currentUser.value
            if (me != null) item(span = { GridItemSpan(maxLineSpan) }) {
                AppCard(onClick = { app.logout() }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(me.name, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(me.name, fontWeight = FontWeight.SemiBold)
                            Text(com.vitrinapos.data.Roles.label(me.role), fontSize = 12.sp, color = AppTheme.extra.muted)
                        }
                        Text("Cambiar de usuario", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                AppCard(onClick = { if (admin) app.open(Screen.Settings) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.logoPath != null) ProductImage(s.logoPath, s.businessName, Modifier.size(48.dp), RoundedCornerShape(14.dp))
                        else Avatar(s.businessName, 48.dp, square = true)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.businessName, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(s.phone, s.address).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Toca para completar los datos del negocio" },
                                fontSize = 12.sp, color = AppTheme.extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (admin) Text("Editar", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            items(items) { item ->
                AppCard(Modifier.height(128.dp), onClick = { app.open(item.screen) }, padding = PaddingValues(14.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(item.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(item.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.sub, fontSize = 12.sp, color = AppTheme.extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ================= Configuración =================

@Composable
fun SettingsScreen() {
    val app = LocalAppState.current
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    val saved = app.settings
    var d by remember { mutableStateOf(saved) }
    var taxText by remember { mutableStateOf(saved.taxRate.toString().removeSuffix(".0")) }
    var printers by remember { mutableStateOf<List<PrinterDevice>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmSamples by remember { mutableStateOf(false) }
    val files = rememberFileExchange()
    val logoPicker = rememberImagePicker { temp ->
        if (temp != null) scope.launch {
            val name = app.repo.importImage(temp, "logo")
            temp.delete()
            if (name != null) d = d.copy(logoPath = name)
        }
    }
    val admin = app.can(com.vitrinapos.data.Permission.SETTINGS)
    val dirty = d != saved || taxText != saved.taxRate.toString().removeSuffix(".0")

    fun save() {
        scope.launch {
            val rate = taxText.replace(',', '.').toDoubleOrNull() ?: 0.0
            if (saved.logoPath != null && saved.logoPath != d.logoPath) app.repo.deleteImage(saved.logoPath)
            val newSettings = d.copy(taxRate = rate)
            app.repo.saveSettings(newSettings)
            d = newSettings
            app.toast("Configuración guardada")
        }
    }

    fun searchPrinters() {
        searching = true
        app.requestPrinterPermission {
            scope.launch {
                printers = runCatching { withContext(app.platform.io) { app.platform.listPrinters() } }
                    .getOrElse { app.toast("No se pudo buscar: ${it.message}"); emptyList() }
                searching = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Configuración", onBack = if (app.stack.isNotEmpty()) ({ app.back() }) else null) {
            if (dirty) TextButton(onClick = ::save) { Text("Guardar", fontWeight = FontWeight.Bold) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (admin) {
            // ---- Negocio ----
            SectionTitle("Negocio")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).clickable { logoPicker.pickFromGallery() }) {
                    if (d.logoPath != null) ProductImage(d.logoPath, d.businessName, Modifier.fillMaxSize(), RoundedCornerShape(18.dp))
                    else Avatar(d.businessName, 72.dp, square = true)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Logo", fontWeight = FontWeight.SemiBold)
                    Text("Sale en las cotizaciones en PDF", fontSize = 12.sp, color = AppTheme.extra.muted)
                    Row {
                        TextButton(onClick = { logoPicker.pickFromGallery() }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Elegir imagen")
                        }
                        if (d.logoPath != null) TextButton(onClick = { d = d.copy(logoPath = null) }) { Text("Quitar") }
                    }
                }
            }
            TextInput(d.businessName, { d = d.copy(businessName = it) }, "Nombre del negocio")
            TextInput(d.businessTagline, { d = d.copy(businessTagline = it) }, "Eslogan o actividad (opcional)")
            TextInput(d.address, { d = d.copy(address = it) }, "Dirección")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextInput(d.phone, { d = d.copy(phone = it) }, "Teléfono", Modifier.weight(1f), keyboard = KeyboardType.Phone)
                TextInput(d.taxId, { d = d.copy(taxId = it) }, "RNC", Modifier.weight(1f))
            }
            TextInput(d.sellerName, { d = d.copy(sellerName = it) }, "Nombre del vendedor (sale en el ticket)")

            // ---- Impuestos ----
            SectionTitle("Moneda e impuestos")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextInput(d.currencySymbol, { d = d.copy(currencySymbol = it.take(4)) }, "Moneda", Modifier.weight(1f))
                TextInput(d.taxName, { d = d.copy(taxName = it.take(12)) }, "Impuesto", Modifier.weight(1f))
                TextInput(taxText, { taxText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, "%", Modifier.weight(1f), keyboard = KeyboardType.Decimal)
            }
            SwitchRow("Los precios ya incluyen el impuesto", if (d.pricesIncludeTax) "El impuesto se muestra como incluido" else "El impuesto se suma al cobrar", d.pricesIncludeTax) {
                d = d.copy(pricesIncludeTax = it)
            }

            }
            // ---- Impresora ----
            SectionTitle("Impresora de tickets")
            AppCard {
                Text("Ancho del papel", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(58, 80).forEachIndexed { i, w ->
                        SegmentedButton(selected = d.paperWidthMm == w, onClick = { d = d.copy(paperWidthMm = w) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                            Text("$w mm")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (app.platform.isDesktop) Icons.Rounded.Print else Icons.Rounded.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.printerName.ifBlank { "Ninguna impresora elegida" }, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (app.platform.isDesktop) "Impresoras instaladas en Windows" else "Primero vincula la impresora en el Bluetooth del teléfono",
                            fontSize = 12.sp, color = AppTheme.extra.muted,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = ::searchPrinters, enabled = !searching, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text(if (searching) "Buscando…" else "Buscar impresoras")
                }
                printers?.let { list ->
                    if (list.isEmpty()) Text("No se encontraron impresoras.", color = AppTheme.extra.muted, modifier = Modifier.padding(top = 8.dp))
                    list.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { d = d.copy(printerId = p.id, printerName = p.name) }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(if (d.printerId == p.id) MaterialTheme.colorScheme.primary else AppTheme.extra.line))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(p.name, fontWeight = if (d.printerId == p.id) FontWeight.Bold else FontWeight.Normal)
                                if (p.detail.isNotBlank()) Text(p.detail, fontSize = 12.sp, color = AppTheme.extra.muted)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(onClick = { app.print(Ticket.test(d.copy(taxRate = taxText.toDoubleOrNull() ?: d.taxRate)), d) }, enabled = d.printerId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Imprimir ticket de prueba") }
            }
            AppCard(onClick = { app.open(Screen.LabelPrinter) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.QrCode2, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Etiquetadora", fontWeight = FontWeight.SemiBold)
                        Text(saved.labelPrinterName.ifBlank { "Sin configurar" } + if (saved.labelPrinterId.isNotBlank()) " · ${saved.labelPrinter.language.title}" else "",
                            fontSize = 12.sp, color = AppTheme.extra.muted)
                    }
                }
            }
            SwitchRow("Imprimir al cobrar", "Imprime el ticket automáticamente en cada venta", d.autoPrint) { d = d.copy(autoPrint = it) }
            SwitchRow("Abrir gaveta de dinero", "Si tienes gaveta conectada a la impresora", d.openDrawer) { d = d.copy(openDrawer = it) }
            SwitchRow("Imprimir sin acentos", "Actívalo si tu impresora muestra símbolos raros en á, é, ñ", d.ticketAscii) { d = d.copy(ticketAscii = it) }
            TextInput(d.ticketFooter, { d = d.copy(ticketFooter = it) }, "Mensaje al final del ticket", singleLine = false)

            if (admin) {
            // ---- Cotizaciones ----
            SectionTitle("Cotizaciones")
            TextInput(d.quoteValidDays.toString(), { d = d.copy(quoteValidDays = it.filter(Char::isDigit).toIntOrNull() ?: 0) }, "Días de validez", keyboard = KeyboardType.Number)
            TextInput(d.quoteTerms, { d = d.copy(quoteTerms = it) }, "Condiciones", singleLine = false, minLines = 3)

            // ---- Ventas ----
            SectionTitle("Reglas de venta")
            SwitchRow("Exigir caja abierta para cobrar", "Las ventas, abonos y entregas solo se cobran con la caja del equipo abierta", d.requireCash) { d = d.copy(requireCash = it) }
            TextInput(d.maxDiscountPct.toString(), { d = d.copy(maxDiscountPct = it.filter(Char::isDigit).take(3).toIntOrNull()?.coerceIn(0, 100) ?: 0) },
                "Descuento máximo sin autorización (%)", keyboard = KeyboardType.Number)
            SwitchRow("El administrador puede vender sin existencias", "Apagado: nadie vende más de lo que hay en stock. A otros usuarios se les da el permiso en Más → Usuarios", d.adminNoStock) { d = d.copy(adminNoStock = it) }
            Text("Vender sin stock o cambiar precios se permite por usuario en Más → Usuarios.", fontSize = 12.sp, color = AppTheme.extra.muted)

            // ---- Taller ----
            SectionTitle("Taller de reparación")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.vitrinapos.ui.components.MoneyInput(d.repairDiagnosisFee, { d = d.copy(repairDiagnosisFee = it ?: 0) }, "Costo de diagnóstico", Modifier.weight(1f))
                TextInput(d.repairWarrantyDays.toString(), { d = d.copy(repairWarrantyDays = it.filter(Char::isDigit).take(3).toIntOrNull() ?: 0) }, "Días de garantía", Modifier.weight(1f), keyboard = KeyboardType.Number)
            }
            TextInput(d.repairAbandonDays.toString(), { d = d.copy(repairAbandonDays = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) }, "Días para considerar un equipo abandonado", keyboard = KeyboardType.Number)
            TextInput(d.repairTerms, { d = d.copy(repairTerms = it) }, "Condiciones del comprobante de recepción", singleLine = false, minLines = 3)
            TextInput(d.whatsappCountry, { d = d.copy(whatsappCountry = it.filter(Char::isDigit).take(4)) }, "Código de país para WhatsApp (1 = Rep. Dominicana)", keyboard = KeyboardType.Number)
            AppCard(onClick = { app.open(Screen.RepairStatuses) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Estados del taller", fontWeight = FontWeight.SemiBold)
                        Text(com.vitrinapos.data.RepairStatus.defs.joinToString(" · ") { it.label }, fontSize = 12.sp, color = AppTheme.extra.muted, maxLines = 2)
                    }
                    Text("Editar", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            }

            if (dirty) {
                Button(onClick = ::save, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Guardar cambios", fontWeight = FontWeight.Bold) }
            }

            // ---- Equipos y usuarios ----
            if (admin) {
            SectionTitle("Equipos y usuarios")
            AppCard(onClick = { app.open(Screen.Network) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lan, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Trabajo en red", fontWeight = FontWeight.SemiBold)
                        Text("Usar los mismos datos en varios celulares y computadoras", fontSize = 12.sp, color = AppTheme.extra.muted)
                    }
                }
            }
            AppCard(onClick = { app.open(Screen.Users) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Badge, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Usuarios y PIN", fontWeight = FontWeight.SemiBold)
                        Text("Saber quién vendió, anuló o cambió algo", fontSize = 12.sp, color = AppTheme.extra.muted)
                    }
                }
            }
            if (app.repo.users.collectAsState().value.isEmpty()) AppCard(onClick = { pinDialog = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("PIN para abrir la app", fontWeight = FontWeight.SemiBold)
                        Text(if (saved.pin.isNotBlank()) "Activado" else "Desactivado", fontSize = 12.sp, color = AppTheme.extra.muted)
                    }
                    Text(if (saved.pin.isNotBlank()) "Cambiar" else "Activar", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }

            // ---- Respaldo ----
            SectionTitle("Respaldo de datos")
            if (app.graph.client != null) Text("Este equipo está conectado a un principal: los respaldos se hacen en el equipo principal, que guarda los datos de todos.", color = AppTheme.extra.muted, fontSize = 13.sp)
            else AppCard {
                Text("Tus datos viven solo en este equipo. Haz un respaldo seguido y guárdalo fuera (USB, correo, Drive).", color = AppTheme.extra.muted, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(if (saved.lastBackupAt > 0) "Último respaldo: ${Time.dateTime(saved.lastBackupAt)}" else "Todavía no has hecho un respaldo", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val name = "vitrina-respaldo-${Time.date(Time.now()).replace('/', '-')}.zip"
                            files.saveAs(name, "application/zip", { out -> app.graph.exportBackup(out) }) { ok ->
                                if (ok) scope.launch { app.repo.updateSettings { it.copy(lastBackupAt = Time.now()) }; app.toast("Respaldo guardado") }
                            }
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                    ) { Text("Crear respaldo") }
                    OutlinedButton(onClick = { confirmRestore = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Restaurar") }
                }
            }
            TextButton(onClick = { confirmSamples = true }) { Text("Cargar productos de ejemplo") }
            }
            Text("Vitrina POS 1.3 · equipo: ${app.graph.deviceName}", fontSize = 12.sp, color = AppTheme.extra.muted)
        }
    }

    if (pinDialog) {
        PinDialog(saved.pin.isNotBlank(), onDismiss = { pinDialog = false }) { pin ->
            pinDialog = false
            scope.launch { app.repo.updateSettings { it.copy(pin = pin) }; app.toast(if (pin.isBlank()) "PIN desactivado" else "PIN guardado") }
        }
    }
    if (confirmRestore) {
        ConfirmDialog(
            "Restaurar respaldo",
            "Se reemplazan TODOS los datos de este equipo (productos, ventas, clientes, fotos) por los del respaldo. Haz un respaldo antes si no estás seguro.",
            "Elegir archivo",
            onConfirm = {
                files.open(arrayOf("application/zip", "application/octet-stream"), { input -> app.graph.importBackup(input) }) { ok ->
                    ImageCache.clear()
                    app.clearCart()
                    app.toast(if (ok) "Respaldo restaurado" else "No se pudo restaurar: el archivo no es un respaldo válido")
                    if (ok) app.selectTab(Screen.Home)
                }
            },
            onDismiss = { confirmRestore = false }, destructive = true,
        )
    }
    if (confirmSamples) {
        ConfirmDialog("Productos de ejemplo", "Se agregan 11 productos, 6 categorías, 2 proveedores y 3 clientes de ejemplo. Puedes borrarlos después.", "Agregar",
            onConfirm = { scope.launch { SampleData.load(app.repo); app.toast("Datos de ejemplo agregados") } }, onDismiss = { confirmSamples = false })
    }
}

@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    AppCard(onClick = { onChange(!checked) }, padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(sub, fontSize = 12.sp, color = AppTheme.extra.muted)
            }
            Switch(checked, onChange)
        }
    }
}

@Composable
private fun PinDialog(hasPin: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length in 4..8 && pin == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasPin) "Cambiar PIN" else "Activar PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("De 4 a 8 números. Se pedirá cada vez que abras la app.", color = AppTheme.extra.muted)
                TextInput(pin, { pin = it.filter(Char::isDigit).take(8) }, "PIN nuevo", keyboard = KeyboardType.NumberPassword)
                TextInput(confirm, { confirm = it.filter(Char::isDigit).take(8) }, "Repite el PIN", keyboard = KeyboardType.NumberPassword,
                    error = if (confirm.isNotEmpty() && confirm != pin) "No coincide" else null)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(pin) }, enabled = valid) { Text("Guardar") } },
        dismissButton = {
            Row {
                if (hasPin) TextButton(onClick = { onSave("") }) { Text("Desactivar", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}
