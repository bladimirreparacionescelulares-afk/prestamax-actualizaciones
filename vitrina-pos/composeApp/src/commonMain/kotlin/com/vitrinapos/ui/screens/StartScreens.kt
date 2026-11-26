package com.vitrinapos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.data.SampleData
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch

/** Primera vez que se abre la app: datos básicos del negocio. */
@Composable
fun WelcomeScreen(onConnect: () -> Unit = {}) {
    val app = LocalAppState.current
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    val s = app.settingsNow
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(s.currencySymbol) }
    var taxName by remember { mutableStateOf(s.taxName) }
    var taxRate by remember { mutableStateOf(s.taxRate.toString().removeSuffix(".0")) }
    var included by remember { mutableStateOf(s.pricesIncludeTax) }
    var busy by remember { mutableStateOf(false) }

    fun finish(withSamples: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            if (withSamples) SampleData.load(app.repo)
            app.repo.saveSettings(
                app.settingsNow.copy(
                    businessName = name.trim().ifBlank { "Mi Tienda" },
                    currencySymbol = currency.trim().ifBlank { "$" },
                    taxName = taxName.trim().ifBlank { "Impuesto" },
                    taxRate = taxRate.replace(',', '.').toDoubleOrNull() ?: 0.0,
                    pricesIncludeTax = included,
                    onboarded = true,
                ),
            )
        }
    }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF0A5E4E))))
                    .padding(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Storefront, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Text("Bienvenido a Vitrina POS", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Vende por fotos, controla tu inventario y crea cotizaciones. Todo se guarda en este equipo, sin internet.",
                        color = Color.White.copy(alpha = 0.88f),
                    )
                }
            }
            Text("Datos de tu negocio", style = MaterialTheme.typography.titleMedium)
            TextInput(name, { name = it }, "Nombre del negocio", placeholder = "Mi Tienda")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextInput(currency, { currency = it.take(4) }, "Moneda", Modifier.weight(1f))
                TextInput(taxName, { taxName = it.take(12) }, "Impuesto", Modifier.weight(1f))
                TextInput(taxRate, { taxRate = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, "% impuesto", Modifier.weight(1f), keyboard = KeyboardType.Decimal)
            }
            AppCard(onClick = { included = !included }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mis precios ya incluyen el impuesto", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (included) "El total es el precio marcado; el impuesto se muestra como incluido."
                            else "El impuesto se suma al precio al cobrar.",
                            style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted,
                        )
                    }
                    Switch(included, { included = it })
                }
            }
            Text("Puedes cambiar todo esto después en Configuración.", style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted)
            Button(onClick = { finish(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Empezar", fontSize = 16.sp)
            }
            OutlinedButton(onClick = { finish(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Empezar con productos de ejemplo")
            }
            TextButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text("¿Ya tienes Vitrina POS en otro equipo? Conectarme a él")
            }
        }
    }
}

/** Pantalla de PIN cuando está activado. */
@Composable
fun LockScreen(pin: String, onUnlock: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val app = LocalAppState.current
    fun press(d: String) {
        if (entered.length >= 8) return
        entered += d
        error = false
        if (entered.length == pin.length) {
            if (entered == pin) onUnlock() else { error = true; entered = "" }
        }
    }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(app.settings.businessName, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(if (error) "PIN incorrecto, intenta de nuevo" else "Escribe tu PIN", color = if (error) MaterialTheme.colorScheme.error else AppTheme.extra.muted)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(pin.length) { i ->
                Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < entered.length) MaterialTheme.colorScheme.primary else AppTheme.extra.line))
            }
        }
        Spacer(Modifier.height(28.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.size(76.dp).clip(CircleShape)
                                .background(if (k.isEmpty()) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer)
                                .clickable(enabled = k.isNotEmpty()) { if (k == "<") entered = entered.dropLast(1) else press(k) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (k == "<") Icon(Icons.AutoMirrored.Rounded.Backspace, "Borrar")
                            else Text(k, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/** Entrada con usuario y PIN cuando hay usuarios configurados. */
@Composable
fun LoginScreen(users: List<com.vitrinapos.db.AppUser>) {
    val app = LocalAppState.current
    var selected by remember { mutableStateOf(if (users.size == 1) users.first() else null) }
    val chosen = selected
    if (chosen == null) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(app.settings.businessName, style = MaterialTheme.typography.headlineSmall)
            Text("¿Quién va a usar la app?", color = AppTheme.extra.muted)
            Column(Modifier.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                users.forEach { u ->
                    AppCard(Modifier.fillMaxWidth(), onClick = { selected = u }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            com.vitrinapos.ui.components.Avatar(u.name, 46.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(u.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                Text(com.vitrinapos.data.Roles.label(u.role), color = AppTheme.extra.muted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    } else {
        PinPad(
            title = chosen.name,
            subtitle = "Escribe tu PIN",
            check = { pin, final -> app.repo.pinAttempt(chosen, pin, final) },
            onSuccess = { app.repo.currentUser.value = chosen },
            onBack = if (users.size > 1) ({ selected = null }) else null,
        )
    }
}

/** Teclado de PIN: comprueba a partir de 4 números y entra en cuanto coincide (hasta 8). */
@Composable
fun PinPad(
    title: String, subtitle: String, check: (String, Boolean) -> com.vitrinapos.data.Repository.PinResult,
    onSuccess: () -> Unit, onBack: (() -> Unit)? = null,
) {
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var lockedUntil by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(com.vitrinapos.data.Time.now()) }
    androidx.compose.runtime.LaunchedEffect(lockedUntil) {
        while (lockedUntil > com.vitrinapos.data.Time.now()) { now = com.vitrinapos.data.Time.now(); kotlinx.coroutines.delay(1000) }
        now = com.vitrinapos.data.Time.now()
    }
    val locked = lockedUntil > now
    fun handle(r: com.vitrinapos.data.Repository.PinResult, final: Boolean) {
        when (r) {
            is com.vitrinapos.data.Repository.PinResult.Ok -> onSuccess()
            is com.vitrinapos.data.Repository.PinResult.Locked -> { lockedUntil = r.until; entered = ""; error = null }
            is com.vitrinapos.data.Repository.PinResult.Wrong -> if (final) {
                entered = ""
                error = "PIN incorrecto. " + (if (r.attemptsLeft == 1) "Queda 1 intento" else "Quedan ${r.attemptsLeft} intentos") + " antes de bloquearse."
            }
        }
    }
    // Se prueba desde el 4.º número (sin contar como fallo); al llegar a 8 o al tocar ✓ cuenta como intento.
    fun press(d: String) {
        if (entered.length >= 8 || locked) return
        entered += d
        error = null
        if (entered.length >= 4) {
            val r = check(entered, entered.length == 8)
            if (r !is com.vitrinapos.data.Repository.PinResult.Wrong || entered.length == 8) handle(r, entered.length == 8)
        }
    }
    fun submit() { if (entered.length >= 4 && !locked) handle(check(entered, true), true) }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        com.vitrinapos.ui.components.Avatar(title, 64.dp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                locked -> "Bloqueado por seguridad. Intenta en " + ((lockedUntil - now + 999) / 1000).let { if (it >= 60) "${(it + 59) / 60} min" else "$it s" }
                error != null -> error!!
                else -> subtitle
            },
            color = if (locked || error != null) MaterialTheme.colorScheme.error else AppTheme.extra.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(maxOf(4, entered.length)) { i ->
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < entered.length) MaterialTheme.colorScheme.primary else AppTheme.extra.line))
            }
        }
        Spacer(Modifier.height(26.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "ok", "0", "<")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.size(74.dp).clip(CircleShape)
                                .background(if (k == "ok") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                                .clickable(enabled = !locked) { when (k) { "<" -> entered = entered.dropLast(1); "ok" -> submit(); else -> press(k) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (k == "<") Icon(Icons.AutoMirrored.Rounded.Backspace, "Borrar")
                            else if (k == "ok") Icon(Icons.Rounded.Check, "Entrar", tint = Color.White)
                            else Text(k, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        if (onBack != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onBack) { Text("Cambiar de usuario") }
        }
    }
}
