package com.vitrinapos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddBusiness
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.Screen
import com.vitrinapos.data.MAIN_BRANCH
import com.vitrinapos.data.Money
import com.vitrinapos.data.fmtQty
import com.vitrinapos.data.Roles
import com.vitrinapos.data.Time
import com.vitrinapos.db.Branch
import com.vitrinapos.net.Device
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.Avatar
import com.vitrinapos.ui.components.Pill
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.components.rememberSafeScope
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch

/** Más → Sucursales. En la computadora, la lista y el detalle lado a lado. */
@Composable
fun BranchesScreen() {
    val app = LocalAppState.current
    val branches by app.repo.branches.collectAsState()
    var selected by remember { mutableStateOf<Long?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        if (wide) Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                BranchList(selected ?: app.repo.branchId) { selected = it }
            }
            Box(Modifier.weight(1.2f).fillMaxHeight()) {
                val id = selected ?: app.repo.branchId
                if (branches.any { it.id == id }) BranchDetailScreen(id, embedded = true)
            }
        } else BranchList(null) { app.open(Screen.BranchDetail(it)) }
    }
}

@Composable
private fun BranchList(selected: Long?, onOpen: (Long) -> Unit) {
    val app = LocalAppState.current
    val branches by app.repo.branches.collectAsState()
    val cloud = app.graph.cloud
    val plan = cloud?.plan?.collectAsState()?.value
    var sales by remember { mutableStateOf<Map<Long, Pair<Long, Long>>>(emptyMap()) }
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    val users by app.repo.users.collectAsState()
    val sym = app.settings.currencySymbol
    LaunchedEffect(branches) {
        val start = Time.startOfDay(Time.now())
        sales = app.repo.salesByBranch(start, start + 86_400_000L)
        devices = runCatching { cloud?.devices().orEmpty() }.getOrDefault(emptyList()).filter { !it.revoked }
    }
    val active = branches.filter { it.active == 1L }
    val maxBranches = plan?.maxBranches ?: 1
    val canAdd = cloud != null && (maxBranches <= 0 || active.size < maxBranches)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Sucursales", subtitle = listOfNotNull(app.settings.businessName, plan?.let { "Plan " + it.planName }).joinToString(" · "), onBack = { app.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cloud == null) AppCard(color = AppTheme.extra.warnSoft) {
                Text("Las sucursales funcionan con la nube", fontWeight = FontWeight.Bold, color = AppTheme.extra.warn)
                Text("Cada sucursal tiene sus propios equipos en otro lugar: para eso los datos tienen que estar en la nube. " +
                    "Pasa tus datos en Más → Trabajo en red → En la nube.", fontSize = 13.sp, color = AppTheme.extra.warn)
                if (app.allows(Screen.Network)) TextButton(onClick = { app.open(Screen.Network) }) { Text("Ir a Trabajo en red") }
            }
            branches.sortedWith(compareBy({ it.active == 0L }, { it.id })).forEach { b ->
                val (count, total) = sales[b.id] ?: (0L to 0L)
                val devs = devices.count { it.branchId == b.id }
                val ppl = users.count { it.active == 1L && app.repo.branchesOf(it).let { l -> l.isEmpty() || b.id in l } }
                AppCard(
                    onClick = { onOpen(b.id) },
                    color = if (selected == b.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Store, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            if (b.address.isNotBlank()) Text(b.address, fontSize = 12.sp, color = AppTheme.extra.muted)
                            Text(
                                if (b.active == 0L) "Desactivada"
                                else listOfNotNull(
                                    "Hoy ${Money.format(total, sym)}" + if (count > 0) " · $count ${if (count == 1L) "venta" else "ventas"}" else "",
                                    if (cloud != null) "$devs ${if (devs == 1) "equipo" else "equipos"}" else null,
                                    "$ppl ${if (ppl == 1) "usuario" else "usuarios"}",
                                ).joinToString(" · "),
                                fontSize = 12.sp, color = AppTheme.extra.muted,
                            )
                        }
                        if (b.id == app.repo.branchId) Pill("Este equipo", AppTheme.extra.ok, AppTheme.extra.okSoft)
                    }
                }
            }
            if (cloud != null) {
                Button(onClick = { creating = true }, enabled = canAdd, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.AddBusiness, null); Spacer(Modifier.width(8.dp)); Text("Nueva sucursal")
                }
                if (!canAdd) Text("Tu plan permite $maxBranches ${if (maxBranches == 1) "sucursal" else "sucursales"}. Para agregar otra, cambia de plan.", fontSize = 13.sp, color = AppTheme.extra.warn)
            }
            Text("Productos, precios y clientes son los mismos en todas. Cada sucursal tiene su propio inventario, caja y ventas.", fontSize = 13.sp, color = AppTheme.extra.muted)
        }
    }
    if (creating) BranchDialog(null, onDone = { id -> creating = false; if (id != null) onOpen(id) })
}

/** Crear o editar una sucursal. */
@Composable
fun BranchDialog(existing: Branch?, onDone: (Long?) -> Unit) {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var address by remember { mutableStateOf(existing?.address.orEmpty()) }
    var phone by remember { mutableStateOf(existing?.phone.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone(null) },
        title = { Text(if (existing == null) "Nueva sucursal" else "Editar sucursal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextInput(name, { name = it.take(40) }, "Nombre *", placeholder = "Sucursal Norte")
                TextInput(address, { address = it.take(80) }, "Dirección")
                TextInput(phone, { phone = it.take(20) }, "Teléfono (sale en el ticket)", keyboard = KeyboardType.Phone)
                if (existing == null) Text("Su inventario empieza en cero. Luego registras una compra o una entrada en esa sucursal.", fontSize = 12.sp, color = AppTheme.extra.muted)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { app.repo.saveBranch(existing?.id, name, address, phone, existing?.active != 0L) }
                        .onSuccess { app.toast(if (existing == null) "Sucursal creada" else "Sucursal guardada"); onDone(it) }
                        .onFailure { error = cloudError(it) }
                    busy = false
                }
            }) { Text(if (existing == null) "Crear sucursal" else "Guardar") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { onDone(null) }) { Text("Cancelar") } },
    )
}

/** Detalle de una sucursal: ventas de hoy, equipos, usuarios, existencias bajas y datos del ticket. */
@Composable
fun BranchDetailScreen(id: Long, embedded: Boolean = false) {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    val branches by app.repo.branches.collectAsState()
    val b = branches.firstOrNull { it.id == id } ?: return
    val cloud = app.graph.cloud
    val users by app.repo.users.collectAsState()
    var sales by remember(id) { mutableStateOf(0L to 0L) }
    var stock by remember(id) { mutableStateOf(0L to 0.0) }
    var low by remember(id) { mutableStateOf<List<com.vitrinapos.db.LowStockByBranch>>(emptyList()) }
    var devices by remember(id) { mutableStateOf<List<Device>?>(null) }
    var allDevices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    var moveMenu by remember { mutableStateOf(false) }
    val sym = app.settings.currencySymbol
    fun load() = scope.launch {
        val start = Time.startOfDay(Time.now())
        sales = app.repo.salesByBranch(start, start + 86_400_000L)[id] ?: (0L to 0L)
        stock = app.repo.stockSummaryByBranch()[id] ?: (0L to 0.0)
        low = app.repo.lowStockAt(id)
        val all = runCatching { cloud?.devices().orEmpty() }.getOrNull()?.filter { !it.revoked }
        allDevices = all.orEmpty()
        devices = all?.filter { it.branchId == id }
    }
    LaunchedEffect(id, branches) { load() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            b.name, subtitle = b.address.ifBlank { null }, onBack = if (embedded) null else ({ app.back() }),
            actions = { if (app.can(com.vitrinapos.data.Permission.SETTINGS)) TextButton(onClick = { editing = true }) { Text("Editar") } },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (b.active == 0L) Pill("Desactivada", AppTheme.extra.muted, MaterialTheme.colorScheme.surfaceContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppCard(Modifier.weight(1f)) { Text(Money.format(sales.second, sym), fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("Ventas hoy (${sales.first})", fontSize = 12.sp, color = AppTheme.extra.muted) }
                AppCard(Modifier.weight(1f)) { Text(stock.first.toString(), fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("Productos con existencia", fontSize = 12.sp, color = AppTheme.extra.muted) }
            }
            if (cloud != null) {
                SectionTitle("Equipos", action = if (app.can(com.vitrinapos.data.Permission.SETTINGS) && allDevices.any { it.branchId != id }) "Traer un equipo" else null, onAction = { moveMenu = true })
                Box {
                    DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                        allDevices.filter { it.branchId != id }.forEach { d ->
                            DropdownMenuItem(
                                text = { Text("${d.name} (hoy en ${branches.firstOrNull { it.id == d.branchId }?.name ?: "otra"})") },
                                onClick = {
                                    moveMenu = false
                                    scope.launch {
                                        runCatching { cloud.setDeviceBranch(d.id, id) }
                                            .onSuccess { app.toast("${d.name} ahora está en ${b.name}"); load() }
                                            .onFailure { app.toast(cloudError(it)) }
                                    }
                                },
                            )
                        }
                    }
                }
                when (val list = devices) {
                    null -> Text("Sin conexión con la nube: no se pueden ver los equipos.", fontSize = 13.sp, color = AppTheme.extra.muted)
                    else -> if (list.isEmpty()) Text("Sin equipos todavía. En el equipo nuevo, entra con la cuenta y elige esta sucursal.", fontSize = 13.sp, color = AppTheme.extra.muted)
                    else list.forEach { d ->
                        AppCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (d.platform.contains("Android", true)) Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.name, fontWeight = FontWeight.SemiBold)
                                    Text(listOf(d.platform, d.lastUse?.let { "usado ${Time.dateTime(it)}" } ?: "sin usar").filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = AppTheme.extra.muted)
                                }
                                if (d.id == cloud.deviceId) Pill("Este equipo", AppTheme.extra.ok, AppTheme.extra.okSoft)
                            }
                        }
                    }
                }
            }
            SectionTitle("Usuarios", action = if (app.allows(Screen.Users)) "Asignar" else null, onAction = { app.open(Screen.Users) })
            val here = users.filter { it.active == 1L && app.repo.worksAt(it, id) }
            if (here.isEmpty()) Text("Nadie asignado todavía.", fontSize = 13.sp, color = AppTheme.extra.muted)
            here.forEach { u ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(u.name, 36.dp); Spacer(Modifier.width(10.dp))
                        Text(u.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(Roles.label(u.role) + if (app.repo.branchesOf(u).isEmpty()) " · todas" else "", fontSize = 13.sp, color = AppTheme.extra.muted)
                    }
                }
            }
            SectionTitle("Existencia baja en esta sucursal")
            if (low.isEmpty()) Text("Todo bien.", fontSize = 13.sp, color = AppTheme.extra.muted)
            low.forEach { p ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(listOf(p.name, p.variantName).filter { it.isNotBlank() }.joinToString(" · "), modifier = Modifier.weight(1f))
                        Pill("${p.qty.fmtQty()} und", AppTheme.extra.warn, AppTheme.extra.warnSoft)
                    }
                }
            }
            SectionTitle("Datos del ticket")
            AppCard {
                Text(app.settings.businessName + if (b.id != MAIN_BRANCH) " · ${b.name}" else "", fontWeight = FontWeight.SemiBold)
                Text(listOf(b.phone.takeIf { it.isNotBlank() }?.let { "Tel. $it" }, b.address).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Sin teléfono ni dirección" },
                    fontSize = 12.sp, color = AppTheme.extra.muted)
            }
            if (b.id != MAIN_BRANCH && app.can(com.vitrinapos.data.Permission.SETTINGS)) {
                OutlinedButton(onClick = { if (b.active == 1L) confirmOff = true else scope.launch {
                    runCatching { app.repo.saveBranch(b.id, b.name, b.address, b.phone, true) }.onSuccess { app.toast("Sucursal activada") }.onFailure { app.toast(cloudError(it)) }
                } }, Modifier.fillMaxWidth()) {
                    Text(if (b.active == 1L) "Desactivar sucursal" else "Activar sucursal", color = if (b.active == 1L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (editing) BranchDialog(b) { editing = false }
    if (confirmOff) com.vitrinapos.ui.components.ConfirmDialog(
        "Desactivar ${b.name}", "Deja de aparecer para vender. Sus ventas y su historial se conservan. Antes, pasa su mercancía a otra sucursal.",
        "Desactivar",
        onConfirm = { scope.launch { runCatching { app.repo.saveBranch(b.id, b.name, b.address, b.phone, false) }.onSuccess { app.toast("Sucursal desactivada") }.onFailure { app.toast(cloudError(it)) } } },
        onDismiss = { confirmOff = false }, destructive = true,
    )
}

/** Etiqueta con la sucursal de este equipo (si el negocio tiene más de una). */
@Composable
fun BranchPill() {
    val app = LocalAppState.current
    val branches by app.repo.branches.collectAsState()
    val meta by app.repo.meta.collectAsState()
    if (branches.count { it.active == 1L } < 2) return
    val b = branches.firstOrNull { it.id == (meta["branchId"]?.toLongOrNull() ?: MAIN_BRANCH) } ?: return
    Pill(b.name, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
}
