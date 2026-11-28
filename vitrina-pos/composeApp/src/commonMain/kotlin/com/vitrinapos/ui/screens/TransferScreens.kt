package com.vitrinapos.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.Screen
import com.vitrinapos.data.Time
import com.vitrinapos.data.TransferLine
import com.vitrinapos.data.TransferStatus
import com.vitrinapos.data.fmtQty
import com.vitrinapos.data.fullName
import com.vitrinapos.db.Product
import com.vitrinapos.db.Transfer
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.Pill
import com.vitrinapos.ui.components.ProductPickerDialog
import com.vitrinapos.ui.components.QtyStepper
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.components.rememberSafeScope
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch

/** Inventario → Transferencias: lo que llega a esta sucursal, lo enviado que está en camino y las anteriores. */
@Composable
fun TransfersScreen() {
    val app = LocalAppState.current
    val transfers by app.repo.transfers.collectAsState()
    val branches by app.repo.branches.collectAsState()
    val here = app.repo.branchId
    val incoming = transfers.filter { it.status == TransferStatus.IN_TRANSIT && it.toBranch == here }
    val outgoing = transfers.filter { it.status == TransferStatus.IN_TRANSIT && it.fromBranch == here }
    val past = transfers.filter { it.status != TransferStatus.IN_TRANSIT && (it.toBranch == here || it.fromBranch == here || app.can(com.vitrinapos.data.Permission.SETTINGS)) }
    val multi = branches.count { it.active == 1L } > 1
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Transferencias", subtitle = app.repo.thisBranch?.name, onBack = { app.back() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).widthIn(max = 760.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!multi) AppCard(color = AppTheme.extra.warnSoft) {
                Text("Hace falta otra sucursal", fontWeight = FontWeight.Bold, color = AppTheme.extra.warn)
                Text("Las transferencias pasan mercancía de una sucursal a otra. Crea la otra sucursal en Más → Sucursales.", fontSize = 13.sp, color = AppTheme.extra.warn)
            } else Button(onClick = { app.open(Screen.NewTransfer) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Rounded.LocalShipping, null); Spacer(Modifier.width(8.dp)); Text("Nueva transferencia")
            }
            if (incoming.isNotEmpty()) {
                SectionTitle("Por recibir aquí")
                incoming.forEach { TransferCard(it, canReceive = true) }
            }
            if (outgoing.isNotEmpty()) {
                SectionTitle("Enviadas, en camino")
                outgoing.forEach { TransferCard(it, canCancel = true) }
            }
            SectionTitle("Anteriores")
            if (past.isEmpty()) Text("Todavía no hay transferencias.", fontSize = 13.sp, color = AppTheme.extra.muted)
            past.take(50).forEach { TransferCard(it) }
            Text("Lo enviado sale de esta sucursal al momento. Entra a la otra cuando allá tocan \"Recibir\". Si llega menos, \"Llegó diferente\" anota lo que faltó.",
                fontSize = 13.sp, color = AppTheme.extra.muted)
        }
    }
}

@Composable
private fun TransferCard(t: Transfer, canReceive: Boolean = false, canCancel: Boolean = false) {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    val items by produceState(emptyList<com.vitrinapos.db.TransferItem>(), t.id, t.status) { value = app.repo.transferItems(t.id) }
    var receiveDiff by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val from = app.repo.branchById(t.fromBranch)?.name ?: "?"
    val to = app.repo.branchById(t.toBranch)?.name ?: "?"
    val ex = AppTheme.extra
    val (fg, bg) = when (t.status) {
        TransferStatus.IN_TRANSIT -> ex.warn to ex.warnSoft
        TransferStatus.RECEIVED -> ex.ok to ex.okSoft
        TransferStatus.DIFFERENT -> ex.danger to ex.dangerSoft
        else -> ex.muted to MaterialTheme.colorScheme.surfaceContainer
    }
    AppCard(color = if (canReceive) ex.warnSoft else MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (t.status == TransferStatus.IN_TRANSIT) Icons.Rounded.LocalShipping else Icons.Rounded.SwapHoriz, null, tint = fg)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("#${t.number} · $from → $to", fontWeight = FontWeight.Bold)
                Text("${items.sumOf { it.qty }.fmtQty()} und · envió ${t.userName.ifBlank { "—" }} ${Time.dateTime(t.createdAt)}", fontSize = 12.sp, color = ex.muted)
            }
            Pill(TransferStatus.label(t.status), fg, bg)
        }
        if (items.isNotEmpty()) Text(
            items.joinToString(" · ") { i ->
                "${i.name} × ${i.qty.fmtQty()}" + (i.received?.takeIf { r -> r + 1e-9 < i.qty }?.let { r -> " (llegaron ${r.fmtQty()})" } ?: "")
            },
            fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
        )
        if (t.note.isNotBlank()) Text("Nota: ${t.note}", fontSize = 12.sp, color = ex.muted)
        if (t.receiveNote.isNotBlank()) Text("Al recibir: ${t.receiveNote}" + (t.receivedBy.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""), fontSize = 12.sp, color = ex.muted)
        val canStock = app.can(com.vitrinapos.data.Permission.ADJUST_STOCK)
        if (canReceive && canStock) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                busy = true
                scope.launch {
                    runCatching { app.repo.receiveTransfer(t.id) }.onSuccess { app.toast("Transferencia recibida: ya está en el inventario de $to") }.onFailure { app.toast(cloudError(it)) }
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Recibir todo") }
            OutlinedButton(onClick = { receiveDiff = true }, enabled = !busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Llegó diferente") }
        }
        if (canCancel && canStock) TextButton(onClick = { cancel = true }, enabled = !busy) { Text("Cancelar transferencia", color = MaterialTheme.colorScheme.error) }
    }
    if (receiveDiff) ReceiveDifferentDialog(t, items) { receiveDiff = false }
    if (cancel) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { cancel = false },
            title = { Text("Cancelar la transferencia #${t.number}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("La mercancía vuelve al inventario de $from.")
                    TextInput(reason, { reason = it.take(120) }, "Motivo *")
                }
            },
            confirmButton = {
                TextButton(enabled = reason.isNotBlank(), onClick = {
                    cancel = false
                    scope.launch { runCatching { app.repo.cancelTransfer(t.id, reason) }.onSuccess { app.toast("Transferencia cancelada") }.onFailure { app.toast(cloudError(it)) } }
                }) { Text("Cancelar transferencia", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { cancel = false }) { Text("Volver") } },
        )
    }
}

/** "Llegó diferente": cuánto llegó de cada artículo y qué pasó con lo demás. */
@Composable
private fun ReceiveDifferentDialog(t: Transfer, items: List<com.vitrinapos.db.TransferItem>, onDone: () -> Unit) {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    val got = remember { androidx.compose.runtime.mutableStateMapOf<Long, Double>().apply { items.forEach { put(it.id, it.qty) } } }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("¿Qué llegó de la #${t.number}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { i ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(i.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Enviaron ${i.qty.fmtQty()}", fontSize = 12.sp, color = AppTheme.extra.muted)
                        }
                        QtyStepper(got[i.id] ?: i.qty, { v -> got[i.id] = v.coerceIn(0.0, i.qty) })
                    }
                }
                TextInput(note, { note = it.take(160) }, "¿Qué pasó con lo que no llegó? *", singleLine = false, minLines = 2)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { app.repo.receiveTransfer(t.id, got.toMap(), note) }
                        .onSuccess { app.toast("Recibida con diferencia: quedó anotado"); onDone() }
                        .onFailure { error = cloudError(it) }
                    busy = false
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text("Cancelar") } },
    )
}

/** Nueva transferencia: de esta sucursal a otra, con sus IMEI si los productos se controlan por unidad. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewTransferScreen() {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    val branches by app.repo.branches.collectAsState()
    val products by app.repo.products.collectAsState()
    val units by app.repo.availableUnits.collectAsState()
    val here = app.repo.branchId
    val targets = branches.filter { it.active == 1L && it.id != here }
    var to by remember { mutableStateOf(targets.firstOrNull()?.id) }
    data class Line(val p: Product, val qty: Double, val serials: List<String>)
    val lines = remember { mutableStateListOf<Line>() }
    var picking by remember { mutableStateOf(false) }
    var imeiFor by remember { mutableStateOf<Product?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Transferir inventario", onBack = { app.back() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).widthIn(max = 760.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppCard(Modifier.weight(1f)) { Text("Desde", fontSize = 12.sp, color = AppTheme.extra.muted); Text(app.repo.thisBranch?.name ?: "Esta sucursal", fontWeight = FontWeight.Bold) }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
                AppCard(Modifier.weight(1f), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("Hacia", fontSize = 12.sp, color = AppTheme.extra.muted)
                    Text(targets.firstOrNull { it.id == to }?.name ?: "Elige", fontWeight = FontWeight.Bold)
                }
            }
            if (targets.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { b ->
                    val sel = to == b.id
                    Surface(
                        onClick = { to = b.id }, shape = RoundedCornerShape(999.dp),
                        color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else AppTheme.extra.line),
                    ) { Text(b.name, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp) }
                }
            }
            SectionTitle("Productos", action = "+ Agregar", onAction = { picking = true })
            if (lines.isEmpty()) Text("Agrega lo que vas a enviar.", fontSize = 13.sp, color = AppTheme.extra.muted)
            lines.forEachIndexed { idx, l ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.p.fullName, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (l.p.serialized == 1L) (if (l.serials.isEmpty()) "Toca para elegir los IMEI" else "IMEI " + l.serials.joinToString(", ") { it.takeLast(6) })
                                else "Hay ${l.p.stock.fmtQty()} aquí",
                                fontSize = 12.sp, color = if (l.p.serialized == 1L && l.serials.isEmpty()) AppTheme.extra.warn else AppTheme.extra.muted,
                            )
                        }
                        if (l.p.serialized == 1L) TextButton(onClick = { imeiFor = l.p }) { Text("IMEI (${l.serials.size})") }
                        else QtyStepper(l.qty, { v -> lines[idx] = l.copy(qty = v.coerceIn(0.0, l.p.stock)) })
                        IconButton(onClick = { lines.removeAt(idx) }) { Icon(Icons.Rounded.Delete, "Quitar", tint = AppTheme.extra.muted) }
                    }
                }
            }
            TextInput(note, { note = it.take(120) }, "Nota (opcional)", placeholder = "Pedido del lunes")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = {
                    val dest = to ?: return@Button
                    busy = true; error = null
                    scope.launch {
                        runCatching { app.repo.sendTransfer(dest, lines.map { TransferLine(it.p.id, it.qty, it.serials) }, note) }
                            .onSuccess { app.toast("Transferencia enviada a ${app.repo.branchById(dest)?.name}"); app.replace(Screen.Transfers) }
                            .onFailure { error = cloudError(it) }
                        busy = false
                    }
                },
                enabled = !busy && to != null && lines.any { it.qty > 0 },
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
            ) { Icon(Icons.Rounded.LocalShipping, null); Spacer(Modifier.width(8.dp)); Text("Enviar transferencia", fontSize = 16.sp) }
            Text("Sale del inventario de ${app.repo.thisBranch?.name ?: "esta sucursal"} ahora. Entra al de ${targets.firstOrNull { it.id == to }?.name ?: "la otra"} cuando allá toquen \"Recibir\".",
                fontSize = 13.sp, color = AppTheme.extra.muted)
        }
    }
    if (picking) ProductPickerDialog(
        products.filter { it.trackStock == 1L && it.stock > 0 && lines.none { l -> l.p.id == it.id } },
        onPick = { p -> picking = false; lines += Line(p, if (p.serialized == 1L) 0.0 else 1.0, emptyList()); if (p.serialized == 1L) imeiFor = p },
        onDismiss = { picking = false }, title = "¿Qué vas a enviar?",
    )
    imeiFor?.let { p ->
        val idx = lines.indexOfFirst { it.p.id == p.id }
        val chosen = remember(p.id) { mutableStateListOf<String>().apply { if (idx >= 0) addAll(lines[idx].serials) } }
        AlertDialog(
            onDismissRequest = { imeiFor = null },
            title = { Text("IMEI de ${p.fullName}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val mine = units.filter { it.productId == p.id }
                    if (mine.isEmpty()) Text("No hay unidades con IMEI en esta sucursal.")
                    mine.forEach { u ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(u.serial in chosen, { on -> if (on) chosen += u.serial else chosen -= u.serial })
                            Text(u.serial)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (idx >= 0) lines[idx] = lines[idx].copy(qty = chosen.size.toDouble(), serials = chosen.toList())
                    imeiFor = null
                }) { Text("Listo (${chosen.size})") }
            },
        )
    }
}

/** Aviso en Inicio: transferencias que llegan a esta sucursal. */
@Composable
fun IncomingTransfersBanner() {
    val app = LocalAppState.current
    val transfers by app.repo.transfers.collectAsState()
    val n = transfers.count { it.status == TransferStatus.IN_TRANSIT && it.toBranch == app.repo.branchId }
    if (n == 0 || !app.can(com.vitrinapos.data.Permission.ADJUST_STOCK)) return
    AppCard(onClick = { app.open(Screen.Transfers) }, color = AppTheme.extra.warnSoft) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocalShipping, null, tint = AppTheme.extra.warn)
            Spacer(Modifier.width(10.dp))
            Text(if (n == 1) "Hay 1 transferencia por recibir" else "Hay $n transferencias por recibir", color = AppTheme.extra.warn, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Add, null, tint = androidx.compose.ui.graphics.Color.Transparent)
            Text("Ver", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}
