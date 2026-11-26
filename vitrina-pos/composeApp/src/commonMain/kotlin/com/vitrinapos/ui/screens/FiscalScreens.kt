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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.UploadFile
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.Screen
import com.vitrinapos.data.EcfStatus
import com.vitrinapos.data.Fiscal
import com.vitrinapos.data.FiscalSteps
import com.vitrinapos.data.Money
import com.vitrinapos.data.NetRole
import com.vitrinapos.data.Time
import com.vitrinapos.fiscal.DgiiEnv
import com.vitrinapos.fiscal.Ecf
import com.vitrinapos.fiscal.EcfType
import com.vitrinapos.fiscal.TaxId
import com.vitrinapos.platform.rememberFileExchange
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.Divider
import com.vitrinapos.ui.components.KeyValue
import com.vitrinapos.ui.components.Pill
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.components.rememberSafeScope
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val URL_VIAFIRMA = "https://www.viafirma.do/certificado-procesos-tributarios/"
private const val URL_DIGIFIRMA = "https://digi.camarasantodomingo.do/"
private const val URL_OFV = "https://dgii.gov.do/OFV/home.aspx"
private const val URL_DOCS = "https://dgii.gov.do/cicloContribuyente/facturacion/comprobantesFiscalesElectronicosE-CF/Paginas/documentacionSobreE-CF.aspx"

@Composable
private fun Page(title: String, subtitle: String? = null, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val app = LocalAppState.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, subtitle = subtitle, onBack = { app.back() }) { actions() }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun StepRow(done: Boolean, title: String, detail: String, badge: String? = null, badgeWarn: Boolean = false, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(if (done) AppTheme.extra.ok else AppTheme.extra.line),
                contentAlignment = Alignment.Center,
            ) { if (done) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, fontSize = 12.sp, color = AppTheme.extra.muted)
            }
            if (badge != null) Pill(badge, if (badgeWarn) AppTheme.extra.warn else MaterialTheme.colorScheme.primary,
                if (badgeWarn) AppTheme.extra.warnSoft else MaterialTheme.colorScheme.primaryContainer)
        }
    }
}

// ================= Panel =================

@Composable
fun FiscalScreen() {
    val app = LocalAppState.current
    val s = app.settings
    val scope = rememberSafeScope()
    val cert by app.repo.fiscal.certificate.collectAsState()
    val ranges by app.repo.fiscalRanges.collectAsState()
    val docs by app.repo.ecfDocs.collectAsState()
    var testing by remember { mutableStateOf(false) }
    var confirmEnable by remember { mutableStateOf(false) }
    var confirmProd by remember { mutableStateOf(false) }
    val steps = FiscalSteps.parse(s.fiscalSteps)
    val missing = app.repo.fiscal.missing(s)
    val emitterOk = missing.none { it.startsWith("RNC") || it.startsWith("Razón") || it.startsWith("Dirección") }
    val isClient = app.repo.role == NetRole.CLIENT

    fun setEnv(e: DgiiEnv) = scope.launch { app.repo.updateSettings { it.copy(fiscalEnv = e.name) }; app.toast("Ambiente: ${e.title}") }

    Page("Facturación electrónica", "DGII · e-CF") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            DgiiEnv.entries.forEachIndexed { i, e ->
                SegmentedButton(selected = s.fiscalEnv == e.name, onClick = { if (e == DgiiEnv.PROD) confirmProd = true else setEnv(e) },
                    shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(e.title, fontSize = 12.sp) }
            }
        }
        Text(
            when (DgiiEnv.of(s.fiscalEnv)) {
                DgiiEnv.TEST -> "Pruebas: para practicar. Las facturas no tienen validez fiscal."
                DgiiEnv.CERT -> "Certificación: para el set de pruebas que pide la DGII antes de autorizarte."
                DgiiEnv.PROD -> "Producción: facturas reales con validez fiscal."
            },
            fontSize = 12.sp, color = AppTheme.extra.muted,
        )
        StepRow(emitterOk, "Datos del negocio", if (s.fiscalRnc.isBlank()) "Persona física o empresa, RNC y dirección" else "${s.fiscalName.ifBlank { s.businessName }} · ${TaxId.format(TaxId.digits(s.fiscalRnc))}") {
            app.open(Screen.FiscalEmitter)
        }
        StepRow(cert != null, "Certificado digital", cert?.let { "${it.holder} · vence ${Time.date(it.notAfter)}" } ?: "Cómo conseguirlo y agregarlo en este equipo",
            badge = if (cert == null) "Agregar" else null) { app.open(Screen.FiscalCert) }
        StepRow(FiscalSteps.AUTHORIZED in steps, "Trámite con la DGII", "${steps.size} de 7 pasos marcados",
            badge = if (FiscalSteps.AUTHORIZED in steps) null else "En proceso", badgeWarn = true) { app.open(Screen.FiscalDgii) }
        StepRow(ranges.isNotEmpty(), "Secuencias e-NCF", if (ranges.isEmpty()) "Los rangos de números que autoriza la DGII" else ranges.groupBy { it.type }.keys.joinToString(" · ") { "E$it" }) {
            app.open(Screen.FiscalSequences)
        }
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Probar conexión con la DGII", fontWeight = FontWeight.SemiBold)
                    Text("Pide un token en ${DgiiEnv.of(s.fiscalEnv).title.lowercase()} con el certificado de este equipo", fontSize = 12.sp, color = AppTheme.extra.muted)
                }
                OutlinedButton(enabled = cert != null && !testing, onClick = {
                    testing = true
                    scope.launch {
                        val msg = runCatching { withContext(app.platform.io) { app.repo.fiscal.testConnection() } }.getOrElse { "La DGII respondió: ${it.message}" }
                        testing = false
                        app.toast(msg)
                    }
                }) { if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Probar") }
            }
        }
        AppCard(color = if (s.fiscalEnabled) AppTheme.extra.okSoft else MaterialTheme.colorScheme.surface) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Facturar con e-CF", fontWeight = FontWeight.Bold)
                    Text(
                        if (s.fiscalEnabled) "Cada venta sale con su comprobante electrónico firmado." else "Mientras esté apagado, las ventas salen como hoy, sin e-CF.",
                        fontSize = 12.sp, color = AppTheme.extra.muted,
                    )
                }
                Switch(s.fiscalEnabled, { on ->
                    if (!on) scope.launch { app.repo.updateSettings { it.copy(fiscalEnabled = false) } } else confirmEnable = true
                }, enabled = !isClient)
            }
        }
        SectionTitle("Comprobantes")
        StepRow(docs.isNotEmpty(), "Comprobantes emitidos", "${docs.count { it.status == EcfStatus.ACCEPTED }} aceptados · ${docs.count { it.status == EcfStatus.SIGNED || it.status == EcfStatus.SENT }} pendientes") {
            app.open(Screen.FiscalDocs)
        }
        TextButton(onClick = { app.platform.openUrl(URL_DOCS) }) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Documentación oficial de la DGII")
        }
    }
    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text(if (missing.isEmpty() && ranges.isNotEmpty()) "Activar facturación electrónica" else "Falta completar") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (missing.isEmpty() && ranges.isNotEmpty()) {
                        Text("Desde ahora cada venta se firma como e-CF en ambiente ${DgiiEnv.of(s.fiscalEnv).title.lowercase()} y se envía a la DGII.")
                        Text("Con la facturación activa no se pueden restaurar respaldos ni cargar datos de ejemplo, para no repetir números.", fontSize = 12.sp, color = AppTheme.extra.muted)
                    } else {
                        (missing + if (ranges.isEmpty()) listOf("Al menos un rango de secuencias e-NCF") else emptyList()).forEach { Text("• $it") }
                    }
                }
            },
            confirmButton = {
                if (missing.isEmpty() && ranges.isNotEmpty()) TextButton(onClick = {
                    confirmEnable = false
                    scope.launch { app.repo.updateSettings { it.copy(fiscalEnabled = true) }; app.toast("Facturación electrónica activa") }
                }) { Text("Activar") } else TextButton(onClick = { confirmEnable = false }) { Text("Entendido") }
            },
            dismissButton = { if (missing.isEmpty() && ranges.isNotEmpty()) TextButton(onClick = { confirmEnable = false }) { Text("Cancelar") } },
        )
    }
    if (confirmProd) {
        com.vitrinapos.ui.components.ConfirmDialog(
            "Pasar a producción", "Úsalo solo cuando la DGII ya te autorizó como emisor electrónico y registraste las secuencias reales. Desde ahí las facturas tienen validez fiscal.",
            "Pasar a producción", onConfirm = { setEnv(DgiiEnv.PROD) }, onDismiss = { confirmProd = false },
        )
    }
}

// ================= Datos del negocio =================

@Composable
fun FiscalEmitterScreen() {
    val app = LocalAppState.current
    val s = app.settings
    val scope = rememberSafeScope()
    var kind by remember { mutableStateOf(s.fiscalKind) }
    var rnc by remember { mutableStateOf(s.fiscalRnc.ifBlank { s.taxId }) }
    var name by remember { mutableStateOf(s.fiscalName.ifBlank { s.businessName }) }
    var trade by remember { mutableStateOf(s.fiscalTradeName.ifBlank { s.businessName }) }
    var address by remember { mutableStateOf(s.fiscalAddress.ifBlank { s.address }) }
    var phone by remember { mutableStateOf(s.fiscalPhone.ifBlank { s.phone }) }
    var email by remember { mutableStateOf(s.fiscalEmail) }
    val check = TaxId.check(rnc)
    val idOk = if (kind == "FISICA") check.valid || check.digits.length == 11 else check.valid && check.kind == TaxId.Kind.RNC
    val ok = idOk && name.isNotBlank() && address.isNotBlank() && (email.isBlank() || Ecf.emailOk(email))

    Page("Datos del negocio", "Como aparecen en la DGII", actions = {
        TextButton(enabled = ok, onClick = {
            scope.launch {
                app.repo.updateSettings {
                    it.copy(fiscalKind = kind, fiscalRnc = check.digits, fiscalName = name.trim(), fiscalTradeName = trade.trim(),
                        fiscalAddress = address.trim(), fiscalPhone = phone.trim(), fiscalEmail = email.trim())
                }
                app.toast("Datos guardados"); app.back()
            }
        }) { Text("Guardar", fontWeight = FontWeight.Bold) }
    }) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = kind == "FISICA", onClick = { kind = "FISICA" }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Persona física") }
            SegmentedButton(selected = kind == "JURIDICA", onClick = { kind = "JURIDICA" }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Empresa (SRL, SA…)") }
        }
        Text(
            if (kind == "FISICA") "Facturas a tu nombre con tu cédula o tu RNC. El certificado digital es tuyo."
            else "Facturas a nombre de la empresa con su RNC. El certificado lo compra el representante legal o la persona que firmará.",
            fontSize = 12.sp, color = AppTheme.extra.muted,
        )
        TextInput(rnc, { rnc = it }, if (kind == "FISICA") "Cédula o RNC *" else "RNC de la empresa *", keyboard = KeyboardType.Number,
            error = if (rnc.isNotBlank() && !idOk) (if (kind == "JURIDICA" && check.kind == TaxId.Kind.CEDULA) "Una empresa usa su RNC de 9 números" else check.message) else null)
        if (idOk) Text("✓ ${TaxId.format(check.digits)}", fontSize = 12.sp, color = AppTheme.extra.ok)
        TextInput(name, { name = it }, if (kind == "FISICA") "Nombre completo, como en la DGII *" else "Razón social *")
        TextInput(trade, { trade = it }, "Nombre comercial")
        TextInput(address, { address = it.take(100) }, "Dirección *")
        TextInput(phone, { phone = it }, "Teléfono", keyboard = KeyboardType.Phone)
        TextInput(email, { email = it }, "Correo", keyboard = KeyboardType.Email, error = if (email.isNotBlank() && !Ecf.emailOk(email)) "Revisa el correo" else null)
    }
}

// ================= Certificado =================

@Composable
fun FiscalCertScreen() {
    val app = LocalAppState.current
    val s = app.settings
    val scope = rememberSafeScope()
    val cert by app.repo.fiscal.certificate.collectAsState()
    val files = rememberFileExchange()
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val isClient = app.repo.role == NetRole.CLIENT
    val juridica = s.fiscalKind == "JURIDICA"

    Page("Certificado digital", "Para firmar las facturas") {
        val c = cert
        if (c != null) {
            AppCard(color = AppTheme.extra.okSoft) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(AppTheme.extra.ok), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, null, tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.holder, fontWeight = FontWeight.Bold)
                        Text("Identificación ${TaxId.format(c.holderId)} (campo SN)", fontSize = 12.sp, color = AppTheme.extra.muted)
                    }
                }
            }
            AppCard {
                KeyValue("Emitido por", c.issuer)
                KeyValue("Válido desde", Time.date(c.notBefore))
                val days = ((c.notAfter - Time.now()) / 86_400_000L).toInt()
                KeyValue("Vence", Time.date(c.notAfter), valueColor = if (days < 30) AppTheme.extra.danger else Color.Unspecified)
                if (days < 30) Text(if (days < 0) "Venció: renuévalo con la entidad para seguir facturando." else "Vence en $days días: renuévalo a tiempo.", fontSize = 12.sp, color = AppTheme.extra.danger)
                if (!c.looksLikePerson) Text("Ojo: la DGII pide un certificado de persona física. Este parece de empresa.", fontSize = 12.sp, color = AppTheme.extra.warn)
            }
            Text("Guardado cifrado en este equipo. Lo puedes reemplazar cuando lo renueves.", fontSize = 12.sp, color = AppTheme.extra.muted)
            if (!isClient && app.repo.role == NetRole.SERVER) {
                AppCard(onClick = { scope.launch { app.repo.updateSettings { it.copy(fiscalCertOnClients = !s.fiscalCertOnClients) } } }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Permitir firmar en las cajas", fontWeight = FontWeight.SemiBold)
                            Text("Las cajas reciben una copia cifrada para facturar aunque se caiga la red", fontSize = 12.sp, color = AppTheme.extra.muted)
                        }
                        Switch(s.fiscalCertOnClients, { on -> scope.launch { app.repo.updateSettings { it.copy(fiscalCertOnClients = on) } } })
                    }
                }
            }
            TextButton(onClick = { confirmRemove = true }) { Text("Quitar el certificado de este equipo", color = MaterialTheme.colorScheme.error) }
            SectionTitle("Reemplazar o renovar")
        } else {
            SectionTitle("1. Conseguir el certificado")
            AppCard(color = AppTheme.extra.warnSoft) {
                Text(
                    if (juridica) "Lo compra el representante legal (o la persona que firmará por la empresa) con su cédula. Es un certificado de persona física para procedimientos tributarios."
                    else "Lo compras tú, con tu cédula. Es un certificado de persona física para procedimientos tributarios. Dura 1 año.",
                    fontSize = 13.sp,
                )
            }
            ProviderCard("Avansi · Viafirma", "RD$2,360 por año (precio publicado)", URL_VIAFIRMA)
            ProviderCard("Digifirma · Cámara de Comercio de Santo Domingo", "US$29.95 por 1 año · US$45 por 2 años (precio publicado)", URL_DIGIFIRMA)
            Text("La compra y la verificación de identidad se hacen en la página de la entidad. Al final descargas un archivo .p12 y eliges su contraseña. El certificado gratuito de la DGII solo sirve en su facturador.",
                fontSize = 12.sp, color = AppTheme.extra.muted)
            SectionTitle("2. Agregarlo aquí")
        }
        if (isClient) {
            OutlinedButton(onClick = {
                busy = true
                scope.launch {
                    runCatching { app.graph.client!!.fetchFiscalCertificate() }
                        .onSuccess { app.toast("Certificado recibido del equipo principal") }
                        .onFailure { app.toast(it.message ?: "No se pudo traer el certificado") }
                    busy = false
                }
            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = !busy) { Text("Traer el certificado del equipo principal") }
            Text("O agrega el archivo .p12 directamente en esta caja:", fontSize = 12.sp, color = AppTheme.extra.muted)
        }
        Surface(
            onClick = { files.open(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*"), read = { fileBytes = it.readBytes() }, onDone = { ok -> if (!ok) app.toast("No se abrió el archivo") }) },
            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.UploadFile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Text(if (fileBytes == null) "Elegir el archivo .p12 o .pfx" else "Archivo listo (${(fileBytes!!.size + 1023) / 1024} KB)", fontWeight = FontWeight.SemiBold)
            }
        }
        TextInput(password, { password = it; error = null }, "Contraseña del certificado", visual = PasswordVisualTransformation(), error = error)
        Button(
            enabled = fileBytes != null && password.isNotEmpty() && !busy,
            onClick = {
                busy = true
                scope.launch {
                    runCatching { withContext(app.platform.io) { app.repo.fiscal.installCertificate(fileBytes!!, password.toCharArray()) } }
                        .onSuccess { fileBytes = null; password = ""; error = null; app.toast("Certificado verificado y guardado") }
                        .onFailure { error = it.message }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
        ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) else Text("Verificar y guardar", fontWeight = FontWeight.Bold) }
        Text("Al guardar, la app lee el certificado, hace una firma de prueba y lo guarda cifrado. La contraseña no se guarda en texto.", fontSize = 12.sp, color = AppTheme.extra.muted)
    }
    if (confirmRemove) com.vitrinapos.ui.components.ConfirmDialog(
        "Quitar el certificado", "Este equipo no podrá firmar facturas hasta que lo agregues otra vez.", "Quitar",
        onConfirm = { app.repo.fiscal.removeCertificate(); app.toast("Certificado quitado") }, onDismiss = { confirmRemove = false }, destructive = true,
    )
}

@Composable
private fun ProviderCard(name: String, price: String, url: String) {
    val app = LocalAppState.current
    AppCard(onClick = { if (!app.platform.openUrl(url)) app.toast("Abre $url en el navegador") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold)
                Text(price, fontSize = 12.sp, color = AppTheme.extra.muted)
            }
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Abrir", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

// ================= Trámite con la DGII =================

@Composable
fun FiscalDgiiScreen() {
    val app = LocalAppState.current
    val s = app.settings
    val scope = rememberSafeScope()
    val done = FiscalSteps.parse(s.fiscalSteps)
    val juridica = s.fiscalKind == "JURIDICA"
    fun toggle(k: String) = scope.launch {
        app.repo.updateSettings { it.copy(fiscalSteps = FiscalSteps.format(FiscalSteps.parse(it.fiscalSteps).let { st -> if (k in st) st - k else st + k })) }
    }
    val steps = listOf(
        Triple(FiscalSteps.CERT_BOUGHT, "Comprar el certificado digital", if (juridica) "A nombre del representante legal o de quien firmará." else "A tu nombre, con tu cédula."),
        Triple(FiscalSteps.OFV_ACCESS, "Entrar a la Oficina Virtual (OFV)", "Con el usuario y la clave de la DGII del negocio."),
        Triple(FiscalSteps.REQUEST_SENT, "Solicitar ser emisor electrónico", "En la OFV, solicitud de facturación electrónica. Indica que usas un sistema propio (Vitrina POS)."),
        Triple(FiscalSteps.SIGNER_ROLE, "Asignar los roles", if (juridica) "Delegar a la persona del certificado como Firmante y Aprobador comercial; ella acepta desde su buzón de la OFV." else "Asignarte como Firmante y Aprobador comercial."),
        Triple(FiscalSteps.TESTS_DONE, "Set de pruebas", "Pon el ambiente en Certificación y factura los casos que indique la DGII; Vitrina los firma y los envía."),
        Triple(FiscalSteps.SWORN, "Declaración jurada", "Se firma en la OFV al terminar las pruebas."),
        Triple(FiscalSteps.AUTHORIZED, "Autorización y secuencias", "Pide las secuencias e-NCF en la OFV, regístralas en Vitrina y cambia a Producción."),
    )
    Page("Trámite con la DGII", "${done.size} de ${steps.size} pasos") {
        Text("Estos pasos se hacen en la página de la DGII; aquí vas marcando lo que ya hiciste. La DGII no tiene un servicio para hacerlos desde otra aplicación.",
            fontSize = 13.sp, color = AppTheme.extra.muted)
        Button(onClick = { if (!app.platform.openUrl(URL_OFV)) app.toast("Abre $URL_OFV en el navegador") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Abrir la Oficina Virtual de la DGII")
        }
        AppCard(padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            steps.forEachIndexed { i, (key, title, detail) ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().clickable { toggle(key) }.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(if (key in done) AppTheme.extra.ok else MaterialTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.Center,
                    ) { if (key in done) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) else Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.SemiBold)
                        Text(detail, fontSize = 12.sp, color = AppTheme.extra.muted)
                    }
                }
            }
        }
        AppCard(color = AppTheme.extra.warnSoft) {
            Text("Servicios en internet", fontWeight = FontWeight.SemiBold)
            Text("Para las pruebas de comunicación, la DGII exige que el emisor publique en internet tres direcciones (autenticación, recepción y aprobación comercial) para recibir facturas de sus suplidores. Esa parte necesita un servidor en la nube y es la siguiente fase de Vitrina.",
                fontSize = 12.sp)
        }
    }
}

// ================= Secuencias =================

@Composable
fun FiscalSequencesScreen() {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    val ranges by app.repo.fiscalRanges.collectAsState()
    val blocks by app.repo.fiscalBlocks.collectAsState()
    val docs by app.repo.ecfDocs.collectAsState()
    var type by remember { mutableStateOf(EcfType.CONSUMO) }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var expires by remember { mutableStateOf("") }
    val fmt = DateTimeFormatter.ofPattern("d/M/yyyy")
    val expDate = runCatching { LocalDate.parse(expires.trim(), fmt) }.getOrNull()
    val fromN = from.filter(Char::isDigit).toLongOrNull()
    val toN = to.filter(Char::isDigit).toLongOrNull()
    val valid = fromN != null && toN != null && fromN in 1..toN && (expires.isBlank() || expDate != null)

    Page("Secuencias e-NCF", "Números autorizados por la DGII") {
        if (ranges.isEmpty()) Text("Todavía no hay rangos. En la Oficina Virtual pide la autorización de secuencias y escribe aquí lo que te asignen.", fontSize = 13.sp, color = AppTheme.extra.muted)
        ranges.forEach { r ->
            val t = EcfType.of(r.type.toInt())
            val used = docs.count { it.type == r.type && it.encf.takeLast(10).toLongOrNull()?.let { n -> n in r.fromNo..r.toNo } == true }
            val total = r.toNo - r.fromNo + 1
            val reserved = blocks.filter { it.parentId == r.id }
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${t.prefix} · ${t.short}", fontWeight = FontWeight.Bold)
                        Text("${Ecf.encf(t, r.fromNo)} a ${Ecf.encf(t, r.toNo)}", fontSize = 12.sp, color = AppTheme.extra.muted)
                        Text("Usados $used de $total" + (r.expiresAt?.let { " · vence ${Time.date(it)}" } ?: ""), fontSize = 12.sp,
                            color = if (total - used < 50) AppTheme.extra.warn else AppTheme.extra.muted)
                        if (reserved.isNotEmpty()) Text(if (reserved.size == 1) "1 bloque entregado a un equipo" else "${reserved.size} bloques entregados a equipos", fontSize = 11.sp, color = AppTheme.extra.muted)
                    }
                    if (reserved.isEmpty()) IconButton(onClick = { scope.launch { runCatching { app.repo.deleteFiscalRange(r.id) }.onFailure { app.toast(it.message ?: "No se pudo borrar") } } }) {
                        Icon(Icons.Rounded.Delete, "Borrar", tint = AppTheme.extra.danger)
                    }
                }
            }
        }
        SectionTitle("Registrar un rango autorizado")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(EcfType.CONSUMO, EcfType.CREDITO_FISCAL, EcfType.NOTA_CREDITO, EcfType.NOTA_DEBITO, EcfType.GUBERNAMENTAL).forEach { t ->
                FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.prefix, fontSize = 12.sp) }, shape = RoundedCornerShape(10.dp))
            }
        }
        Text(type.title, fontSize = 12.sp, color = AppTheme.extra.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextInput(from, { from = it }, "Desde (número)", Modifier.weight(1f), keyboard = KeyboardType.Number)
            TextInput(to, { to = it }, "Hasta (número)", Modifier.weight(1f), keyboard = KeyboardType.Number)
        }
        TextInput(expires, { expires = it }, "Vence (día/mes/año)", error = if (expires.isNotBlank() && expDate == null) "Escribe la fecha como 31/12/2027" else null)
        if (fromN != null && toN != null && valid) Text("${Ecf.encf(type, fromN)} a ${Ecf.encf(type, toN)} · ${toN - fromN + 1} números", fontSize = 12.sp, color = AppTheme.extra.ok)
        Button(enabled = valid, onClick = {
            scope.launch {
                val exp = expDate?.atTime(23, 59, 59)?.atZone(Ecf.zone)?.toInstant()?.toEpochMilli()
                runCatching { app.repo.saveFiscalRange(type.code, fromN!!, toN!!, exp) }
                    .onSuccess { from = ""; to = ""; expires = ""; app.toast("Rango registrado") }
                    .onFailure { app.toast(it.message ?: "No se pudo registrar") }
            }
        }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) { Text("Registrar rango") }
        Text("Cada equipo toma bloques de ${app.settings.fiscalBlockSize} números de estos rangos, así una caja puede facturar aunque se caiga la red. Los números que no se usen se anulan con el formato ANECF.",
            fontSize = 12.sp, color = AppTheme.extra.muted)
    }
}

// ================= Comprobantes =================

@Composable
fun FiscalDocsScreen() {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    val docs by app.repo.ecfDocs.collectAsState()
    val note by app.repo.fiscal.sendingNote.collectAsState()
    var sending by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(0) }
    val sym = app.settings.currencySymbol
    val pending = docs.filter { it.status == EcfStatus.SIGNED || it.status == EcfStatus.SENT }
    val bad = docs.filter { it.status == EcfStatus.REJECTED || it.status == EcfStatus.ERROR }
    val oldest = docs.filter { it.status == EcfStatus.SIGNED }.minOfOrNull { it.signedAt }
    val list = when (filter) { 1 -> pending; 2 -> bad; else -> docs }
    val canSend = app.repo.role != NetRole.CLIENT

    Page("Comprobantes e-CF", "${DgiiEnv.of(app.settings.fiscalEnv).title} · últimos ${docs.size}", actions = {
        if (canSend) TextButton(enabled = !sending, onClick = {
            sending = true
            scope.launch {
                val n = runCatching { withContext(app.platform.io) { app.repo.fiscal.sendPending() } }.getOrElse { app.toast(it.message ?: "Error"); 0 }
                sending = false
                app.toast(if (n > 0) "$n actualizados" else note.ifBlank { "Nada nuevo" })
            }
        }) { Text(if (sending) "Enviando…" else "Enviar ahora") }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple("Aceptados", docs.count { it.status == EcfStatus.ACCEPTED || it.status == EcfStatus.CONDITIONAL }, AppTheme.extra.ok),
                Triple("Pendientes", pending.size, AppTheme.extra.warn),
                Triple("Rechazados", bad.size, AppTheme.extra.danger),
            ).forEachIndexed { i, (label, n, color) ->
                AppCard(Modifier.weight(1f), onClick = { filter = if (i == 0) 0 else i }, padding = PaddingValues(10.dp)) {
                    Text(label, fontSize = 12.sp, color = AppTheme.extra.muted)
                    Text(n.toString(), fontWeight = FontWeight.Bold, fontSize = 22.sp, color = color)
                }
            }
        }
        if (oldest != null) {
            val left = ((oldest + Fiscal.CONTINGENCY_MS - Time.now()) / 3_600_000L).toInt()
            AppCard(color = if (left < 12) AppTheme.extra.dangerSoft else AppTheme.extra.warnSoft) {
                Text(
                    "${docs.count { it.status == EcfStatus.SIGNED }} firmados esperan envío. " +
                        if (left > 0) "Plazo para enviarlos: $left h." else "Pasó el plazo de 72 h: envíalos cuanto antes.",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
                if (note.isNotBlank()) Text(note, fontSize = 12.sp, color = AppTheme.extra.muted)
            }
        }
        if (docs.isEmpty()) Text("Todavía no hay comprobantes. Aparecen aquí al cobrar con la facturación electrónica activa.", color = AppTheme.extra.muted)
        list.forEach { d ->
            AppCard(onClick = d.saleId?.takeIf { it > 0 }?.let { sid -> { app.open(Screen.SaleDetail(sid)) } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.encf, fontWeight = FontWeight.Bold)
                        Text("${Money.format(d.total, sym)} · ${EcfType.of(d.type.toInt()).short}" + (d.buyerName.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            fontSize = 12.sp, color = AppTheme.extra.muted)
                        if (d.message.isNotBlank() && d.status != EcfStatus.ACCEPTED) Text(d.message, fontSize = 12.sp, color = if (d.status == EcfStatus.REJECTED || d.status == EcfStatus.ERROR) AppTheme.extra.danger else AppTheme.extra.muted, maxLines = 3)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        EcfStatusPill(d.status)
                        if (d.status == EcfStatus.ERROR && canSend) TextButton(onClick = { app.repo.fiscal.retry(d.id) }) { Text("Reintentar", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

