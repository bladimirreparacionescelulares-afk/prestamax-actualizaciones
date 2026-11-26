package com.vitrinapos.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.AppState
import com.vitrinapos.LocalAppState
import com.vitrinapos.Screen
import com.vitrinapos.data.Money
import com.vitrinapos.data.Time
import com.vitrinapos.db.Product
import com.vitrinapos.labels.LabelContent
import com.vitrinapos.labels.LabelLimits
import com.vitrinapos.labels.LabelOptions
import com.vitrinapos.labels.LabelPdf
import com.vitrinapos.labels.LabelSize
import com.vitrinapos.labels.LabelTemplate
import com.vitrinapos.labels.SheetPreset
import com.vitrinapos.labels.PT_PER_MM
import com.vitrinapos.platform.rememberBarcodeScanner
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.EmptyState
import com.vitrinapos.ui.components.LabelPreview
import com.vitrinapos.ui.components.ProductImage
import com.vitrinapos.ui.components.QtyStepper
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SearchBox
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.SquareIconButton
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.components.matches
import com.vitrinapos.ui.components.rememberSafeScope
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

fun AppState.labelContent(p: Product, code: String = p.code): LabelContent {
    val s = settingsNow
    return LabelContent(name = p.name, price = Money.format(p.price, s.currencySymbol), code = code, store = s.businessName)
}

/** Copias sugeridas: una por unidad en stock (mínimo 1). */
fun Product.suggestedCopies(): Int = if (trackStock == 1L && stock >= 1) ceil(stock).toInt().coerceAtMost(500) else 1

// ================= Elegir productos =================

@Composable
fun LabelsScreen() {
    val app = LocalAppState.current
    val products by app.repo.products.collectAsState()
    val categories by app.repo.categories.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<Long?>(null) }
    val selected = remember { mutableStateMapOf<Long, Int>().apply { app.labelJob.forEach { put(it.productId, it.copies) } } }
    val scanner = rememberBarcodeScanner { code ->
        val p = app.repo.productForCode(code, products)
        if (p == null) app.toast("No hay producto con el código $code")
        else { selected[p.id] = (selected[p.id] ?: 0) + 1; app.toast("${p.name}: ${selected[p.id]} etiquetas") }
    }
    val list = products.filter { p ->
        p.matches(query) && (category == null || p.categoryId == category) && when (filter) {
            "nocode" -> p.code.isBlank()
            "selected" -> p.id in selected
            else -> true
        }
    }
    val total = selected.values.sum()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Etiquetas", subtitle = "Elige qué productos etiquetar", onBack = if (app.stack.isNotEmpty()) ({ app.back() }) else null) {
            if (scanner != null) SquareIconButton(Icons.Rounded.QrCodeScanner, "Escanear productos", primary = true) {
                app.labelJob.clear()
                app.labelJob.addAll(selected.map { (id, c) -> AppState.LabelItem(id, c) })
                app.open(Screen.Scanner(ScanMode.LABELS))
            }
        }
        SearchBox(query, { query = it }, "Buscar por nombre o código", Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { CategoryChip("Todos", filter == null && category == null) { filter = null; category = null } }
            item { CategoryChip("Elegidos · ${selected.size}", filter == "selected") { filter = if (filter == "selected") null else "selected" } }
            item { CategoryChip("Sin código · ${products.count { it.code.isBlank() }}", filter == "nocode") { filter = if (filter == "nocode") null else "nocode" } }
            items(categories, key = { it.id }) { c -> CategoryChip(c.name, category == c.id) { category = if (category == c.id) null else c.id } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Copias: una por unidad en stock", fontSize = 13.sp, color = AppTheme.extra.muted, modifier = Modifier.weight(1f))
            TextButton(onClick = { list.forEach { if (it.id !in selected) selected[it.id] = it.suggestedCopies() } }) { Text("Elegir todos") }
            if (selected.isNotEmpty()) TextButton(onClick = { selected.clear() }) { Text("Quitar todos") }
        }
        if (products.isEmpty()) EmptyState(Icons.Rounded.QrCode2, "Sin productos", "Crea productos para imprimir sus etiquetas.")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.id }) { p ->
                val copies = selected[p.id]
                val on = copies != null
                Surface(
                    onClick = { if (on) selected.remove(p.id) else selected[p.id] = p.suggestedCopies() },
                    shape = RoundedCornerShape(14.dp),
                    color = if (on) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else AppTheme.extra.line),
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { if (it) selected[p.id] = p.suggestedCopies() else selected.remove(p.id) })
                        ProductImage(p.imagePath, p.name, Modifier.size(44.dp), textSize = 14)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (p.code.ifBlank { "Sin código: se asigna al imprimir" }) + if (p.trackStock == 1L) " · stock ${p.stock.roundToInt()}" else "",
                                fontSize = 12.sp, color = AppTheme.extra.muted, maxLines = 1,
                            )
                        }
                        if (copies != null) QtyStepper(copies.toDouble(), { selected[p.id] = it.roundToInt().coerceIn(1, 500) })
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${selected.size} productos", fontSize = 12.sp, color = AppTheme.extra.muted)
                    Text("$total etiquetas", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Button(
                    onClick = { app.openLabelsFor(selected.map { (id, c) -> AppState.LabelItem(id, c) }) },
                    enabled = total > 0,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta),
                    modifier = Modifier.height(50.dp),
                ) { Text("Diseñar", fontWeight = FontWeight.Bold); Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp)) }
            }
        }
    }
}

// ================= Diseñador =================

@Composable
fun LabelDesignerScreen() {
    val app = LocalAppState.current
    val scope = rememberSafeScope()
    val saved = app.settings
    val products by app.repo.products.collectAsState()
    var opts by remember { mutableStateOf(saved.labelOptions) }
    var output by remember { mutableStateOf(saved.labelOutput) }
    var sheetIndex by remember { mutableIntStateOf(saved.labelSheet.coerceIn(0, SheetPreset.all.lastIndex)) }
    var startAt by remember { mutableIntStateOf(1) }
    var page by remember { mutableIntStateOf(0) }
    var custom by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var codes by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    val job = app.labelJob.toList()
    val items = job.mapNotNull { j -> products.firstOrNull { it.id == j.productId }?.let { it to j.copies } }
    val total = items.sumOf { it.second }
    val sheet = SheetPreset.all[sheetIndex]
    val previewOpts = if (output == "sheet") opts.copy(widthMm = sheet.labelW, heightMm = sheet.labelH) else opts

    // Los productos sin código reciben uno ahora, para que su QR sirva al escanear.
    LaunchedEffect(job) {
        val missing = items.filter { it.first.code.isBlank() }.map { it.first.id }
        if (missing.isNotEmpty()) codes = runCatching { app.repo.ensureCodes(missing) }.getOrElse {
            app.toast("No se pudo asignar código a ${missing.size} productos: ${it.message}"); emptyMap()
        }
    }

    fun content(p: Product) = app.labelContent(p, p.code.ifBlank { codes[p.id] ?: "" })
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val hasPrinter = saved.labelPrinterId.isNotBlank()

    fun printNow() {
        busy = true
        app.requestPrinterPermission {
            scope.launch {
                try {
                    app.repo.saveSettings(app.settingsNow.withLabelOptions(opts).copy(labelOutput = output))
                    app.printLabels(opts, items.map { (p, n) -> content(p) to n }, measurer) { done, all -> progress = done to all }
                    app.toast("$total etiquetas enviadas a ${app.settingsNow.labelPrinterName}")
                } finally {
                    busy = false
                    progress = null
                }
            }
        }
    }

    fun create() {
        busy = true
        scope.launch {
            try {
                app.repo.saveSettings(app.settingsNow.withLabelOptions(opts).copy(labelOutput = output, labelSheet = sheetIndex))
                val labels = items.flatMap { (p, n) -> List(n) { content(p) } }
                val file = File(app.repo.docsDir, "etiquetas-${Time.now()}.pdf")
                withContext(app.platform.io) {
                    if (output == "sheet") {
                        app.platform.writePdf(file, sheet.pageW * PT_PER_MM, sheet.pageH * PT_PER_MM) { c -> LabelPdf.drawSheet(c, sheet, opts, labels, startAt) }
                    } else {
                        val (w, h) = LabelPdf.pageSizePt(opts)
                        app.platform.writePdf(file, w, h) { c -> LabelPdf.drawRoll(c, opts, labels) }
                    }
                }
                if (app.platform.isDesktop) app.platform.openFile(file) else app.platform.shareFile(file, "application/pdf", "Etiquetas")
                app.toast("PDF de $total etiquetas listo")
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Diseño de etiqueta", subtitle = "$total etiquetas · ${items.size} productos", onBack = { app.back() })
        if (items.isEmpty()) {
            EmptyState(Icons.Rounded.QrCode2, "No hay productos elegidos", "Elige los productos en Etiquetas.") {
                Button(onClick = { app.replace(Screen.Labels) }) { Text("Elegir productos") }
            }
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Vista previa
            val current = items[page.coerceIn(0, items.lastIndex)].first
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 18.dp, horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LabelPreview(previewOpts, content(current), Modifier.fillMaxWidth().padding(horizontal = 18.dp), maxHeightDp = 190f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Anterior") }
                    Text("${page + 1} de ${items.size} · ${current.name}", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                    IconButton(onClick = { page = (page + 1).coerceAtMost(items.lastIndex) }, enabled = page < items.lastIndex) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Siguiente") }
                }
                Text(
                    "${fmt(previewOpts.widthMm)} × ${fmt(previewOpts.heightMm)} mm" + if (output == "sheet") " · ${sheet.title.substringBefore(" ·")}" else "",
                    fontSize = 12.sp, color = AppTheme.extra.muted,
                )
            }

            SectionTitle("Plantilla")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabelTemplate.entries.forEach { t ->
                    val on = opts.template == t
                    Surface(
                        onClick = { opts = opts.copy(template = t) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else AppTheme.extra.line),
                        modifier = Modifier.width(150.dp),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LabelPreview(previewOpts.copy(template = t), content(current), Modifier.fillMaxWidth().height(70.dp), maxHeightDp = 70f)
                            Text(t.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(t.hint, fontSize = 11.sp, color = AppTheme.extra.muted, maxLines = 2, lineHeight = 13.sp)
                        }
                    }
                }
            }

            SectionTitle("Dónde se imprime")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = output == "roll", onClick = { output = "roll" }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Etiquetadora (rollo)", fontSize = 13.sp) }
                SegmentedButton(selected = output == "sheet", onClick = { output = "sheet" }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Hoja A4 / Carta", fontSize = 13.sp) }
            }
            if (output == "roll") {
                Text("Tamaño de la etiqueta (mm)", fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelSize.presets.forEach { sz ->
                        FilterChip(
                            selected = opts.widthMm == sz.w && opts.heightMm == sz.h,
                            onClick = { opts = opts.copy(widthMm = sz.w, heightMm = sz.h) },
                            label = { Text(sz.text) }, shape = RoundedCornerShape(12.dp),
                        )
                    }
                    val isCustom = LabelSize.presets.none { it.w == opts.widthMm && it.h == opts.heightMm }
                    FilterChip(
                        selected = isCustom, onClick = { custom = true },
                        label = { Text(if (isCustom) "${fmt(opts.widthMm)} × ${fmt(opts.heightMm)}" else "Otro tamaño…") }, shape = RoundedCornerShape(12.dp),
                    )
                }
                AppCard(onClick = { app.open(Screen.LabelPrinter) }, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (hasPrinter) saved.labelPrinterName else "Configurar la etiquetadora", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (hasPrinter) "${saved.labelPrinter.language.title} · ${saved.labelDpi} dpi · toca para cambiar"
                                else "Elige la impresora y su lenguaje (TSPL, ZPL o ESC/POS)",
                                fontSize = 12.sp, color = AppTheme.extra.muted,
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                    }
                }
            } else {
                SheetPreset.all.forEachIndexed { i, sp ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { sheetIndex = i; startAt = startAt.coerceAtMost(sp.perSheet) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(sheetIndex == i, { sheetIndex = i })
                        Text(sp.title, fontSize = 14.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Empezar en la etiqueta", fontWeight = FontWeight.SemiBold)
                        Text("Para aprovechar una hoja ya usada", fontSize = 12.sp, color = AppTheme.extra.muted)
                    }
                    QtyStepper(startAt.toDouble(), { startAt = it.roundToInt().coerceIn(1, sheet.perSheet) })
                }
                Text(
                    "Se usarán ${LabelPdf.sheetsNeeded(sheet, total, startAt)} ${if (LabelPdf.sheetsNeeded(sheet, total, startAt) == 1) "hoja" else "hojas"}. " +
                        "Imprime en una impresora normal al 100 % (sin \"ajustar a la página\").",
                    fontSize = 13.sp, color = AppTheme.extra.muted,
                )
            }

            SectionTitle("Qué se muestra")
            AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                ToggleLine("Nombre del producto", opts.showName) { opts = opts.copy(showName = it) }
                ToggleLine("Precio", opts.showPrice) { opts = opts.copy(showPrice = it) }
                ToggleLine("Código en texto", opts.showCode) { opts = opts.copy(showCode = it) }
                ToggleLine("Nombre de la tienda", opts.showStore) { opts = opts.copy(showStore = it) }
            }
            Text("El QR lleva el código del producto: al escanearlo en Vender, el producto se agrega a la venta.", fontSize = 13.sp, color = AppTheme.extra.muted)
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
          Column {
            if (output == "roll" && hasPrinter) {
                Button(
                    onClick = ::printNow, enabled = !busy && total > 0,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp).height(54.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta),
                ) {
                    val pr = progress
                    if (busy && pr != null) Text("Enviando ${pr.first} de ${pr.second} productos…", fontWeight = FontWeight.Bold)
                    else if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AppTheme.extra.onCta)
                    else { Icon(Icons.Rounded.Print, null); Spacer(Modifier.width(8.dp)); Text("Imprimir $total etiquetas", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                }
                TextButton(onClick = ::create, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("o crear PDF") }
            } else Button(
                onClick = ::create, enabled = !busy && total > 0,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AppTheme.extra.onCta)
                else {
                    Icon(Icons.Rounded.PictureAsPdf, null); Spacer(Modifier.width(8.dp))
                    Text(
                        if (output == "sheet") "Crear PDF · ${LabelPdf.sheetsNeeded(sheet, total, startAt)} hojas" else "Crear PDF · $total etiquetas",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    )
                }
            }
          }
        }
    }
    if (custom) {
        CustomSizeDialog(opts.widthMm, opts.heightMm, onDismiss = { custom = false }) { w, h -> opts = opts.copy(widthMm = w, heightMm = h); custom = false }
    }
}

private fun fmt(v: Float) = if (v % 1f == 0f) v.toInt().toString() else ((v * 10).roundToInt() / 10f).toString().replace('.', ',')

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun CustomSizeDialog(w0: Float, h0: Float, onDismiss: () -> Unit, onSave: (Float, Float) -> Unit) {
    var w by remember { mutableStateOf(fmt(w0)) }
    var h by remember { mutableStateOf(fmt(h0)) }
    val wv = w.replace(',', '.').toFloatOrNull()
    val hv = h.replace(',', '.').toFloatOrNull()
    val wErr = if (wv == null || wv < LabelLimits.MIN_W || wv > LabelLimits.MAX_W) "Entre ${LabelLimits.MIN_W.toInt()} y ${LabelLimits.MAX_W.toInt()} mm" else null
    val hErr = if (hv == null || hv < LabelLimits.MIN_H || hv > LabelLimits.MAX_H) "Entre ${LabelLimits.MIN_H.toInt()} y ${LabelLimits.MAX_H.toInt()} mm" else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tamaño de la etiqueta") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Mide el ancho y el alto de una etiqueta del rollo, sin contar el espacio entre etiquetas.", fontSize = 13.sp, color = AppTheme.extra.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextInput(w, { w = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) }, "Ancho (mm)", Modifier.weight(1f), keyboard = KeyboardType.Decimal, error = wErr)
                    TextInput(h, { h = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) }, "Alto (mm)", Modifier.weight(1f), keyboard = KeyboardType.Decimal, error = hErr)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(wv!!, hv!!) }, enabled = wErr == null && hErr == null) { Text("Usar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ================= Pregunta al crear un producto =================

@Composable
fun LabelPromptDialog(productId: Long) {
    val app = LocalAppState.current
    val products by app.repo.products.collectAsState()
    val p = products.firstOrNull { it.id == productId }
    if (p == null) {
        // Puede tardar un instante en llegar (equipo conectado); si no aparece, no se pregunta.
        LaunchedEffect(productId) { kotlinx.coroutines.delay(3000); if (app.labelPrompt == productId) app.labelPrompt = null }
        return
    }
    var copies by remember(productId) { mutableIntStateOf(p.suggestedCopies()) }
    var dontAsk by remember { mutableStateOf(false) }
    fun close() {
        if (dontAsk) app.scope.launch { runCatching { app.repo.updateSettings { it.copy(labelAskOnSave = false) } } }
        app.labelPrompt = null
    }
    AlertDialog(
        onDismissRequest = ::close,
        icon = { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Producto guardado") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("¿Imprimir sus etiquetas ahora?", color = AppTheme.extra.muted)
                LabelPreview(app.settings.labelOptions, app.labelContent(p), Modifier.fillMaxWidth(), maxHeightDp = 130f)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Copias", fontWeight = FontWeight.SemiBold)
                        if (p.trackStock == 1L) Text("Una por unidad en stock (${p.stock.roundToInt()})", fontSize = 12.sp, color = AppTheme.extra.muted)
                    }
                    QtyStepper(copies.toDouble(), { copies = it.roundToInt().coerceIn(1, 500) })
                }
                Row(Modifier.fillMaxWidth().clickable { dontAsk = !dontAsk }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(dontAsk, { dontAsk = it })
                    Text("No volver a preguntar", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = { close(); app.openLabelsFor(listOf(AppState.LabelItem(p.id, copies))) }, shape = RoundedCornerShape(12.dp)) {
                Text("Etiquetas ($copies)")
            }
        },
        dismissButton = { TextButton(onClick = ::close) { Text("Ahora no") } },
    )
}
