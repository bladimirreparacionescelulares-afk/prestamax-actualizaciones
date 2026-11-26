package com.vitrinapos.labels

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.vitrinapos.ui.components.ComposeLabelPainter
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

/** Lenguaje que entiende la etiquetadora. */
enum class LabelLanguage(val title: String, val hint: String) {
    TSPL("TSPL", "Xprinter, HPRT, Gprinter, Munbyn, TSC, iDPRT y la mayoría de etiquetadoras económicas"),
    ZPL("ZPL", "Zebra y compatibles (Honeywell, Godex en modo ZPL)"),
    ESCPOS("ESC/POS", "Impresoras de tickets con rollo de etiquetas"),
}

/** Imagen en blanco y negro, un punto por punto de la impresora. */
class MonoBitmap(val width: Int, val height: Int, val black: BooleanArray) {
    val rowBytes get() = (width + 7) / 8

    /** Filas empaquetadas de 8 puntos por byte, el bit más alto a la izquierda. */
    fun packed(blackIsOne: Boolean): ByteArray {
        val out = ByteArray(rowBytes * height)
        for (y in 0 until height) for (x in 0 until rowBytes * 8) {
            val isBlack = x < width && black[y * width + x]
            if (isBlack == blackIsOne) {
                val i = y * rowBytes + x / 8
                out[i] = (out[i].toInt() or (0x80 ushr (x % 8))).toByte()
            }
        }
        return out
    }

    fun countBlack() = black.count { it }
}

data class LabelPrinterConfig(
    val language: LabelLanguage = LabelLanguage.TSPL,
    val dpi: Int = 203,
    val gapMm: Float = 2f,
    /** Oscuridad 1 (claro) a 15 (oscuro). */
    val darkness: Int = 8,
    val offsetXmm: Float = 0f,
    val offsetYmm: Float = 0f,
) {
    val dotsPerMm: Float get() = dpi / 25.4f
}

object LabelRaster {
    /**
     * Dibuja la etiqueta en puntos de impresora. El QR y las barras quedan alineados a los puntos,
     * y el texto se oscurece un poco al pasarlo a blanco y negro para que no salga delgado.
     */
    fun render(o: LabelOptions, c: LabelContent, cfg: LabelPrinterConfig, measurer: TextMeasurer): MonoBitmap {
        val k = cfg.dotsPerMm
        val w = (o.widthMm * k).roundToInt().coerceAtLeast(8)
        val h = (o.heightMm * k).roundToInt().coerceAtLeast(8)
        val img = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color.White)
            val painter = ComposeLabelPainter(this, measurer, k, cfg.offsetXmm * k, cfg.offsetYmm * k, Color.Black, dotsPerMm = k)
            LabelLayout.draw(painter, o, c)
        }
        val px = IntArray(w * h)
        img.readPixels(px, 0, 0, w, h)
        val black = BooleanArray(w * h) { i ->
            val p = px[i]
            val a = (p ushr 24) and 0xFF
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            // Fondo transparente = blanco
            val lum = if (a < 128) 255 else (r * 299 + g * 587 + b * 114) / 1000
            lum < 170
        }
        return MonoBitmap(w, h, black)
    }
}

object LabelEncoder {
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun mm(v: Float) = ((v * 10).roundToInt() / 10f).toString()

    /** Varias etiquetas distintas, cada una con sus copias. */
    fun encode(cfg: LabelPrinterConfig, o: LabelOptions, labels: List<Pair<MonoBitmap, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        when (cfg.language) {
            LabelLanguage.TSPL -> {
                out.write(ascii("SIZE ${mm(o.widthMm)} mm,${mm(o.heightMm)} mm\r\nGAP ${mm(cfg.gapMm)} mm,0 mm\r\nDIRECTION 1,0\r\nREFERENCE 0,0\r\nDENSITY ${cfg.darkness.coerceIn(0, 15)}\r\nSET TEAR ON\r\n"))
                for ((bmp, copies) in labels) {
                    out.write(ascii("CLS\r\nBITMAP 0,0,${bmp.rowBytes},${bmp.height},0,"))
                    // En TSPL un bit en 0 se imprime (negro).
                    out.write(bmp.packed(blackIsOne = false))
                    out.write(ascii("\r\nPRINT 1,${copies.coerceIn(1, 999)}\r\n"))
                }
            }
            LabelLanguage.ZPL -> {
                for ((bmp, copies) in labels) {
                    val data = bmp.packed(blackIsOne = true)
                    val hex = StringBuilder(data.size * 2)
                    data.forEach { hex.append("%02X".format(it.toInt() and 0xFF)) }
                    val md = ((cfg.darkness - 8) * 30 / 7).coerceIn(-30, 30)
                    out.write(ascii("^XA^MD$md^PW${bmp.width}^LL${bmp.height}^LH0,0^FO0,0^GFA,${data.size},${data.size},${bmp.rowBytes},$hex^FS^PQ${copies.coerceIn(1, 999)}^XZ\n"))
                }
            }
            LabelLanguage.ESCPOS -> {
                out.write(byteArrayOf(0x1B, 0x40))
                for ((bmp, copies) in labels) {
                    val data = bmp.packed(blackIsOne = true)
                    repeat(copies.coerceIn(1, 999)) {
                        out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00,
                            (bmp.rowBytes and 0xFF).toByte(), (bmp.rowBytes shr 8).toByte(),
                            (bmp.height and 0xFF).toByte(), (bmp.height shr 8).toByte()))
                        out.write(data)
                        // Avanza el espacio entre etiquetas.
                        val feed = (cfg.gapMm * cfg.dotsPerMm).roundToInt().coerceIn(0, 255)
                        out.write(byteArrayOf(0x1B, 0x4A, feed.toByte()))
                    }
                }
            }
        }
        return out.toByteArray()
    }
}

/** Impresoras de red (puerto 9100): sirve para tickets y etiquetas en cualquier equipo. */
object NetworkPrinter {
    const val PREFIX = "net:"

    fun id(host: String, port: Int = 9100) = "$PREFIX$host:$port"

    fun parse(id: String): Pair<String, Int>? {
        if (!id.startsWith(PREFIX)) return null
        val rest = id.removePrefix(PREFIX)
        val host = rest.substringBeforeLast(':')
        val port = rest.substringAfterLast(':', "9100").toIntOrNull() ?: 9100
        return host to port
    }

    fun send(host: String, port: Int, bytes: ByteArray) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 4000)
                s.soTimeout = 10_000
                val out = s.getOutputStream()
                bytes.toList().chunked(4096).forEach { out.write(it.toByteArray()) }
                out.flush()
                Thread.sleep(300)
            }
        } catch (e: Exception) {
            throw java.io.IOException("No se pudo conectar con la impresora $host. Revisa que esté encendida y en la misma red.")
        }
    }
}
