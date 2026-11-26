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

enum class LabelTemplate(val title: String, val hint: String) {
    PRICE_QR("Precio + QR", "La más usada: precio grande y QR"),
    BARCODE("Código de barras", "La leen los lectores USB de caja. Desde 40 mm de ancho"),
    MINI("Mini", "Para micas, cables y cosas pequeñas"),
    SHELF("Exhibición", "Con el nombre de la tienda, para vitrinas"),
}

object LabelLimits {
    const val MIN_W = 20f
    const val MIN_H = 10f
    const val MAX_W = 80f
    const val MAX_H = 60f

    fun clampW(w: Float) = w.coerceIn(MIN_W, MAX_W)
    fun clampH(h: Float) = h.coerceIn(MIN_H, MAX_H)
}

data class LabelSize(val w: Float, val h: Float) {
    val text: String get() = "${fmt(w)} × ${fmt(h)}"

    companion object {
        /** Tamaños de rollo más comunes (mm). */
        val presets = listOf(LabelSize(50f, 30f), LabelSize(40f, 30f), LabelSize(60f, 40f), LabelSize(30f, 20f), LabelSize(25f, 15f), LabelSize(80f, 50f))
        private fun fmt(v: Float) = if (v % 1f == 0f) v.toInt().toString() else v.toString()
    }
}

data class LabelOptions(
    val template: LabelTemplate = LabelTemplate.PRICE_QR,
    val widthMm: Float = 50f,
    val heightMm: Float = 30f,
    val showName: Boolean = true,
    val showPrice: Boolean = true,
    val showCode: Boolean = true,
    val showStore: Boolean = true,
)

/** Lo que se imprime en una etiqueta. [qr] es el texto del código (el código del producto). */
data class LabelContent(
    val name: String,
    val price: String,
    val code: String,
    val store: String,
    val extra: String = "",
    /** Si viene, se dibuja la etiqueta de un equipo del taller en vez de la de un producto. */
    val repair: RepairLabel? = null,
)

/** Datos de la etiqueta que se pega a un equipo en reparación. */
data class RepairLabel(
    val code: String,
    val owner: String,
    val contact: String,
    val device: String,
    val serial: String,
    val date: String,
    val problem: String,
)

/** Hojas de etiquetas adhesivas para impresoras normales (medidas en mm). */
data class SheetPreset(
    val title: String,
    val pageW: Float, val pageH: Float,
    val cols: Int, val rows: Int,
    val labelW: Float, val labelH: Float,
    val left: Float, val top: Float,
    val pitchX: Float, val pitchY: Float,
) {
    val perSheet get() = cols * rows

    companion object {
        val all = listOf(
            SheetPreset("A4 · 24 etiquetas (70 × 37 mm)", 210f, 297f, 3, 8, 70f, 37f, 0f, 0.5f, 70f, 37f),
            SheetPreset("A4 · 21 etiquetas (63,5 × 38,1 mm)", 210f, 297f, 3, 7, 63.5f, 38.1f, 7.25f, 15.15f, 66.04f, 38.1f),
            SheetPreset("A4 · 65 etiquetas (38,1 × 21,2 mm)", 210f, 297f, 5, 13, 38.1f, 21.2f, 4.75f, 10.7f, 40.64f, 21.2f),
            SheetPreset("Carta · 30 etiquetas (66,7 × 25,4 mm)", 215.9f, 279.4f, 3, 10, 66.7f, 25.4f, 4.8f, 12.7f, 69.85f, 25.4f),
        )
    }
}

// ================= Dibujo =================

/** Lienzo para dibujar etiquetas. Medidas en mm desde la esquina superior izquierda. [y] de texto = línea base. */
interface LabelPainter {
    /** Puntos por mm de la impresora (8 = 203 dpi). 0 = pantalla o PDF, sin ajustar a puntos. */
    val dotsPerMm: Float get() = 0f
    fun rect(x: Float, y: Float, w: Float, h: Float)
    fun text(x: Float, y: Float, text: String, sizeMm: Float, bold: Boolean, align: PdfCanvas.TextAlign = PdfCanvas.TextAlign.Left)
    fun textWidth(text: String, sizeMm: Float, bold: Boolean): Float
}

object Codes {
    /** EAN-13 válido (13 dígitos con dígito de control correcto). */
    fun isEan13(code: String): Boolean {
        if (code.length != 13 || !code.all { it.isDigit() }) return false
        return checkDigit(code.substring(0, 12)) == code[12] - '0'
    }

    fun checkDigit(body12: String): Int {
        val sum = body12.mapIndexed { i, ch -> (ch - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return (10 - sum % 10) % 10
    }

    /** Código interno de 13 dígitos que empieza en 200 (rango reservado para uso interno de las tiendas). */
    fun newInternalEan(exists: (String) -> Boolean): String {
        while (true) {
            val body = "200" + (1..9).map { (0..9).random() }.joinToString("")
            val code = body + checkDigit(body)
            if (!exists(code)) return code
        }
    }

    fun qr(text: String): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )

    /** Código de barras lineal: EAN-13 si el código lo permite; si no, Code 128. */
    fun linear(code: String): BitMatrix = when {
        isEan13(code) -> EAN13Writer().encode(code, BarcodeFormat.EAN_13, 0, 1, mapOf(EncodeHintType.MARGIN to 0))
        code.length == 12 && code.all { it.isDigit() } -> EAN13Writer().encode(code + checkDigit(code), BarcodeFormat.EAN_13, 0, 1, mapOf(EncodeHintType.MARGIN to 0))
        else -> Code128Writer().encode(code.ifBlank { "0" }, BarcodeFormat.CODE_128, 0, 1, mapOf(EncodeHintType.MARGIN to 0))
    }
}

