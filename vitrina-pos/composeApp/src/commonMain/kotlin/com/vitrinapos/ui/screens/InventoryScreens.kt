package com.vitrinapos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.RemoveShoppingCart
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.Screen
import com.vitrinapos.data.CountLine
import com.vitrinapos.data.LineInput
import com.vitrinapos.data.LossReason
import com.vitrinapos.data.Money
import com.vitrinapos.data.MoveType
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.Permission
import com.vitrinapos.data.PurchaseLine
import com.vitrinapos.data.ReturnLine
import com.vitrinapos.data.SaleStatus
import com.vitrinapos.data.Time
import com.vitrinapos.data.UnitStatus
import com.vitrinapos.data.fmtQty
import com.vitrinapos.data.fullName
import com.vitrinapos.data.ncfProblem
import com.vitrinapos.db.Product
import com.vitrinapos.platform.rememberBarcodeScanner
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.ConfirmDialog
import com.vitrinapos.ui.components.Divider
import com.vitrinapos.ui.components.DropdownField
import com.vitrinapos.ui.components.EmptyState
import com.vitrinapos.ui.components.KeyValue
import com.vitrinapos.ui.components.MoneyInput
import com.vitrinapos.ui.components.Pill
import com.vitrinapos.ui.components.PickerDialog
import com.vitrinapos.ui.components.ProductImage
import com.vitrinapos.ui.components.ProductPickerDialog
import com.vitrinapos.ui.components.QtyInput
import com.vitrinapos.ui.components.QtyStepper
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SearchBox
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.SquareIconButton
import com.vitrinapos.ui.components.StockPill
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.components.matches
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong

// ================= Inventario (centro) =================

@Composable
fun InventoryScreen() {
    val app = LocalAppState.current
    val sym = app.settings.currencySymbol
    val products by app.repo.products.collectAsState()
    val purchases by app.repo.purchases.collectAsState()
    var loss by remember { mutableStateOf(false) }
    val monthLosses by produceState(0L, products) {
        value = app.repo.lossesBetween(Time.startOfMonth(), Long.MAX_VALUE).sumOf { (-it.qty * it.unitCost).roundToLong() }
    }
    val counts by produceState(emptyList<com.vitrinapos.db.StockCount>(), products) { value = app.repo.counts() }
    val tracked = products.filter { it.trackStock == 1L }
    val low = tracked.filter { it.stock >= 0 && it.stock <= it.minStock }.sortedBy { it.stock }
    val canStock = app.can(Permission.ADJUST_STOCK)
    val canBuy = app.can(Permission.PURCHASES)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Inventario", subtitle = "${tracked.size} productos con existencias", onBack = { app.back() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)
                .widthIn(max = 900.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Valor a costo", Money.format(tracked.sumOf { (it.stock.coerceAtLeast(0.0) * it.cost).roundToLong() }, sym), Modifier.weight(1f), accent = true)
                StatCard("Valor a precio", Money.format(tracked.sumOf { (it.stock.coerceAtLeast(0.0) * it.price).roundToLong() }, sym), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Unidades", tracked.sumOf { it.stock.coerceAtLeast(0.0) }.fmtQty(), Modifier.weight(1f))
                StatCard("Mermas del mes", Money.format(monthLosses, sym), Modifier.weight(1f))
            }
            InvActions(buildList {
                if (canBuy) add(Triple(Icons.Rounded.MoveToInbox, "Nueva compra", "Con su NCF; actualiza el costo") to { app.open(Screen.StockEntry()) })
                if (canBuy) add(Triple(Icons.Rounded.ReceiptLong, "Compras", "${purchases.count { it.status == SaleStatus.OK }} registradas") to { app.open(Screen.Purchases) })
                if (canStock) add(Triple(Icons.Rounded.Checklist, "Conteo físico", if (app.repo.countDraft().isNotEmpty()) "Hay un conteo en curso" else "Cuenta con el escáner") to { app.open(Screen.StockCount) })
                if (canStock) add(Triple(Icons.Rounded.RemoveShoppingCart, "Merma", "Dañado, vencido, pérdida…") to { loss = true })
                if (app.settings.modImei) add(Triple(Icons.Rounded.PhoneAndroid, "Buscar IMEI", "En existencia o vendido") to { app.open(Screen.ImeiSearch) })
                if (canStock) add(Triple(Icons.Rounded.History, "Movimientos", "Todo lo que entra y sale") to { app.open(Screen.StockMoves) })
                if (canStock) {
                    val pending = app.repo.transfers.value.count { it.status == com.vitrinapos.data.TransferStatus.IN_TRANSIT && it.toBranch == app.repo.branchId }
                    add(Triple(Icons.Rounded.LocalShipping, "Transferencias", if (pending > 0) "$pending por recibir aquí" else "Pasar mercancía a otra sucursal") to { app.open(Screen.Transfers) })
                }
            })
            val negative = tracked.filter { it.stock < 0 }
            if (negative.isNotEmpty()) {
                SectionTitle("Revisar · ${negative.size} en negativo")
                AppCard(color = AppTheme.extra.dangerSoft, padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    Text("Se vendió más de lo que el sistema tenía (por ejemplo, ventas hechas sin conexión). Corrígelo con una compra, un conteo o un ajuste.",
                        fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                    negative.forEach { p ->
                        Divider()
                        Row(Modifier.fillMaxWidth().clickable { app.open(Screen.Kardex(p.id)) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.fullName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p.stock.fmtQty(), color = AppTheme.extra.danger, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (low.isNotEmpty()) {
                SectionTitle("Stock bajo · ${low.size}")
                AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    low.take(12).forEachIndexed { i, p ->
                        if (i > 0) Divider()
                        Row(
                            Modifier.fillMaxWidth().clickable { app.open(Screen.ProductForm(p.id)) }.padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(p.fullName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.stock.fmtQty()} / mín. ${p.minStock.fmtQty()}", color = if (p.stock <= 0) AppTheme.extra.danger else AppTheme.extra.warn, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (counts.isNotEmpty() && canStock) {
                SectionTitle("Conteos anteriores")
                AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    counts.take(5).forEachIndexed { i, c ->
                        if (i > 0) Divider()
                        Row(Modifier.fillMaxWidth().clickable { app.open(Screen.StockCountDetail(c.id)) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Conteo #${c.number} · ${c.products} productos", fontWeight = FontWeight.SemiBold)
                                Text(Time.dateTime(c.createdAt) + (if (c.userName.isNotBlank()) " · ${c.userName}" else ""), fontSize = 12.sp, color = AppTheme.extra.muted)
                            }
                            DiffText(c.diffValue, sym)
                        }
                    }
                }
            }
        }
    }
    if (loss) StockLossDialog(null, null) { loss = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InvActions(items: List<Pair<Triple<ImageVector, String, String>, () -> Unit>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
        items.forEach { (t, onClick) ->
            AppCard(Modifier.weight(1f), onClick = onClick, padding = PaddingValues(14.dp)) {
                Icon(t.first, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Text(t.second, fontWeight = FontWeight.Bold)
                Text(t.third, fontSize = 12.sp, color = AppTheme.extra.muted, maxLines = 2)
            }
        }
    }
}

@Composable
private fun DiffText(value: Long, sym: String) {
    Text(
        (if (value > 0) "+" else if (value < 0) "−" else "") + Money.format(abs(value), sym), fontWeight = FontWeight.Bold,
        color = when { value > 0 -> AppTheme.extra.ok; value < 0 -> AppTheme.extra.danger; else -> AppTheme.extra.muted },
    )
}

// ================= Compras =================

private data class PurchaseDraftLine(val product: Product, val qty: Double?, val cost: Long?, val serials: List<String> = emptyList())

private fun parseDate(t: String): Long? {
    val m = Regex("(\\d{1,2})/(\\d{1,2})/(\\d{4})").matchEntire(t.trim()) ?: return null
    val (d, mo, y) = m.destructured
    return runCatching { Time.startOfDay(kotlinx.datetime.LocalDate(y.toInt(), mo.toInt(), d.toInt())) }.getOrNull()
}

private fun dateText(ms: Long): String {
    val d = Time.local(ms).date
    return "${d.dayOfMonth.toString().padStart(2, '0')}/${d.monthNumber.toString().padStart(2, '0')}/${d.year}"
}

@Composable
fun PurchaseFormScreen(supplierId: Long?) {
    val app = LocalAppState.current
    val s = app.settings
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    val suppliers by app.repo.suppliers.collectAsState()
    val products by app.repo.products.collectAsState()
    var supplier by remember { mutableStateOf(suppliers.firstOrNull { it.id == supplierId }) }
    var rnc by remember { mutableStateOf(supplier?.docId ?: "") }
    var ncf by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(dateText(Time.now())) }
    var payment by remember { mutableStateOf("Contado") }
    var tax by remember { mutableStateOf<Long?>(0) }
    var note by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<PurchaseDraftLine>() }
    var picking by remember { mutableStateOf(false) }
    var imeiFor by remember { mutableStateOf<Int?>(null) }
    fun add(p: Product) {
        val i = lines.indexOfFirst { it.product.id == p.id }
        if (i >= 0) { if (p.serialized != 1L) lines[i] = lines[i].copy(qty = (lines[i].qty ?: 0.0) + 1) }
        else lines += PurchaseDraftLine(p, if (p.serialized == 1L) 0.0 else 1.0, p.cost)
        if (p.serialized == 1L) imeiFor = lines.indexOfFirst { it.product.id == p.id }
    }
    val scanner = rememberBarcodeScanner { code ->
        app.repo.productForCode(code, products)?.let { add(it) } ?: app.toast("No hay producto con el código $code")
    }
    val pickable = products.filter { it.isService == 0L }.let { list -> supplier?.let { sp -> list.sortedByDescending { it.supplierId == sp.id } } ?: list }
    val subtotal = lines.sumOf { ((it.qty ?: 0.0) * (it.cost ?: 0)).roundToLong() }
    val ncfError = ncfProblem(ncf)
    val docDate = parseDate(date)
    val missingImei = lines.firstOrNull { it.product.serialized == 1L && it.serials.isEmpty() }
    com.vitrinapos.ui.components.TrackUnsaved(lines.isNotEmpty() || ncf.isNotBlank() || note.isNotBlank())

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Nueva compra", subtitle = "Suma existencias y recalcula el costo promedio", onBack = { app.back() })
        LazyColumn(
            Modifier.weight(1f).widthIn(max = 760.dp).align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                DropdownField("Proveedor", supplier, suppliers, { it.name }, { supplier = it; if (it != null && it.docId.isNotBlank()) rnc = it.docId }, noneLabel = "Sin proveedor")
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextInput(rnc, { rnc = it.filter { c -> c.isDigit() || c == '-' }.take(13) }, "RNC o cédula", Modifier.weight(1f), keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                    TextInput(ncf, { ncf = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(13) }, "NCF de la factura", Modifier.weight(1f),
                        error = if (ncf.isNotBlank()) ncfError else null, placeholder = "B0100000123")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextInput(date, { date = it.filter { c -> c.isDigit() || c == '/' }.take(10) }, "Fecha de la factura", Modifier.weight(1f),
                        error = if (docDate == null) "Usa día/mes/año" else null, placeholder = "dd/mm/aaaa")
                    DropdownField("Pago", payment, listOf("Contado", "Crédito", "Transferencia", "Tarjeta"), { it }, { payment = it ?: "Contado" }, Modifier.weight(1f), noneLabel = null)
                }
            }
            itemsIndexed(lines, key = { _, l -> l.product.id }) { i, l ->
                AppCard(padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProductImage(l.product.imagePath, l.product.name, Modifier.size(48.dp), textSize = 14)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(l.product.fullName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Stock ${l.product.stock.fmtQty()} · costo actual ${Money.format(l.product.cost, s.currencySymbol)}", fontSize = 12.sp, color = AppTheme.extra.muted)
                        }
                        IconButton(onClick = { lines.removeAt(i) }) { Icon(Icons.Rounded.Delete, "Quitar", tint = AppTheme.extra.muted) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (l.product.serialized == 1L) {
                            OutlinedButton(onClick = { imeiFor = i }, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(14.dp)) {
                                Text(if (l.serials.isEmpty()) "Agregar IMEI" else "${l.serials.size} IMEI")
                            }
                        } else QtyInput(l.qty, { lines[i] = l.copy(qty = it) }, "Cantidad", Modifier.weight(1f))
                        MoneyInput(l.cost, { lines[i] = l.copy(cost = it) }, "Costo unitario sin ${s.taxName}", Modifier.weight(1f))
                    }
                    val q = l.qty ?: 0.0; val c = l.cost ?: 0
                    if (q > 0 && c != l.product.cost && l.product.stock > 0) {
                        val avg = ((l.product.stock * l.product.cost + q * c) / (l.product.stock + q)).roundToLong()
                        Text("Costo promedio nuevo: ${Money.format(avg, s.currencySymbol)}", fontSize = 12.sp, color = AppTheme.extra.muted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Agregar producto")
                    }
                    if (scanner != null) OutlinedButton(onClick = scanner, modifier = Modifier.height(50.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.QrCodeScanner, "Escanear")
                    }
                }
            }
            item {
                AppCard {
                    KeyValue("Subtotal", Money.format(subtotal, s.currencySymbol))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MoneyInput(tax, { tax = it }, "${s.taxName} de la factura", Modifier.weight(1f))
                        TextButton(onClick = {
                            tax = lines.filter { it.product.taxable == 1L }.sumOf { ((it.qty ?: 0.0) * (it.cost ?: 0) * s.taxRate / 100.0).roundToLong() }
                        }) { Text("Calcular ${s.taxRate.fmtQty()} %") }
                    }
                    Divider()
                    KeyValue("Total de la factura", Money.format(subtotal + (tax ?: 0), s.currencySymbol), strong = true)
                }
            }
            item { TextInput(note, { note = it }, "Nota (opcional)") }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 760.dp).align(Alignment.CenterHorizontally)) {
                if (tried && missingImei != null) Text("${missingImei.product.fullName}: agrega los IMEI de lo que entra.", color = AppTheme.extra.danger, fontSize = 13.sp)
                Button(
                    onClick = {
                        tried = true
                        if (missingImei != null || docDate == null || (ncf.isNotBlank() && ncfError != null) || saving) return@Button
                        saving = true
                        scope.launch {
                            runCatching {
                                app.repo.savePurchase(
                                    supplier, supplier?.name ?: "", rnc, ncf, docDate,
                                    lines.mapNotNull { l ->
                                        val q = if (l.product.serialized == 1L) l.serials.size.toDouble() else l.qty ?: 0.0
                                        if (q <= 0) null else PurchaseLine(l.product.id, q, l.cost ?: 0, l.serials)
                                    },
                                    tax ?: 0, payment, note,
                                )
                            }.onSuccess { id -> app.toast("Compra registrada"); app.replace(Screen.PurchaseDetail(id)) }
                                .onFailure { app.toast(it.message ?: "No se pudo guardar"); saving = false }
                        }
                    },
                    enabled = lines.any { (it.qty ?: 0.0) > 0 || it.serials.isNotEmpty() } && !saving,
                    modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                ) { Text("Registrar compra · ${Money.format(subtotal + (tax ?: 0), s.currencySymbol)}", fontWeight = FontWeight.Bold) }
            }
        }
    }
    if (picking) ProductPickerDialog(pickable, onPick = { add(it); picking = false }, onDismiss = { picking = false })
    imeiFor?.let { i ->
        val l = lines.getOrNull(i)
        if (l == null) imeiFor = null
        else ImeiEntryDialog("IMEI que entran · ${l.product.fullName}", l.serials, onDismiss = { imeiFor = null }) { list ->
            lines[i] = l.copy(serials = list, qty = list.size.toDouble()); imeiFor = null
        }
    }
}

/** Escribir o escanear varios IMEI (uno por línea). */
@Composable
fun ImeiEntryDialog(title: String, initial: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(initial.joinToString("\n")) }
    val scanner = rememberBarcodeScanner { code -> text = (text.trimEnd() + "\n" + code.trim()).trim() }
    val list = text.lines().map { it.trim().uppercase() }.filter { it.isNotEmpty() }
    val dup = list.groupBy { it }.filter { it.value.size > 1 }.keys
    val short = list.filter { it.length < 6 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Uno por línea. Con el lector de códigos, escanea uno tras otro.", fontSize = 13.sp, color = AppTheme.extra.muted)
                TextInput(text, { text = it }, "IMEI o número de serie", singleLine = false, minLines = 5,
                    error = when { dup.isNotEmpty() -> "Repetido: ${dup.first()}"; short.isNotEmpty() -> "Muy corto: ${short.first()}"; else -> null })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${list.size} unidades", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    if (scanner != null) TextButton(onClick = scanner) { Icon(Icons.Rounded.QrCodeScanner, null); Spacer(Modifier.width(4.dp)); Text("Escanear") }
                }
            }
        },
        confirmButton = { TextButton(enabled = dup.isEmpty() && short.isEmpty(), onClick = { onSave(list) }) { Text("Listo") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun PurchasesScreen() {
    val app = LocalAppState.current
    val sym = app.settings.currencySymbol
    val purchases by app.repo.purchases.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Compras", subtitle = "${purchases.size} registradas", onBack = { app.back() }) {
            SquareIconButton(Icons.Rounded.Add, "Nueva compra", primary = true) { app.open(Screen.StockEntry()) }
        }
        if (purchases.isEmpty()) EmptyState(Icons.Rounded.MoveToInbox, "Sin compras", "Registra la mercancía que te llega con su factura (NCF) y el costo se actualiza solo.") {
            Button(onClick = { app.open(Screen.StockEntry()) }) { Text("Nueva compra") }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(purchases, key = { it.id }) { p ->
                AppCard(onClick = { app.open(Screen.PurchaseDetail(p.id)) }, padding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Compra #${p.number} · ${p.supplierName.ifBlank { "Sin proveedor" }}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(Time.date(p.docDate), p.ncf.ifBlank { "Sin NCF" }, p.paymentMethod).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = AppTheme.extra.muted)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Money.format(p.total, sym), fontWeight = FontWeight.Bold)
                            if (p.status != SaleStatus.OK) Pill("Anulada", AppTheme.extra.danger, AppTheme.extra.dangerSoft)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PurchaseDetailScreen(id: Long) {
    val app = LocalAppState.current
    val sym = app.settings.currencySymbol
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    var refresh by remember { mutableIntStateOf(0) }
    val data by produceState<Pair<com.vitrinapos.db.Purchase, List<com.vitrinapos.db.PurchaseItem>>?>(null, id, refresh) {
        value = app.repo.purchaseById(id)?.let { it to app.repo.purchaseItems(id) }
    }
    var voiding by remember { mutableStateOf(false) }
    val (p, items) = data ?: run { Column { ScreenHeader("Compra", onBack = { app.back() }) }; return }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Compra #${p.number}", subtitle = Time.dateTime(p.createdAt) + (if (p.userName.isNotBlank()) " · ${p.userName}" else ""), onBack = { app.back() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).widthIn(max = 760.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (p.status != SaleStatus.OK) Pill("Compra anulada: la mercancía salió del inventario", AppTheme.extra.danger, AppTheme.extra.dangerSoft)
            AppCard {
                KeyValue("Proveedor", p.supplierName.ifBlank { "Sin proveedor" })
                if (p.supplierRnc.isNotBlank()) KeyValue("RNC", p.supplierRnc)
                KeyValue("NCF", p.ncf.ifBlank { "—" })
                KeyValue("Fecha de la factura", Time.date(p.docDate))
                KeyValue("Pago", p.paymentMethod)
                if (p.note.isNotBlank()) KeyValue("Nota", p.note)
            }
            AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                items.forEachIndexed { i, it ->
                    if (i > 0) Divider()
                    Column(Modifier.padding(vertical = 9.dp)) {
                        Row {
                            Text(it.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text(Money.format((it.qty * it.unitCost).roundToLong(), sym), fontWeight = FontWeight.Bold)
                        }
                        Text("${it.qty.fmtQty()} × ${Money.format(it.unitCost, sym)}", fontSize = 12.sp, color = AppTheme.extra.muted)
                        if (it.serials.isNotBlank()) Text("IMEI " + it.serials.replace(",", ", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            AppCard {
                KeyValue("Subtotal", Money.format(p.subtotal, sym))
                KeyValue(app.settings.taxName, Money.format(p.tax, sym))
                Divider()
                KeyValue("Total", Money.format(p.total, sym), strong = true)
            }
        }
        if (p.status == SaleStatus.OK && app.can(Permission.PURCHASES)) Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            OutlinedButton(onClick = { voiding = true }, modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp), shape = RoundedCornerShape(14.dp)) {
                Text("Anular compra", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (voiding) ReasonDialog("Anular compra #${p.number}", "La mercancía sale del inventario y el costo vuelve al de antes. Solo se puede si todavía está en existencia.", "Anular",
        onDismiss = { voiding = false }) { reason ->
        voiding = false
        scope.launch {
            runCatching { app.repo.voidPurchase(id, reason) }.onSuccess { refresh++; app.toast("Compra anulada") }.onFailure { app.toast(it.message ?: "No se pudo anular") }
        }
    }
}

@Composable
private fun ReasonDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(message); TextInput(reason, { reason = it }, "Motivo *") } },
        confirmButton = { TextButton(enabled = reason.isNotBlank(), onClick = { onConfirm(reason.trim()) }) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ================= Mermas =================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StockLossDialog(productId: Long?, serial: String?, onDone: () -> Unit) {
    val app = LocalAppState.current
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    val products by app.repo.products.collectAsState()
    val units by app.repo.availableUnits.collectAsState()
    var product by remember { mutableStateOf(products.firstOrNull { it.id == productId }) }
    var picking by remember { mutableStateOf(product == null) }
    var qty by remember { mutableStateOf<Double?>(1.0) }
    var reason by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>().apply { serial?.let { add(it) } } }
    val p = product
    if (picking || p == null) {
        ProductPickerDialog(products.filter { it.trackStock == 1L && it.stock > 0 }, onPick = { product = it; picking = false }, onDismiss = { if (product == null) onDone() else picking = false }, title = "¿Qué producto?")
        return
    }
    val serialized = p.serialized == 1L
    val mine = units.filter { it.productId == p.id }
    val n = if (serialized) chosen.size.toDouble() else qty ?: 0.0
    var needAuth by remember { mutableStateOf(false) }
    if (needAuth) {
        com.vitrinapos.ui.components.AdminAuthDialog(
            "Merma grande", "${n.fmtQty()} ${p.fullName}: ${Money.format((n * p.cost).roundToLong(), app.settings.currencySymbol)} a costo.",
            onAuthorized = { name ->
                needAuth = false
                scope.launch {
                    runCatching { app.repo.stockLoss(p.id, n, reason ?: "", note, chosen.toList(), name) }
                        .onSuccess { app.toast("Merma registrada"); onDone() }.onFailure { app.toast(it.message ?: "No se pudo guardar") }
                }
            },
            onDismiss = { needAuth = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Registrar merma") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = productId == null) { picking = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProductImage(p.imagePath, p.name, Modifier.size(40.dp), textSize = 12)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.fullName, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text("Hay ${p.stock.fmtQty()} · costo ${Money.format(p.cost, app.settings.currencySymbol)}", fontSize = 12.sp, color = AppTheme.extra.muted)
                    }
                }
                if (serialized) {
                    Text("¿Cuál unidad?", fontWeight = FontWeight.SemiBold)
                    if (mine.isEmpty()) Text("No hay IMEI registrados en existencia.", color = AppTheme.extra.muted)
                    mine.forEach { u ->
                        Row(Modifier.fillMaxWidth().clickable { if (u.serial in chosen) chosen.remove(u.serial) else chosen.add(u.serial) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(u.serial in chosen, { if (it) chosen.add(u.serial) else chosen.remove(u.serial) })
                            Text(u.serial, fontFamily = FontFamily.Monospace)
                        }
                    }
                } else QtyInput(qty, { qty = it }, "Cantidad que se pierde")
                Text("Motivo", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LossReason.all.forEach { r -> CategoryChip(r, reason == r) { reason = r } }
                }
                TextInput(note, { note = it }, "Detalle (opcional)")
                if (n > 0) Text("Pérdida: ${Money.format((n * p.cost).roundToLong(), app.settings.currencySymbol)}", color = AppTheme.extra.danger, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            TextButton(enabled = n > 0 && reason != null, onClick = {
                val r = reason ?: return@TextButton
                val limit = app.settingsNow.approvalAmount
                if (limit > 0 && n * p.cost > limit && app.needsAdminAuth) { needAuth = true; return@TextButton }
                val by = if (limit > 0 && n * p.cost > limit) app.repo.currentUser.value?.name ?: "Administrador" else ""
                scope.launch {
                    runCatching { app.repo.stockLoss(p.id, n, r, note, chosen.toList(), by) }
                        .onSuccess { app.toast("Merma registrada"); onDone() }.onFailure { app.toast(it.message ?: "No se pudo guardar") }
                }
            }) { Text("Registrar") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar") } },
    )
}

// ================= Conteo físico =================

@Composable
fun StockCountScreen() {
    val app = LocalAppState.current
    val sym = app.settings.currencySymbol
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    val products by app.repo.products.collectAsState()
    // productId → (contado, stock del sistema cuando se contó por primera vez)
    val counted = remember { mutableStateMapOf<Long, Pair<Double, Double>>().apply { putAll(app.repo.countDraft()) } }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Product?>(null) }
    val countable = products.filter { it.trackStock == 1L && it.serialized == 0L }
    fun save() = app.repo.saveCountDraft(counted.toMap())
    fun bump(p: Product, by: Double = 1.0) {
        val cur = counted[p.id]
        counted[p.id] = ((cur?.first ?: 0.0) + by) to (cur?.second ?: p.stock)
        save()
    }
    fun bumpCode(code: String): Boolean {
        val p = app.repo.productForCode(code, countable)
        if (p == null) {
            app.toast(if (products.any { it.code.equals(code.trim(), true) && it.serialized == 1L }) "Los equipos con IMEI se revisan en su ficha" else "No hay producto con el código $code")
            return false
        }
        bump(p); app.toast("${p.fullName}: ${counted[p.id]?.first?.fmtQty()}")
        return true
    }
    val scanner = rememberBarcodeScanner { bumpCode(it) }
    val list = countable.filter { it.matches(query) }.filter { p ->
        when (filter) {
            1 -> p.id in counted
            2 -> counted[p.id]?.let { (c, x) -> abs(c - x) > 1e-9 } == true
            3 -> p.id !in counted
            else -> true
        }
    }
    val diffs = counted.mapNotNull { (id, v) -> products.firstOrNull { it.id == id }?.let { p -> p to (v.first - v.second) } }
    val diffValue = diffs.sumOf { (p, d) -> (d * p.cost).roundToLong() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Conteo físico", subtitle = "${counted.size} de ${countable.size} contados", onBack = { app.back() }) {
            if (counted.isNotEmpty()) IconButton(onClick = { discard = true }) { Icon(Icons.Rounded.Delete, "Descartar conteo", tint = AppTheme.extra.muted) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchBox(query, { query = it }, "Buscar o escanear (cada lectura suma 1)", Modifier.weight(1f), onSubmit = { if (bumpCode(query)) query = "" })
            if (scanner != null) { Spacer(Modifier.width(8.dp)); SquareIconButton(Icons.Rounded.QrCodeScanner, "Escanear", primary = true, onClick = scanner) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val names = listOf("Todos", "Contados", "Con diferencia", "Sin contar")
            items(names.size) { i -> CategoryChip(names[i], filter == i) { filter = i } }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            items(list, key = { it.id }) { p ->
                val c = counted[p.id]
                Row(Modifier.fillMaxWidth().clickable { editing = p }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.fullName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Sistema: ${(c?.second ?: p.stock).fmtQty()}" + (if (p.code.isNotBlank()) " · ${p.code}" else ""), fontSize = 12.sp, color = AppTheme.extra.muted)
                    }
                    if (c != null) {
                        val d = c.first - c.second
                        if (abs(d) > 1e-9) Text((if (d > 0) "+" else "") + d.fmtQty(), color = if (d > 0) AppTheme.extra.ok else AppTheme.extra.danger, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                        QtyStepper(c.first, { v -> counted[p.id] = v.coerceAtLeast(0.0) to c.second; save() })
                    } else OutlinedButton(onClick = { editing = p }, shape = RoundedCornerShape(12.dp)) { Text("Contar") }
                }
                Divider()
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 760.dp).align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (counted.isNotEmpty()) Row {
                    Text("${diffs.count { abs(it.second) > 1e-9 }} con diferencia", Modifier.weight(1f), color = AppTheme.extra.muted)
                    DiffText(diffValue, sym)
                }
                Button(onClick = { confirm = true }, enabled = counted.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                    Text("Revisar y aplicar", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    editing?.let { p ->
        var v by remember(p.id) { mutableStateOf(counted[p.id]?.first) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(p.fullName) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("En el sistema: ${p.stock.fmtQty()} ${p.unit}", color = AppTheme.extra.muted); QtyInput(v, { v = it }, "Cantidad contada") } },
            confirmButton = { TextButton(enabled = v != null, onClick = { counted[p.id] = (v ?: 0.0).coerceAtLeast(0.0) to (counted[p.id]?.second ?: p.stock); save(); editing = null }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } },
        )
    }
    if (discard) ConfirmDialog("Descartar el conteo", "Se borra lo contado hasta ahora. El inventario no cambia.", "Descartar",
        onConfirm = { counted.clear(); save() }, onDismiss = { discard = false }, destructive = true)
    if (confirm) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Aplicar conteo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Se ajustan ${counted.size} productos a lo que contaste. Lo que se vendió mientras contabas se descuenta solo. Los productos sin contar no cambian.")
                    KeyValue("Diferencia en valor", (if (diffValue >= 0) "+" else "−") + Money.format(abs(diffValue), sym), strong = true)
                    TextInput(note, { note = it }, "Nota (opcional)")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        runCatching { app.repo.applyCount(counted.map { (id, v) -> CountLine(id, v.first, v.second) }, note) }
                            .onSuccess { id -> counted.clear(); save(); app.toast("Conteo aplicado"); app.replace(Screen.StockCountDetail(id)) }
                            .onFailure { app.toast(it.message ?: "No se pudo aplicar") }
                    }
                }) { Text("Aplicar") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Seguir contando") } },
        )
    }
}

@Composable
fun StockCountDetailScreen(id: Long) {
    val app = LocalAppState.current
    val sym = app.settings.currencySymbol
    val data by produceState<Pair<com.vitrinapos.db.StockCount?, List<com.vitrinapos.db.StockCountItem>>?>(null, id) {
        value = app.repo.counts().firstOrNull { it.id == id } to app.repo.countItems(id)
    }
    val (c, items) = data ?: (null to emptyList())
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(c?.let { "Conteo #${it.number}" } ?: "Conteo", subtitle = c?.let { Time.dateTime(it.createdAt) + (if (it.userName.isNotBlank()) " · ${it.userName}" else "") }, onBack = { app.back() })
        if (c == null) return@Column
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("Productos", c.products.toString(), Modifier.weight(1f))
                    StatCard("Diferencia", (if (c.diffValue >= 0) "+" else "−") + Money.format(abs(c.diffValue), sym), Modifier.weight(1f), accent = true)
                }
            }
            if (c.note.isNotBlank()) item { Text(c.note, color = AppTheme.extra.muted) }
            item {
                AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    items.sortedByDescending { abs(it.counted - it.expected) }.forEachIndexed { i, it ->
                        if (i > 0) Divider()
                        val d = it.counted - it.expected
                        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(it.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Sistema ${it.expected.fmtQty()} · contado ${it.counted.fmtQty()}", fontSize = 12.sp, color = AppTheme.extra.muted)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (abs(d) < 1e-9) "Cuadra" else (if (d > 0) "+" else "") + d.fmtQty(), fontWeight = FontWeight.Bold,
                                    color = when { d > 1e-9 -> AppTheme.extra.ok; d < -1e-9 -> AppTheme.extra.danger; else -> AppTheme.extra.muted })
                                if (abs(d) > 1e-9) Text(Money.format((d * it.cost).roundToLong(), sym), fontSize = 12.sp, color = AppTheme.extra.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================= Kardex =================

@Composable
fun KardexScreen(productId: Long) {
    val app = LocalAppState.current
    val sym = app.settings.currencySymbol
    val products by app.repo.products.collectAsState()
    val p = products.firstOrNull { it.id == productId }
    var range by remember { mutableIntStateOf(0) }
    val today = Time.startOfDay()
    val from = when (range) { 0 -> Time.startOfMonth(); 1 -> Time.addDays(today, -89); else -> 0L }
    val data by produceState<Pair<Double, List<com.vitrinapos.db.StockMove>>?>(null, productId, range, p?.stock) { value = app.repo.kardex(productId, from, Long.MAX_VALUE) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Kardex", subtitle = p?.fullName, onBack = { app.back() })
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val names = listOf("Este mes", "90 días", "Todo")
            items(names.size) { i -> CategoryChip(names[i], range == i) { range = i } }
        }
        val (opening, moves) = data ?: return@Column
        val ins = moves.filter { it.qty > 0 }.sumOf { it.qty }
        val outs = moves.filter { it.qty < 0 }.sumOf { -it.qty }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("Existencia", (p?.stock ?: 0.0).fmtQty(), Modifier.weight(1f), accent = true)
                    StatCard("Costo promedio", Money.format(p?.cost ?: 0, sym), Modifier.weight(1f))
                }
            }
            item {
                AppCard {
                    KeyValue("Saldo al inicio", opening.fmtQty())
                    KeyValue("Entradas", "+" + ins.fmtQty(), valueColor = AppTheme.extra.ok)
                    KeyValue("Salidas", "−" + outs.fmtQty(), valueColor = AppTheme.extra.danger)
                    Divider()
                    KeyValue("Valor en existencia", Money.format(((p?.stock ?: 0.0).coerceAtLeast(0.0) * (p?.cost ?: 0)).roundToLong(), sym), strong = true)
                }
            }
            if (moves.isEmpty()) item { Text("Sin movimientos en este periodo.", color = AppTheme.extra.muted) }
            else item {
                AppCard(padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Text("Fecha y movimiento", Modifier.weight(1f), fontSize = 11.sp, color = AppTheme.extra.muted)
                        Text("Cant.", Modifier.width(56.dp), fontSize = 11.sp, color = AppTheme.extra.muted)
                        Text("Saldo", Modifier.width(56.dp), fontSize = 11.sp, color = AppTheme.extra.muted)
                    }
                    var balance = opening
                    moves.forEach { m ->
                        balance += m.qty
                        Divider()
                        Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(MoveType.label(m.type), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(listOf(Time.dateTime(m.createdAt), m.note, if (m.unitCost > 0) "costo ${Money.format(m.unitCost, sym)}" else "").filter { it.isNotBlank() }.joinToString(" · "),
                                    fontSize = 11.sp, color = AppTheme.extra.muted, maxLines = 2)
                            }
                            Text((if (m.qty > 0) "+" else "") + m.qty.fmtQty(), Modifier.width(56.dp), fontWeight = FontWeight.Bold,
                                color = if (m.qty > 0) AppTheme.extra.ok else AppTheme.extra.danger)
                            Text(balance.fmtQty(), Modifier.width(56.dp), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// ================= IMEI =================

@Composable
fun ImeiSearchScreen() {
    val app = LocalAppState.current
    var q by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf("") }
    val results by produceState(emptyList<com.vitrinapos.db.SearchUnits>(), searched) { value = if (searched.length >= 3) app.repo.searchUnits(searched) else emptyList() }
    val scanner = rememberBarcodeScanner { q = it; searched = it }
    LaunchedEffect(q) { if (q.length >= 4) { kotlinx.coroutines.delay(250); searched = q } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Buscar IMEI", subtitle = "Dónde está o a quién se vendió", onBack = { app.back() })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchBox(q, { q = it }, "IMEI o número de serie", Modifier.weight(1f), onSubmit = { searched = q })
            if (scanner != null) { Spacer(Modifier.width(8.dp)); SquareIconButton(Icons.Rounded.QrCodeScanner, "Escanear", primary = true, onClick = scanner) }
        }
        if (searched.length >= 3 && results.isEmpty()) EmptyState(Icons.Rounded.Search, "Sin resultados", "No hay ningún equipo con \"$searched\".")
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.id }) { u ->
                AppCard(onClick = { if (u.saleId != null && u.status == UnitStatus.SOLD) app.open(Screen.SaleDetail(u.saleId!!)) else app.open(Screen.ProductUnits(u.productId)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(u.serial, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(u.productName, fontSize = 13.sp)
                            Text(listOf(Time.dateTime(u.updatedAt), u.note).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = AppTheme.extra.muted)
                        }
                        UnitStatusPill(u.status)
                    }
                }
            }
        }
    }
}

@Composable
fun UnitStatusPill(status: String) {
    val (fg, bg) = when (status) {
        UnitStatus.AVAILABLE -> AppTheme.extra.ok to AppTheme.extra.okSoft
        UnitStatus.SOLD -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
        else -> AppTheme.extra.danger to AppTheme.extra.dangerSoft
    }
    Pill(UnitStatus.label(status), fg, bg)
}

@Composable
fun ProductUnitsScreen(productId: Long) {
    val app = LocalAppState.current
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    val products by app.repo.products.collectAsState()
    val p = products.firstOrNull { it.id == productId }
    val units by produceState(emptyList<com.vitrinapos.db.ProductUnit>(), productId, p?.stock, p?.updatedAt) { value = app.repo.unitsOf(productId) }
    var adding by remember { mutableStateOf(false) }
    var loss by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableIntStateOf(0) }
    val available = units.filter { it.status == UnitStatus.AVAILABLE }
    val missing = ((p?.stock ?: 0.0) - available.size).coerceAtLeast(0.0)
    val shown = if (filter == 0) available else units
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("IMEI", subtitle = p?.fullName, onBack = { app.back() }) {
            if (app.can(Permission.ADJUST_STOCK)) SquareIconButton(Icons.Rounded.Add, "Registrar IMEI", primary = true) { adding = true }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("En existencia", available.size.toString(), Modifier.weight(1f), accent = true)
                    StatCard("Vendidos", units.count { it.status == UnitStatus.SOLD }.toString(), Modifier.weight(1f))
                }
            }
            if (missing > 0) item {
                AppCard(color = AppTheme.extra.warnSoft, onClick = { adding = true }) {
                    Text("Faltan ${missing.fmtQty()} IMEI por registrar", fontWeight = FontWeight.Bold, color = AppTheme.extra.warn)
                    Text("El sistema dice que hay ${(p?.stock ?: 0.0).fmtQty()}, pero solo ${available.size} tienen IMEI. Sin IMEI no se pueden vender.", fontSize = 13.sp)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryChip("En existencia", filter == 0) { filter = 0 }
                    CategoryChip("Todos", filter == 1) { filter = 1 }
                }
            }
            if (shown.isEmpty()) item { Text("No hay IMEI registrados.", color = AppTheme.extra.muted) }
            items(shown, key = { it.id }) { u ->
                AppCard(onClick = { if (u.saleId != null && u.status == UnitStatus.SOLD) app.open(Screen.SaleDetail(u.saleId!!)) }, padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(u.serial, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(listOf("Costo ${Money.format(u.cost, app.settings.currencySymbol)}", Time.date(u.updatedAt), u.note).filter { it.isNotBlank() }.joinToString(" · "),
                                fontSize = 12.sp, color = AppTheme.extra.muted)
                        }
                        UnitStatusPill(u.status)
                        if (u.status == UnitStatus.AVAILABLE && app.can(Permission.ADJUST_STOCK))
                            IconButton(onClick = { loss = u.serial }) { Icon(Icons.Rounded.RemoveShoppingCart, "Merma", tint = AppTheme.extra.muted) }
                    }
                }
            }
        }
    }
    if (adding && p != null) ImeiEntryDialog("Registrar IMEI · ${p.fullName}", emptyList(), onDismiss = { adding = false }) { list ->
        adding = false
        if (list.isNotEmpty()) scope.launch {
            runCatching { app.repo.addUnits(productId, list) }
                .onSuccess { app.toast(if (list.size > missing) "IMEI registrados; entraron ${(list.size - missing).fmtQty()} unidades nuevas" else "IMEI registrados") }
                .onFailure { app.toast(it.message ?: "No se pudo guardar") }
        }
    }
    loss?.let { sn -> StockLossDialog(productId, sn) { loss = null } }
}

/** Elegir el IMEI de la unidad que se vende. */
@Composable
fun UnitPickerDialog(productId: Long) {
    val app = LocalAppState.current
    val products by app.repo.products.collectAsState()
    val units by app.repo.availableUnits.collectAsState()
    val p = products.firstOrNull { it.id == productId } ?: run { app.unitPicker = null; return }
    var q by remember { mutableStateOf("") }
    val inCart = app.cart.flatMap { it.serials }.toSet()
    val mine = units.filter { it.productId == productId && it.serial !in inCart && it.serial.contains(q.trim(), ignoreCase = true) }
    val scanner = rememberBarcodeScanner { code -> if (app.addUnit(p, code)) app.unitPicker = null }
    PickerDialog("IMEI · ${p.fullName}", onDismiss = { app.unitPicker = null }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchBox(q, { q = it }, "Buscar o escanear IMEI", Modifier.weight(1f), onSubmit = { if (app.addUnit(p, q)) app.unitPicker = null })
            if (scanner != null) { Spacer(Modifier.width(8.dp)); SquareIconButton(Icons.Rounded.QrCodeScanner, "Escanear", primary = true, onClick = scanner) }
        }
        if (mine.isEmpty()) Text(
            if (units.none { it.productId == productId }) "No hay unidades con IMEI en existencia. Regístralas en la ficha del producto o con una compra." else "No hay más unidades disponibles.",
            Modifier.padding(16.dp), color = AppTheme.extra.muted,
        )
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(mine, key = { it.id }) { u ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { if (app.addUnit(p, u.serial)) app.unitPicker = null }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(u.serial, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    if (u.note.isNotBlank()) Text(u.note, fontSize = 11.sp, color = AppTheme.extra.muted, maxLines = 1)
                }
            }
        }
    }
}

/** Elegir la variante (color, capacidad…) al vender. */
@Composable
fun VariantPickerDialog(group: String) {
    val app = LocalAppState.current
    val products by app.repo.products.collectAsState()
    val list = products.filter { it.variantGroup == group }
    if (list.isEmpty()) { app.variantPicker = null; return }
    PickerDialog(list.first().name, onDismiss = { app.variantPicker = null }) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.id }) { p ->
                val limit = app.stockLimit(p.id)
                val can = limit == null || app.qtyInCart(p.id) < limit - 1e-9
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = can) {
                        app.variantPicker = null
                        if (app.addToCart(p)) app.toast("Agregado: ${p.fullName}")
                    }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProductImage(p.imagePath, p.fullName, Modifier.size(48.dp), textSize = 14)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.variantName.ifBlank { p.name }, fontWeight = FontWeight.SemiBold)
                        Text(Money.format(p.price, app.settings.currencySymbol), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    }
                    StockPill(p)
                    val inCart = app.qtyInCart(p.id)
                    if (inCart > 0) Text("  ×${inCart.fmtQty()}", color = AppTheme.extra.cta, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ================= Devoluciones =================

@Composable
fun SaleReturnScreen(saleId: Long) {
    val app = LocalAppState.current
    val s = app.settings
    val sym = s.currencySymbol
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    data class Data(val sale: com.vitrinapos.db.Sale, val items: List<com.vitrinapos.db.SaleItem>, val returned: List<com.vitrinapos.db.SaleReturnItem>, val hasEcf: Boolean)
    val data by produceState<Data?>(null, saleId) {
        val sale = app.repo.saleById(saleId) ?: return@produceState
        value = Data(sale, app.repo.saleItems(saleId), app.repo.returnItemsBySale(saleId), app.repo.ecfForSale(saleId).any { it.type != 34L })
    }
    val d = data ?: run { Column { ScreenHeader("Devolución", onBack = { app.back() }) }; return }
    val qty = remember { mutableStateMapOf<Long, Double>() }
    val restock = remember { mutableStateMapOf<Long, Boolean>() }
    val serials = remember { mutableStateMapOf<Long, List<String>>() }
    var method by remember { mutableStateOf(d.sale.paymentMethod) }
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val gross = d.items.sumOf { LineInput(it.qty, it.price, it.taxable == 1L).amount }
    val ratio = if (gross == 0L) 0.0 else d.sale.total.toDouble() / gross
    fun left(it: com.vitrinapos.db.SaleItem) = it.qty - d.returned.filter { r -> r.saleItemId == it.id }.sumOf { r -> r.qty }
    val amount = d.items.sumOf { (((qty[it.id] ?: 0.0) * it.price * ratio)).roundToLong() }
    val anything = qty.values.any { it > 0 }
    val rules = app.settings
    val tooOld = rules.returnMaxDays > 0 && Time.now() - d.sale.createdAt > rules.returnMaxDays * 86_400_000L
    val tooBig = rules.approvalAmount > 0 && amount > rules.approvalAmount
    var needAuth by remember { mutableStateOf(false) }
    fun send(by: String) {
        busy = true
        scope.launch {
            runCatching {
                app.repo.returnSale(saleId, d.items.filter { (qty[it.id] ?: 0.0) > 0 }.map {
                    ReturnLine(it.id, qty[it.id] ?: 0.0, restock[it.id] != false, serials[it.id] ?: emptyList())
                }, reason, method, by)
            }.onSuccess { app.toast("Devolución registrada"); app.back() }.onFailure { busy = false; app.toast(it.message ?: "No se pudo registrar") }
        }
    }
    if (needAuth) com.vitrinapos.ui.components.AdminAuthDialog(
        "Devolución que pide autorización",
        listOfNotNull(if (tooOld) "La venta tiene más de ${rules.returnMaxDays} días." else null, if (tooBig) "Se devuelven ${Money.format(amount, sym)}." else null).joinToString(" "),
        onAuthorized = { name -> needAuth = false; send(name) }, onDismiss = { needAuth = false },
    )
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Devolución", subtitle = "${d.sale.let { if (it.number < 0) "Venta pendiente" else "Venta #${it.number}" }} · ${Time.date(d.sale.createdAt)}", onBack = { app.back() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).widthIn(max = 760.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Elige qué devuelve el cliente.", color = AppTheme.extra.muted)
            if (tooOld) AppCard(color = AppTheme.extra.warnSoft) { Text("La venta tiene más de ${rules.returnMaxDays} días: la devolución la autoriza un administrador.", fontSize = 13.sp) }
            d.items.forEach { item ->
                val max = left(item)
                val sold = item.serials.split(',').filter { it.isNotBlank() }
                val back = d.returned.filter { it.saleItemId == item.id }.flatMap { it.serials.split(',') }.toSet()
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.SemiBold)
                            Text("Vendido ${item.qty.fmtQty()} × ${Money.format(item.price, sym)}" + (if (max < item.qty) " · ya devuelto ${(item.qty - max).fmtQty()}" else ""),
                                fontSize = 12.sp, color = AppTheme.extra.muted)
                        }
                        if (sold.isEmpty() && max > 0) QtyStepper(qty[item.id] ?: 0.0, { v -> qty[item.id] = v.coerceIn(0.0, max) })
                    }
                    if (sold.isNotEmpty()) sold.filter { it !in back }.forEach { sn ->
                        val picked = sn in (serials[item.id] ?: emptyList())
                        Row(Modifier.fillMaxWidth().clickable {
                            val list = (serials[item.id] ?: emptyList()).let { if (picked) it - sn else it + sn }
                            serials[item.id] = list; qty[item.id] = list.size.toDouble()
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(picked, null)
                            Text("IMEI $sn", fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (max <= 0) Text("Ya se devolvió completo", fontSize = 12.sp, color = AppTheme.extra.muted)
                    if ((qty[item.id] ?: 0.0) > 0 && item.productId != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (restock[item.id] != false) "Vuelve al inventario" else "Dañado: no vuelve al inventario", Modifier.weight(1f), fontSize = 13.sp)
                        Switch(restock[item.id] != false, { restock[item.id] = it })
                    }
                }
            }
            SectionTitle("Reembolso")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentMethods.all.forEach { m -> CategoryChip(m, method == m) { method = m } }
            }
            if (method == PaymentMethods.CASH) Text("El efectivo sale de la caja abierta de este equipo.", fontSize = 12.sp, color = AppTheme.extra.muted)
            TextInput(reason, { reason = it }, "Motivo de la devolución *")
            if (d.hasEcf) AppCard(color = AppTheme.extra.warnSoft) {
                Text("Esta venta tiene comprobante electrónico: se emite una nota de crédito (E34) por lo devuelto.", fontSize = 13.sp)
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Button(
                onClick = {
                    when {
                        (tooOld || tooBig) && app.needsAdminAuth -> needAuth = true
                        tooOld || tooBig -> send(app.repo.currentUser.value?.name ?: "Administrador")
                        else -> send("")
                    }
                },
                enabled = anything && reason.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp), shape = RoundedCornerShape(16.dp),
            ) { Text("Devolver ${Money.format(amount, sym)}", fontWeight = FontWeight.Bold) }
        }
    }
}
