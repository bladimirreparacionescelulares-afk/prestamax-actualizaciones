package com.vitrinapos.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.LocalPharmacy
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.data.BusinessType
import com.vitrinapos.data.Roles
import com.vitrinapos.data.SampleData
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch

private fun BusinessType.icon(): ImageVector = when (this) {
    BusinessType.PHONES -> Icons.Rounded.PhoneAndroid
    BusinessType.REPAIR -> Icons.Rounded.Build
    BusinessType.COLMADO -> Icons.Rounded.ShoppingBasket
    BusinessType.SUPER -> Icons.Rounded.LocalGroceryStore
    BusinessType.HARDWARE -> Icons.Rounded.Handyman
    BusinessType.CLOTHES -> Icons.Rounded.Checkroom
    BusinessType.PHARMACY -> Icons.Rounded.LocalPharmacy
    BusinessType.OTHER -> Icons.Rounded.MoreHoriz
}

/**
 * Asistente del primer uso: 1) administrador con PIN (obligatorio), 2) tipo de negocio,
 * 3) módulos, 4) datos del negocio. Al terminar, el administrador queda dentro.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onConnect: () -> Unit) {
    val app = LocalAppState.current
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    val start = app.settingsNow
    val users = app.repo.users.collectAsState().value.filter { it.active == 1L }
    // Si ya hay un administrador (por ejemplo, de un respaldo), no se pide otro.
    val needAdmin = users.none { it.role == Roles.ADMIN }
    var step by remember { mutableIntStateOf(if (needAdmin) 0 else 1) }
    var adminName by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf<BusinessType?>(null) }
    var mods by remember { mutableStateOf(start) }
    var name by remember { mutableStateOf("") }
    var rnc by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("RD$") }
    var taxRate by remember { mutableStateOf("18") }
    var included by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    val pinError = when {
        !tried -> null
        pin.length < 4 -> "El PIN lleva de 4 a 6 números"
        pin != pin2 -> "Los dos PIN no coinciden"
        users.any { app.repo.checkPin(it, pin) } -> "Ese PIN ya lo usa otro usuario"
        else -> null
    }

    fun finish(withSamples: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching {
                if (withSamples) SampleData.load(app.repo)
                if (needAdmin) {
                    val id = app.repo.saveUser(null, adminName.trim(), pin, Roles.ADMIN, true)
                    app.repo.currentUser.value = app.repo.db.appUserQueries.selectById(id).executeAsOneOrNull()
                }
                app.repo.saveSettings(
                    app.settingsNow.copy(
                        businessType = mods.businessType, modTaller = mods.modTaller, modImei = mods.modImei, modVariants = mods.modVariants,
                        modQuotes = mods.modQuotes, modLabels = mods.modLabels,
                        businessName = name.trim().ifBlank { "Mi Tienda" }, taxId = rnc.trim(), phone = phone.trim(),
                        currencySymbol = currency.trim().ifBlank { "RD$" }, taxName = "ITBIS",
                        taxRate = taxRate.replace(',', '.').toDoubleOrNull() ?: 0.0, pricesIncludeTax = included,
                        onboarded = true,
                    ),
                )
            }.onFailure { busy = false; app.toast(it.message ?: "No se pudo guardar") }
        }
    }

    val first = if (needAdmin) 0 else 1
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step > first) IconButton(onClick = { step-- }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (first..3).forEach { i ->
                        Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(5.dp))
                            .background(if (i <= step) MaterialTheme.colorScheme.primary else AppTheme.extra.line))
                    }
                }
            }
            when (step) {
                0 -> {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Storefront, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                    }
                    Text("Bienvenido a Vitrina POS", style = MaterialTheme.typography.headlineSmall)
                    Text("Primero, el administrador: es quien maneja la configuración, los usuarios y los permisos. Entrará con este PIN.", color = AppTheme.extra.muted)
                    TextInput(adminName, { adminName = it.take(40) }, "Tu nombre *", error = if (tried && adminName.isBlank()) "Escribe tu nombre" else null)
                    TextInput(pin, { pin = it.filter(Char::isDigit).take(6) }, "PIN de 4 a 6 números *", keyboard = KeyboardType.NumberPassword, visual = PasswordVisualTransformation(), error = pinError)
                    TextInput(pin2, { pin2 = it.filter(Char::isDigit).take(6) }, "Repite el PIN *", keyboard = KeyboardType.NumberPassword, visual = PasswordVisualTransformation())
                    Button(
                        onClick = { tried = true; if (adminName.isNotBlank() && pin.length >= 4 && pin == pin2 && users.none { app.repo.checkPin(it, pin) }) { tried = false; step = 1 } },
                        modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                    ) { Text("Continuar", fontSize = 16.sp) }
                    TextButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("¿Ya tienes Vitrina POS en otro equipo? Conectarme a él") }
                }
                1 -> {
                    Text("¿Qué tipo de negocio tienes?", style = MaterialTheme.typography.headlineSmall)
                    Text("Con esto preparamos la app. Lo puedes cambiar cuando quieras en Configuración.", color = AppTheme.extra.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
                        BusinessType.entries.forEach { t ->
                            val sel = type == t
                            Surface(
                                onClick = { type = t; mods = t.apply(mods) },
                                shape = RoundedCornerShape(16.dp),
                                color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else AppTheme.extra.line),
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(t.icon(), null, tint = MaterialTheme.colorScheme.primary)
                                    Text(t.title, fontWeight = FontWeight.Bold)
                                    Text(t.detail, fontSize = 12.sp, color = AppTheme.extra.muted)
                                }
                            }
                        }
                    }
                    Button(onClick = { step = 2 }, enabled = type != null, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Continuar", fontSize = 16.sp) }
                }
                2 -> {
                    Text("Módulos para ${type?.title?.lowercase() ?: "tu negocio"}", style = MaterialTheme.typography.headlineSmall)
                    Text("Marcamos lo que suele usar este tipo de negocio. Vender, caja, inventario, clientes y reportes vienen siempre.", color = AppTheme.extra.muted)
                    SwitchRow("Taller de reparación", "Recibir equipos, órdenes, entregas y garantía", mods.modTaller) { mods = mods.copy(modTaller = it) }
                    SwitchRow("IMEI y número de serie", "Celulares y equipos que se venden por unidad", mods.modImei) { mods = mods.copy(modImei = it) }
                    SwitchRow("Variantes", "Color, capacidad o talla del mismo producto", mods.modVariants) { mods = mods.copy(modVariants = it) }
                    SwitchRow("Cotizaciones", "Presupuestos en PDF para clientes", mods.modQuotes) { mods = mods.copy(modQuotes = it) }
                    SwitchRow("Etiquetas", "QR y precio para los productos", mods.modLabels) { mods = mods.copy(modLabels = it) }
                    Button(onClick = { step = 3 }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Continuar", fontSize = 16.sp) }
                }
                else -> {
                    Text("Datos de tu negocio", style = MaterialTheme.typography.headlineSmall)
                    Text("Salen en el ticket y en las cotizaciones.", color = AppTheme.extra.muted)
                    TextInput(name, { name = it.take(60) }, "Nombre del negocio", placeholder = "Mi Tienda")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextInput(rnc, { rnc = it.filter { c -> c.isDigit() || c == '-' }.take(13) }, "RNC o cédula (opcional)", Modifier.weight(1f), keyboard = KeyboardType.Number)
                        TextInput(phone, { phone = it.take(20) }, "Teléfono (opcional)", Modifier.weight(1f), keyboard = KeyboardType.Phone)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextInput(currency, { currency = it.take(4) }, "Moneda", Modifier.weight(1f))
                        TextInput(taxRate, { taxRate = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) }, "ITBIS %", Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                    }
                    SwitchRow("Mis precios ya incluyen el ITBIS", if (included) "El total es el precio marcado" else "El ITBIS se suma al cobrar", included) { included = it }
                    Button(onClick = { finish(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Empezar", fontSize = 16.sp) }
                    OutlinedButton(onClick = { finish(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
                        Text("Empezar con productos de ejemplo")
                    }
                }
            }
        }
    }
}
