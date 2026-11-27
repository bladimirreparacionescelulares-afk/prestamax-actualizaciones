package com.vitrinapos.ui.screens

import com.vitrinapos.data.daysLate
import androidx.compose.material.icons.rounded.Build
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.Screen
import com.vitrinapos.data.Money
import com.vitrinapos.data.SaleStatus
import com.vitrinapos.data.Time
import com.vitrinapos.data.label
import com.vitrinapos.db.Sale
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.Divider
import com.vitrinapos.ui.components.Pill
import com.vitrinapos.ui.components.ProductImage
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.SquareIconButton
import com.vitrinapos.ui.components.StockPill
import com.vitrinapos.ui.theme.AppTheme
import kotlin.math.roundToInt

data class DayStats(val total: Long, val count: Int, val profit: Long)

fun statsOf(sales: List<Sale>): DayStats {
    val ok = sales.filter { it.status == SaleStatus.OK }
    return DayStats(ok.sumOf { it.total }, ok.size, ok.sumOf { it.total - it.tax - it.costTotal })
}

@Composable
fun HomeScreen() {
    val app = LocalAppState.current
    val s = app.settings
    val products by app.repo.products.collectAsState()
    val recent by app.repo.recentSales.collectAsState()
    val cash by app.repo.openCash.collectAsState()
    val today = Time.startOfDay()
    val yesterday = Time.addDays(today, -1)
    val both by produceState(emptyList<Sale>() to emptyList<Sale>(), recent) {
        val list = app.repo.salesBetween(yesterday, Long.MAX_VALUE)
        value = list.filter { it.createdAt >= today } to list.filter { it.createdAt < today }
    }
    val todayStats = statsOf(both.first)
    val yStats = statsOf(both.second)
    val low = products.filter { it.trackStock == 1L && it.stock <= it.minStock }.sortedBy { it.stock }

    Column(Modifier.fillMaxSize()) {
        val me = app.user
        ScreenHeader(s.businessName, subtitle = Time.greeting() + (me?.let { ", ${it.name.substringBefore(' ')}" } ?: "")) {
            NetworkPill { app.open(if (app.can(com.vitrinapos.data.Permission.NETWORK)) Screen.Network else Screen.More) }
            SquareIconButton(Icons.Rounded.Settings, "Configuración") { app.open(Screen.Settings) }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OfflineBanner()
            PlanBanner()
            // Resumen del día (solo para quien puede ver ventas o reportes)
            if (app.can(com.vitrinapos.data.Permission.SALES_HISTORY) || app.can(com.vitrinapos.data.Permission.REPORTS)) Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF0A5E4E))))
                    .clickable(enabled = app.can(com.vitrinapos.data.Permission.REPORTS)) { app.open(Screen.Reports) }
                    .padding(18.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ventas de hoy", color = Color.White.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
                        if (yStats.total > 0) {
                            val pct = ((todayStats.total - yStats.total) * 100.0 / yStats.total).roundToInt()
                            Text(
                                (if (pct >= 0) "+" else "") + "$pct % vs ayer",
                                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Text(Money.format(todayStats.total, s.currencySymbol), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HeroStat("Ventas", todayStats.count.toString(), Modifier.weight(1f))
                        if (app.can(com.vitrinapos.data.Permission.REPORTS)) HeroStat("Ganancia", Money.format(todayStats.profit, s.currencySymbol), Modifier.weight(1f))
                        else HeroStat("Ticket promedio", Money.format(if (todayStats.count == 0) 0 else todayStats.total / todayStats.count, s.currencySymbol), Modifier.weight(1f))
                    }
                }
            }

            // Sin usuarios, cualquiera que tome el equipo lo puede todo.
            val users by app.repo.users.collectAsState()
            if (users.none { it.active == 1L }) {
                AppCard(onClick = { app.open(Screen.Users) }, color = AppTheme.extra.warnSoft) {
                    Text("Protege tu negocio", fontWeight = FontWeight.Bold, color = AppTheme.extra.warn)
                    Text("Sin usuarios, cualquiera que tome este equipo puede anular ventas o cambiar precios. Crea el administrador con su PIN.", fontSize = 13.sp)
                }
            }

            // Accesos rápidos
            // Accesos según los permisos del usuario.
            val quick = listOf(
                Triple(Icons.Rounded.AddBox, "Nuevo producto", Screen.ProductForm()),
                Triple(Icons.Rounded.Description, "Cotización", Screen.QuoteEditor()),
                Triple(Icons.Rounded.Build, "Recibir equipo", Screen.RepairReceive()),
                Triple(Icons.Rounded.BarChart, "Reportes", Screen.Reports),
                Triple(Icons.Rounded.PointOfSale, "Caja", Screen.Cash),
            ).filter { app.allows(it.third) }.take(4)
            if (quick.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.forEach { (icon, label, screen) -> QuickAction(icon, label, Modifier.weight(1f)) { app.open(screen) } }
                repeat(4 - quick.size) { Spacer(Modifier.weight(1f)) }
            }

            // Caja
            if (app.can(com.vitrinapos.data.Permission.CASH)) AppCard(onClick = { app.open(Screen.Cash) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PointOfSale, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (cash != null) "Caja abierta" else "Caja cerrada", fontWeight = FontWeight.SemiBold)
                        Text(
                            cash?.let { "Desde ${Time.relativeDay(it.openedAt).lowercase()} a las ${Time.time(it.openedAt)} · inicial ${Money.format(it.openingAmount, s.currencySymbol)}" }
                                ?: "Ábrela al empezar el día para cuadrar el efectivo",
                            style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted,
                        )
                    }
                    Pill(if (cash != null) "Abierta" else "Abrir", if (cash != null) AppTheme.extra.ok else AppTheme.extra.cta,
                        if (cash != null) AppTheme.extra.okSoft else AppTheme.extra.cta.copy(alpha = 0.12f))
                }
            }

            // Taller
            val repairs by app.repo.repairs.collectAsState()
            val pendingRepairs = repairs.filter { com.vitrinapos.data.RepairStatus.isPending(it.status) }
            val readyRepairs = repairs.filter { com.vitrinapos.data.RepairStatus.isReady(it.status) }
            if ((pendingRepairs.isNotEmpty() || readyRepairs.isNotEmpty()) && app.allows(Screen.Taller)) {
                val lateRepairs = pendingRepairs.count { it.daysLate() > 0 }
                AppCard(onClick = { app.selectTab(Screen.Taller) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Build, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Taller", fontWeight = FontWeight.SemiBold)
                            Text(
                                "${pendingRepairs.size} en proceso" + (if (lateRepairs > 0) " · $lateRepairs ${if (lateRepairs == 1) "atrasado" else "atrasados"}" else ""),
                                style = MaterialTheme.typography.bodySmall, color = if (lateRepairs > 0) AppTheme.extra.danger else AppTheme.extra.muted,
                            )
                        }
                        if (readyRepairs.isNotEmpty()) Pill(if (readyRepairs.size == 1) "1 listo" else "${readyRepairs.size} listos", AppTheme.extra.ok, AppTheme.extra.okSoft)
                    }
                }
            }

            if (products.isEmpty()) {
                AppCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Inventory2, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                        Text("Agrega tu primer producto", style = MaterialTheme.typography.titleMedium)
                        Text("Tómale una foto, ponle nombre y precio. En segundos ya lo puedes vender.", color = AppTheme.extra.muted, textAlign = TextAlign.Center)
                        Button(onClick = { app.open(Screen.ProductForm()) }, shape = RoundedCornerShape(14.dp)) { Text("Nuevo producto") }
                    }
                }
            }

            if (low.isNotEmpty()) {
                SectionTitle("Stock bajo · ${low.size}", action = "Ver todo") { app.catalogPreset = "low"; app.selectTab(Screen.Catalog) }
                AppCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    low.take(4).forEachIndexed { i, p ->
                        if (i > 0) Divider()
                        Row(
                            Modifier.fillMaxWidth().clickable { app.open(Screen.ProductForm(p.id)) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ProductImage(p.imagePath, p.name, Modifier.size(44.dp), textSize = 14)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(p.code.ifBlank { "Sin código" }, style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted)
                            }
                            StockPill(p)
                        }
                    }
                }
            }

            val seeSales = app.can(com.vitrinapos.data.Permission.SALES_HISTORY)
            if (seeSales) SectionTitle("Últimas ventas", action = "Historial") { app.open(Screen.SalesHistory) }
            if (!seeSales) {
            } else if (recent.isEmpty()) {
                Text("Todavía no hay ventas. Toca Vender para hacer la primera.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                AppCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    recent.take(5).forEachIndexed { i, sale ->
                        if (i > 0) Divider()
                        SaleRow(sale) { app.open(Screen.SaleDetail(sale.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.extra.line),
            modifier = Modifier.size(54.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp)) }
        }
        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, lineHeight = 14.sp, modifier = Modifier.widthIn(max = 90.dp))
    }
}

@Composable
fun SaleRow(sale: Sale, onClick: () -> Unit) {
    val app = LocalAppState.current
    val voided = sale.status == SaleStatus.VOID
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${sale.label} · ${sale.paymentMethod}", fontWeight = FontWeight.SemiBold)
            Text("${Time.relativeDay(sale.createdAt)} ${Time.time(sale.createdAt)} · ${sale.clientName}" + (if (sale.userName.isNotBlank()) " · ${sale.userName}" else ""), style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted, maxLines = 1)
        }
        if (voided) {
            Pill("Anulada", AppTheme.extra.danger, AppTheme.extra.dangerSoft)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            Money.format(sale.total, app.settings.currencySymbol),
            fontWeight = FontWeight.Bold,
            color = if (voided) AppTheme.extra.muted else MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(0.dp))
}
