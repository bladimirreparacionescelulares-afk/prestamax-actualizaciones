package com.vitrinapos.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.AppState
import com.vitrinapos.LocalAppState
import com.vitrinapos.data.fullName
import com.vitrinapos.Screen
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.Money
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.Totals
import com.vitrinapos.data.fmtQty
import com.vitrinapos.db.Product
import com.vitrinapos.platform.rememberBarcodeScanner
import com.vitrinapos.print.Ticket
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.ClientPickerDialog
import com.vitrinapos.ui.components.CountBadge
import com.vitrinapos.ui.components.Divider
import com.vitrinapos.ui.components.EmptyState
import com.vitrinapos.ui.components.KeyValue
import com.vitrinapos.ui.components.MoneyInput
import com.vitrinapos.ui.components.ProductImage
import com.vitrinapos.ui.components.QtyInput
import com.vitrinapos.ui.components.QtyStepper
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SearchBox
import com.vitrinapos.ui.components.SquareIconButton
import com.vitrinapos.ui.components.StockPill
import com.vitrinapos.ui.components.matches
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** Busca por código exacto (lector o escáner) y agrega al carrito. */
fun AppState.addByCode(code: String, products: List<Product>): Boolean {
    val c = code.trim()
    if (c.isEmpty()) return false
    val p = repo.productForCode(c, products)
    if (p == null) {
        // ¿Es el IMEI de un equipo en existencia?
        val u = repo.availableUnits.value.firstOrNull { it.serial.equals(c, ignoreCase = true) } ?: return false
        val owner = products.firstOrNull { it.id == u.productId } ?: return false
        if (addUnit(owner, u.serial)) toast("Agregado: ${owner.fullName} · IMEI ${u.serial}")
        return true
    }
    if (addToCart(p)) toast("Agregado: ${p.fullName}")
    return true
}

@Composable
fun PosScreen(wide: Boolean) {
    val app = LocalAppState.current
    val products by app.repo.products.collectAsState()
    val categories by app.repo.categories.collectAsState()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<Long?>(null) }
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    val scanner = rememberBarcodeScanner { code ->
        if (app.settingsNow.modTaller && com.vitrinapos.data.parseRepairCode(code) != null && products.none { it.code.equals(code, true) }) {
            scope.launch { app.openRepairByCode(code) }
        } else if (!app.addByCode(code, products)) {
            app.toast("No hay producto con el código $code")
            if (app.can(com.vitrinapos.data.Permission.CATALOG_EDIT)) app.open(Screen.ProductForm(presetCode = code))
        }
    }
    val matching = products.filter { (category == null || it.categoryId == category) && it.matches(query) }
    // Las variantes de un mismo producto salen en una sola tarjeta (al buscar, cada una por separado).
    val list = if (query.isNotBlank()) matching else matching.filter { p ->
        p.variantGroup.isBlank() || matching.first { it.variantGroup == p.variantGroup } == p
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchBox(
                    query, { query = it }, if (scanner != null) "Buscar por nombre o código" else "Buscar o escanear código (lector USB)",
                    Modifier.weight(1f),
                    onSubmit = {
                        if (app.settingsNow.modTaller && com.vitrinapos.data.parseRepairCode(query) != null && products.none { it.code.equals(query.trim(), true) }) {
                            val q = query; query = ""
                            scope.launch { app.openRepairByCode(q) }
                        } else if (app.addByCode(query, products)) query = ""
                    },
                )
                if (scanner != null) {
                    Spacer(Modifier.width(8.dp))
                    SquareIconButton(Icons.Rounded.QrCodeScanner, "Escanear códigos", primary = true) { app.open(Screen.Scanner(ScanMode.SALE)) }
                }
            }
            if (categories.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { CategoryChip("Todos", category == null) { category = null } }
                    items(categories, key = { it.id }) { c -> CategoryChip(c.name, category == c.id) { category = if (category == c.id) null else c.id } }
                }
            }
            Box(Modifier.weight(1f)) {
                when {
                    products.isEmpty() -> EmptyState(
                        Icons.Rounded.ShoppingCart, "Aún no hay productos",
                        "Registra tus productos con foto y precio para empezar a vender.",
                    ) { if (app.can(com.vitrinapos.data.Permission.CATALOG_EDIT)) Button(onClick = { app.open(Screen.ProductForm()) }) { Text("Nuevo producto") } }
                    list.isEmpty() -> EmptyState(Icons.Rounded.SearchOff, "Sin resultados", "No hay productos que coincidan con \"$query\".") {
                        if (app.can(com.vitrinapos.data.Permission.CATALOG_EDIT)) OutlinedButton(onClick = { app.open(Screen.ProductForm(presetCode = query.takeIf { q -> q.none { it == ' ' } })) }) { Text("Crear producto") }
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (wide) 170.dp else 150.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = if (!wide && app.cart.isNotEmpty()) 96.dp else 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(list, key = { it.id }) { p ->
                            val group = if (query.isBlank() && p.variantGroup.isNotBlank()) products.filter { it.variantGroup == p.variantGroup } else emptyList()
                            if (group.size > 1) VariantTile(p, group, group.sumOf { app.qtyInCart(it.id) }) { app.variantPicker = p.variantGroup }
                            else ProductTile(
                                p, app.qtyInCart(p.id), onAdd = { app.addToCart(p) },
                                onEdit = { if (app.can(com.vitrinapos.data.Permission.CATALOG_EDIT)) app.open(Screen.ProductForm(p.id)) },
                                onRemove = { app.removeOne(p.id) },
                            )
                        }
                    }
                }
                if (!wide && app.cart.isNotEmpty()) {
                    CartBar(app, Modifier.align(Alignment.BottomCenter).padding(12.dp))
                }
            }
        }
        if (wide) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.width(360.dp).fillMaxHeight()) {
                CartPanel(app)
            }
        }
    }
}

@Composable
fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.onBackground,
            selectedLabelColor = MaterialTheme.colorScheme.background,
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProductTile(p: Product, inCart: Double, onAdd: () -> Unit, onEdit: () -> Unit, onRemove: (() -> Unit)? = null) {
    val app = LocalAppState.current
    val limit = app.stockLimit(p.id)
    val canAdd = limit == null || inCart < limit - 1e-9
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(if (inCart > 0) 2.dp else 1.dp, if (inCart > 0) AppTheme.extra.cta else AppTheme.extra.line),
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).combinedClickable(onClick = onAdd, onLongClick = onEdit),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                ProductImage(p.imagePath, p.name, Modifier.fillMaxWidth().aspectRatio(1.15f), RoundedCornerShape(13.dp), textSize = 28)
                StockPill(p, Modifier.align(Alignment.TopStart).padding(6.dp))
                if (inCart > 0) CountBadge(inCart.fmtQty())
            }
            Text(p.fullName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp, modifier = Modifier.height(34.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Money.format(p.price, app.settings.currencySymbol), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                    if (p.code.isNotBlank()) Text(p.code, fontSize = 11.sp, color = AppTheme.extra.muted, maxLines = 1)
                }
                // Si ya está en el carrito, se puede quitar uno sin ir al cobro.
                if (inCart > 0 && onRemove != null) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onRemove),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Remove, "Quitar uno", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(6.dp))
                }
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                        .background(if (canAdd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, "Agregar", tint = if (canAdd) Color.White else AppTheme.extra.muted, modifier = Modifier.size(20.dp)) }
            }
            if (inCart > 0 && !canAdd && limit != null) Text("Máximo: ${limit.fmtQty()} en stock", fontSize = 11.sp, color = AppTheme.extra.warn)
        }
    }
}

@Composable
private fun CartBar(app: AppState, modifier: Modifier) {
    val totals = app.repo.totalsFor(app.cart, app.cartDiscount, app.settings)
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) com.vitrinapos.ui.components.ConfirmDialog(
        "Vaciar el carrito", "Se quitan los ${app.cartCount.fmtQty()} artículos de esta venta.", "Vaciar",
        onConfirm = { app.clearCart() }, onDismiss = { confirmClear = false }, destructive = true,
    )
    Surface(
        onClick = { app.open(Screen.Checkout) },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 10.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { confirmClear = true }) {
                Icon(Icons.Rounded.DeleteOutline, "Vaciar carrito", tint = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f))
            }
            Column(Modifier.weight(1f)) {
                Text("${app.cartCount.fmtQty()} artículos", color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f), fontSize = 12.sp)
                Text(Money.format(totals.total, app.settings.currencySymbol), color = MaterialTheme.colorScheme.inverseOnSurface, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            }
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(AppTheme.extra.cta).padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Cobrar", color = AppTheme.extra.onCta, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = AppTheme.extra.onCta, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Carrito lateral en pantallas grandes (Windows y tabletas). */
@Composable
private fun CartPanel(app: AppState) {
    val s = app.settings
    var pickClient by remember { mutableStateOf(false) }
    val clients by app.repo.clients.collectAsState()
    val totals = app.repo.totalsFor(app.cart, app.cartDiscount, s)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Venta actual", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { pickClient = true }) {
                Icon(Icons.Rounded.Person, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                Text(app.cartClient?.name ?: "Consumidor final", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
            }
        }
        if (app.cart.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Toca un producto para agregarlo", color = AppTheme.extra.muted)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(app.cart) { i, line -> CartLineRow(app, i, line, compact = true) }
            }
        }
        Divider()
        TotalsBlock(totals, s.currencySymbol, s.taxName)
        Button(
            onClick = { app.open(Screen.Checkout) },
            enabled = app.cart.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta),
        ) { Text("Cobrar ${Money.format(totals.total, s.currencySymbol)}", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        if (app.cart.isNotEmpty()) TextButton(onClick = { app.clearCart() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Vaciar carrito") }
    }
    if (pickClient) {
        ClientPickerDialog(clients, onPick = { app.cartClient = it; pickClient = false }, onNew = { pickClient = false; app.open(Screen.ClientForm()) }, onDismiss = { pickClient = false })
    }
}

/** Tarjeta de un producto con variantes (color, capacidad…): al tocarla se elige cuál. */
@Composable
fun VariantTile(p: Product, group: List<Product>, inCart: Double, onClick: () -> Unit) {
    val app = LocalAppState.current
    val sym = app.settings.currencySymbol
    Surface(
        onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(if (inCart > 0) 2.dp else 1.dp, if (inCart > 0) AppTheme.extra.cta else AppTheme.extra.line),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                ProductImage(p.imagePath, p.name, Modifier.fillMaxWidth().aspectRatio(1.15f), RoundedCornerShape(13.dp), textSize = 28)
                com.vitrinapos.ui.components.Pill("${group.size} opciones", Color.White, MaterialTheme.colorScheme.primary, Modifier.align(Alignment.TopStart).padding(6.dp))
                if (inCart > 0) CountBadge(inCart.fmtQty())
            }
            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp, modifier = Modifier.height(34.dp))
            val min = group.minOf { it.price }; val max = group.maxOf { it.price }
            Text(if (min == max) Money.format(min, sym) else "Desde " + Money.format(min, sym), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
            Text(group.joinToString(" · ") { it.variantName.ifBlank { "Base" } }, fontSize = 11.sp, color = AppTheme.extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun CartLineRow(app: AppState, index: Int, line: CartLine, compact: Boolean = false) {
    var edit by remember { mutableStateOf(false) }
    val s = app.settings
    Row(Modifier.fillMaxWidth().clickable { edit = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ProductImage(line.imagePath, line.name, Modifier.size(if (compact) 44.dp else 52.dp), textSize = 14)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(line.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Text(
                "${Money.format(line.price, s.currencySymbol)} · ${Money.format(line.amount, s.currencySymbol)}",
                fontSize = 12.sp, color = AppTheme.extra.muted,
            )
            if (line.serials.isNotEmpty()) Text("IMEI " + line.serials.joinToString(", "), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 3)
        }
        QtyStepper(line.qty, { app.setLineQty(index, it) })
    }
    var pendingPrice by remember { mutableStateOf<Pair<Double, Long>?>(null) }
    fun apply(qty: Double, price: Long) {
        if (qty <= 0) { app.setLineQty(index, 0.0); return }
        if (index in app.cart.indices) { app.cart[index] = line.copy(price = price); app.setLineQty(index, qty) }
    }
    if (edit) LineEditDialog(line, canChangePrice = app.can(com.vitrinapos.data.Permission.CHANGE_PRICES), onDismiss = { edit = false }, onSave = { qty, price ->
        edit = false
        // Por debajo del costo hace falta un administrador.
        if (price != line.price && price < line.cost && app.needsAdminAuth) pendingPrice = qty to price else apply(qty, price)
    })
    pendingPrice?.let { (q, p) ->
        com.vitrinapos.ui.components.AdminAuthDialog(
            "Precio por debajo del costo",
            "${line.name} cuesta ${Money.format(line.cost, s.currencySymbol)} y lo quieres vender a ${Money.format(p, s.currencySymbol)}.",
            onAuthorized = { pendingPrice = null; apply(q, p) }, onDismiss = { pendingPrice = null },
        )
    }
}

@Composable
fun LineEditDialog(line: CartLine, canChangePrice: Boolean = true, onDismiss: () -> Unit, onSave: (Double, Long) -> Unit) {
    var qty by remember { mutableStateOf<Double?>(line.qty) }
    var price by remember { mutableStateOf<Long?>(line.price) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(line.name, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QtyInput(qty, { qty = it }, "Cantidad")
                if (canChangePrice) MoneyInput(price, { price = it }, "Precio unitario", error = if ((price ?: 1) <= 0) "El precio debe ser mayor que cero" else null)
                else Text("Precio ${Money.format(line.price, LocalAppState.current.settings.currencySymbol)} · cambiarlo requiere el permiso \"Cambiar precios al vender\"", fontSize = 12.sp, color = AppTheme.extra.muted)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(qty ?: 0.0, price ?: line.price) }, enabled = qty != null && price != null && (price ?: 0) > 0) { Text("Guardar") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(0.0, line.price) }) {
                    Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Quitar", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

@Composable
fun TotalsBlock(t: Totals, symbol: String, taxName: String) {
    Column {
        KeyValue("Subtotal", Money.format(t.gross, symbol))
        if (t.discount > 0) KeyValue("Descuento", "-" + Money.format(t.discount, symbol), valueColor = AppTheme.extra.cta)
        if (t.tax > 0) KeyValue(if (t.taxIncluded) "$taxName incluido" else taxName, Money.format(t.tax, symbol))
        KeyValue("Total", Money.format(t.total, symbol), strong = true)
    }
}

@Composable
fun CheckoutScreen() {
    val app = LocalAppState.current
    val s = app.settings
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    val clients by app.repo.clients.collectAsState()
    var method by remember { mutableStateOf(PaymentMethods.CASH) }
    var received by remember { mutableStateOf<Long?>(null) }
    var pickClient by remember { mutableStateOf(false) }
    var discountDialog by remember { mutableStateOf(false) }
    var pendingDiscount by remember { mutableStateOf<Long?>(null) }
    var openAmount by remember { mutableStateOf<Long?>(null) }
    val openCash by app.repo.openCash.collectAsState()
    val cashBlocked = s.requireCash && openCash == null
    var busy by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    // Facturación electrónica: tipo de comprobante y datos del comprador.
    var ecfType by remember { mutableStateOf(com.vitrinapos.fiscal.EcfType.CONSUMO) }
    var buyerId by remember(app.cartClient) { mutableStateOf(app.cartClient?.docId ?: "") }
    var buyerName by remember(app.cartClient) { mutableStateOf(app.cartClient?.name ?: "") }
    val buyerCheck = com.vitrinapos.fiscal.TaxId.check(buyerId)
    val needsRnc = s.fiscalEnabled && ecfType != com.vitrinapos.fiscal.EcfType.CONSUMO
    val totals = app.repo.totalsFor(app.cart, app.cartDiscount, s)
    val change = (received ?: totals.total) - totals.total
    val bigConsumo = s.fiscalEnabled && ecfType == com.vitrinapos.fiscal.EcfType.CONSUMO && totals.total >= com.vitrinapos.fiscal.Ecf.RFCE_LIMIT
    val fiscalOk = !s.fiscalEnabled || ((!needsRnc && !bigConsumo) || (buyerCheck.valid && buyerName.isNotBlank()))
    val canPay = app.cart.isNotEmpty() && !busy && fiscalOk && !cashBlocked && (method != PaymentMethods.CASH || (received ?: totals.total) >= totals.total)

    fun pay(print: Boolean) {
        busy = true
        scope.launch {
            val saleId = runCatching {
                val fiscal = if (s.fiscalEnabled) com.vitrinapos.data.FiscalChoice(
                    ecfType.code, if (buyerCheck.valid) buyerCheck.digits else "", if (buyerCheck.valid) buyerName.trim() else "",
                ) else null
                app.repo.recordSale(
                    app.cart.toList(), app.cartClient, app.cartDiscount, method, received ?: totals.total, app.cartQuoteId, fiscal,
                    allowNoStock = app.canSellNoStock,
                )
            }.getOrElse { app.toast("No se pudo guardar la venta: ${it.message}"); busy = false; return@launch }
            app.clearCart()
            if (print) {
                val sale = app.repo.saleById(saleId)
                val ecf = app.repo.ecfForSale(saleId).firstOrNull { it.type != 34L }
                if (sale != null) app.print(Ticket.forSale(app.settingsNow, sale, app.repo.saleItems(saleId), ecf = ecf))
            }
            app.replace(Screen.SaleDetail(saleId))
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Cobrar venta", onBack = { app.back() }) {
            TextButton(onClick = { pickClient = true }) {
                Icon(Icons.Rounded.Person, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                Text(app.cartClient?.name ?: "Consumidor final", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
            }
        }
        if (app.cart.isEmpty()) {
            EmptyState(Icons.Rounded.ShoppingCart, "El carrito está vacío", "Agrega productos desde Vender.") {
                Button(onClick = { app.selectTab(Screen.Pos) }) { Text("Ir a Vender") }
            }
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (cashBlocked) AppCard(color = AppTheme.extra.warnSoft) {
                Text("La caja está cerrada", fontWeight = FontWeight.Bold)
                Text("Para cobrar hay que abrir la caja de este equipo y contar el efectivo inicial.", fontSize = 13.sp)
                if (app.can(com.vitrinapos.data.Permission.CASH)) {
                    Spacer(Modifier.height(8.dp))
                    MoneyInput(openAmount, { openAmount = it }, "Efectivo inicial")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        scope.launch { runCatching { app.repo.openCashSession(openAmount ?: 0) }.onSuccess { app.toast("Caja abierta") }.onFailure { app.toast(it.message ?: "No se pudo abrir") } }
                    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Abrir caja") }
                } else Text("Pídele a alguien con permiso de Caja que la abra.", fontSize = 13.sp, color = AppTheme.extra.muted)
            }
            AppCard(padding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                app.cart.forEachIndexed { i, line ->
                    if (i > 0) Divider()
                    CartLineRow(app, i, line)
                }
            }
            AppCard {
                TotalsBlock(totals, s.currencySymbol, s.taxName)
                TextButton(onClick = { discountDialog = true }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (app.cartDiscount > 0) "Cambiar descuento" else "+ Agregar descuento")
                }
            }
            if (s.fiscalEnabled) {
                Text("Comprobante", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.vitrinapos.fiscal.EcfType.forSale.forEach { t ->
                        val label = if (t == com.vitrinapos.fiscal.EcfType.GUBERNAMENTAL) "Gobierno" else t.short
                        FilterChip(selected = ecfType == t, onClick = { ecfType = t }, label = { Text("$label ${t.prefix}", fontSize = 12.sp, maxLines = 1) }, shape = RoundedCornerShape(10.dp))
                    }
                }
                if (needsRnc || bigConsumo || buyerId.isNotBlank()) {
                    com.vitrinapos.ui.components.TextInput(
                        buyerId, { buyerId = it }, if (needsRnc) "RNC o cédula del cliente *" else "RNC o cédula del cliente",
                        keyboard = androidx.compose.ui.text.input.KeyboardType.Number,
                        error = if (buyerId.isNotBlank() && !buyerCheck.valid) buyerCheck.message else null,
                    )
                    com.vitrinapos.ui.components.TextInput(buyerName, { buyerName = it }, if (needsRnc) "Razón social o nombre *" else "Nombre del cliente")
                }
                if (bigConsumo) Text("Una factura de consumo de RD$250,000 o más necesita la identificación del cliente.", fontSize = 12.sp, color = AppTheme.extra.warn)
                else if (!needsRnc && buyerId.isBlank()) Text("Factura de consumo electrónica a consumidor final.", fontSize = 12.sp, color = AppTheme.extra.muted)
            }
            Text("Forma de pago", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PaymentMethods.all.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = method == m,
                        onClick = { method = m },
                        shape = SegmentedButtonDefaults.itemShape(i, PaymentMethods.all.size),
                    ) { Text(m, maxLines = 1, fontSize = 13.sp) }
                }
            }
            if (method == PaymentMethods.CASH) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    MoneyInput(received, { received = it }, "Recibido", Modifier.weight(1f))
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (change >= 0) AppTheme.extra.okSoft else AppTheme.extra.dangerSoft)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(if (change >= 0) "Cambio" else "Falta", fontSize = 12.sp, color = AppTheme.extra.muted)
                        Text(Money.format(kotlin.math.abs(change), s.currencySymbol), fontWeight = FontWeight.Bold, fontSize = 20.sp,
                            color = if (change >= 0) AppTheme.extra.ok else AppTheme.extra.danger)
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quickAmounts(totals.total)) { amount ->
                        FilterChip(
                            selected = received == amount,
                            onClick = { received = amount },
                            label = { Text(if (amount == totals.total) "Exacto" else Money.format(amount, s.currencySymbol)) },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 720.dp).align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val hasPrinter = s.printerId.isNotBlank()
                Button(
                    onClick = { pay(print = hasPrinter && s.autoPrint) },
                    enabled = canPay,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta),
                ) {
                    if (hasPrinter && s.autoPrint) { Icon(Icons.Rounded.Print, null); Spacer(Modifier.width(8.dp)) }
                    Text(
                        (if (hasPrinter && s.autoPrint) "Cobrar e imprimir · " else "Cobrar · ") + Money.format(totals.total, s.currencySymbol),
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                }
                // Debajo del cobro: vaciar el carrito y (si imprime solo) cobrar sin imprimir, lado a lado.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { confirmEmpty = true }, enabled = !busy && app.cart.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Vaciar carrito", color = MaterialTheme.colorScheme.error, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 16.sp)
                    }
                    if (hasPrinter && s.autoPrint) OutlinedButton(
                        onClick = { pay(print = false) }, enabled = canPay,
                        modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Cobrar sin imprimir", fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 16.sp) }
                }
            }
        }
    }
    if (confirmEmpty) {
        com.vitrinapos.ui.components.ConfirmDialog(
            "Vaciar el carrito", "Se quitan los ${app.cartCount.fmtQty()} artículos, el cliente y el descuento de esta venta.", "Vaciar",
            onConfirm = { app.clearCart(); app.back() }, onDismiss = { confirmEmpty = false }, destructive = true,
        )
    }

    if (pickClient) {
        ClientPickerDialog(clients, onPick = { app.cartClient = it; pickClient = false }, onNew = { pickClient = false; app.open(Screen.ClientForm()) }, onDismiss = { pickClient = false })
    }
    pendingDiscount?.let { amount ->
        com.vitrinapos.ui.components.AdminAuthDialog(
            "Autorizar descuento", "Descuento de ${Money.format(amount, s.currencySymbol)} sobre ${Money.format(totals.gross, s.currencySymbol)}.",
            onAuthorized = { app.cartDiscount = amount; pendingDiscount = null }, onDismiss = { pendingDiscount = null },
        )
    }
    if (discountDialog) {
        val maxFree = when {
            !app.needsAdminAuth -> totals.gross
            app.can(com.vitrinapos.data.Permission.DISCOUNTS) -> totals.gross * s.maxDiscountPct / 100
            else -> 0L
        }
        DiscountDialog(totals.gross, app.cartDiscount, s.currencySymbol, maxFree, onDismiss = { discountDialog = false }) { amount ->
            discountDialog = false
            if (amount > maxFree) pendingDiscount = amount else app.cartDiscount = amount
        }
    }
}

fun quickAmounts(total: Long): List<Long> {
    val out = linkedSetOf(total)
    listOf(10000L, 50000L, 100000L, 200000L).forEach { step ->
        val v = (ceil(total.toDouble() / step) * step).toLong()
        if (v > total) out += v
    }
    return out.take(5)
}

@Composable
fun DiscountDialog(gross: Long, current: Long, symbol: String, maxFree: Long = gross, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var amount by remember { mutableStateOf<Long?>(current.takeIf { it > 0 }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Descuento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MoneyInput(amount, { amount = it }, "Monto a descontar")
                Text("O un porcentaje:", style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 20).forEach { pct ->
                        FilterChip(selected = false, onClick = { amount = gross * pct / 100 }, label = { Text("$pct %") })
                    }
                }
                if ((amount ?: 0) > gross) Text("El descuento no puede ser mayor que ${Money.format(gross, symbol)}", color = MaterialTheme.colorScheme.error)
                else if ((amount ?: 0) > maxFree) Text(
                    if (maxFree == 0L) "Para dar descuentos hace falta la autorización de un administrador."
                    else "Tu límite es ${Money.format(maxFree, symbol)}. Más que eso pide el PIN de un administrador.",
                    color = AppTheme.extra.warn, fontSize = 13.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave((amount ?: 0).coerceIn(0, gross)) }) { Text("Aplicar") } },
        dismissButton = {
            Row {
                if (current > 0) TextButton(onClick = { onSave(0) }) { Text("Quitar") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}
