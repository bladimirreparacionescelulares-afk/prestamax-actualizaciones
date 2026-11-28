package com.vitrinapos.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.automirrored.rounded.Logout
import com.vitrinapos.net.NewBusinessRequest
import com.vitrinapos.net.BusinessChoice
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Business
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.data.BusinessType
import com.vitrinapos.data.Time
import com.vitrinapos.net.ChangePasswordRequest
import com.vitrinapos.net.CloudClient
import com.vitrinapos.net.Device
import com.vitrinapos.net.Entitlements
import com.vitrinapos.net.HttpStatusException
import com.vitrinapos.net.LinkState
import com.vitrinapos.net.ResetRequest
import com.vitrinapos.net.SignupRequest
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.ConfirmDialog
import com.vitrinapos.ui.components.Pill
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.components.rememberSafeScope
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Qué se hace en la pantalla de cuenta de la nube. */
enum class CloudMode { LOGIN, SIGNUP, MIGRATE, FORGOT, CHOOSE, NEW_BUSINESS }

/** Mensaje claro para errores de la nube. */
fun cloudError(e: Throwable): String = when (e) {
    is HttpStatusException -> e.message ?: "La nube no aceptó el pedido"
    is java.io.IOException -> "No hay conexión con la nube. Revisa el internet e intenta de nuevo."
    else -> e.message ?: "Algo salió mal"
}

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** Problema de la contraseña nueva (las mismas reglas que pide la nube). */
fun passwordProblem(p: String): String? = when {
    p.length < 8 -> "La contraseña lleva al menos 8 caracteres"
    !p.any(Char::isLetter) || !p.any(Char::isDigit) -> "Usa letras y números"
    else -> null
}

/** Campo de contraseña con botón para verla. */
@Composable
private fun PasswordInput(value: String, onChange: (String) -> Unit, label: String, error: String? = null) {
    var show by remember { mutableStateOf(false) }
    TextInput(
        value, { onChange(it.take(64)) }, label, keyboard = KeyboardType.Password, error = error,
        visual = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Ocultar" else "Ver") } },
    )
}

/** Marco de las pantallas de entrada: en la computadora, la marca a la izquierda y el formulario a la derecha. */
@Composable
fun EntryFrame(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (maxWidth >= 840.dp) Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.primary).padding(48.dp), contentAlignment = Alignment.CenterStart) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Vitrina POS", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text("Vende, controla tu inventario y tus sucursales desde cualquier equipo.", color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp)
                    Text("Celular · Windows · Mac", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) { Box(Modifier.widthIn(max = 460.dp).fillMaxHeight()) { content() } }
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { Box(Modifier.widthIn(max = 560.dp).fillMaxHeight()) { content() } }
    }
}

/**
 * Iniciar sesión con la cuenta del negocio, crear la cuenta, elegir el negocio, crear otro negocio,
 * pasar los datos de este equipo a la nube o recuperar la contraseña. Al terminar, este equipo trabaja con la nube.
 * [entry]: es la primera pantalla de la app (equipo nuevo o sesión cerrada).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudAccountScreen(initial: CloudMode, entry: Boolean = false, onLan: (() -> Unit)? = null, onClose: () -> Unit) {
    val app = LocalAppState.current
    val graph = app.graph
    val scope = rememberSafeScope()
    var mode by remember { mutableStateOf(initial) }
    val s = app.settingsNow
    var email by remember { mutableStateOf(graph.lastAccountEmail) }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(if (initial == CloudMode.MIGRATE) app.repo.users.value.firstOrNull { it.role == com.vitrinapos.data.Roles.ADMIN }?.name.orEmpty() else "") }
    var business by remember { mutableStateOf(if (initial == CloudMode.MIGRATE) s.businessName else "") }
    var type by remember { mutableStateOf(if (initial == CloudMode.MIGRATE) BusinessType.of(s.businessType) else null) }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<BusinessChoice>>(emptyList()) }
    // Recuperar contraseña
    var codeSent by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    // La cuenta tiene una contraseña temporal (la puso el panel de Vitrina POS): se cambia al entrar.
    var mustChange by remember { mutableStateOf<String?>(null) }

    val hasLocalData = !entry && (app.repo.products.value.isNotEmpty() || app.repo.db.saleQueries.selectRecent(1).executeAsList().isNotEmpty())
    val needsBusinessForm = mode == CloudMode.SIGNUP || mode == CloudMode.MIGRATE || mode == CloudMode.NEW_BUSINESS

    fun go(to: CloudMode) { mode = to; tried = false; error = null }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            runCatching { withContext(app.platform.io) { block() } }
                .onFailure { error = cloudError(it) }
            busy = false; step = null
        }
    }

    suspend fun done(message: String) = withContext(kotlinx.coroutines.Dispatchers.Main) { app.toast(message); onClose() }

    /** Autoriza este equipo en el negocio elegido y recibe sus datos. */
    suspend fun enter(businessId: Long?) {
        step = "Recibiendo los datos de la nube…"
        val creds = graph.cloudLogin(email, password, businessId)
        if (creds.mustChangePassword) mustChange = password else done("Listo: entraste a ${creds.businessName}")
    }

    val emailError = if (tried && !EMAIL.matches(email.trim())) "Escribe un correo válido" else null
    val passError = if (tried && (mode == CloudMode.SIGNUP || mode == CloudMode.MIGRATE)) passwordProblem(password) else if (tried && password.isBlank()) "Escribe la contraseña" else null
    val pinError = when {
        !tried -> null
        pin.length < 4 -> "El PIN lleva de 4 a 6 números"
        pin != pin2 -> "Los dos PIN no coinciden"
        else -> null
    }
    val nameError = if (tried) com.vitrinapos.data.userNameProblem(name)?.let { if (name.isBlank()) "Escribe tu nombre" else it } else null

    fun signupRequest() = SignupRequest(email.trim(), password, name.trim(), business.trim(), type?.name ?: "", pin, graph.deviceName, app.platform.platformName)

    fun submit() {
        tried = true
        when (mode) {
            CloudMode.LOGIN -> {
                if (emailError != null || password.isBlank()) return
                if (hasLocalData && !confirmReplace) { confirmReplace = true; return }
                run {
                    step = "Entrando…"
                    // Un servidor anterior a la 1.14 no tiene la lista de negocios: se entra al único que hay.
                    val list = try { CloudClient.businesses(graph.cloudUrl, email, password) }
                        catch (e: HttpStatusException) { if (e.code == 404) listOf(BusinessChoice(0, "", "", "", 0)) else throw e }
                    if (list.size == 1 && list.first().id == 0L) { enter(null); return@run }
                    when {
                        list.isEmpty() -> throw IllegalStateException("Esta cuenta todavía no tiene negocios")
                        list.size == 1 -> enter(list.first().id)
                        else -> { choices = list; withContext(kotlinx.coroutines.Dispatchers.Main) { go(CloudMode.CHOOSE) } }
                    }
                }
            }
            CloudMode.CHOOSE -> {}
            CloudMode.SIGNUP, CloudMode.MIGRATE, CloudMode.NEW_BUSINESS -> {
                val pwdOk = if (mode == CloudMode.NEW_BUSINESS) password.isNotBlank() else passwordProblem(password) == null
                if (emailError != null || !pwdOk || nameError != null || business.isBlank() || type == null || pinError != null) return
                if (mode == CloudMode.SIGNUP && hasLocalData && !confirmReplace) { confirmReplace = true; return }
                run {
                    when (mode) {
                        CloudMode.MIGRATE -> { graph.migrateToCloud(signupRequest()) { step = it }; done("Tus datos ya están en la nube") }
                        CloudMode.NEW_BUSINESS -> {
                            step = "Creando el negocio…"
                            graph.cloudNewBusiness(NewBusinessRequest(email.trim(), password, business.trim(), type?.name ?: "", name.trim(), pin))
                            done("Negocio creado: este equipo trabaja en ${business.trim()}")
                        }
                        else -> { step = "Creando la cuenta…"; graph.cloudSignup(signupRequest()); done("Cuenta creada: este equipo trabaja con la nube") }
                    }
                }
            }
            CloudMode.FORGOT -> {
                if (emailError != null) return
                if (!codeSent) run {
                    step = "Enviando el código…"
                    CloudClient.forgotPassword(graph.cloudUrl, email.trim())
                    codeSent = true; tried = false
                } else {
                    if (code.length != 6 || passwordProblem(newPass) != null) return
                    run {
                        step = "Cambiando la contraseña…"
                        CloudClient.resetPassword(graph.cloudUrl, ResetRequest(email.trim(), code, newPass))
                        password = ""; codeSent = false; code = ""; newPass = ""; tried = false
                        mode = CloudMode.LOGIN
                        withContext(kotlinx.coroutines.Dispatchers.Main) { app.toast("Contraseña cambiada. Entra con la nueva.") }
                    }
                }
            }
        }
    }

    fun back() {
        when {
            mode == CloudMode.NEW_BUSINESS -> go(CloudMode.CHOOSE)
            mode == CloudMode.FORGOT || mode == CloudMode.CHOOSE -> go(CloudMode.LOGIN)
            entry && mode == CloudMode.SIGNUP -> go(CloudMode.LOGIN)
            else -> onClose()
        }
    }

    EntryFrame {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!(entry && mode == CloudMode.LOGIN)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }, enabled = !busy) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver") }
                }
            } else Spacer(Modifier.height(20.dp))
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(
                    when (mode) { CloudMode.CHOOSE, CloudMode.NEW_BUSINESS -> Icons.Rounded.Business; CloudMode.LOGIN -> if (entry) Icons.Rounded.Storefront else Icons.Rounded.Cloud; else -> Icons.Rounded.Cloud },
                    null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp),
                )
            }
            Text(
                when (mode) {
                    CloudMode.LOGIN -> if (entry) "Iniciar sesión" else "Entrar con la cuenta del negocio"
                    CloudMode.SIGNUP -> "Crear la cuenta de tu negocio"
                    CloudMode.MIGRATE -> "Pasar mis datos a la nube"
                    CloudMode.FORGOT -> "Recuperar la contraseña"
                    CloudMode.CHOOSE -> "¿Con qué negocio vas a trabajar?"
                    CloudMode.NEW_BUSINESS -> "Otro negocio con tu cuenta"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                when (mode) {
                    CloudMode.LOGIN -> when {
                        entry && graph.signedOut -> "Cerraste la sesión de la cuenta en este equipo. Tus datos siguen guardados en la nube."
                        entry -> "Entra con el correo y la contraseña de tu negocio."
                        else -> "Con el correo y la contraseña del dueño. Este equipo queda autorizado y recibe los datos del negocio."
                    }
                    CloudMode.SIGNUP -> "Tus datos se guardan en la nube y cada equipo entra con la cuenta del negocio. Si se va el internet, sigues vendiendo."
                    CloudMode.MIGRATE -> "Se crea la cuenta y se suben los productos, ventas, clientes, usuarios y fotos de este equipo. Los usuarios entran con el mismo PIN."
                    CloudMode.FORGOT -> if (codeSent) "Te enviamos un código de 6 números a ${email.trim()}. Vence en 30 minutos." else "Te enviaremos un código al correo de la cuenta."
                    CloudMode.CHOOSE -> "Tu correo administra varios negocios. Elige uno; para cambiar después, cierra la sesión de la cuenta."
                    CloudMode.NEW_BUSINESS -> "Se crea con la misma cuenta (${email.trim()}), con su propia prueba gratis. Tú serás su administrador."
                },
                color = AppTheme.extra.muted,
            )
            if (entry && mode == CloudMode.LOGIN && graph.signedOut) Text(
                "✓ Sesión cerrada. Para volver, entra de nuevo: los datos se descargan solos.",
                color = AppTheme.extra.ok, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppTheme.extra.okSoft).padding(12.dp),
            )

            if (mode == CloudMode.CHOOSE) {
                choices.forEach { b ->
                    AppCard(onClick = { if (!busy) run { enter(b.id) } }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Storefront, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text("Plan ${b.planName} · ${b.devices} ${if (b.devices == 1) "equipo" else "equipos"}", fontSize = 13.sp, color = AppTheme.extra.muted)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = AppTheme.extra.muted)
                        }
                    }
                }
            }

            if (needsBusinessForm) {
                if (mode != CloudMode.NEW_BUSINESS) TextInput(name, { name = it.take(40) }, "Tu nombre *", error = nameError)
                TextInput(business, { business = it.take(60) }, "Nombre del negocio *", error = if (tried && business.isBlank()) "Escribe el nombre del negocio" else null)
                if (mode != CloudMode.MIGRATE || type == null) {
                    Text("Tipo de negocio *", fontWeight = FontWeight.SemiBold, color = if (tried && type == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BusinessType.entries.forEach { t ->
                            val sel = type == t
                            Surface(
                                onClick = { type = t }, shape = RoundedCornerShape(999.dp),
                                color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else AppTheme.extra.line),
                            ) { Text(t.title, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) }
                        }
                    }
                }
                if (mode == CloudMode.NEW_BUSINESS) TextInput(name, { name = it.take(40) }, "Tu nombre como administrador *", error = nameError)
            }
            if ((mode == CloudMode.LOGIN || mode == CloudMode.SIGNUP || mode == CloudMode.MIGRATE || mode == CloudMode.FORGOT) && !(mode == CloudMode.FORGOT && codeSent)) {
                TextInput(email, { email = it.trim().take(80) }, "Correo *", keyboard = KeyboardType.Email, error = emailError)
            }
            if (mode == CloudMode.LOGIN || mode == CloudMode.SIGNUP || mode == CloudMode.MIGRATE) {
                PasswordInput(password, { password = it }, if (mode == CloudMode.LOGIN) "Contraseña *" else "Contraseña (8+, letras y números) *", passError)
            }
            if (needsBusinessForm) {
                TextInput(pin, { pin = it.filter(Char::isDigit).take(6) }, if (mode == CloudMode.MIGRATE) "PIN del administrador *" else "Tu PIN para la app (4 a 6 números) *",
                    keyboard = KeyboardType.NumberPassword, visual = PasswordVisualTransformation(), error = pinError)
                TextInput(pin2, { pin2 = it.filter(Char::isDigit).take(6) }, "Repite el PIN *", keyboard = KeyboardType.NumberPassword, visual = PasswordVisualTransformation())
                if (mode == CloudMode.MIGRATE) Text("Si la subida no se completa, entrarás a la cuenta con este PIN. Cuando termina, cada usuario sigue con el suyo.", fontSize = 12.sp, color = AppTheme.extra.muted)
            }
            if (mode == CloudMode.FORGOT && codeSent) {
                TextInput(code, { code = it.filter(Char::isDigit).take(6) }, "Código de 6 números *", keyboard = KeyboardType.Number,
                    error = if (tried && code.length != 6) "El código tiene 6 números" else null)
                PasswordInput(newPass, { newPass = it }, "Contraseña nueva *", if (tried) passwordProblem(newPass) else null)
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(AppTheme.extra.dangerSoft).padding(12.dp))
            }
            if (mode == CloudMode.CHOOSE && busy) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(step ?: "Un momento…")
            }
            if (mode != CloudMode.CHOOSE) Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(step ?: "Un momento…")
                } else Text(
                    when (mode) {
                        CloudMode.LOGIN -> "Entrar"
                        CloudMode.SIGNUP -> "Crear cuenta"
                        CloudMode.MIGRATE -> "Crear cuenta y subir mis datos"
                        CloudMode.NEW_BUSINESS -> "Crear negocio"
                        CloudMode.FORGOT -> if (codeSent) "Cambiar contraseña" else "Enviar código"
                        CloudMode.CHOOSE -> ""
                    },
                    fontSize = 16.sp,
                )
            }
            when (mode) {
                CloudMode.LOGIN -> {
                    TextButton(onClick = { go(CloudMode.FORGOT) }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Olvidé mi contraseña") }
                    if (entry) {
                        HorizontalDivider(color = AppTheme.extra.line)
                        OutlinedButton(onClick = { go(CloudMode.SIGNUP) }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp), enabled = !busy) {
                            Text("¿Negocio nuevo? Crear cuenta gratis")
                        }
                        Text("Prueba gratis. Funciona en celular, Windows y Mac.", fontSize = 12.sp, color = AppTheme.extra.muted)
                        if (onLan != null) TextButton(onClick = onLan, Modifier.fillMaxWidth(), enabled = !busy) {
                            Text("Conectar a un equipo principal de la red local", fontSize = 12.sp, color = AppTheme.extra.muted)
                        }
                    } else if (initial != CloudMode.MIGRATE) TextButton(onClick = { go(CloudMode.SIGNUP) }, Modifier.fillMaxWidth(), enabled = !busy) { Text("¿No tienes cuenta? Crear una") }
                }
                CloudMode.SIGNUP -> TextButton(onClick = { go(CloudMode.LOGIN) }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Ya tengo cuenta: entrar") }
                CloudMode.CHOOSE -> TextButton(onClick = { go(CloudMode.NEW_BUSINESS) }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Crear otro negocio con esta cuenta") }
                CloudMode.FORGOT -> if (codeSent) TextButton(onClick = { codeSent = false; code = "" }, Modifier.fillMaxWidth(), enabled = !busy) { Text("No me llegó: enviar otro") }
                else -> {}
            }
            if (mode == CloudMode.SIGNUP || mode == CloudMode.MIGRATE || mode == CloudMode.NEW_BUSINESS) {
                Text("Empiezas con una prueba gratis. Para la facturación electrónica (e-CF) desde la nube, espera una próxima versión.", fontSize = 12.sp, color = AppTheme.extra.muted)
            }
        }
    }

    if (confirmReplace) ConfirmDialog(
        "Reemplazar los datos de este equipo",
        "Este equipo ya tiene productos o ventas. Al " + (if (mode == CloudMode.LOGIN) "entrar" else "crear la cuenta") +
            " se reemplazan por los de la nube. Si quieres conservarlos, usa \"Pasar mis datos a la nube\" en Más → Trabajo en red.",
        "Reemplazar",
        onConfirm = { submit() },
        onDismiss = { confirmReplace = false },
        destructive = true,
    )

    mustChange?.let { temp ->
        ChangePasswordDialog(email.trim(), temp, title = "Pon tu contraseña", text = "Entraste con una contraseña temporal. Escribe una nueva para tu cuenta.",
            onDone = { mustChange = null; app.toast("Listo: este equipo trabaja con la nube"); onClose() })
    }
}

/** Cambiar la contraseña de la cuenta (con la actual o la temporal). */
@Composable
fun ChangePasswordDialog(email: String, current: String?, title: String = "Cambiar la contraseña", text: String = "", onDone: () -> Unit, onDismiss: (() -> Unit)? = null) {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    var old by remember { mutableStateOf(current.orEmpty()) }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val problem = passwordProblem(new1) ?: if (new1 != new2) "Las dos contraseñas no coinciden" else null
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (text.isNotBlank()) Text(text, color = AppTheme.extra.muted)
                if (current == null) PasswordInput(old, { old = it }, "Contraseña actual")
                PasswordInput(new1, { new1 = it }, "Contraseña nueva", if (tried) problem else null)
                PasswordInput(new2, { new2 = it }, "Repite la contraseña nueva")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                tried = true
                if (problem != null || old.isBlank()) return@TextButton
                busy = true
                scope.launch {
                    runCatching { withContext(app.platform.io) { CloudClient.changePassword(app.graph.cloudUrl, ChangePasswordRequest(email, old, new1)) } }
                        .onSuccess { app.toast("Contraseña cambiada"); onDone() }
                        .onFailure { error = cloudError(it) }
                    busy = false
                }
            }) { Text("Guardar") }
        },
        dismissButton = { if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Cómo va el plan, en palabras. */
fun planStateText(p: Entitlements): String {
    val until = p.until?.let { Time.shortDate(it) }
    return when (p.state) {
        "TRIAL" -> "Prueba gratis" + (until?.let { " hasta el $it" } ?: "")
        "ACTIVE" -> "Al día" + (until?.let { " · pagado hasta el $it" } ?: "")
        "GRACE" -> "Pago pendiente" + (until?.let { " · puedes vender hasta el $it" } ?: "")
        "READ_ONLY" -> "Vencido: solo consulta"
        "SUSPENDED" -> "Suspendido"
        else -> p.state
    }
}

private val MODULE_NAMES = mapOf(
    "ventas" to "Ventas y caja", "inventario" to "Inventario", "taller" to "Taller", "imei" to "IMEI y serie",
    "variantes" to "Variantes", "cotizaciones" to "Cotizaciones", "etiquetas" to "Etiquetas",
    "facturacion" to "Facturación electrónica", "actividad" to "Actividad", "multiequipo" to "Varios equipos",
)

/** Panel de la nube (en Trabajo en red): estado, plan, equipos y salir. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudPanel() {
    val app = LocalAppState.current
    val graph = app.graph
    val cloud = graph.cloud
    val scope = rememberSafeScope()
    val meta by app.repo.meta.collectAsState()
    var confirmLeave by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<Device>?>(null) }
    var devicesError by remember { mutableStateOf<String?>(null) }
    var revoke by remember { mutableStateOf<Device?>(null) }
    var changePass by remember { mutableStateOf(false) }

    fun loadDevices() {
        val c = cloud ?: return
        scope.launch {
            runCatching { c.devices() }.onSuccess { devices = it; devicesError = null }.onFailure { devicesError = cloudError(it) }
        }
    }
    LaunchedEffect(cloud) { loadDevices() }

    if (cloud == null) {
        AppCard {
            Text("No se pudo abrir la conexión con la nube en este equipo.", fontWeight = FontWeight.SemiBold)
            Text("Entra de nuevo con la cuenta del negocio.", color = AppTheme.extra.muted, fontSize = 13.sp)
        }
        return
    }
    val st by cloud.status.collectAsState()
    val plan by cloud.plan.collectAsState()
    val ex = AppTheme.extra
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (st.state == LinkState.ONLINE) Icons.Rounded.Cloud else Icons.Rounded.CloudOff, null,
                tint = if (st.state == LinkState.ONLINE) ex.ok else ex.warn, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meta["cloud.businessName"] ?: app.settingsNow.businessName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(graph.cloudEmail.orEmpty(), fontSize = 13.sp, color = ex.muted)
            }
            when (st.state) {
                LinkState.ONLINE -> Pill("En línea", ex.ok, ex.okSoft)
                LinkState.CONNECTING -> Pill("Conectando…", ex.muted, MaterialTheme.colorScheme.surfaceContainer)
                LinkState.OFFLINE -> Pill("Sin red", ex.warn, ex.warnSoft)
                LinkState.REVOKED -> Pill("Sin acceso", ex.danger, ex.dangerSoft)
            }
        }
        if (st.lastSync > 0) Text("Última sincronización: ${Time.dateTime(st.lastSync)}", fontSize = 12.sp, color = ex.muted, modifier = Modifier.padding(top = 8.dp))
        if (st.state == LinkState.OFFLINE && st.message.isNotBlank()) Text(st.message, fontSize = 12.sp, color = ex.warn, modifier = Modifier.padding(top = 4.dp))
    }

    plan?.let { p ->
        SectionTitle("Plan")
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.planName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(planStateText(p), fontSize = 13.sp, color = when (p.state) { "GRACE" -> ex.warn; "READ_ONLY", "SUSPENDED" -> ex.danger; else -> ex.muted })
                }
                Text("${p.maxDevices} ${if (p.maxDevices == 1) "equipo" else "equipos"} · " + (if (p.maxUsers <= 0) "usuarios sin límite" else "${p.maxUsers} usuarios"),
                    fontSize = 12.sp, color = ex.muted)
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.modules.forEach { m -> Pill(MODULE_NAMES[m] ?: m, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer) }
            }
            Text("Para cambiar de plan o agregar módulos, escríbenos.", fontSize = 12.sp, color = ex.muted, modifier = Modifier.padding(top = 8.dp))
        }
    }

    SectionTitle("Equipos del negocio", action = "Actualizar", onAction = { loadDevices() })
    when {
        devicesError != null -> Text(devicesError!!, color = ex.muted, fontSize = 13.sp)
        devices == null -> Text("Cargando…", color = ex.muted, fontSize = 13.sp)
        else -> devices!!.filter { !it.revoked }.forEach { d ->
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Devices, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontWeight = FontWeight.SemiBold)
                        Text(listOf(d.platform, d.lastUse?.let { "Usado ${Time.dateTime(it)}" } ?: "Sin usar todavía").filter { it.isNotBlank() }.joinToString(" · "),
                            fontSize = 12.sp, color = ex.muted)
                    }
                    if (d.id == cloud.deviceId) Pill("Este equipo", ex.ok, ex.okSoft)
                    else TextButton(onClick = { revoke = d }) { Text("Quitar", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    Text("Para agregar otro equipo, instala Vitrina POS allí y toca \"Entrar con mi cuenta\" con el correo del negocio.", fontSize = 13.sp, color = ex.muted)

    SectionTitle("Cuenta")
    OutlinedButton(onClick = { changePass = true }, Modifier.fillMaxWidth()) { Text("Cambiar la contraseña de la cuenta") }
    OutlinedButton(onClick = { confirmLeave = true }, Modifier.fillMaxWidth()) { Text("Salir de la nube en este equipo", color = MaterialTheme.colorScheme.error) }

    revoke?.let { d ->
        ConfirmDialog(
            "Quitar \"${d.name}\"",
            "Ese equipo deja de tener acceso a los datos del negocio. Para volver a usarlo, hay que entrar otra vez con la cuenta.",
            "Quitar",
            onConfirm = {
                scope.launch {
                    runCatching { cloud.revokeDevice(d.id) }.onSuccess { app.toast("Equipo quitado"); loadDevices() }.onFailure { app.toast(cloudError(it)) }
                }
            },
            onDismiss = { revoke = null },
            destructive = true,
        )
    }
    if (confirmLeave) ConfirmDialog(
        "Salir de la nube",
        "Este equipo deja de recibir los cambios y se queda trabajando solo con una copia de los datos de este momento. " +
            (if (app.repo.pendingSales.value > 0) "Tiene ventas sin enviar: conéctalo a internet antes de salir. " else "") +
            "Para volver, entra otra vez con la cuenta.",
        "Salir",
        onConfirm = { graph.leaveCloud(); app.toast("Este equipo ahora trabaja solo") },
        onDismiss = { confirmLeave = false },
        destructive = true,
    )
    if (changePass) ChangePasswordDialog(graph.cloudEmail.orEmpty(), null, onDone = { changePass = false }, onDismiss = { changePass = false })
}

/** Aviso en Inicio cuando el plan de la nube está por vencer o vencido. */
@Composable
fun PlanBanner() {
    val app = LocalAppState.current
    val cloud = app.graph.cloud ?: return
    val p = cloud.plan.collectAsState().value ?: return
    val ex = AppTheme.extra
    val (text, fg, bg) = when (p.state) {
        "GRACE" -> Triple("El pago del plan está pendiente. ${planStateText(p)}.", ex.warn, ex.warnSoft)
        "READ_ONLY" -> Triple("El plan venció: puedes consultar, pero no registrar ventas. Renueva para seguir vendiendo.", ex.danger, ex.dangerSoft)
        "SUSPENDED" -> Triple("Este negocio está suspendido. Comunícate con Vitrina POS.", ex.danger, ex.dangerSoft)
        "TRIAL" -> {
            val left = p.until?.let { ((it - Time.now()) / 86_400_000L).toInt() } ?: 99
            if (left > 5) return
            Triple(if (left <= 0) "La prueba gratis termina hoy." else "La prueba gratis termina en $left ${if (left == 1) "día" else "días"}.", ex.warn, ex.warnSoft)
        }
        else -> return
    }
    Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(12.dp))
}

/** Negocio, sucursal y cuenta de este equipo (arriba de "¿Quién va a usar la app?"). */
@Composable
fun AccountBar() {
    val app = LocalAppState.current
    val cloud = app.graph.cloud ?: return
    val st by cloud.status.collectAsState()
    val meta by app.repo.meta.collectAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (st.state == LinkState.OFFLINE) Icons.Rounded.CloudOff else Icons.Rounded.Cloud, null,
            tint = if (st.state == LinkState.OFFLINE) AppTheme.extra.warn else AppTheme.extra.ok, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(meta["cloud.businessName"] ?: app.settings.businessName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
        Text(app.graph.cloudEmail.orEmpty(), fontSize = 12.sp, color = AppTheme.extra.muted, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
    }
}

/** Botón rojo "Cerrar sesión de la cuenta" (solo en equipos que trabajan con la nube). */
@Composable
fun CloseAccountButton(modifier: Modifier = Modifier.fillMaxWidth(), outlined: Boolean = true) {
    val app = LocalAppState.current
    if (app.graph.cloud == null) return
    if (outlined) OutlinedButton(
        onClick = { app.askCloseAccount = true }, modifier = modifier.height(50.dp), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp))
        Text("Cerrar sesión de la cuenta", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
    } else TextButton(onClick = { app.askCloseAccount = true }, modifier = modifier) {
        Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp))
        Text("Cerrar sesión de la cuenta", color = MaterialTheme.colorScheme.error)
    }
}

/**
 * "Cerrar sesión de la cuenta": explica que los datos siguen en la nube y, si hay ventas sin enviar,
 * no deja cerrar hasta enviarlas.
 */
@Composable
fun CloseAccountDialog() {
    val app = LocalAppState.current
    if (!app.askCloseAccount) return
    val scope = rememberSafeScope()
    var pending by remember { mutableStateOf(app.graph.unsentSales()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun close() { app.askCloseAccount = false }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(if (pending > 0) "Primero hay que enviar $pending ${if (pending == 1L) "venta" else "ventas"}" else "¿Cerrar la sesión de la cuenta?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pending > 0) Text(
                    "Este equipo tiene $pending ${if (pending == 1L) "venta hecha" else "ventas hechas"} sin internet que todavía no llegan a la nube. " +
                        "Conéctate a internet para enviarlas; si cierras ahora se perderían.",
                    color = AppTheme.extra.warn, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(AppTheme.extra.warnSoft).padding(12.dp),
                ) else {
                    Text("• Tus datos NO se borran: siguen guardados en la nube.")
                    Text("• Se quita la copia de este equipo, para que nadie más los vea aquí.")
                    Text("• Al volver a entrar con tu correo, todo se descarga de nuevo solo.")
                    Text("• Así también puedes entrar a otro negocio en este equipo.")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (pending > 0) TextButton(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    pending = withContext(app.platform.io) { app.graph.sendPendingNow() }
                    busy = false
                    if (pending > 0) error = "Todavía no hay conexión con la nube. Revisa el internet e intenta otra vez."
                }
            }) { Text("Enviar ahora") }
            else TextButton(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { withContext(app.platform.io) { app.graph.signOutAccount() } }
                        .onSuccess { app.signedOutReset(); close() }
                        .onFailure { error = cloudError(it); pending = app.graph.unsentSales() }
                    busy = false
                }
            }) { Text("Cerrar sesión", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { close() }) { Text("Cancelar") } },
    )
}
