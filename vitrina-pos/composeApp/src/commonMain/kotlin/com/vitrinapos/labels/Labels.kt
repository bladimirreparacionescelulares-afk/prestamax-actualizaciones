package com.vitrinapos.labels

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.oned.EAN13Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.vitrinapos.platform.PdfCanvas

// ================= Modelo =================

/** Acomoda cada plantilla en cualquier tamaño entre 20×10 y 80×60 mm. */
object LabelLayout {
    private fun clamp(v: Float, lo: Float, hi: Float) = v.coerceIn(lo, hi)

    fun draw(p: LabelPainter, o: LabelOptions, c: LabelContent) {
        val w = o.widthMm
        val h = o.heightMm
        val pad = clamp(minOf(w, h) * 0.07f, 1f, 2.5f)
        c.repair?.let { repair(p, it, pad, w, h); return }
        when (o.template) {
            LabelTemplate.PRICE_QR -> qrBlock(p, o, c, pad, pad, w - 2 * pad, h - 2 * pad, qrRatio = 0.44f)
            LabelTemplate.MINI -> mini(p, o, c, pad, w, h)
            LabelTemplate.BARCODE -> barcode(p, o, c, pad, w, h)
            LabelTemplate.SHELF -> shelf(p, o, c, pad, w, h)
        }
    }

    /** QR a la izquierda y textos a la derecha, centrados en vertical. */
    private fun qrBlock(p: LabelPainter, o: LabelOptions, c: LabelContent, x: Float, y: Float, w: Float, h: Float, qrRatio: Float, showStore: Boolean = o.showStore) {
        val qr = minOf(h, w * qrRatio)
        drawQr(p, c.code.ifBlank { c.name }, x, y + (h - qr) / 2, qr)
        val tx = x + qr + clamp(w * 0.04f, 0.8f, 2.5f)
        val tw = x + w - tx
        val priceSize = clamp(h * 0.24f, 2.2f, 9f)
        val nameSize = clamp(h * 0.11f, 1.4f, 3.6f)
        val small = clamp(h * 0.075f, 1.1f, 2.4f)
        val lines = mutableListOf<Line>()
        if (showStore && c.store.isNotBlank() && h >= 14) lines += Line(fit(p, c.store.uppercase(), small, true, tw), small, true)
        if (o.showName) wrap(p, c.name, nameSize, true, tw, if (h >= 16) 2 else 1).forEach { lines += Line(it, nameSize, true) }
        if (o.showPrice) {
            val ps = shrink(p, c.price, priceSize, true, tw)
            lines += Line(c.price, ps, true, gapBefore = ps * 0.25f)
        }
        if (o.showCode && c.code.isNotBlank() && h >= 12) lines += Line(fit(p, c.code, small, false, tw), small, false)
        stack(p, lines, tx, y, h, PdfCanvas.TextAlign.Left)
    }

    /** Etiqueta del equipo: QR con el número de orden, número grande en negativo y los datos del dueño. */
    private fun repair(p: LabelPainter, r: RepairLabel, pad: Float, w: Float, h: Float) {
        val inner = h - 2 * pad
        val qr = minOf(inner, (w - 2 * pad) * 0.38f)
        drawQr(p, r.code, pad, (h - qr) / 2, qr)
        val tx = pad + qr + clamp(w * 0.04f, 0.8f, 2f)
        val tw = w - pad - tx
        val codeSize = shrink(p, r.code, clamp(h * 0.17f, 2f, 7f), true, tw - 1f)
        val body = clamp(h * 0.085f, 1.2f, 3f)
        val small = clamp(h * 0.07f, 1.1f, 2.4f)
        val lines = mutableListOf<Line>()
        lines += Line(fit(p, r.owner, body * 1.1f, true, tw), body * 1.1f, true)
        if (r.contact.isNotBlank() && h >= 15) lines += Line(fit(p, r.contact, small, false, tw), small, false)
        if (h >= 20 && r.device.isNotBlank()) lines += Line(fit(p, r.device, small, false, tw), small, false)
        // El IMEI/serie va en su propia línea para que se lea completo.
        if (h >= 20 && r.serial.isNotBlank()) lines += Line(fit(p, r.serial, small, false, tw), small, false)
        if (h >= 28 && r.problem.isNotBlank()) lines += Line(fit(p, "${r.date} · ${r.problem}", small, false, tw), small, false)
        // Bloque del número: fondo negro con letras blancas no existe en una térmica de 1 color,
        // así que se usa un recuadro grueso alrededor del número.
        val boxH = codeSize * 1.35f
        val textH = lines.sumOf { (it.size * 1.12f).toDouble() }.toFloat()
        val top = pad + (inner - boxH - textH - 0.8f) / 2
        val stroke = clamp(h * 0.015f, 0.25f, 0.5f)
        val boxW = minOf(tw, p.textWidth(r.code, codeSize, true) + codeSize * 0.6f)
        p.rect(tx, top, boxW, stroke); p.rect(tx, top + boxH - stroke, boxW, stroke)
        p.rect(tx, top, stroke, boxH); p.rect(tx + boxW - stroke, top, stroke, boxH)
        p.text(tx + boxW / 2, top + boxH * 0.74f, r.code, codeSize, true, PdfCanvas.TextAlign.Center)
        stack(p, lines, tx, top + boxH + 0.8f, textH, PdfCanvas.TextAlign.Left)
    }

    private fun mini(p: LabelPainter, o: LabelOptions, c: LabelContent, pad: Float, w: Float, h: Float) {
        val inner = h - 2 * pad
        val qr = minOf(inner, (w - 2 * pad) * 0.5f)
        drawQr(p, c.code.ifBlank { c.name }, pad, (h - qr) / 2, qr)
        val tx = pad + qr + clamp(w * 0.05f, 0.8f, 2f)
        val tw = w - pad - tx
        val lines = mutableListOf<Line>()
        val small = clamp(h * 0.09f, 1.1f, 2.2f)
        if (o.showName && h >= 20) lines += Line(fit(p, c.name, small, true, tw), small, true)
        if (o.showPrice) lines += Line(c.price, shrink(p, c.price, clamp(h * 0.3f, 2.4f, 9f), true, tw), true)
        if (o.showCode && c.code.isNotBlank()) lines += Line(fit(p, c.code, small, false, tw), small, false)
        stack(p, lines, tx, pad, inner, PdfCanvas.TextAlign.Left)
    }

    /** Ancho mínimo de cada barra para que se lea en una térmica de 203 dpi (2 puntos ≈ 0,25 mm). */
    private const val MIN_MODULE_MM = 0.25f

    /** Si las barras quedarían demasiado finas para este ancho, se usa QR (siempre legible). */
    fun barcodeFits(code: String, widthMm: Float): Boolean {
        val modules = runCatching { Codes.linear(code.ifBlank { "0" }).width }.getOrDefault(200)
        val pad = clamp(minOf(widthMm, 30f) * 0.07f, 1f, 2.5f)
        return (widthMm - 2 * pad) / modules >= MIN_MODULE_MM
    }

    private fun barcode(p: LabelPainter, o: LabelOptions, c: LabelContent, pad: Float, w: Float, h: Float) {
        val innerW = w - 2 * pad
        if (!barcodeFits(c.code, w) || h < 14) { mini(p, o, c, pad, w, h); return }
        val nameSize = clamp(h * 0.12f, 1.4f, 3.6f)
        val small = clamp(h * 0.08f, 1.1f, 2.4f)
        var top = pad
        if (o.showName) {
            val lines = wrap(p, c.name, nameSize, true, innerW, if (h >= 35) 2 else 1)
            lines.forEach { top += nameSize * 0.95f; p.text(pad, top, it, nameSize, true) }
            top += nameSize * 0.35f
        }
        val priceSize = if (o.showPrice) shrink(p, c.price, clamp(h * 0.2f, 2f, 7f), true, innerW * 0.6f) else 0f
        val bottomH = maxOf(priceSize, if (o.showCode) small else 0f)
        val base = h - pad
        if (o.showPrice) p.text(w - pad, base, c.price, priceSize, true, PdfCanvas.TextAlign.Right)
        if (o.showCode && c.code.isNotBlank()) {
            if (o.showPrice) p.text(pad, base, fit(p, c.code, small, false, innerW * 0.45f), small, false)
            else p.text(w / 2, base, c.code, small, false, PdfCanvas.TextAlign.Center)
        }
        val barTop = top
        val barBottom = base - bottomH - if (bottomH > 0) bottomH * 0.35f else 0f
        if (barBottom - barTop > 2f) drawLinear(p, c.code.ifBlank { "0" }, pad, barTop, innerW, barBottom - barTop)
    }

    private fun shelf(p: LabelPainter, o: LabelOptions, c: LabelContent, pad: Float, w: Float, h: Float) {
        if (!o.showStore || c.store.isBlank() || h < 18) {
            qrBlock(p, o, c, pad, pad, w - 2 * pad, h - 2 * pad, qrRatio = 0.4f, showStore = false)
            return
        }
        val head = clamp(h * 0.11f, 1.6f, 3.4f)
        val baseline = pad + head
        p.text(pad, baseline, fit(p, c.store.uppercase(), head, true, w - 2 * pad), head, true)
        val ruleY = baseline + head * 0.35f
        p.rect(pad, ruleY, w - 2 * pad, clamp(h * 0.012f, 0.25f, 0.5f))
        val bodyTop = ruleY + head * 0.5f
        qrBlock(p, o, c, pad, bodyTop, w - 2 * pad, h - pad - bodyTop, qrRatio = 0.36f, showStore = false)
    }

    // ---------- Ayudas ----------

    private data class Line(val text: String, val size: Float, val bold: Boolean, val gapBefore: Float = 0f)

    /** Apila las líneas centradas en vertical dentro de [y, y+h]. */
    private fun stack(p: LabelPainter, lines: List<Line>, x: Float, y: Float, h: Float, align: PdfCanvas.TextAlign) {
        if (lines.isEmpty()) return
        val heights = lines.mapIndexed { i, l -> l.size * 1.12f + if (i > 0) l.gapBefore else 0f }
        var cursor = y + (h - heights.sum()) / 2
        lines.forEachIndexed { i, l ->
            cursor += if (i > 0) l.gapBefore else 0f
            cursor += l.size * 0.92f
            p.text(x, cursor, l.text, l.size, l.bold, align)
            cursor += l.size * 0.2f
        }
    }

    fun fit(p: LabelPainter, text: String, size: Float, bold: Boolean, maxW: Float): String {
        if (p.textWidth(text, size, bold) <= maxW) return text
        var t = text
        while (t.length > 1 && p.textWidth("$t…", size, bold) > maxW) t = t.dropLast(1)
        return "${t.trimEnd()}…"
    }

    /** Reduce el tamaño hasta que el texto quepa (para el precio). */
    private fun shrink(p: LabelPainter, text: String, size: Float, bold: Boolean, maxW: Float): Float {
        var s = size
        while (s > 1.2f && p.textWidth(text, s, bold) > maxW) s *= 0.92f
        return s
    }

    fun wrap(p: LabelPainter, text: String, size: Float, bold: Boolean, maxW: Float, maxLines: Int): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        val words = text.split(' ').filter { it.isNotEmpty() }
        for ((i, word) in words.withIndex()) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (p.textWidth(candidate, size, bold) <= maxW || line.isEmpty()) {
                line = candidate
            } else {
                out += line
                line = word
                if (out.size == maxLines - 1) {
                    line = words.drop(i).joinToString(" ")
                    break
                }
            }
        }
        if (line.isNotEmpty()) out += line
        return out.take(maxLines).mapIndexed { i, l -> if (i == maxLines - 1 || i == out.lastIndex) fit(p, l, size, bold, maxW) else l }
    }

    fun drawQr(p: LabelPainter, text: String, x: Float, y: Float, side: Float) {
        val m = Codes.qr(text)
        var cell = side / m.width
        var ox = x
        var oy = y
        val dots = p.dotsPerMm
        if (dots > 0f) {
            // En la impresora cada cuadrito del QR ocupa un número entero de puntos: queda nítido y se lee mejor.
            val perModule = maxOf(1, (cell * dots).toInt())
            cell = perModule / dots
            ox = kotlin.math.round(x * dots) / dots + (side - cell * m.width) / 2
            oy = kotlin.math.round(y * dots) / dots + (side - cell * m.width) / 2
            ox = kotlin.math.round(ox * dots) / dots
            oy = kotlin.math.round(oy * dots) / dots
        }
        for (row in 0 until m.height) {
            var col = 0
            while (col < m.width) {
                if (m[col, row]) {
                    var end = col
                    while (end + 1 < m.width && m[end + 1, row]) end++
                    p.rect(ox + col * cell, oy + row * cell, (end - col + 1) * cell, cell)
                    col = end + 1
                } else col++
            }
        }
    }

    fun drawLinear(p: LabelPainter, code: String, x: Float, y: Float, w: Float, h: Float) {
        val m = runCatching { Codes.linear(code) }.getOrNull() ?: return
        var module = w / m.width
        var ox = x
        val dots = p.dotsPerMm
        if (dots > 0f) {
            module = maxOf(1, (module * dots).toInt()) / dots
            ox = kotlin.math.round((x + (w - module * m.width) / 2) * dots) / dots
        }
        var col = 0
        while (col < m.width) {
            if (m[col, 0]) {
                var end = col
                while (end + 1 < m.width && m[end + 1, 0]) end++
                p.rect(ox + col * module, y, (end - col + 1) * module, h)
                col = end + 1
            } else col++
        }
    }
}

// ================= PDF =================

const val PT_PER_MM = 72f / 25.4f

/** Dibuja etiquetas en un PDF (para hojas o para el driver de una etiquetadora). */
class PdfLabelPainter(private val c: PdfCanvas, var ox: Float = 0f, var oy: Float = 0f) : LabelPainter {
    override fun rect(x: Float, y: Float, w: Float, h: Float) =
        c.rect((ox + x) * PT_PER_MM, (oy + y) * PT_PER_MM, w * PT_PER_MM, h * PT_PER_MM, 0xFF000000)

    override fun text(x: Float, y: Float, text: String, sizeMm: Float, bold: Boolean, align: PdfCanvas.TextAlign) =
        c.text((ox + x) * PT_PER_MM, (oy + y) * PT_PER_MM, text, sizeMm * PT_PER_MM, bold, 0xFF000000, align)

    override fun textWidth(text: String, sizeMm: Float, bold: Boolean): Float =
        c.textWidth(text, sizeMm * PT_PER_MM, bold) / PT_PER_MM
}

object LabelPdf {
    /** Una etiqueta por página, del tamaño exacto de la etiqueta. */
    fun pageSizePt(o: LabelOptions) = o.widthMm * PT_PER_MM to o.heightMm * PT_PER_MM

    fun drawRoll(c: PdfCanvas, o: LabelOptions, labels: List<LabelContent>) {
        val p = PdfLabelPainter(c)
        labels.forEachIndexed { i, l ->
            if (i > 0) c.newPage()
            LabelLayout.draw(p, o, l)
        }
    }

    /** Hoja de etiquetas adhesivas. [startAt] permite aprovechar una hoja usada (1 = primera etiqueta). */
    fun drawSheet(c: PdfCanvas, sheet: SheetPreset, o: LabelOptions, labels: List<LabelContent>, startAt: Int = 1, guides: Boolean = false) {
        val opts = o.copy(widthMm = sheet.labelW, heightMm = sheet.labelH)
        val p = PdfLabelPainter(c)
        var slot = (startAt - 1).coerceIn(0, sheet.perSheet - 1)
        var first = true
        for (l in labels) {
            if (slot >= sheet.perSheet) { c.newPage(); slot = 0 }
            if (first) first = false
            val col = slot % sheet.cols
            val row = slot / sheet.cols
            p.ox = sheet.left + col * sheet.pitchX
            p.oy = sheet.top + row * sheet.pitchY
            if (guides) {
                c.line(p.ox * PT_PER_MM, p.oy * PT_PER_MM, (p.ox + sheet.labelW) * PT_PER_MM, p.oy * PT_PER_MM, 0xFFDDDDDD, 0.3f)
            }
            LabelLayout.draw(p, opts, l)
            slot++
        }
    }

    fun sheetsNeeded(sheet: SheetPreset, count: Int, startAt: Int): Int =
        if (count == 0) 0 else ((startAt - 1) + count + sheet.perSheet - 1) / sheet.perSheet
}
