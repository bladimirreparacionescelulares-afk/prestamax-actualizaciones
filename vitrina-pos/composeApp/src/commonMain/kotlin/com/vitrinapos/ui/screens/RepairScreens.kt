package com.vitrinapos.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.AppState
import com.vitrinapos.LocalAppState
import com.vitrinapos.Screen
import com.vitrinapos.data.Accessories
import com.vitrinapos.data.Condition
import com.vitrinapos.data.DeviceTypes
import com.vitrinapos.data.Money
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.RepairDraft
import com.vitrinapos.data.RepairStatus
import com.vitrinapos.data.Roles
import com.vitrinapos.data.Time
import com.vitrinapos.data.UnlockTypes
import com.vitrinapos.data.code
import com.vitrinapos.data.daysLate
import com.vitrinapos.data.daysWaiting
import com.vitrinapos.data.isAbandoned
import com.vitrinapos.data.warrantyUntil
import com.vitrinapos.data.StatusKind
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.automirrored.rounded.Chat
import com.vitrinapos.data.deviceName
import com.vitrinapos.data.parseRepairCode
import com.vitrinapos.data.photoList
import com.vitrinapos.db.RepairOrder
import com.vitrinapos.labels.LabelContent
import com.vitrinapos.labels.LabelOptions
import com.vitrinapos.labels.LabelPdf
import com.vitrinapos.labels.RepairLabel
import com.vitrinapos.platform.rememberBarcodeScanner
import com.vitrinapos.platform.rememberImagePicker
import com.vitrinapos.print.Ticket
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.ClientPickerDialog
import com.vitrinapos.ui.components.Divider
import com.vitrinapos.ui.components.EmptyState
import com.vitrinapos.ui.components.KeyValue
import com.vitrinapos.ui.components.MoneyInput
import com.vitrinapos.ui.components.Pill
import com.vitrinapos.ui.components.ProductImage
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SearchBox
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.SquareIconButton
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.components.rememberSafeScope
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ================= Ayudas =================

fun deviceIcon(type: String): ImageVector = when (type) {
    DeviceTypes.PHONE -> Icons.Rounded.PhoneAndroid
    DeviceTypes.TABLET -> Icons.Rounded.TabletAndroid
    DeviceTypes.LAPTOP -> Icons.Rounded.Computer
    DeviceTypes.PC -> Icons.Rounded.DesktopWindows
    else -> Icons.Rounded.Devices
}

@Composable
fun RepairStatusPill(status: String, modifier: Modifier = Modifier) {
    val ex = AppTheme.extra
    val (fg, bg) = when (RepairStatus.kind(status)) {
        StatusKind.READY -> ex.ok to ex.okSoft
        StatusKind.DELIVERED -> ex.muted to MaterialTheme.colorScheme.surfaceContainer
        StatusKind.NOT_REPAIRED -> ex.danger to ex.dangerSoft
        StatusKind.WAIT -> ex.warn to ex.warnSoft
        StatusKind.WORK -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
    }
    Pill(RepairStatus.label(status), fg, bg, modifier)
}

fun AppState.repairLabel(r: RepairOrder): LabelContent = LabelContent(
    name = r.clientName, price = "", code = r.code, store = settingsNow.businessName,
    repair = RepairLabel(
        code = r.code, owner = r.clientName, contact = r.clientPhone.ifBlank { r.clientEmail },
        device = r.deviceName, serial = r.serial, date = Time.shortDate(r.createdAt), problem = r.problem,
    ),
)

/** Etiqueta del equipo: en la etiquetadora si está configurada; si no, PDF del tamaño de la etiqueta. */
suspend fun AppState.printRepairLabel(r: RepairOrder, measurer: TextMeasurer, copies: Int = 1) {
    val s = settingsNow
    val o = LabelOptions(widthMm = s.labelOptions.widthMm, heightMm = s.labelOptions.heightMm)
    if (s.labelPrinterId.isNotBlank()) {
        printLabels(o, listOf(repairLabel(r) to copies), measurer)
        toast("Etiqueta de ${r.code} enviada")
    } else {
        val file = File(repo.docsDir, "${r.code}-etiqueta.pdf")
        withContext(platform.io) {
            val (w, h) = LabelPdf.pageSizePt(o)
            platform.writePdf(file, w, h) { c -> LabelPdf.drawRoll(c, o, List(copies) { repairLabel(r) }) }
        }
        if (platform.isDesktop) platform.openFile(file) else platform.shareFile(file, "application/pdf", "Etiqueta ${r.code}")
        toast("No hay etiquetadora configurada: se creó el PDF de la etiqueta")
    }
}

fun AppState.printRepairReceipt(r: RepairOrder) = print(Ticket.forRepair(settingsNow, r)) { open(Screen.Settings) }

/** Abre la orden si el texto es un código REP-0042. Devuelve true si lo era. */
suspend fun AppState.openRepairByCode(text: String): Boolean {
    val n = parseRepairCode(text) ?: return false
    val r = repo.repairByNumber(n)
    if (r == null) toast("No existe la orden ${text.trim()}") else open(Screen.RepairDetail(r.id))
    return true
}

// ================= Lista del taller =================

@Composable
fun TallerScreen() {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    val repairs by app.repo.repairs.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val scanner = rememberBarcodeScanner { code ->
        scope.launch { if (!app.openRepairByCode(code)) app.toast("Ese código no es de una orden del taller") }
    }
    val pending = repairs.filter { RepairStatus.isPending(it.status) }
    val ready = repairs.filter { RepairStatus.isReady(it.status) }
    val delivered = repairs.filter { RepairStatus.isClosed(it.status) }
    val late = pending.count { it.daysLate() > 0 }
    val abandoned = ready.filter { it.isAbandoned(app.settings.repairAbandonDays) }
    val base = when (tab) { 0 -> pending; 1 -> ready.sortedByDescending { it.daysWaiting() }; else -> delivered }
    val q = query.trim()
    val list = base.filter { r ->
        q.isEmpty() || listOf(r.code, r.clientName, r.clientPhone, r.serial, r.brand, r.model, r.problem).any { it.contains(q, true) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Reparaciones", subtitle = "Taller") {
                if (scanner != null) SquareIconButton(Icons.Rounded.QrCodeScanner, "Escanear etiqueta del equipo", primary = true) { scanner() }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kpi("Pendientes", pending.size.toString(), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f)) { tab = 0 }
                Kpi("Listos", ready.size.toString(), AppTheme.extra.ok, Modifier.weight(1f)) { tab = 1 }
                Kpi("Atrasados", late.toString(), if (late > 0) AppTheme.extra.danger else AppTheme.extra.muted, Modifier.weight(1f)) { tab = 0 }
            }
            if (abandoned.isNotEmpty()) {
                Surface(
                    onClick = { tab = 1; query = "" }, shape = RoundedCornerShape(14.dp), color = AppTheme.extra.dangerSoft,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (abandoned.size == 1) "1 equipo lleva más de ${app.settings.repairAbandonDays} días sin retirar: se considera abandonado. Avísale al cliente por WhatsApp."
                        else "${abandoned.size} equipos llevan más de ${app.settings.repairAbandonDays} días sin retirar: se consideran abandonados. Avísales por WhatsApp.",
                        color = AppTheme.extra.danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                listOf("Pendientes", "Listos", "Entregados").forEachIndexed { i, t ->
                    SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(t, fontSize = 13.sp) }
                }
            }
            SearchBox(
                query, { query = it }, "Buscar por nombre, teléfono, IMEI o REP-…",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                onSubmit = { scope.launch { if (app.openRepairByCode(query)) query = "" } },
            )
            if (repairs.isEmpty()) {
                EmptyState(Icons.Rounded.Build, "Todavía no hay equipos en el taller", "Toca \"Recibir equipo\" cuando un cliente te deje un teléfono o una computadora.")
            } else if (list.isEmpty()) {
                Text("No hay órdenes aquí.", color = AppTheme.extra.muted, modifier = Modifier.padding(horizontal = 16.dp))
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { r -> RepairRow(r) { app.open(Screen.RepairDetail(r.id)) } }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { app.open(Screen.RepairReceive()) },
            containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Recibir equipo", fontWeight = FontWeight.Bold) },
        )
    }
}

@Composable
private fun Kpi(label: String, value: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    AppCard(modifier, onClick = onClick, padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, fontSize = 12.sp, color = AppTheme.extra.muted)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = color)
    }
}

@Composable
fun RepairRow(r: RepairOrder, onClick: () -> Unit) {
    val abandonDays = LocalAppState.current.settings.repairAbandonDays
    val waiting = r.daysWaiting()
    val abandoned = r.isAbandoned(abandonDays)
    val late = if (abandoned) waiting else r.daysLate()
    Surface(
        onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (late > 0) AppTheme.extra.danger.copy(alpha = 0.5f) else AppTheme.extra.line),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(deviceIcon(r.deviceType), null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.deviceName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            abandoned -> "Abandonado · $waiting días"
                            late > 0 -> "+$late ${if (late == 1) "día" else "días"}"
                            RepairStatus.isReady(r.status) && waiting > 0 -> "listo hace $waiting ${if (waiting == 1) "día" else "días"}"
                            RepairStatus.isClosed(r.status) -> Time.shortDate(r.deliveredAt ?: r.updatedAt)
                            else -> Time.relativeDay(r.createdAt).lowercase()
                        },
                        fontSize = 12.sp, fontWeight = if (late > 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (late > 0) AppTheme.extra.danger else AppTheme.extra.muted,
                    )
                }
                Text("${r.clientName} · ${r.problem}", fontSize = 13.sp, color = AppTheme.extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RepairStatusPill(r.status)
                    Spacer(Modifier.width(8.dp))
                    Text(r.code, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.extra.muted)
                }
            }
        }
    }
}

// ================= Recepción =================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepairReceiveScreen(editId: Long?, warrantyOf: Long? = null) {
    val app = LocalAppState.current
    val s = app.settings
    val scope = rememberSafeScope()
    val clients by app.repo.clients.collectAsState()
    val users by app.repo.users.collectAsState()
    val measurer = rememberTextMeasurer()
    var step by remember { mutableIntStateOf(0) }
    var d by remember { mutableStateOf(RepairDraft(diagnosisFee = s.repairDiagnosisFee, warrantyDays = s.repairWarrantyDays, technician = "")) }
    var loaded by remember { mutableStateOf(editId == null && warrantyOf == null) }
    var depositMethod by remember { mutableStateOf(PaymentMethods.CASH) }
    var etaDays by remember { mutableStateOf<Int?>(2) }
    var printLabel by remember { mutableStateOf(true) }
    var printReceipt by remember { mutableStateOf(s.printerId.isNotBlank()) }
    var pickClient by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(editId) {
        if (editId != null) app.repo.repairById(editId)?.let { r ->
            d = RepairDraft(
                r.id, r.clientId, r.clientName, r.clientPhone, r.clientEmail, r.deviceType, r.brand, r.model, r.serial, r.color, r.unlockType,
                r.unlockCode, r.problem, r.conditionText, r.accessories, r.photos, r.estimate, r.deposit, r.diagnosisFee, r.estimatedAt,
                r.technician, r.notes, r.warrantyDays.toInt(),
            )
            etaDays = null
            loaded = true
        }
        // Reingreso por garantía: mismos datos del equipo, sin costo.
        if (editId == null && warrantyOf != null) app.repo.repairById(warrantyOf)?.let { r ->
            d = d.copy(
                clientId = r.clientId, clientName = r.clientName, clientPhone = r.clientPhone, clientEmail = r.clientEmail, deviceType = r.deviceType,
                brand = r.brand, model = r.model, serial = r.serial, color = r.color, unlockType = r.unlockType, unlockCode = r.unlockCode,
                problem = "Garantía de ${r.code}: ", estimate = 0, diagnosisFee = 0, technician = r.technician,
                notes = "Reingreso por garantía de ${r.code} (entregado el ${r.deliveredAt?.let { Time.date(it) } ?: "-"})",
            )
            loaded = true
        }
    }
    val picker = rememberImagePicker { temp ->
        if (temp != null) scope.launch {
            val name = app.repo.importImage(temp, "r")
            temp.delete()
            if (name != null) d = d.copy(photos = (d.photos.split(',').filter { it.isNotBlank() } + name).joinToString(","))
        }
    }
    val serialScanner = rememberBarcodeScanner { d = d.copy(serial = it) }
    val techs = users.filter { it.active == 1L && (it.role == Roles.TECH || it.role == Roles.ADMIN) }

    val step1Ok = d.clientName.isNotBlank() && (d.brand.isNotBlank() || d.model.isNotBlank())
    val step2Ok = d.problem.isNotBlank()

    fun save() {
        busy = true
        scope.launch {
            try {
                val eta = etaDays?.let { Time.addDays(Time.startOfDay(), it) + 18 * 3_600_000L } ?: d.estimatedAt
                val id = app.repo.saveRepair(d.copy(estimatedAt = eta), depositMethod)
                val r = app.repo.repairById(id) ?: return@launch
                if (editId == null) {
                    if (printLabel) runCatching { app.printRepairLabel(r, measurer) }.onFailure { app.toast("No se pudo imprimir la etiqueta: ${it.message}") }
                    if (printReceipt) app.printRepairReceipt(r)
                    app.toast("Equipo recibido: ${r.code}")
                } else app.toast("Orden ${r.code} actualizada")
                app.replace(Screen.RepairDetail(id))
            } finally { busy = false }
        }
    }

    // Cada paso empieza arriba.
    val stepScroll = rememberScrollState()
    LaunchedEffect(step) { stepScroll.scrollTo(0) }
    var confirmExit by remember { mutableStateOf(false) }
    fun goBack() {
        when {
            step > 0 -> step--
            editId == null && (d.clientName.isNotBlank() || d.problem.isNotBlank() || d.model.isNotBlank()) -> confirmExit = true
            else -> app.back()
        }
    }
    // El botón "atrás" del teléfono vuelve al paso anterior en vez de cerrar y perder lo escrito.
    com.vitrinapos.platform.PlatformBackHandler(enabled = true) { goBack() }
    if (confirmExit) com.vitrinapos.ui.components.ConfirmDialog(
        "¿Salir sin guardar?", "Se pierde lo que escribiste de este equipo.", "Salir",
        onConfirm = { confirmExit = false; app.back() }, onDismiss = { confirmExit = false }, destructive = true,
    )

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            if (editId != null) "Editar orden" else if (warrantyOf != null) "Recibir por garantía" else "Recibir equipo",
            subtitle = "Paso ${step + 1} de 3",
            onBack = ::goBack,
        )
        if (!loaded) return@Column
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Equipo", "Estado", "Confirmar").forEachIndexed { i, t ->
                val color = when {
                    i == step -> MaterialTheme.colorScheme.primary
                    i < step -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainer
                }
                Text(
                    (if (i < step) "✓ " else "${i + 1} ") + t,
                    color = if (i == step) MaterialTheme.colorScheme.onPrimary else if (i < step) MaterialTheme.colorScheme.onPrimaryContainer else AppTheme.extra.muted,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(color).clickable(enabled = i < step) { step = i }.padding(vertical = 8.dp),
                )
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(stepScroll).padding(16.dp).widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                0 -> {
                    SectionTitle("Cliente")
                    AppCard(onClick = { pickClient = true }, padding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(if (d.clientId != null) "Cliente registrado · cambiar" else "Elegir un cliente registrado", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        }
                    }
                    TextInput(d.clientName, { d = d.copy(clientName = it, clientId = null) }, "Nombre del dueño *", error = if (showErrors && d.clientName.isBlank()) "Escribe el nombre" else null)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextInput(d.clientPhone, { d = d.copy(clientPhone = it) }, "Teléfono", Modifier.weight(1f), keyboard = KeyboardType.Phone)
                        TextInput(d.clientEmail, { d = d.copy(clientEmail = it) }, "Correo", Modifier.weight(1f), keyboard = KeyboardType.Email)
                    }
                    if (d.clientId == null && d.clientName.isNotBlank()) Text("Se registrará como cliente nuevo.", fontSize = 12.sp, color = AppTheme.extra.muted)

                    SectionTitle("Equipo")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeviceTypes.all.forEach { t ->
                            val on = d.deviceType == t
                            Surface(
                                onClick = { d = d.copy(deviceType = t) }, shape = RoundedCornerShape(14.dp),
                                color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else AppTheme.extra.line),
                            ) {
                                Column(Modifier.width(76.dp).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(deviceIcon(t), null, tint = MaterialTheme.colorScheme.primary)
                                    Text(DeviceTypes.label(t), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    TextInput(d.brand, { d = d.copy(brand = it) }, "Marca")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DeviceTypes.brands[d.deviceType].orEmpty().forEach { b ->
                            FilterChip(selected = d.brand == b, onClick = { d = d.copy(brand = b) }, label = { Text(b, fontSize = 12.sp) }, shape = RoundedCornerShape(10.dp))
                        }
                    }
                    TextInput(d.model, { d = d.copy(model = it) }, "Modelo", error = if (showErrors && d.brand.isBlank() && d.model.isBlank()) "Escribe la marca o el modelo" else null)
                    TextInput(
                        d.serial, { d = d.copy(serial = it) }, DeviceTypes.serialLabel(d.deviceType),
                        placeholder = if (d.deviceType == DeviceTypes.PHONE) "Marca *#06# para verlo" else null,
                        trailing = serialScanner?.let { sc -> { IconButton(onClick = sc) { Icon(Icons.Rounded.QrCodeScanner, "Escanear", tint = MaterialTheme.colorScheme.primary) } } },
                    )
                    TextInput(d.color, { d = d.copy(color = it) }, "Color")
                    SectionTitle("Desbloqueo (opcional, no sale impreso)")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(UnlockTypes.NONE, UnlockTypes.PIN, UnlockTypes.PATTERN, UnlockTypes.PASSWORD).forEachIndexed { i, t ->
                            SegmentedButton(selected = d.unlockType == t, onClick = { d = d.copy(unlockType = t, unlockCode = "") }, shape = SegmentedButtonDefaults.itemShape(i, 4)) {
                                Text(UnlockTypes.label(t), fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                    when (d.unlockType) {
                        UnlockTypes.PIN -> TextInput(d.unlockCode, { d = d.copy(unlockCode = it.filter(Char::isDigit).take(12)) }, "PIN", keyboard = KeyboardType.Number)
                        UnlockTypes.PASSWORD -> TextInput(d.unlockCode, { d = d.copy(unlockCode = it.take(40)) }, "Contraseña")
                        UnlockTypes.PATTERN -> PatternInput(d.unlockCode) { d = d.copy(unlockCode = it) }
                    }
                }
                1 -> {
                    TextInput(
                        d.problem, { d = d.copy(problem = it) }, "Falla que reporta el cliente *", singleLine = false, minLines = 3,
                        error = if (showErrors && d.problem.isBlank()) "Describe la falla" else null,
                    )
                    SectionTitle("Cómo llega el equipo")
                    Text("Toca cada punto: ✓ funciona, ✕ con daño, ? sin revisar.", fontSize = 12.sp, color = AppTheme.extra.muted)
                    val cond = Condition.parse(d.conditionText)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Condition.items.forEach { item ->
                            val v = cond[item] ?: Condition.UNKNOWN
                            val (fg, bg) = when (v) {
                                Condition.OK -> AppTheme.extra.ok to AppTheme.extra.okSoft
                                Condition.BAD -> AppTheme.extra.danger to AppTheme.extra.dangerSoft
                                else -> AppTheme.extra.muted to MaterialTheme.colorScheme.surfaceContainer
                            }
                            Text(
                                "${when (v) { Condition.OK -> "✓"; Condition.BAD -> "✕"; else -> "?" }} $item",
                                color = fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(bg).clickable {
                                    val nextV = when (v) { Condition.UNKNOWN -> Condition.OK; Condition.OK -> Condition.BAD; else -> Condition.UNKNOWN }
                                    d = d.copy(conditionText = Condition.format(cond + (item to nextV)))
                                }.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    SectionTitle("Accesorios que deja")
                    val acc = d.accessories.split(',').filter { it.isNotBlank() }.toSet()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Accessories.all.forEach { a ->
                            FilterChip(selected = a in acc, onClick = {
                                d = d.copy(accessories = (if (a in acc) acc - a else acc + a).joinToString(","))
                            }, label = { Text(a) }, shape = RoundedCornerShape(10.dp))
                        }
                    }
                    SectionTitle("Fotos del equipo al recibirlo")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.photos.split(',').filter { it.isNotBlank() }.forEach { ph ->
                            Box {
                                ProductImage(ph, "Foto", Modifier.size(84.dp), RoundedCornerShape(12.dp))
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Color(0xAA000000))
                                        .clickable { d = d.copy(photos = d.photos.split(',').filter { it.isNotBlank() && it != ph }.joinToString(",")) },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Rounded.Close, "Quitar", tint = Color.White, modifier = Modifier.size(14.dp)) }
                            }
                        }
                        Box(
                            Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable { if (picker.canTakePhoto) picker.takePhoto() else picker.pickFromGallery() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.CameraAlt, "Agregar foto", tint = MaterialTheme.colorScheme.primary) }
                    }
                    Text("Las fotos de golpes y rayones protegen al negocio si el cliente reclama después.", fontSize = 12.sp, color = AppTheme.extra.muted)
                    TextInput(d.notes, { d = d.copy(notes = it) }, "Notas internas (opcional)", singleLine = false, minLines = 2)
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MoneyInput(d.estimate.takeIf { it > 0 }, { d = d.copy(estimate = it ?: 0) }, "Presupuesto", Modifier.weight(1f))
                        MoneyInput(d.diagnosisFee, { d = d.copy(diagnosisFee = it ?: 0) }, "Diagnóstico", Modifier.weight(1f))
                    }
                    Text("El diagnóstico se cobra si el cliente no aprueba la reparación.", fontSize = 12.sp, color = AppTheme.extra.muted)
                    if (editId == null) {
                        MoneyInput(d.deposit.takeIf { it > 0 }, { d = d.copy(deposit = it ?: 0) }, "Abono (opcional)")
                        if (d.deposit > 0) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                PaymentMethods.all.forEachIndexed { i, m ->
                                    SegmentedButton(selected = depositMethod == m, onClick = { depositMethod = m }, shape = SegmentedButtonDefaults.itemShape(i, PaymentMethods.all.size)) { Text(m, fontSize = 12.sp) }
                                }
                            }
                            Text("El abono se registra como cobro en Caja y en Ventas.", fontSize = 12.sp, color = AppTheme.extra.muted)
                        }
                    }
                    SectionTitle("Entrega estimada")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Hoy", 1 to "Mañana", 2 to "2 días", 3 to "3 días", 7 to "1 semana", 15 to "15 días").forEach { (n, t) ->
                            FilterChip(selected = etaDays == n, onClick = { etaDays = n }, label = { Text(t) }, shape = RoundedCornerShape(10.dp))
                        }
                    }
                    val eta = etaDays?.let { Time.addDays(Time.startOfDay(), it) } ?: d.estimatedAt
                    eta?.let { Text("${Time.weekday(it).replaceFirstChar { c -> c.uppercase() }} ${Time.longDate(it)}", fontSize = 13.sp, color = AppTheme.extra.muted) }
                    SectionTitle("Técnico")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = d.technician.isBlank(), onClick = { d = d.copy(technician = "") }, label = { Text("Sin asignar") }, shape = RoundedCornerShape(10.dp))
                        techs.forEach { u -> FilterChip(selected = d.technician == u.name, onClick = { d = d.copy(technician = u.name) }, label = { Text(u.name) }, shape = RoundedCornerShape(10.dp)) }
                    }
                    if (techs.isEmpty()) TextInput(d.technician, { d = d.copy(technician = it) }, "Nombre del técnico (opcional)")
                    TextInput(d.warrantyDays.toString(), { d = d.copy(warrantyDays = it.filter(Char::isDigit).take(3).toIntOrNull() ?: 0) }, "Días de garantía de la reparación", keyboard = KeyboardType.Number)

                    AppCard {
                        Text("${d.clientName} · ${listOf(d.brand, d.model).filter { it.isNotBlank() }.joinToString(" ")}", fontWeight = FontWeight.Bold)
                        Text(d.problem, fontSize = 13.sp, color = AppTheme.extra.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (d.estimate > 0) {
                            Spacer(Modifier.height(6.dp))
                            KeyValue("Presupuesto", Money.format(d.estimate, s.currencySymbol))
                            if (d.deposit > 0) KeyValue("Abono", Money.format(d.deposit, s.currencySymbol))
                            if (d.deposit > 0) KeyValue("Saldo estimado", Money.format((d.estimate - d.deposit).coerceAtLeast(0), s.currencySymbol), strong = true)
                        }
                    }
                    if (editId == null) {
                        SectionTitle("Al recibir, imprimir")
                        AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth().clickable { printLabel = !printLabel }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Etiqueta para el equipo", fontWeight = FontWeight.SemiBold)
                                    Text(if (s.labelPrinterId.isNotBlank()) s.labelPrinterName else "Sin etiquetadora: se crea un PDF", fontSize = 12.sp, color = AppTheme.extra.muted)
                                }
                                Switch(printLabel, { printLabel = it })
                            }
                            Divider()
                            Row(Modifier.fillMaxWidth().clickable { printReceipt = !printReceipt }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Comprobante para el cliente", fontWeight = FontWeight.SemiBold)
                                    Text(if (s.printerId.isNotBlank()) "En ${s.printerName}" else "Elige la impresora de tickets en Configuración", fontSize = 12.sp, color = AppTheme.extra.muted)
                                }
                                Switch(printReceipt, { printReceipt = it }, enabled = s.printerId.isNotBlank())
                            }
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Button(
                onClick = {
                    showErrors = true
                    when (step) {
                        0 -> if (step1Ok) { step = 1; showErrors = false }
                        1 -> if (step2Ok) { step = 2; showErrors = false }
                        else -> if (step1Ok && step2Ok) save()
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = if (step == 2) ButtonDefaults.buttonColors(containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta) else ButtonDefaults.buttonColors(),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AppTheme.extra.onCta)
                else Text(
                    when {
                        step < 2 -> "Siguiente"
                        editId != null -> "Guardar cambios"
                        printLabel || printReceipt -> "Recibir e imprimir"
                        else -> "Recibir equipo"
                    },
                    fontWeight = FontWeight.Bold, fontSize = 16.sp,
                )
            }
        }
    }
    if (pickClient) {
        ClientPickerDialog(clients, onPick = { c ->
            pickClient = false
            if (c != null) d = d.copy(clientId = c.id, clientName = c.name, clientPhone = c.phone, clientEmail = c.email)
        }, onNew = { pickClient = false }, onDismiss = { pickClient = false })
    }
}

/** Patrón de desbloqueo: se tocan los puntos en orden (1 a 9). */
@Composable
private fun PatternInput(value: String, onChange: (String) -> Unit) {
    val seq = value.split('-').mapNotNull { it.toIntOrNull() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (0 until 3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    (1..3).forEach { col ->
                        val n = row * 3 + col
                        val idx = seq.indexOf(n)
                        Box(
                            Modifier.size(44.dp).clip(CircleShape)
                                .background(if (idx >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                                .clickable(enabled = idx < 0) { onChange((seq + n).joinToString("-")) },
                            contentAlignment = Alignment.Center,
                        ) { if (idx >= 0) Text("${idx + 1}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text("Toca los puntos en el orden del patrón.", fontSize = 13.sp, color = AppTheme.extra.muted)
            if (seq.isNotEmpty()) TextButton(onClick = { onChange("") }) { Text("Borrar patrón") }
        }
    }
}

// ================= Orden =================

@Composable
fun RepairDetailScreen(id: Long) {
    val app = LocalAppState.current
    val s = app.settings
    val scope = rememberSafeScope()
    val repairs by app.repo.repairs.collectAsState()
    val r = repairs.firstOrNull { it.id == id }
    val measurer = rememberTextMeasurer()
    val events by produceState(emptyList<com.vitrinapos.db.RepairEvent>(), r) { value = app.repo.repairEvents(id) }
    var changeStatus by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var receipt by remember { mutableStateOf(false) }
    var whatsapp by remember { mutableStateOf(false) }
    val deliveryTicket by produceState<Ticket?>(null, r?.saleId, r?.status) {
        val sid = r?.saleId
        value = if (r != null && sid != null && RepairStatus.isClosed(r.status)) {
            val sale = app.repo.saleById(sid)
            sale?.let { Ticket.forSale(s, it, app.repo.saleItems(sid), repair = r) }
        } else null
    }
    if (r == null) {
        Column { ScreenHeader("Orden", onBack = { app.back() }) }
        return
    }
    val late = r.daysLate()
    val sym = s.currencySymbol

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(r.code, subtitle = "Recibido ${Time.relativeDay(r.createdAt).lowercase()} · ${Time.time(r.createdAt)}", onBack = { app.back() }) {
            if (!RepairStatus.isClosed(r.status)) IconButton(onClick = { app.open(Screen.RepairReceive(r.id)) }) { Icon(Icons.Rounded.Edit, "Editar") }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(deviceIcon(r.deviceType), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.deviceName + if (r.color.isNotBlank()) " · ${r.color}" else "", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        if (r.serial.isNotBlank()) Text("${if (r.deviceType == DeviceTypes.PHONE) "IMEI" else "Serie"} ${r.serial}", fontSize = 13.sp, color = AppTheme.extra.muted)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RepairStatusPill(r.status)
                            if (late > 0) { Spacer(Modifier.width(6.dp)); Pill("Atrasado $late ${if (late == 1) "día" else "días"}", AppTheme.extra.danger, AppTheme.extra.dangerSoft) }
                            if (r.isAbandoned(s.repairAbandonDays)) { Spacer(Modifier.width(6.dp)); Pill("Abandonado · ${r.daysWaiting()} días", AppTheme.extra.danger, AppTheme.extra.dangerSoft) }
                        }
                    }
                }
            }
            StatusTrack(r.status)
            if (RepairStatus.isReady(r.status)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { app.open(Screen.RepairDeliver(r.id)) },
                        modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta),
                    ) { Text("Entregar y cobrar", fontWeight = FontWeight.Bold, maxLines = 1) }
                    OutlinedButton(onClick = { changeStatus = true }, modifier = Modifier.height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("Otro estado") }
                }
            } else if (!RepairStatus.isClosed(r.status)) {
                val next = RepairStatus.next(r.status)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (next != null) Button(
                        onClick = { scope.launch { app.repo.setRepairStatus(r.id, next); app.toast("${r.code}: ${RepairStatus.label(next)}") } },
                        modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp),
                    ) { Text("Pasar a: ${RepairStatus.short(next)}", fontWeight = FontWeight.Bold, maxLines = 1) }
                    OutlinedButton(onClick = { changeStatus = true }, modifier = Modifier.height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("Otro estado") }
                }
            }

            AppCard(onClick = r.clientId?.let { cid -> { app.open(Screen.ClientDetail(cid)) } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.clientName, fontWeight = FontWeight.SemiBold)
                        Text(listOf(r.clientPhone, r.clientEmail).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Sin contacto" }, fontSize = 13.sp, color = AppTheme.extra.muted)
                    }
                    FilledTonalButton(onClick = { whatsapp = true }, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.Chat, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("WhatsApp")
                    }
                }
            }

            AppCard(Modifier.fillMaxWidth()) {
                Text("Falla", fontSize = 12.sp, color = AppTheme.extra.muted)
                Text(r.problem, fontWeight = FontWeight.SemiBold)
                if (r.conditionText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Cómo llegó", fontSize = 12.sp, color = AppTheme.extra.muted)
                    Text(Condition.summary(r.conditionText), fontSize = 14.sp)
                }
                if (r.accessories.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Dejó: ${r.accessories.replace(",", ", ")}", fontSize = 14.sp)
                }
                if (r.unlockType != UnlockTypes.NONE && r.unlockCode.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${UnlockTypes.label(r.unlockType)}: ${if (showUnlock) r.unlockCode else "••••"}", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showUnlock = !showUnlock }) { Icon(Icons.Rounded.Visibility, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (showUnlock) "Ocultar" else "Ver") }
                    }
                }
                if (r.notes.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text("Notas: ${r.notes}", fontSize = 13.sp, color = AppTheme.extra.muted) }
            }
            if (r.photoList.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.photoList.forEach { ph -> ProductImage(ph, "Foto", Modifier.size(110.dp), RoundedCornerShape(12.dp), maxSide = 640) }
                }
            }
            if (RepairStatus.isClosed(r.status)) AppCard(color = MaterialTheme.colorScheme.primaryContainer) {
                r.deliveredAt?.let { KeyValue("Entregado", "${Time.shortDate(it)} · ${Time.time(it)}") }
                KeyValue("Total cobrado", Money.format(r.total, sym), strong = true)
                val until = r.warrantyUntil
                if (until != null) {
                    val active = Time.now() <= until
                    KeyValue("Garantía hasta", Time.longDate(until), valueColor = if (active) AppTheme.extra.ok else AppTheme.extra.danger)
                    Text(if (active) "Garantía vigente" else "Garantía vencida", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (active) AppTheme.extra.ok else AppTheme.extra.danger)
                    if (active) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { app.open(Screen.RepairReceive(warrantyOf = r.id)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Text("Recibir por garantía")
                        }
                    }
                } else Text("Entregado sin garantía", fontSize = 13.sp, color = AppTheme.extra.muted)
                r.saleId?.let { sid -> TextButton(onClick = { app.open(Screen.SaleDetail(sid)) }) { Text("Ver la venta") } }
            }
            AppCard {
                if (r.estimate > 0) KeyValue("Presupuesto", Money.format(r.estimate, sym))
                if (r.diagnosisFee > 0 && !RepairStatus.isClosed(r.status)) KeyValue("Diagnóstico", Money.format(r.diagnosisFee, sym))
                KeyValue("Abonado", Money.format(r.deposit, sym))
                r.estimatedAt?.let { KeyValue("Entrega estimada", Time.longDate(it), valueColor = if (late > 0) AppTheme.extra.danger else Color.Unspecified) }
                if (r.technician.isNotBlank()) KeyValue("Técnico", r.technician)
                if (r.receivedBy.isNotBlank()) KeyValue("Recibió", r.receivedBy)
                KeyValue("Garantía", "${r.warrantyDays} días")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { app.printRepairLabel(r, measurer) } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.QrCode2, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Etiqueta")
                }
                OutlinedButton(onClick = { receipt = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.Receipt, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Recibo")
                }
                OutlinedButton(
                    onClick = { app.platform.shareText((deliveryTicket ?: Ticket.forRepair(s, r)).preview(), "${r.code} · ${r.clientName}") },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Compartir") }
            }

            SectionTitle("Historial")
            AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                events.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(RepairStatus.label(e.status), fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull("${Time.relativeDay(e.createdAt)} ${Time.time(e.createdAt)}", e.userName.ifBlank { null }, e.note.ifBlank { null }).joinToString(" · "),
                                fontSize = 12.sp, color = AppTheme.extra.muted,
                            )
                        }
                    }
                }
            }
        }
    }
    if (whatsapp) WhatsAppDialog(r) { whatsapp = false }
    if (changeStatus) StatusDialog(r.status, onDismiss = { changeStatus = false }) { st, note ->
        changeStatus = false
        scope.launch { app.repo.setRepairStatus(r.id, st, note); app.toast("${r.code}: ${RepairStatus.label(st)}") }
    }
    if (receipt) {
        AlertDialog(
            onDismissRequest = { receipt = false },
            title = { Text(if (deliveryTicket != null) "Entrega ${r.code}" else "Comprobante ${r.code}") },
            text = {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color.White).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    TicketPreview(deliveryTicket ?: Ticket.forRepair(s, r), Modifier.padding(12.dp))
                }
            },
            confirmButton = { TextButton(onClick = { receipt = false; deliveryTicket?.let { app.print(it) } ?: app.printRepairReceipt(r) }) { Icon(Icons.Rounded.Print, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Imprimir") } },
            dismissButton = { TextButton(onClick = { receipt = false }) { Text("Cerrar") } },
        )
    }
}

@Composable
private fun StatusTrack(status: String) {
    // Recibido → (estado de trabajo actual) → Listo → Entregado. Se adapta a los estados que el negocio haya creado.
    val kind = RepairStatus.kind(status)
    val middle = if (RepairStatus.isPending(status) && status != RepairStatus.RECEIVED) RepairStatus.short(status) else "En proceso"
    val steps = listOf("Recibido", middle, RepairStatus.short(if (kind == StatusKind.NOT_REPAIRED) status else RepairStatus.READY), RepairStatus.short(RepairStatus.DELIVERED))
    val position = when {
        status == RepairStatus.RECEIVED -> 0
        RepairStatus.isPending(status) -> 1
        RepairStatus.isReady(status) -> 2
        else -> 3
    }
    val waiting = RepairStatus.isWaiting(status)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        steps.forEachIndexed { i, st ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val current = if (waiting) AppTheme.extra.warn else if (kind == StatusKind.NOT_REPAIRED) AppTheme.extra.danger else AppTheme.extra.cta
                Box(
                    Modifier.size(16.dp).clip(CircleShape).background(
                        when {
                            i < position -> MaterialTheme.colorScheme.primary
                            i == position -> current
                            else -> AppTheme.extra.line
                        },
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Text(st, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (i == position) current else AppTheme.extra.muted, fontWeight = if (i == position) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun StatusDialog(current: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var st by remember { mutableStateOf(current) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cambiar estado") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // "Entregado" solo se pone con el botón "Entregar y cobrar".
                RepairStatus.all.filter { !RepairStatus.isClosed(it) }.forEach { s ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { st = s }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(st == s, { st = s })
                        Text(RepairStatus.label(s))
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextInput(note, { note = it }, "Nota (opcional)", singleLine = false)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(st, note) }, enabled = st != current || note.isNotBlank()) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
