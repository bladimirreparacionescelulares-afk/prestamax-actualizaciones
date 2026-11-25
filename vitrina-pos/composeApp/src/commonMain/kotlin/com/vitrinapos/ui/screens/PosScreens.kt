package com.vitrinapos.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
    val p = products.firstOrNull { it.code.equals(c, ignoreCase = true) } ?: return false
    addToCart(p)
    toast("Agregado: ${p.name}")
    return true
}

@Composable
fun PosScreen(wide: Boolean) {
    val app = LocalAppState.current
    val products by app.repo.products.collectAsState()
    val categories by app.repo.categories.collectAsState()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<Long?>(null) }
    val scanner = rememberBarcodeScanner { code ->
        if (!app.addByCode(code, products)) {
            app.toast("No hay producto con el código $code")
            app.open(Screen.ProductForm(presetCode = code))
        }
    }
    val list = products.filter { (category == null || it.categoryId == category) && it.matches(query) }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchBox(
                    query, { query = it }, if (scanner != null) "Buscar por nombre o código" else "Buscar o escanear código (lector USB)",
                    Modifier.weight(1f),
                    onSubmit = { if (app.addByCode(query, products)) query = "" },
                )
                if (scanner != null) {
                    Spacer(Modifier.width(8.dp))
                    SquareIconButton(Icons.Rounded.QrCodeScanner, "Escanear código", primary = true) { scanner() }
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
                    ) { Button(onClick = { app.open(Screen.ProductForm()) }) { Text("Nuevo producto") } }
                    list.isEmpty() -> EmptyState(Icons.Rounded.SearchOff, "Sin resultados", "No hay productos que coincidan con \"$query\".") {
                        OutlinedButton(onClick = { app.open(Screen.ProductForm(presetCode = query.takeIf { q -> q.none { it == ' ' } })) }) { Text("Crear producto") }
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (wide) 170.dp else 150.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = if (!wide && app.cart.isNotEmpty()) 96.dp else 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(list, key = { it.id }) { p ->
                            ProductTile(p, app.qtyInCart(p.id), onAdd = { app.addToCart(p) }, onEdit = { app.open(Screen.ProductForm(p.id)) })
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
fun ProductTile(p: Product, inCart: Double, onAdd: () -> Unit, onEdit: () -> Unit) {
    val app = LocalAppState.current
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
            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp, modifier = Modifier.height(34.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Money.format(p.price, app.settings.currencySymbol), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                    if (p.code.isNotBlank()) Text(p.code, fontSize = 11.sp, color = AppTheme.extra.muted, maxLines = 1)
                }
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primary).clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, "Agregar", tint = Color.White, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable
private fun CartBar(app: AppState, modifier: Modifier) {
    val totals = app.repo.totalsFor(app.cart, app.cartDiscount, app.settings)
    Surface(
        onClick = { app.open(Screen.Checkout) },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 10.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
        }
        QtyStepper(line.qty, { app.setLineQty(index, it) })
    }
    if (edit) LineEditDialog(line, onDismiss = { edit = false }, onSave = { qty, price ->
        edit = false
        if (qty <= 0) app.setLineQty(index, 0.0) else if (index in app.cart.indices) app.cart[index] = line.copy(qty = qty, price = price)
    })
}

@Composable
fun LineEditDialog(line: CartLine, onDismiss: () -> Unit, onSave: (Double, Long) -> Unit) {
    var qty by remember { mutableStateOf<Double?>(line.qty) }
    var price by remember { mutableStateOf<Long?>(line.price) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(line.name, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QtyInput(qty, { qty = it }, "Cantidad")
                MoneyInput(price, { price = it }, "Precio unitario")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(qty ?: 0.0, price ?: line.price) }, enabled = qty != null && price != null) { Text("Guardar") } },
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
    var busy by remember { mutableStateOf(false) }
    val totals = app.repo.totalsFor(app.cart, app.cartDiscount, s)
    val change = (received ?: totals.total) - totals.total
    val canPay = app.cart.isNotEmpty() && !busy && (method != PaymentMethods.CASH || (received ?: totals.total) >= totals.total)

    fun pay(print: Boolean) {
        busy = true
        scope.launch {
            val saleId = runCatching {
                app.repo.recordSale(app.cart.toList(), app.cartClient, app.cartDiscount, method, received ?: totals.total, app.cartQuoteId)
            }.getOrElse { app.toast("No se pudo guardar la venta: ${it.message}"); busy = false; return@launch }
            app.clearCart()
            if (print) {
                val sale = app.repo.saleById(saleId)
                if (sale != null) app.print(Ticket.forSale(app.settingsNow, sale, app.repo.saleItems(saleId)))
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
                if (hasPrinter && s.autoPrint) {
                    OutlinedButton(onClick = { pay(print = false) }, enabled = canPay, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Text("Cobrar sin imprimir")
                    }
                }
            }
        }
    }

    if (pickClient) {
        ClientPickerDialog(clients, onPick = { app.cartClient = it; pickClient = false }, onNew = { pickClient = false; app.open(Screen.ClientForm()) }, onDismiss = { pickClient = false })
    }
    if (discountDialog) {
        DiscountDialog(totals.gross, app.cartDiscount, s.currencySymbol, onDismiss = { discountDialog = false }) { app.cartDiscount = it; discountDialog = false }
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
fun DiscountDialog(gross: Long, current: Long, symbol: String, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
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
