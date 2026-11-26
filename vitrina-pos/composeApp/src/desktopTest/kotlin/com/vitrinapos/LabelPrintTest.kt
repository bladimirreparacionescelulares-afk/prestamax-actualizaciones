package com.vitrinapos

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.vitrinapos.labels.LabelContent
import com.vitrinapos.labels.LabelEncoder
import com.vitrinapos.labels.LabelLanguage
import com.vitrinapos.labels.LabelOptions
import com.vitrinapos.labels.LabelPrinterConfig
import com.vitrinapos.labels.LabelRaster
import com.vitrinapos.labels.LabelTemplate
import com.vitrinapos.labels.NetworkPrinter
import java.awt.image.BufferedImage
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Imprime en una "etiquetadora de red" falsa: recibe los bytes, reconstruye la imagen
 * desde TSPL, ZPL o ESC/POS y comprueba que el QR se lee.
 */
class LabelPrintTest {
    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
    private val content = LabelContent("Audífonos Bluetooth Pro", "$1,200.00", "7501234567893", "Mi Tienda")

    /** Recibe una impresión por la red, como una impresora en el puerto 9100. */
    private fun fakePrinter(send: (port: Int) -> Unit): ByteArray {
        ServerSocket(0).use { server ->
            var data = ByteArray(0)
            val t = thread { server.accept().use { s -> data = s.getInputStream().readBytes() } }
            send(server.localPort)
            t.join(10_000)
            return data
        }
    }

    private fun toImage(w: Int, h: Int, rowBytes: Int, bytes: ByteArray, offset: Int, blackIsOne: Boolean): BufferedImage {
        val img = BufferedImage(w + 40, h + 40, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = java.awt.Color.WHITE; g.fillRect(0, 0, img.width, img.height)
        for (y in 0 until h) for (x in 0 until w) {
            val bit = (bytes[offset + y * rowBytes + x / 8].toInt() shr (7 - x % 8)) and 1
            if ((bit == 1) == blackIsOne) img.setRGB(x + 20, y + 20, 0)
        }
        return img
    }

    private fun decode(img: BufferedImage): String? = runCatching {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(img))), mapOf(DecodeHintType.TRY_HARDER to true)).text
    }.getOrNull()

    private fun indexOf(data: ByteArray, text: String, from: Int = 0): Int {
        val t = text.toByteArray()
        outer@ for (i in from..data.size - t.size) { for (j in t.indices) if (data[i + j] != t[j]) continue@outer; return i }
        return -1
    }

    @Test
    fun tsplPorRed() {
        val o = LabelOptions(LabelTemplate.PRICE_QR, 50f, 30f)
        val cfg = LabelPrinterConfig(LabelLanguage.TSPL, 203)
        val bmp = LabelRaster.render(o, content, cfg, measurer)
        assertEquals(400, bmp.width); assertEquals(240, bmp.height)
        val data = fakePrinter { port -> NetworkPrinter.send("127.0.0.1", port, LabelEncoder.encode(cfg, o, listOf(bmp to 3))) }
        val text = String(data, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("SIZE 50.0 mm,30.0 mm\r\nGAP 2.0 mm,0 mm"), text.take(60))
        assertTrue(text.contains("PRINT 1,3"), "3 copias")
        val head = "BITMAP 0,0,50,240,0,"
        val at = indexOf(data, head)
        assertTrue(at >= 0)
        val img = toImage(400, 240, 50, data, at + head.length, blackIsOne = false)
        System.getProperty("screenshots")?.let { File(it).mkdirs(); javax.imageio.ImageIO.write(img, "png", File(it, "etiqueta-impresa-tspl.png")) }
        assertEquals("7501234567893", decode(img))
    }

    @Test
    fun zplYEscPos() {
        val o = LabelOptions(LabelTemplate.MINI, 40f, 30f)
        // ZPL
        val cfgZ = LabelPrinterConfig(LabelLanguage.ZPL, 203)
        val bz = LabelRaster.render(o, content, cfgZ, measurer)
        val z = String(LabelEncoder.encode(cfgZ, o, listOf(bz to 2)), Charsets.US_ASCII)
        assertTrue(z.startsWith("^XA") && z.contains("^PQ2^XZ"))
        val hex = Regex("""\^GFA,(\d+),\d+,(\d+),([0-9A-F]+)\^FS""").find(z)!!
        val bytes = hex.groupValues[3].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("7501234567893", decode(toImage(bz.width, bz.height, hex.groupValues[2].toInt(), bytes, 0, blackIsOne = true)))
        // ESC/POS
        val cfgE = LabelPrinterConfig(LabelLanguage.ESCPOS, 203)
        val be = LabelRaster.render(o, content, cfgE, measurer)
        val e = LabelEncoder.encode(cfgE, o, listOf(be to 1))
        val gs = (0 until e.size - 3).first { e[it] == 0x1D.toByte() && e[it + 1] == 0x76.toByte() && e[it + 2] == 0x30.toByte() }
        val rowBytes = (e[gs + 4].toInt() and 0xFF) or ((e[gs + 5].toInt() and 0xFF) shl 8)
        val h = (e[gs + 6].toInt() and 0xFF) or ((e[gs + 7].toInt() and 0xFF) shl 8)
        assertEquals("7501234567893", decode(toImage(rowBytes * 8, h, rowBytes, e, gs + 8, blackIsOne = true)))
    }

    @Test
    fun alta300dpiYEtiquetaPequena() {
        val o = LabelOptions(LabelTemplate.PRICE_QR, 25f, 15f)
        val cfg = LabelPrinterConfig(LabelLanguage.TSPL, 300)
        val bmp = LabelRaster.render(o, content, cfg, measurer)
        assertTrue(bmp.countBlack() > 0)
        val packed = bmp.packed(blackIsOne = true)
        assertEquals("7501234567893", decode(toImage(bmp.width, bmp.height, bmp.rowBytes, packed, 0, blackIsOne = true)))
    }
}
