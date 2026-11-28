package com.vitrinapos.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.AppState
import com.vitrinapos.LocalAppState
import com.vitrinapos.data.AppSettings
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.Money
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.RepairStatus
import com.vitrinapos.data.StatusDef
import com.vitrinapos.data.StatusKind
import com.vitrinapos.data.Time
import com.vitrinapos.data.code
import com.vitrinapos.data.daysWaiting
import com.vitrinapos.data.deviceName
import com.vitrinapos.data.fmtQty
import com.vitrinapos.data.warrantyUntil
import com.vitrinapos.data.whatsappNumber
import com.vitrinapos.db.RepairOrder
import com.vitrinapos.print.Ticket
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.Divider
import com.vitrinapos.ui.components.KeyValue
import com.vitrinapos.ui.components.MoneyInput
import com.vitrinapos.ui.components.ProductPickerDialog
import com.vitrinapos.ui.components.QtyStepper
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.components.rememberSafeScope
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.net.URLEncoder

// ================= Entrega y cobro =================

@Composable
fun RepairDeliverScreen(id: Long) {
    val app = LocalAppState.current
    val s = app.settings
    val sym = s.currencySymbol
    val scope = rememberSafeScope()
    val repairs by app.repo.repairs.collectAsState()
    val products by app.repo.products.collectAsState()
    val r = repairs.firstOrNull { it.id == id }
    if (r == null || RepairStatus.isClosed(r.status)) {
        Column { ScreenHeader("Entregar", onBack = { app.back() }) }
        return
    }
    var repaired by remember { mutableStateOf(RepairStatus.kind(r.status) != StatusKind.NOT_REPAIRED) }
    val taxService = remember { mutableStateOf(s.pricesIncludeTax && s.taxRate > 0) }
    // Servicio (mano de obra) y repuestos del inventario.
    val service = remember { mutableStateOf(serviceLine(r, repaired, taxService.value)) }
    val parts = remember { mutableStateListOf<CartLine>() }
    var method by remember { mutableStateOf(PaymentMethods.CASH) }
    var received by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }
    var printIt by remember { mutableStateOf(s.printerId.isNotBlank()) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun setRepaired(v: Boolean) {
        repaired = v
        service.value = serviceLine(r, v, taxService.value)
        if (!v) parts.clear()
    }

    val lines = listOf(service.value).filter { it.price > 0 } + parts
    val totals = app.repo.totalsFor(lines, 0)
    val balance = totals.total - r.deposit
    val cashIn = received ?: balance.coerceAtLeast(0)
    val change = if (method == PaymentMethods.CASH) cashIn - balance else 0
    val canCharge = balance >= 0 && (method != PaymentMethods.CASH || cashIn >= balance) && !busy

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Entregar ${r.code}", subtitle = "${r.deviceName} · ${r.clientName}", onBack = { app.back() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = repaired, onClick = { setRepaired(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Reparado") }
                SegmentedButton(selected = !repaired, onClick = { setRepaired(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Sin reparar") }
            }
            if (!repaired) Text("Se cobra el diagnóstico (puedes cambiarlo o dejarlo en cero). No lleva garantía.", fontSize = 12.sp, color = AppTheme.extra.muted)

            SectionTitle(if (repaired) "Mano de obra" else "Diagnóstico")
            AppCard {
                TextInput(service.value.name, { service.value = service.value.copy(name = it) }, "Concepto")
                Spacer(Modifier.height(8.dp))
                MoneyInput(service.value.price.takeIf { it > 0 }, { service.value = service.value.copy(price = it ?: 0) }, "Precio")
                if (s.taxRate > 0) {
                    Row(Modifier.fillMaxWidth().clickable {
                        taxService.value = !taxService.value; service.value = service.value.copy(taxable = taxService.value)
                    }.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Lleva ${s.taxName} ${s.taxRate.fmtQty()}%" + if (s.pricesIncludeTax) " (incluido en el precio)" else " (se suma)", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(taxService.value, { taxService.value = it; service.value = service.value.copy(taxable = it) })
                    }
                }
            }

            if (repaired) {
                SectionTitle("Repuestos del inventario", action = "Agregar") { picking = true }
                if (parts.isEmpty()) {
                    Text("Si usaste piezas de tu inventario (pantalla, batería, puerto), agrégalas para descontarlas del stock.", fontSize = 13.sp, color = AppTheme.extra.muted)
                } else AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    parts.forEachIndexed { i, l ->
                        if (i > 0) Divider()
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Money.format(l.amount, sym), fontSize = 13.sp, color = AppTheme.extra.muted)
                            }
                            QtyStepper(l.qty, { q -> if (q <= 0) parts.removeAt(i) else parts[i] = l.copy(qty = q) })
                        }
                    }
                }
            }

            SectionTitle("Cobro")
            AppCard {
                KeyValue("Total", Money.format(totals.total, sym))
                if (totals.tax > 0) KeyValue("  ${s.taxName} incluido", Money.format(totals.tax, sym))
                if (r.deposit > 0) KeyValue("Abono recibido", "-" + Money.format(r.deposit, sym))
                Divider()
                KeyValue("A cobrar", Money.format(balance.coerceAtLeast(0), sym), strong = true)
                if (balance < 0) Text("El abono es mayor que el total. Sube el precio o devuelve la diferencia por fuera.", color = AppTheme.extra.danger, fontSize = 13.sp)
            }
            if (balance > 0) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PaymentMethods.all.forEachIndexed { i, m ->
                        SegmentedButton(selected = method == m, onClick = { method = m }, shape = SegmentedButtonDefaults.itemShape(i, PaymentMethods.all.size)) { Text(m, fontSize = 12.sp) }
                    }
                }
                if (method == PaymentMethods.CASH) {
                    MoneyInput(received, { received = it }, "Recibido en efectivo")
                    if (change > 0) KeyValue("Cambio", Money.format(change, sym), valueColor = AppTheme.extra.ok)
                }
            }
            TextInput(note, { note = it }, "Nota de entrega (opcional)")
            AppCard(onClick = { printIt = !printIt }, padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Imprimir comprobante de entrega", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (repaired && r.warrantyDays > 0) "Con la garantía de ${r.warrantyDays} días" else "Sin garantía",
                            fontSize = 12.sp, color = AppTheme.extra.muted,
                        )
                    }
                    Switch(printIt, { printIt = it }, enabled = s.printerId.isNotBlank())
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val saleId = app.repo.deliverRepair(r.id, lines, repaired, method, if (method == PaymentMethods.CASH) cashIn else balance, note)
                            if (printIt) printDelivery(app, saleId, r.id)
                            app.toast("${r.code} entregado")
                            app.back()
                        } catch (e: Exception) {
                            app.toast(e.message ?: "No se pudo entregar")
                        } finally { busy = false }
                    }
                },
                enabled = canCharge,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AppTheme.extra.onCta)
                else Text(if (balance > 0) "Cobrar ${Money.format(balance, sym)} y entregar" else "Entregar", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
    if (picking) ProductPickerDialog(products, onPick = { p ->
        picking = false
        val i = parts.indexOfFirst { it.productId == p.id }
        if (i >= 0) parts[i] = parts[i].copy(qty = parts[i].qty + 1) else parts.add(CartLine.of(p))
    }, onDismiss = { picking = false }, title = "Repuesto usado")
}

private fun serviceLine(r: RepairOrder, repaired: Boolean, taxable: Boolean) =
    if (repaired) CartLine(null, "Reparación ${r.deviceName} (${r.code})", r.code, 1.0, (r.estimate).coerceAtLeast(0), 0, taxable, null, false)
    else CartLine(null, "Diagnóstico ${r.deviceName} (${r.code})", r.code, 1.0, r.diagnosisFee, 0, taxable, null, false)

/** Imprime el comprobante de entrega (venta + garantía). */
suspend fun printDelivery(app: AppState, saleId: Long, repairId: Long) {
    val sale = app.repo.saleById(saleId) ?: return
    val items = app.repo.saleItems(saleId)
    val r = app.repo.repairById(repairId) ?: return
    val ecf = app.repo.ecfForSale(saleId).firstOrNull { it.type != 34L }
    app.print(Ticket.forSale(app.settingsNow, sale, items, repair = r, ecf = ecf, branch = app.repo.branchById(sale.branchId)))
}

// ================= WhatsApp =================

/** Mensajes listos según el momento de la orden. */
object RepairMessages {
    data class Option(val title: String, val text: String)

    fun options(s: AppSettings, r: RepairOrder): List<Option> {
        val name = r.clientName.split(' ').first()
        val shop = s.businessName.ifBlank { "el taller" }
        val sym = s.currencySymbol
        val dev = r.deviceName
        val balance = (r.estimate - r.deposit).coerceAtLeast(0)
        val list = mutableListOf<Option>()
        val k = RepairStatus.kind(r.status)
        if (k == StatusKind.READY) list += Option(
            "Ya está listo",
            "Hola $name, te saluda $shop. Tu $dev (orden ${r.code}) ya está listo para retirar." +
                (if (balance > 0) " Saldo pendiente: ${Money.format(balance, sym)}." else "") + " ¡Te esperamos!",
        )
        if (k == StatusKind.NOT_REPAIRED) list += Option(
            "No se pudo reparar",
            "Hola $name, te saluda $shop. Revisamos tu $dev (orden ${r.code}) y lamentablemente no fue posible repararlo." +
                (if (r.diagnosisFee > 0) " El diagnóstico tiene un costo de ${Money.format(r.diagnosisFee, sym)}." else "") + " Puedes pasar a retirarlo.",
        )
        if (r.status == RepairStatus.APPROVAL || k == StatusKind.WAIT || r.status == RepairStatus.DIAGNOSIS) list += Option(
            "Presupuesto para aprobar",
            "Hola $name, te saluda $shop. Ya revisamos tu $dev (orden ${r.code})." +
                (if (r.estimate > 0) " La reparación cuesta ${Money.format(r.estimate, sym)}." else "") + " ¿Nos confirmas si la hacemos?",
        )
        if (r.status == RepairStatus.PARTS) list += Option(
            "Esperando repuesto",
            "Hola $name, te saluda $shop. Tu $dev (orden ${r.code}) está esperando un repuesto. Te avisamos en cuanto llegue.",
        )
        if (RepairStatus.isReady(r.status) && r.daysWaiting() >= 7) list += Option(
            "Recordatorio para retirar",
            "Hola $name, te saluda $shop. Tu $dev (orden ${r.code}) lleva ${r.daysWaiting()} días esperando que lo retires. " +
                "Recuerda que los equipos no retirados en ${s.repairAbandonDays} días se consideran abandonados.",
        )
        if (RepairStatus.isPending(r.status)) list += Option(
            "Recibimos tu equipo",
            "Hola $name, te saluda $shop. Recibimos tu $dev con la orden ${r.code}. Te avisaremos cuando esté listo." +
                (r.estimatedAt?.let { " Entrega estimada: ${Time.date(it)}." } ?: ""),
        )
        if (RepairStatus.isClosed(r.status)) list += Option(
            "Gracias",
            "Hola $name, gracias por confiar en $shop." + (r.warrantyUntil?.let { " Tu reparación (${r.code}) tiene garantía hasta el ${Time.date(it)}." } ?: ""),
        )
        return list
    }
}

@Composable
fun WhatsAppDialog(r: RepairOrder, onDismiss: () -> Unit) {
    val app = LocalAppState.current
    val s = app.settings
    val scope = rememberSafeScope()
    val options = remember(r) { RepairMessages.options(s, r) }
    var choice by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf(options.firstOrNull()?.text ?: "") }
    var phone by remember { mutableStateOf(r.clientPhone) }
    val number = whatsappNumber(phone, s.whatsappCountry)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Avisar por WhatsApp") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput(phone, { phone = it }, "Teléfono", error = if (number == null) "Escribe el número del cliente" else null)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        options.forEachIndexed { i, o ->
                            FilterChip(selected = choice == i, onClick = { choice = i; text = o.text }, label = { Text(o.title) }, shape = RoundedCornerShape(10.dp))
                        }
                    }
                }
                TextInput(text, { text = it }, "Mensaje", singleLine = false, minLines = 4)
            }
        },
        confirmButton = {
            TextButton(enabled = number != null && text.isNotBlank(), onClick = {
                val url = "https://wa.me/$number?text=" + URLEncoder.encode(text, "UTF-8").replace("+", "%20")
                if (app.platform.openUrl(url)) {
                    scope.launch { app.repo.setRepairStatus(r.id, r.status, "Aviso por WhatsApp: ${options.getOrNull(choice)?.title ?: "mensaje"}") }
                    onDismiss()
                } else {
                    app.platform.shareText(text, "Mensaje para ${r.clientName}")
                    app.toast("No se pudo abrir WhatsApp: el mensaje se copió para pegarlo")
                }
            }) { Text("Abrir WhatsApp") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ================= Editor de estados =================

@Composable
fun RepairStatusesScreen() {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    val saved = RepairStatus.defs
    val list = remember { mutableStateListOf<StatusDef>().also { it.addAll(saved) } }
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    val dirty = list.toList() != saved

    fun move(i: Int, d: Int) {
        val j = i + d
        if (j !in list.indices) return
        val a = list[i]; list[i] = list[j]; list[j] = a
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Estados del taller", subtitle = "Nombre, orden y tipo", onBack = { app.back() }) {
            if (dirty) TextButton(onClick = {
                scope.launch {
                    app.repo.updateSettings { it.copy(repairStatuses = RepairStatus.encode(list)) }
                    app.toast("Estados guardados")
                    app.back()
                }
            }) { Text("Guardar", fontWeight = FontWeight.Bold) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "El orden es el que sigue el botón \"Pasar a\". Los estados con candado se pueden renombrar, pero no quitar. " +
                    "Los de tipo \"En espera\" no se sugieren solos: se eligen con \"Otro estado\".",
                fontSize = 13.sp, color = AppTheme.extra.muted,
            )
            AppCard(padding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                list.forEachIndexed { i, d ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { editing = i }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.padding(start = 4.dp)) {
                            IconButton(onClick = { move(i, -1) }, enabled = i > 0, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "Subir") }
                            IconButton(onClick = { move(i, 1) }, enabled = i < list.lastIndex, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Bajar") }
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.label, fontWeight = FontWeight.SemiBold)
                            Text(d.kind.title, fontSize = 12.sp, color = AppTheme.extra.muted)
                        }
                        RepairStatusPreview(d)
                        if (d.locked) Icon(Icons.Rounded.Lock, "Fijo", tint = AppTheme.extra.muted, modifier = Modifier.padding(horizontal = 12.dp).size(18.dp))
                        else IconButton(onClick = {
                            scope.launch {
                                val used = app.repo.repairsWithStatus(d.key)
                                if (used > 0) app.toast("No se puede quitar: $used ${if (used == 1L) "orden tiene" else "órdenes tienen"} este estado")
                                else list.removeAt(i)
                            }
                        }) { Icon(Icons.Rounded.Delete, "Quitar", tint = AppTheme.extra.danger) }
                    }
                }
            }
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Agregar estado")
            }
            TextButton(onClick = { list.clear(); list.addAll(RepairStatus.defaults) }) { Text("Volver a los estados de fábrica") }
        }
    }
    editing?.let { i ->
        StatusDefDialog(list[i], onDismiss = { editing = null }) { list[i] = it; editing = null }
    }
    if (adding) StatusDefDialog(StatusDef("", "", StatusKind.WORK), onDismiss = { adding = false }) { d ->
        val key = RepairStatus.newKey(d.label, list.map { it.key } + RepairStatus.defaults.map { it.key })
        // Se agrega antes de "Listo para entregar".
        val at = list.indexOfFirst { it.kind == StatusKind.READY }.let { if (it < 0) list.size else it }
        list.add(at, d.copy(key = key))
        adding = false
    }
}

@Composable
private fun RepairStatusPreview(d: StatusDef) {
    val ex = AppTheme.extra
    val (fg, bg) = when (d.kind) {
        StatusKind.READY -> ex.ok to ex.okSoft
        StatusKind.DELIVERED -> ex.muted to MaterialTheme.colorScheme.surfaceContainer
        StatusKind.NOT_REPAIRED -> ex.danger to ex.dangerSoft
        StatusKind.WAIT -> ex.warn to ex.warnSoft
        StatusKind.WORK -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
    }
    Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(bg)) { Box(Modifier.padding(3.dp).fillMaxSize().clip(RoundedCornerShape(2.dp)).background(fg)) }
}

@Composable
private fun StatusDefDialog(d: StatusDef, onDismiss: () -> Unit, onSave: (StatusDef) -> Unit) {
    var label by remember { mutableStateOf(d.label) }
    var kind by remember { mutableStateOf(d.kind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (d.key.isEmpty()) "Nuevo estado" else "Editar estado") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput(label, { label = it.take(30) }, "Nombre")
                if (!d.locked) {
                    Text("Tipo", fontSize = 13.sp, color = AppTheme.extra.muted)
                    listOf(StatusKind.WORK to "Se está trabajando (sale en Pendientes)", StatusKind.WAIT to "En espera de algo (Pendientes, en amarillo)").forEach { (k, t) ->
                        Surface(
                            onClick = { kind = k }, shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(if (kind == k) 2.dp else 1.dp, if (kind == k) MaterialTheme.colorScheme.primary else AppTheme.extra.line),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(t, modifier = Modifier.padding(12.dp), fontWeight = if (kind == k) FontWeight.SemiBold else FontWeight.Normal) }
                    }
                } else {
                    Text("Estado fijo: ${d.kind.title}. Solo se puede cambiar el nombre.", fontSize = 13.sp, color = AppTheme.extra.muted)
                }
            }
        },
        confirmButton = { TextButton(enabled = label.isNotBlank(), onClick = { onSave(d.copy(label = label.trim(), kind = kind)) }) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
