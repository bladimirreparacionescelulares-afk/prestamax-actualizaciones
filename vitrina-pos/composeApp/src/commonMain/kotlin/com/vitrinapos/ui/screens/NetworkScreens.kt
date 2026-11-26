package com.vitrinapos.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.Screen
import com.vitrinapos.data.NetRole
import com.vitrinapos.data.Roles
import com.vitrinapos.data.Time
import com.vitrinapos.db.AppUser
import com.vitrinapos.net.FoundServer
import com.vitrinapos.net.LinkState
import com.vitrinapos.net.NetInfo
import com.vitrinapos.net.SYNC_PORT
import com.vitrinapos.net.SyncClient
import com.vitrinapos.platform.rememberBarcodeScanner
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.Avatar
import com.vitrinapos.ui.components.ConfirmDialog
import com.vitrinapos.ui.components.Divider
import com.vitrinapos.ui.components.Pill
import com.vitrinapos.ui.components.QrCodeImage
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.components.rememberSafeScope
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Etiqueta pequeña con el estado de la conexión (para el encabezado de Inicio). */
@Composable
fun NetworkPill(onClick: () -> Unit) {
    val app = LocalAppState.current
    val graph = app.graph
    val ex = AppTheme.extra
    val client = graph.client
    val server = graph.server
    when {
        client != null -> {
            val st by client.status.collectAsState()
            val pending by app.repo.pendingSales.collectAsState()
            val (text, fg, bg) = when (st.state) {
                LinkState.ONLINE -> Triple("En línea", ex.ok, ex.okSoft)
                LinkState.CONNECTING -> Triple("Conectando…", ex.muted, MaterialTheme.colorScheme.surfaceContainer)
                LinkState.OFFLINE -> Triple(if (pending > 0) "Sin red · $pending pendientes" else "Sin red", ex.warn, ex.warnSoft)
                LinkState.REVOKED -> Triple("Sin acceso", ex.danger, ex.dangerSoft)
            }
            StatusChip(text, fg, bg, onClick)
        }
        server != null -> {
            val devices by produceState(0, server) { while (true) { value = server.devices.count { Time.now() - it.lastSeen < 60_000 && it.revoked == 0L }; delay(5_000) } }
            StatusChip(if (devices == 0) "Principal" else "Principal · $devices", ex.ok, ex.okSoft, onClick)
        }
    }
}

@Composable
private fun StatusChip(text: String, fg: Color, bg: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(6.dp))
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Aviso cuando un equipo conectado se quedó sin red. */
@Composable
fun OfflineBanner() {
    val app = LocalAppState.current
    val client = app.graph.client ?: return
    val st by client.status.collectAsState()
    val pending by app.repo.pendingSales.collectAsState()
    if (st.state != LinkState.OFFLINE && st.state != LinkState.REVOKED) return
    val revoked = st.state == LinkState.REVOKED
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (revoked) AppTheme.extra.dangerSoft else AppTheme.extra.warnSoft).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.WifiOff, null, tint = if (revoked) AppTheme.extra.danger else AppTheme.extra.warn)
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                revoked -> "El equipo principal quitó el acceso a este equipo. Pide que lo vuelvan a unir."
                pending > 0 -> "Sin conexión con el equipo principal. Puedes seguir vendiendo: $pending ${if (pending == 1L) "venta se enviará" else "ventas se enviarán"} al volver la red."
                else -> "Sin conexión con el equipo principal. Puedes seguir vendiendo; las ventas se enviarán al volver la red."
            },
            color = if (revoked) AppTheme.extra.danger else AppTheme.extra.warn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

// ================= Trabajo en red =================

@Composable
fun NetworkScreen(onClose: (() -> Unit)? = null) {
    val app = LocalAppState.current
    val graph = app.graph
    val scope = rememberSafeScope()
    val role = app.repo.meta.collectAsState().value["role"] ?: NetRole.SOLO
    var connecting by remember { mutableStateOf(onClose != null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf(graph.deviceName) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Trabajo en red", subtitle = "Varios equipos con los mismos datos", onBack = { onClose?.invoke() ?: app.back() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextInput(name, { name = it.take(40); graph.setDeviceName(name) }, "Nombre de este equipo", placeholder = app.platform.defaultDeviceName)

            if (!connecting) {
                SectionTitle("¿Cómo trabaja este equipo?")
                ModeCard(Icons.Rounded.PhoneAndroid, "Solo", "Los datos se quedan en este equipo.", role == NetRole.SOLO) {
                    if (role != NetRole.SOLO) confirm = NetRole.SOLO
                }
                ModeCard(Icons.Rounded.Computer, "Equipo principal", "Guarda los datos del negocio y los demás se conectan a él. Lo ideal es la PC de la caja, encendida mientras el local está abierto.", role == NetRole.SERVER) {
                    if (role != NetRole.SERVER) confirm = NetRole.SERVER
                }
                ModeCard(Icons.Rounded.Lan, "Conectado a un principal", "Usa los datos de otro equipo de la red. Si se cae el Wi-Fi, sigue vendiendo y envía las ventas después.", role == NetRole.CLIENT) {
                    if (role != NetRole.CLIENT) connecting = true
                }
            }

            when {
                connecting -> ConnectPanel(onCancel = { if (onClose != null) onClose() else connecting = false }, onDone = { connecting = false; onClose?.invoke() })
                role == NetRole.SERVER -> ServerPanel()
                role == NetRole.CLIENT -> ClientPanel(onLeave = { confirm = NetRole.SOLO })
                else -> Text(
                    "Para que otros celulares o computadoras usen estos mismos datos, elige \"Equipo principal\" aquí, y en los demás equipos elige \"Conectado a un principal\".",
                    color = AppTheme.extra.muted, fontSize = 14.sp,
                )
            }
        }
    }

    when (confirm) {
        NetRole.SERVER -> ConfirmDialog(
            "Usar como equipo principal",
            "Este equipo guardará los datos y los demás se conectarán a él por la red del local. Debe estar encendido y con la app abierta para que los demás trabajen conectados.",
            "Activar",
            onConfirm = { scope.launch { withContext(app.platform.io) { graph.becomeServer() }; app.toast("Este equipo ahora es el principal") } },
            onDismiss = { confirm = null },
        )
        NetRole.SOLO -> ConfirmDialog(
            "Trabajar solo",
            if (role == NetRole.CLIENT) "Este equipo deja de recibir los cambios del principal y se queda con una copia de los datos de este momento."
            else "Los equipos conectados ya no podrán trabajar con los datos de este equipo hasta que vuelvas a activarlo como principal.",
            "Trabajar solo",
            onConfirm = { graph.becomeSolo(); app.toast("Este equipo ahora trabaja solo") },
            onDismiss = { confirm = null },
            destructive = true,
        )
    }
}

@Composable
private fun ModeCard(icon: ImageVector, title: String, text: String, selected: Boolean, onClick: () -> Unit) {
    AppCard(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(text, fontSize = 13.sp, color = AppTheme.extra.muted)
            }
            if (selected) Pill("Actual", MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ServerPanel() {
    val app = LocalAppState.current
    val server = app.graph.server ?: return
    val code by server.pairCode.collectAsState()
    val ips = remember { NetInfo.localAddresses() }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); tick++ } }
    val devices = remember(tick, code) { server.devices }
    var menuFor by remember { mutableStateOf<com.vitrinapos.db.Device?>(null) }

    SectionTitle("Unir otro equipo")
    AppCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ips.isEmpty()) {
                Text("Este equipo no está conectado a ninguna red Wi-Fi o de cable.", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            } else {
                Text("En el otro equipo abre Trabajo en red → Conectado a un principal, y escanea este código:", color = AppTheme.extra.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                QrCodeImage(code.qrText(ips.first()), Modifier.size(200.dp))
                Text("o escribe este código", color = AppTheme.extra.muted, fontSize = 13.sp)
                Text(code.code.chunked(3).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text("Dirección: ${ips.joinToString(" · ")}", color = AppTheme.extra.muted, fontSize = 13.sp)
                TextButton(onClick = { server.refreshCode() }) { Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Nuevo código") }
                Text("El código cambia cada 10 minutos y después de cada uso.", color = AppTheme.extra.muted, fontSize = 12.sp)
            }
        }
    }
    SectionTitle("Equipos conectados · ${devices.count { it.revoked == 0L }}")
    if (devices.isEmpty()) Text("Todavía no se ha unido ningún equipo.", color = AppTheme.extra.muted)
    else AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
        devices.forEachIndexed { i, d ->
            if (i > 0) Divider()
            val online = Time.now() - d.lastSeen < 60_000 && d.revoked == 0L
            Row(Modifier.fillMaxWidth().clickable { menuFor = d }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (d.platform == "Android") Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            d.revoked == 1L -> "Sin acceso"
                            online -> "En línea · ${d.platform}"
                            else -> "Visto ${Time.relativeDay(d.lastSeen).lowercase()} ${Time.time(d.lastSeen)}"
                        },
                        fontSize = 12.sp, color = AppTheme.extra.muted,
                    )
                }
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (online) AppTheme.extra.ok else AppTheme.extra.line))
            }
        }
    }
    Text("Para trabajar conectados, todos los equipos deben estar en la misma red del local. No hace falta internet.", color = AppTheme.extra.muted, fontSize = 13.sp)
    if (app.platform.isDesktop) {
        Text(
            "En Windows: si aparece el aviso del Firewall, elige \"Permitir acceso\" en redes privadas. Si los demás equipos no lo encuentran, revisa que la red Wi-Fi esté marcada como Privada.",
            color = AppTheme.extra.warn, fontSize = 13.sp,
        )
    }

    menuFor?.let { d ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(d.name) },
            text = { Text(if (d.revoked == 1L) "Este equipo ya no tiene acceso." else "Si quitas el acceso, este equipo dejará de ver y cambiar los datos. Úsalo si se pierde o se cambia un equipo.") },
            confirmButton = {
                if (d.revoked == 0L) TextButton(onClick = { server.revoke(d.id); menuFor = null; tick++; app.toast("Acceso quitado a ${d.name}") }) {
                    Text("Quitar acceso", color = MaterialTheme.colorScheme.error)
                } else TextButton(onClick = { server.forget(d.id); menuFor = null; tick++ }) { Text("Quitar de la lista") }
            },
            dismissButton = { TextButton(onClick = { menuFor = null }) { Text("Cerrar") } },
        )
    }
}

@Composable
private fun ClientPanel(onLeave: () -> Unit) {
    val app = LocalAppState.current
    val client = app.graph.client ?: return
    val scope = rememberSafeScope()
    val st by client.status.collectAsState()
    val pending by app.repo.pendingSales.collectAsState()
    val meta by app.repo.meta.collectAsState()
    SectionTitle("Conexión")
    AppCard {
        val (label, color) = when (st.state) {
            LinkState.ONLINE -> "En línea" to AppTheme.extra.ok
            LinkState.CONNECTING -> "Conectando…" to AppTheme.extra.muted
            LinkState.OFFLINE -> "Sin conexión" to AppTheme.extra.warn
            LinkState.REVOKED -> "Sin acceso" to AppTheme.extra.danger
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Text(label, fontWeight = FontWeight.Bold, color = color, fontSize = 17.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text("Principal: ${meta["serverName"] ?: client.host}", fontWeight = FontWeight.SemiBold)
        Text("Dirección: ${client.host}", color = AppTheme.extra.muted, fontSize = 13.sp)
        if (st.lastSync > 0) Text("Última sincronización: ${Time.time(st.lastSync)}", color = AppTheme.extra.muted, fontSize = 13.sp)
        if (pending > 0) Text("Ventas pendientes de enviar: $pending", color = AppTheme.extra.warn, fontWeight = FontWeight.SemiBold)
        if (st.message.isNotBlank() && st.state != LinkState.ONLINE) Text(st.message, color = AppTheme.extra.muted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { scope.launch { client.flushOutbox(); client.pull(); app.toast("Datos al día") } }, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sincronizar ahora")
            }
            OutlinedButton(onClick = onLeave, shape = RoundedCornerShape(12.dp)) { Text("Trabajar solo") }
        }
    }
    Text("Cada equipo imprime en su propia impresora. Configúrala en Configuración → Impresora.", color = AppTheme.extra.muted, fontSize = 13.sp)
}

@Composable
private fun ConnectPanel(onCancel: () -> Unit, onDone: () -> Unit) {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    var host by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchKey by remember { mutableStateOf(0) }
    var askReplace by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    val found by produceState<List<FoundServer>?>(null, searchKey) {
        value = null
        value = withContext(app.platform.io) { SyncClient.discover(2000) }
    }
    val hasData = app.repo.products.collectAsState().value.isNotEmpty() || app.repo.recentSales.collectAsState().value.isNotEmpty()

    fun connect(h: String, port: Int, secret: String) {
        busy = true
        error = null
        scope.launch {
            try {
                withContext(app.platform.io) { app.graph.connectTo(h.trim(), port, secret.trim()) }
                app.toast("Conectado. Los datos del principal ya están en este equipo.")
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "No se pudo conectar"
            } finally {
                busy = false
            }
        }
    }

    fun start(h: String, port: Int, secret: String) {
        if (hasData) askReplace = Triple(h, port, secret) else connect(h, port, secret)
    }

    val scanner = rememberBarcodeScanner { text ->
        val parsed = SyncClient.parseQr(text)
        if (parsed == null) error = "Ese código no es de Vitrina POS. Escanea el QR que aparece en el equipo principal."
        else { host = parsed.first; start(parsed.first, parsed.second, parsed.third) }
    }

    SectionTitle("Conectarse a un equipo principal")
    if (scanner != null) {
        Button(onClick = scanner, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Rounded.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Escanear el QR del principal", fontWeight = FontWeight.Bold)
        }
        Text("o elige el equipo y escribe el código:", color = AppTheme.extra.muted, fontSize = 13.sp)
    }
    AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Equipos principales en esta red", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (found == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else TextButton(onClick = { searchKey++ }) { Text("Buscar") }
        }
        val list = found
        if (list != null && list.isEmpty()) Text("No se encontró ninguno. Revisa que estén en el mismo Wi-Fi, o escribe la dirección abajo.", color = AppTheme.extra.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        list?.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { host = s.host }
                    .background(if (host == s.host) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Devices, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.info.name, fontWeight = FontWeight.SemiBold)
                    Text("${s.host} · ${s.info.platform}", fontSize = 12.sp, color = AppTheme.extra.muted)
                }
            }
        }
    }
    TextInput(host, { host = it.trim() }, "Dirección del principal", keyboard = KeyboardType.Uri, placeholder = "192.168.1.20")
    TextInput(code, { code = it.filter(Char::isDigit).take(6) }, "Código de 6 números que muestra el principal", keyboard = KeyboardType.Number)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Cancelar") }
        Button(
            onClick = { start(host, SYNC_PORT, code) },
            enabled = !busy && host.isNotBlank() && code.length == 6,
            modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("Conectar", fontWeight = FontWeight.Bold)
        }
    }
    askReplace?.let { (h, p, s) ->
        ConfirmDialog(
            "Reemplazar los datos de este equipo",
            "Este equipo ya tiene productos o ventas. Al conectarlo, se usarán los datos del principal y los de aquí dejarán de verse. Si los necesitas, haz un respaldo antes.",
            "Conectar",
            onConfirm = { connect(h, p, s) },
            onDismiss = { askReplace = null },
            destructive = true,
        )
    }
}

// ================= Usuarios =================

@Composable
fun UsersScreen() {
    val app = LocalAppState.current
    val users by app.repo.users.collectAsState()
    val current = app.user
    var editing by remember { mutableStateOf<AppUser?>(null) }
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Usuarios", subtitle = "${users.count { it.active == 1L }} activos", onBack = { app.back() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (users.isEmpty()) {
                AppCard(color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("Usuarios con PIN", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Cada persona entra con su nombre y su PIN. Así queda registrado quién hizo cada venta, quién anuló o quién cambió el estado de una reparación. " +
                            "El primer usuario será el Administrador.",
                        fontSize = 14.sp,
                    )
                }
            }
            users.forEach { u ->
                AppCard(onClick = { app.open(Screen.UserDetail(u.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(u.name, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.name + if (current?.id == u.id) " (tú)" else "", fontWeight = FontWeight.SemiBold)
                            Text(
                                Roles.label(u.role) + if (u.role != Roles.ADMIN && u.permissions.isNotBlank()) " · permisos personalizados" else "",
                                fontSize = 13.sp, color = AppTheme.extra.muted,
                            )
                        }
                        if (u.active == 0L) Pill("Inactivo", AppTheme.extra.muted, MaterialTheme.colorScheme.surfaceContainer)
                    }
                }
            }
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Rounded.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text(if (users.isEmpty()) "Crear el administrador" else "Nuevo usuario")
            }
            if (current != null) {
                OutlinedButton(onClick = { app.logout() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Cambiar de usuario") }
            }
            Text("Toca un usuario para cambiar sus datos y encender o apagar lo que puede ver y hacer.", fontSize = 13.sp, color = AppTheme.extra.muted)
            SectionTitle("Qué puede hacer cada rol")
            Roles.all.forEach { r ->
                Column(Modifier.padding(vertical = 2.dp)) {
                    Text(Roles.label(r), fontWeight = FontWeight.SemiBold)
                    Text(Roles.description(r), color = AppTheme.extra.muted, fontSize = 13.sp)
                }
            }
        }
    }
    if (creating || editing != null) {
        UserDialog(editing, firstUser = users.isEmpty(), users = users, onDismiss = { creating = false; editing = null })
    }
}

@Composable
internal fun UserDialog(existing: AppUser?, firstUser: Boolean, users: List<AppUser>, onDismiss: () -> Unit) {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var role by remember { mutableStateOf(existing?.role ?: if (firstUser) Roles.ADMIN else Roles.SELLER) }
    var pin by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(existing?.active != 0L) }
    val otherAdmins = users.count { it.active == 1L && it.role == Roles.ADMIN && it.id != existing?.id }
    val lastAdmin = existing != null && existing.role == Roles.ADMIN && otherAdmins == 0
    val pinOk = if (existing == null) pin.length in 4..8 else pin.isEmpty() || pin.length in 4..8
    val pinTaken = pin.length >= 4 && users.any { it.id != existing?.id && it.active == 1L && app.repo.checkPin(it, pin) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) (if (firstUser) "Administrador" else "Nuevo usuario") else "Editar usuario") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextInput(name, { name = it.take(40) }, "Nombre o identificación")
                if (!firstUser && !lastAdmin) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Roles.all.forEachIndexed { i, r ->
                            SegmentedButton(selected = role == r, onClick = { role = r }, shape = SegmentedButtonDefaults.itemShape(i, Roles.all.size)) {
                                Text(Roles.label(r), fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                    Text(Roles.description(role), fontSize = 12.sp, color = AppTheme.extra.muted)
                } else if (lastAdmin) {
                    Text("Es el único administrador: no se puede cambiar su rol ni desactivarlo.", fontSize = 12.sp, color = AppTheme.extra.muted)
                }
                TextInput(
                    pin, { pin = it.filter(Char::isDigit).take(8) },
                    if (existing == null) "PIN (4 a 8 números)" else "PIN nuevo (vacío para no cambiarlo)",
                    keyboard = KeyboardType.NumberPassword,
                    error = if (pinTaken) "Otro usuario ya usa ese PIN" else null,
                )
                if (existing != null && !lastAdmin) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Activo", Modifier.weight(1f))
                        Switch(active, { active = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && pinOk && !pinTaken,
                onClick = {
                    scope.launch {
                        val id = app.repo.saveUser(existing?.id, name, pin.ifBlank { null }, if (lastAdmin) Roles.ADMIN else role, active || lastAdmin)
                        // Quien crea el primer usuario entra con él, para no quedarse fuera.
                        if (firstUser) app.repo.users.value.firstOrNull { it.id == id }?.let { app.repo.currentUser.value = it }
                            ?: run { app.repo.currentUser.value = app.repo.db.appUserQueries.selectById(id).executeAsOneOrNull() }
                        app.toast("Usuario guardado")
                        onDismiss()
                    }
                },
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Ficha del usuario: sus datos y lo que puede ver y hacer, con un interruptor por módulo. */
@Composable
fun UserDetailScreen(id: Long) {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    val users by app.repo.users.collectAsState()
    val u = users.firstOrNull { it.id == id }
    var editing by remember { mutableStateOf(false) }
    if (u == null) { Column { ScreenHeader("Usuario", onBack = { app.back() }) }; return }
    val admin = u.role == Roles.ADMIN
    val granted = com.vitrinapos.data.Permissions.effective(u.role, u.permissions)
    val custom = !admin && u.permissions.isNotBlank()

    fun set(p: com.vitrinapos.data.Permission, on: Boolean) = scope.launch {
        val next = if (on) granted + p else granted - p
        runCatching { app.repo.setUserPermissions(u.id, next) }.onFailure { app.toast(it.message ?: "No se pudo guardar") }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(u.name, subtitle = Roles.label(u.role) + if (u.active == 0L) " · inactivo" else "", onBack = { app.back() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppCard(onClick = { editing = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u.name, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u.name, fontWeight = FontWeight.SemiBold)
                        Text("${Roles.label(u.role)} · ${if (u.active == 1L) "activo" else "inactivo"}", fontSize = 13.sp, color = AppTheme.extra.muted)
                    }
                    Text("Editar", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("Nombre, rol, PIN y si está activo.", fontSize = 12.sp, color = AppTheme.extra.muted)
            SectionTitle("Qué puede ver y hacer")
            if (admin) {
                AppCard(color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("Los administradores tienen acceso a todo.", fontWeight = FontWeight.SemiBold)
                    Text("Para limitar a esta persona, cámbiale el rol a Vendedor o Técnico y luego apaga lo que no deba usar.", fontSize = 13.sp)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (custom) "Permisos personalizados" else "Permisos del rol ${Roles.label(u.role)}",
                        fontSize = 13.sp, color = AppTheme.extra.muted, modifier = Modifier.weight(1f),
                    )
                    if (custom) TextButton(onClick = { scope.launch { app.repo.setUserPermissions(u.id, null); app.toast("Permisos del rol restablecidos") } }) {
                        Text("Volver a los del rol")
                    }
                }
                AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                    com.vitrinapos.data.Permission.entries.forEachIndexed { i, p ->
                        if (i > 0) Divider()
                        Row(Modifier.fillMaxWidth().clickable { set(p, p !in granted) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.title, fontWeight = FontWeight.SemiBold)
                                Text(p.detail, fontSize = 12.sp, color = AppTheme.extra.muted)
                            }
                            Switch(p in granted, { on -> set(p, on) })
                        }
                    }
                }
                Text("Los cambios se aplican al momento, también en los otros equipos conectados.", fontSize = 12.sp, color = AppTheme.extra.muted)
            }
        }
    }
    if (editing) UserDialog(u, firstUser = false, users = users, onDismiss = { editing = false })
}
