package com.vitrinapos.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.Screen
import com.vitrinapos.data.Money
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.SaleStatus
import com.vitrinapos.data.Time
import com.vitrinapos.data.fmtQty
import com.vitrinapos.db.CashSession
import com.vitrinapos.db.Sale
import com.vitrinapos.db.SaleItem
import com.vitrinapos.print.Ticket
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.ConfirmDialog
import com.vitrinapos.ui.components.Divider
import com.vitrinapos.ui.components.EmptyState
import com.vitrinapos.ui.components.KeyValue
import com.vitrinapos.ui.components.MoneyInput
import com.vitrinapos.ui.components.Pill
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

// ================= Historial =================

@Composable
fun SalesHistoryScreen() {
    val app = LocalAppState.current
    val recent by app.repo.recentSales.collectAsState()
    var range by remember { mutableIntStateOf(0) }
    val ranges = listOf("Hoy", "7 días", "30 días", "Todo")
    val today = Time.startOfDay()
    val from = when (range) { 0 -> today; 1 -> Time.addDays(today, -6); 2 -> Time.addDays(today, -29); else -> 0L }
    val sales by produceState(emptyList<Sale>(), range, recent) { value = app.repo.salesBetween(from, Long.MAX_VALUE) }
    val ok = sales.filter { it.status == SaleStatus.OK }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Ventas", subtitle = "${ok.size} ventas · ${Money.format(ok.sumOf { it.total }, app.settings.currencySymbol)}",
            onBack = if (app.stack.isNotEmpty()) ({ app.back() }) else null)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ranges.size) { i -> CategoryChip(ranges[i], range == i) { range = i } }
        }
        if (sales.isEmpty()) EmptyState(Icons.AutoMirrored.Rounded.ReceiptLong, "Sin ventas en este periodo", "Las ventas que cobres aparecerán aquí.")
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            var lastDay = ""
            sales.forEach { sale ->
                val day = Time.relativeDay(sale.createdAt)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "d$day${sale.id}") { Text(day, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                }
                item(key = sale.id) {
                    SaleRow(sale) { app.open(Screen.SaleDetail(sale.id)) }
                    Divider()
                }
            }
        }
    }
}

// ================= Detalle de venta =================

@Composable
fun SaleDetailScreen(id: Long) {
    val app = LocalAppState.current
    val s = app.settings
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val data by produceState<Pair<Sale, List<SaleItem>>?>(null, id, refresh) {
        val sale = app.repo.saleById(id)
        value = sale?.let { it to app.repo.saleItems(id) }
    }
    var confirmVoid by remember { mutableStateOf(false) }
    val (sale, items) = data ?: run {
        Column { ScreenHeader("Venta", onBack = { app.back() }) }
        return
    }
    val ticket = Ticket.forSale(s, sale, items)
    val fresh = Time.now() - sale.createdAt < 120_000
    val voided = sale.status == SaleStatus.VOID

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Venta #${sale.number}", subtitle = Time.dateTime(sale.createdAt), onBack = { app.back() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (fresh && !voided) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(AppTheme.extra.okSoft).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = AppTheme.extra.ok, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Venta registrada", fontWeight = FontWeight.Bold, color = AppTheme.extra.ok, fontSize = 17.sp)
                        Text(Money.format(sale.total, s.currencySymbol) + " · " + sale.paymentMethod, color = AppTheme.extra.ok)
                    }
                    if (sale.paymentMethod == PaymentMethods.CASH && sale.changeDue > 0) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Cambio", fontSize = 12.sp, color = AppTheme.extra.ok)
                            Text(Money.format(sale.changeDue, s.currencySymbol), fontWeight = FontWeight.Bold, fontSize = 22.sp, color = AppTheme.extra.ok)
                        }
                    }
                }
            }
            if (voided) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AppTheme.extra.dangerSoft).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Block, null, tint = AppTheme.extra.danger); Spacer(Modifier.width(10.dp))
                    Text("Venta anulada. El inventario se devolvió.", color = AppTheme.extra.danger, fontWeight = FontWeight.SemiBold)
                }
            }
            // Vista previa del ticket
            Text("Ticket de ${s.paperWidthMm} mm", style = MaterialTheme.typography.titleSmall)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(6.dp), color = androidx.compose.ui.graphics.Color.White, shadowElevation = 3.dp) {
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        TicketPreview(ticket, Modifier.padding(horizontal = 14.dp, vertical = 16.dp))
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 720.dp).align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { app.print(Ticket.forSale(s, sale, items, reprint = !fresh)) { app.open(Screen.Settings) } },
                        modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Rounded.Print, null); Spacer(Modifier.width(6.dp)); Text("Imprimir")
                    }
                    OutlinedButton(onClick = { app.platform.shareText(ticket.preview(), "Venta #${sale.number}") },
                        modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Compartir")
                    }
                    if (!voided) OutlinedButton(onClick = { confirmVoid = true }, modifier = Modifier.height(50.dp), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text("Anular", color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(
                    onClick = { app.selectTab(Screen.Pos) },
                    modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta),
                ) { Text("Nueva venta", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }
    }
    if (confirmVoid) {
        ConfirmDialog("Anular venta #${sale.number}", "La venta quedará anulada y los productos vuelven al inventario.", "Anular venta",
            onConfirm = { scope.launch { app.repo.voidSale(id); refresh++; app.toast("Venta anulada") } },
            onDismiss = { confirmVoid = false }, destructive = true)
    }
}

// ================= Reportes =================

@Composable
fun ReportsScreen() {
    val app = LocalAppState.current
    val s = app.settings
    val products by app.repo.products.collectAsState()
    val recent by app.repo.recentSales.collectAsState()
    var range by remember { mutableIntStateOf(1) }
    val ranges = listOf("Hoy", "7 días", "30 días", "Este mes")
    val today = Time.startOfDay()
    val from = when (range) { 0 -> today; 1 -> Time.addDays(today, -6); 2 -> Time.addDays(today, -29); else -> Time.startOfMonth() }
    val to = Time.addDays(today, 1)
    val data by produceState<Pair<List<Sale>, List<SaleItem>>?>(null, range, recent) {
        value = app.repo.salesBetween(from, to) to app.repo.itemsBetween(from, to)
    }
    val sales = data?.first.orEmpty().filter { it.status == SaleStatus.OK }
    val items = data?.second.orEmpty()
    val total = sales.sumOf { it.total }
    val profit = sales.sumOf { it.total - it.tax - it.costTotal }
    val sym = s.currencySymbol

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Reportes", subtitle = "Ventas, ganancias e inventario", onBack = if (app.stack.isNotEmpty()) ({ app.back() }) else null)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ranges.size) { i -> CategoryChip(ranges[i], range == i) { range = i } }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 900.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Vendido", Money.format(total, sym), Modifier.weight(1f), accent = true)
                StatCard("Ganancia", Money.format(profit, sym), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Ventas", sales.size.toString(), Modifier.weight(1f))
                StatCard("Ticket promedio", Money.format(if (sales.isEmpty()) 0 else total / sales.size, sym), Modifier.weight(1f))
            }

            if (range != 0) {
                SectionTitle("Ventas por día")
                AppCard {
                    val days = generateSequence(from) { Time.addDays(it, 1) }.takeWhile { it < to }.toList()
                    val perDay = days.map { d -> d to sales.filter { it.createdAt >= d && it.createdAt < Time.addDays(d, 1) }.sumOf { it.total } }
                    DayBars(perDay)
                }
            }

            SectionTitle("Por forma de pago")
            AppCard {
                PaymentMethods.all.forEach { m ->
                    val list = sales.filter { it.paymentMethod == m }
                    KeyValue("$m · ${list.size}", Money.format(list.sumOf { it.total }, sym))
                }
                Divider()
                KeyValue("${s.taxName} cobrado", Money.format(sales.sumOf { it.tax }, sym))
                KeyValue("Descuentos", Money.format(sales.sumOf { it.discount }, sym))
            }

            SectionTitle("Más vendidos")
            val top = items.groupBy { it.productId ?: -it.name.hashCode().toLong() }.values
                .map { list -> Triple(list.first().name, list.sumOf { it.qty }, list.sumOf { (it.qty * it.price).roundToLong() }) }
                .sortedByDescending { it.third }.take(10)
            if (top.isEmpty()) Text("Sin ventas en este periodo.", color = AppTheme.extra.muted)
            else AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                val max = top.maxOf { it.third }.coerceAtLeast(1)
                top.forEachIndexed { i, (name, qty, amount) ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}.", color = AppTheme.extra.muted, modifier = Modifier.width(26.dp))
                            Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(Money.format(amount, sym), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Text(if (qty == 1.0) "1 vendido" else "${qty.fmtQty()} vendidos", fontSize = 12.sp, color = AppTheme.extra.muted, modifier = Modifier.padding(start = 26.dp))
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.padding(start = 26.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
                            Box(Modifier.fillMaxWidth(amount.toFloat() / max).height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }

            SectionTitle("Inventario")
            AppCard {
                val tracked = products.filter { it.trackStock == 1L && it.stock > 0 }
                KeyValue("Productos activos", products.size.toString())
                KeyValue("Unidades en existencia", tracked.sumOf { it.stock }.fmtQty())
                KeyValue("Valor a costo", Money.format(tracked.sumOf { (it.stock * it.cost).roundToLong() }, sym))
                KeyValue("Valor a precio de venta", Money.format(tracked.sumOf { (it.stock * it.price).roundToLong() }, sym))
                KeyValue("Con stock bajo", products.count { it.trackStock == 1L && it.stock <= it.minStock }.toString())
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { app.open(Screen.StockMoves) }, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Rounded.History, null); Spacer(Modifier.width(6.dp)); Text("Ver movimientos de inventario")
                }
            }
        }
    }
}

@Composable
private fun DayBars(data: List<Pair<Long, Long>>) {
    val app = LocalAppState.current
    val max = (data.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainer
    val best = data.maxByOrNull { it.second }
    if (best != null && best.second > 0) {
        Text("Mejor día: ${Time.weekday(best.first)} ${Time.shortDate(best.first)} · ${Money.format(best.second, app.settings.currencySymbol)}",
            fontSize = 12.sp, color = AppTheme.extra.muted)
        Spacer(Modifier.height(8.dp))
    }
    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        val n = data.size.coerceAtLeast(1)
        val slot = size.width / n
        val w = (slot * 0.62f).coerceAtMost(28.dp.toPx())
        data.forEachIndexed { i, (_, v) ->
            val x = i * slot + (slot - w) / 2
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(6.dp.toPx()))
            val h = size.height * v / max.toFloat()
            if (h > 0) drawRoundRect(bar, Offset(x, size.height - h), Size(w, h), CornerRadius(6.dp.toPx()))
        }
    }
    if (data.size <= 10) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            data.forEach { (d, _) -> Text(Time.weekday(d), Modifier.weight(1f), fontSize = 11.sp, color = AppTheme.extra.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }
}

// ================= Caja =================

@Composable
fun CashScreen() {
    val app = LocalAppState.current
    val s = app.settings
    val scope = rememberCoroutineScope()
    val open by app.repo.openCash.collectAsState()
    val recent by app.repo.recentSales.collectAsState()
    var amount by remember { mutableStateOf<Long?>(null) }
    var notes by remember { mutableStateOf("") }
    var confirmClose by remember { mutableStateOf(false) }
    val expected by produceState<Long?>(null, open, recent) { value = open?.let { app.repo.expectedCash(it) } }
    val history by produceState(emptyList<CashSession>(), open) { value = app.repo.cashHistory() }
    val sym = s.currencySymbol

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Caja", subtitle = if (open != null) "Abierta" else "Cerrada", onBack = if (app.stack.isNotEmpty()) ({ app.back() }) else null)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val session = open
            if (session == null) {
                AppCard {
                    Icon(Icons.Rounded.PointOfSale, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("Abrir caja", style = MaterialTheme.typography.titleMedium)
                    Text("Cuenta el efectivo con el que empiezas el día.", color = AppTheme.extra.muted)
                    Spacer(Modifier.height(10.dp))
                    MoneyInput(amount, { amount = it }, "Efectivo inicial")
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { scope.launch { app.repo.openCashSession(amount ?: 0); amount = null; app.toast("Caja abierta") } },
                        modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Abrir caja", fontWeight = FontWeight.Bold) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("Efectivo inicial", Money.format(session.openingAmount, sym), Modifier.weight(1f))
                    StatCard("Debe haber", expected?.let { Money.format(it, sym) } ?: "…", Modifier.weight(1f), accent = true)
                }
                Text("Abierta ${Time.relativeDay(session.openedAt).lowercase()} a las ${Time.time(session.openedAt)}. \"Debe haber\" suma el efectivo inicial y las ventas en efectivo.",
                    color = AppTheme.extra.muted, fontSize = 13.sp)
                AppCard {
                    Text("Cerrar caja", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    MoneyInput(amount, { amount = it }, "Efectivo contado")
                    val diff = (amount ?: 0) - (expected ?: 0)
                    if (amount != null && expected != null) {
                        Spacer(Modifier.height(8.dp))
                        Pill(
                            when { diff == 0L -> "Cuadra exacto"; diff > 0 -> "Sobran ${Money.format(diff, sym)}"; else -> "Faltan ${Money.format(-diff, sym)}" },
                            if (diff == 0L) AppTheme.extra.ok else if (diff > 0) AppTheme.extra.warn else AppTheme.extra.danger,
                            if (diff == 0L) AppTheme.extra.okSoft else if (diff > 0) AppTheme.extra.warnSoft else AppTheme.extra.dangerSoft,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextInput(notes, { notes = it }, "Nota (opcional)")
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { confirmClose = true }, enabled = amount != null, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                        Text("Cerrar caja", fontWeight = FontWeight.Bold)
                    }
                }
            }
            val closed = history.filter { it.closedAt != null }
            if (closed.isNotEmpty()) {
                SectionTitle("Cierres anteriores")
                AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    closed.forEachIndexed { i, c ->
                        if (i > 0) Divider()
                        val d = (c.countedAmount ?: 0) - (c.expectedAmount ?: 0)
                        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${Time.shortDate(c.openedAt)} · ${Time.time(c.openedAt)} – ${Time.time(c.closedAt ?: 0)}", fontWeight = FontWeight.SemiBold)
                                Text("Contado ${Money.format(c.countedAmount ?: 0, sym)} de ${Money.format(c.expectedAmount ?: 0, sym)}", fontSize = 12.sp, color = AppTheme.extra.muted)
                            }
                            Text(if (d == 0L) "OK" else (if (d > 0) "+" else "") + Money.format(d, sym), fontWeight = FontWeight.Bold,
                                color = if (d == 0L) AppTheme.extra.ok else if (d > 0) AppTheme.extra.warn else AppTheme.extra.danger)
                        }
                    }
                }
            }
        }
    }
    if (confirmClose) {
        val session = open
        ConfirmDialog("Cerrar caja", "Se guarda el cierre con ${Money.format(amount ?: 0, sym)} contados.", "Cerrar caja",
            onConfirm = { if (session != null) scope.launch { app.repo.closeCashSession(session, amount ?: 0, notes); amount = null; notes = ""; app.toast("Caja cerrada") } },
            onDismiss = { confirmClose = false })
    }
}

/** Vista previa del ticket: la letra grande se muestra al doble, como sale impresa. */
@Composable
fun TicketPreview(ticket: Ticket, modifier: Modifier = Modifier) {
    val base = if (ticket.width > 32) 10f else 12f
    Column(modifier) {
        ticket.lines.forEach { line ->
            val big = line.style == Ticket.Style.Big
            Text(
                ticket.aligned(line),
                fontFamily = FontFamily.Monospace,
                fontSize = (if (big) base * 2 else base).sp,
                lineHeight = (if (big) base * 2.4f else base * 1.4f).sp,
                fontWeight = if (line.style == Ticket.Style.Normal) FontWeight.Normal else FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color(0xFF1B1B1B),
                softWrap = false,
                maxLines = 1,
            )
        }
    }
}
