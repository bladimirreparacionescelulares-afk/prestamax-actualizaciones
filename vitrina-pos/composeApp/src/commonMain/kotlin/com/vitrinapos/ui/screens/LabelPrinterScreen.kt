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
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.labels.LabelContent
import com.vitrinapos.labels.LabelLanguage
import com.vitrinapos.labels.LabelOptions
import com.vitrinapos.labels.LabelTemplate
import com.vitrinapos.labels.NetworkPrinter
import com.vitrinapos.platform.PrinterDevice
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.components.rememberSafeScope
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun LabelPrinterScreen() {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    val saved = app.settings
    var d by remember { mutableStateOf(saved) }
    var printers by remember { mutableStateOf<List<PrinterDevice>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var netHost by remember { mutableStateOf(NetworkPrinter.parse(saved.labelPrinterId)?.first ?: "") }
    var testing by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()

    fun save() = scope.launch { app.repo.saveSettings(d); app.toast("Etiquetadora guardada") }

    fun search() {
        searching = true
        app.requestPrinterPermission {
            scope.launch {
                printers = runCatching { withContext(app.platform.io) { app.platform.listPrinters() } }
                    .getOrElse { app.toast("No se pudo buscar: ${it.message}"); emptyList() }
                searching = false
            }
        }
    }

    fun test() {
        testing = true
        val o = LabelOptions(LabelTemplate.PRICE_QR, d.labelW, d.labelH)
        val content = LabelContent("Prueba de etiqueta ${o.widthMm.roundToInt()} × ${o.heightMm.roundToInt()} mm", "$1,234.00", "2000000000008", d.businessName)
        app.requestPrinterPermission {
            scope.launch {
                try {
                    app.repo.saveSettings(d)
                    app.printLabels(o, listOf(content to 1), measurer, d)
                    app.toast("Etiqueta de prueba enviada")
                } finally { testing = false }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Etiquetadora", subtitle = "Impresora de etiquetas de este equipo", onBack = { app.back() }) {
            if (d != saved) TextButton(onClick = { save() }) { Text("Guardar", fontWeight = FontWeight.Bold) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Impresora")
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            d.labelPrinterId.startsWith(NetworkPrinter.PREFIX) -> Icons.Rounded.Wifi
                            app.platform.isDesktop -> Icons.Rounded.Print
                            else -> Icons.Rounded.Bluetooth
                        }, null, tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.labelPrinterName.ifBlank { "Ninguna etiquetadora elegida" }, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (app.platform.isDesktop) "Impresoras instaladas en Windows (USB) o de red"
                            else "Vincula la etiquetadora en el Bluetooth del teléfono, o usa una de red",
                            fontSize = 12.sp, color = AppTheme.extra.muted,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = ::search, enabled = !searching, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text(if (searching) "Buscando…" else if (app.platform.isDesktop) "Ver impresoras instaladas" else "Ver impresoras Bluetooth")
                }
                printers?.let { list ->
                    if (list.isEmpty()) Text("No se encontraron impresoras.", color = AppTheme.extra.muted, modifier = Modifier.padding(top = 8.dp))
                    list.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { d = d.copy(labelPrinterId = p.id, labelPrinterName = p.name) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(if (d.labelPrinterId == p.id) MaterialTheme.colorScheme.primary else AppTheme.extra.line))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(p.name, fontWeight = if (d.labelPrinterId == p.id) FontWeight.Bold else FontWeight.Normal)
                                if (p.detail.isNotBlank()) Text(p.detail, fontSize = 12.sp, color = AppTheme.extra.muted)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput(netHost, { netHost = it.trim() }, "O impresora de red (IP)", Modifier.weight(1f), keyboard = KeyboardType.Uri, placeholder = "192.168.1.50")
                    Button(
                        onClick = { d = d.copy(labelPrinterId = NetworkPrinter.id(netHost), labelPrinterName = "Red · $netHost") },
                        enabled = netHost.isNotBlank(), shape = RoundedCornerShape(12.dp),
                    ) { Text("Usar") }
                }
            }

            SectionTitle("Lenguaje de la impresora")
            AppCard(padding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                LabelLanguage.entries.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { d = d.copy(labelLanguage = l.name) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(d.labelLanguage == l.name, { d = d.copy(labelLanguage = l.name) })
                        Column(Modifier.weight(1f)) {
                            Text(l.title, fontWeight = FontWeight.SemiBold)
                            Text(l.hint, fontSize = 12.sp, color = AppTheme.extra.muted)
                        }
                    }
                }
            }
            Text("¿No sabes cuál? Casi todas las etiquetadoras usan TSPL. Si es Zebra, elige ZPL. El manual de la impresora lo indica.", fontSize = 13.sp, color = AppTheme.extra.muted)

            SectionTitle("Ajustes")
            AppCard {
                Text("Resolución", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(203, 300).forEachIndexed { i, dpi ->
                        SegmentedButton(selected = d.labelDpi == dpi, onClick = { d = d.copy(labelDpi = dpi) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                            Text("$dpi dpi")
                        }
                    }
                }
                Text("La mayoría son de 203 dpi (8 puntos por mm).", fontSize = 12.sp, color = AppTheme.extra.muted)
                Spacer(Modifier.height(12.dp))
                Text("Tamaño de la etiqueta: ${d.labelW.roundToInt()} × ${d.labelH.roundToInt()} mm (se cambia en el diseño)", fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                StepRow("Espacio entre etiquetas", "${d.labelGap.fmt1()} mm", d.labelGap.toDouble()) { d = d.copy(labelGap = it.toFloat().coerceIn(0f, 10f)) }
                Spacer(Modifier.height(10.dp))
                Text("Oscuridad: ${d.labelDarkness}", fontWeight = FontWeight.SemiBold)
                Slider(d.labelDarkness.toFloat(), { d = d.copy(labelDarkness = it.roundToInt()) }, valueRange = 1f..15f, steps = 13)
                StepRow("Correr a la derecha", "${d.labelOffX.fmt1()} mm", d.labelOffX.toDouble(), step = 0.5) { d = d.copy(labelOffX = it.toFloat().coerceIn(-5f, 5f)) }
                StepRow("Correr hacia abajo", "${d.labelOffY.fmt1()} mm", d.labelOffY.toDouble(), step = 0.5) { d = d.copy(labelOffY = it.toFloat().coerceIn(-5f, 5f)) }
                Text("Si la impresión sale corrida, ajústala aquí y vuelve a probar.", fontSize = 12.sp, color = AppTheme.extra.muted)
            }

            Button(
                onClick = ::test, enabled = !testing && d.labelPrinterId.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta),
            ) {
                if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AppTheme.extra.onCta)
                else Text("Imprimir etiqueta de prueba", fontWeight = FontWeight.Bold)
            }
            if (d != saved) Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("Guardar") }
            AppCard(color = MaterialTheme.colorScheme.surfaceContainer) {
                Text("Al comprar una etiquetadora", fontWeight = FontWeight.Bold)
                Text(
                    "Busca una que diga TSPL o ZPL, con Bluetooth o USB (por ejemplo Xprinter XP-365B o XP-420B, o una Zebra). " +
                        "Evita las que solo funcionan con su propia app (como Niimbot o Phomemo): no aceptan etiquetas de otras apps.",
                    fontSize = 13.sp, color = AppTheme.extra.muted,
                )
            }
        }
    }
}

private fun Float.fmt1(): String = ((this * 10).roundToInt() / 10f).toString().removeSuffix(".0").replace('.', ',')

@Composable
private fun StepRow(label: String, value: String, current: Double, step: Double = 0.5, onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(value, fontSize = 12.sp, color = AppTheme.extra.muted)
        }
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange(current - step) }) { Text("−", fontSize = 20.sp) }
            TextButton(onClick = { onChange(current + step) }) { Text("+", fontSize = 20.sp) }
        }
    }
}
