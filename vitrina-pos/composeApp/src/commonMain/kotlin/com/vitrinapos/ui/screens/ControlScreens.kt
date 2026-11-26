package com.vitrinapos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.data.Money
import com.vitrinapos.data.Time
import com.vitrinapos.data.fmtQty
import com.vitrinapos.data.fullName
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.ConfirmDialog
import com.vitrinapos.ui.components.Divider
import com.vitrinapos.ui.components.EmptyState
import com.vitrinapos.ui.components.KeyValue
import com.vitrinapos.ui.components.ProductImage
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SearchBox
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.components.matches
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

// ================= Actividad =================

@Composable
fun ActivityScreen() {
    val app = LocalAppState.current
    var range by remember { mutableIntStateOf(0) }
    var who by remember { mutableStateOf<String?>(null) }
    val today = Time.startOfDay()
    val from = when (range) { 0 -> today; 1 -> Time.addDays(today, -6); else -> Time.addDays(today, -29) }
    val list by remember(from) { app.repo.activityFlow(from) }.collectAsState(emptyList())
    val people = list.map { it.userName.ifBlank { "Sin usuario" } }.distinct()
    val shown = list.filter { who == null || it.userName.ifBlank { "Sin usuario" } == who }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Actividad", subtitle = "Cambios de precio, permisos, caja, anulaciones y borrados", onBack = { app.back() })
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val names = listOf("Hoy", "7 días", "30 días")
            items(names.size) { i -> CategoryChip(names[i], range == i) { range = i } }
        }
        if (people.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { CategoryChip("Todos", who == null) { who = null } }
            items(people.size) { i -> CategoryChip(people[i], who == people[i]) { who = if (who == people[i]) null else people[i] } }
        }
        if (shown.isEmpty()) EmptyState(Icons.Rounded.History, "Sin actividad", "Aquí queda anotado quién cambia precios, permisos, la caja, anula ventas o borra registros.")
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var lastDay = -1L
            shown.forEach { a ->
                val day = Time.startOfDay(a.createdAt)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "d$day") { Text(Time.relativeDay(a.createdAt), fontWeight = FontWeight.SemiBold, color = AppTheme.extra.muted, modifier = Modifier.padding(top = 6.dp)) }
                }
                item(key = a.id) {
                    AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(a.action, fontWeight = FontWeight.SemiBold)
                                if (a.detail.isNotBlank()) Text(a.detail, fontSize = 13.sp)
                                Text(listOf(a.userName.ifBlank { "Sin usuario" }, a.device, Time.time(a.createdAt)).filter { it.isNotBlank() }.joinToString(" · "),
                                    fontSize = 12.sp, color = AppTheme.extra.muted)
                            }
                        }
                    }
                }
            }
            item { Text("La actividad no se puede editar ni borrar.", fontSize = 12.sp, color = AppTheme.extra.muted) }
        }
    }
}

// ================= Unir productos =================

@Composable
fun MergeProductsScreen(productId: Long) {
    val app = LocalAppState.current
    val sym = app.settings.currencySymbol
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    val products by app.repo.products.collectAsState()
    val keep = products.firstOrNull { it.id == productId } ?: run { Column { ScreenHeader("Unir productos", onBack = { app.back() }) }; return }
    val chosen = remember { mutableStateListOf<Long>() }
    var q by remember { mutableStateOf(keep.name.split(' ').take(2).joinToString(" ")) }
    var name by remember { mutableStateOf(keep.name) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val candidates = products.filter { it.id != keep.id && it.serialized == keep.serialized && it.isService == keep.isService && it.matches(q) }
    val picked = products.filter { it.id in chosen }
    val all = listOf(keep) + picked
    val units = all.sumOf { it.stock.coerceAtLeast(0.0) }
    val avg = if (units > 0) (all.sumOf { it.stock.coerceAtLeast(0.0) * it.cost } / units).roundToLong() else keep.cost
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Unir productos", subtitle = "Quedan como uno solo; el historial se conserva", onBack = { app.back() })
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().widthIn(max = 760.dp).align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text("Se queda", fontSize = 12.sp, color = AppTheme.extra.muted)
                    Text(keep.fullName, fontWeight = FontWeight.Bold)
                    Text("${keep.stock.fmtQty()} ${keep.unit} · costo ${Money.format(keep.cost, sym)} · ${keep.code}", fontSize = 12.sp, color = AppTheme.extra.muted)
                }
            }
            item { SectionTitle("¿Con cuáles se une?") }
            item { SearchBox(q, { q = it }, "Buscar productos parecidos", Modifier.fillMaxWidth()) }
            if (candidates.isEmpty()) item { Text("No hay productos que coincidan.", color = AppTheme.extra.muted) }
            items(candidates, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { if (p.id in chosen) chosen.remove(p.id) else chosen.add(p.id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(p.id in chosen, null)
                    ProductImage(p.imagePath, p.name, Modifier.size(40.dp), textSize = 12)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.fullName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${p.stock.fmtQty()} ${p.unit} · costo ${Money.format(p.cost, sym)} · ${p.code}", fontSize = 12.sp, color = AppTheme.extra.muted)
                    }
                }
            }
            if (picked.isNotEmpty()) item {
                AppCard(Modifier.fillMaxWidth()) {
                    TextInput(name, { name = it.take(80) }, "Nombre del producto unido")
                    Spacer(Modifier.height(6.dp))
                    KeyValue("Existencia", "${all.sumOf { it.stock }.fmtQty()} ${keep.unit}")
                    KeyValue("Costo promedio", Money.format(avg, sym))
                    KeyValue("Código", keep.code)
                    Text("Los códigos de los otros también lo encuentran al escanear. Ventas, compras, IMEI y kardex pasan a este producto.", fontSize = 12.sp, color = AppTheme.extra.muted)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Button(onClick = { confirm = true }, enabled = picked.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp), shape = RoundedCornerShape(16.dp)) {
                Text(if (picked.isEmpty()) "Elige qué se une" else "Unir ${picked.size + 1} productos", fontWeight = FontWeight.Bold)
            }
        }
    }
    if (confirm) ConfirmDialog(
        "Unir productos", "${picked.joinToString(", ") { it.fullName }} se unen a \"${name.trim()}\". Esto no se puede deshacer.", "Unir",
        onConfirm = {
            busy = true
            scope.launch {
                runCatching { app.repo.mergeProducts(keep.id, picked.map { it.id }, name) }
                    .onSuccess { app.toast("Productos unidos"); app.back() }
                    .onFailure { busy = false; app.toast(it.message ?: "No se pudieron unir") }
            }
        },
        onDismiss = { confirm = false },
    )
}
