package com.vitrinapos

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.vitrinapos.data.AppGraph
import com.vitrinapos.data.ProductDraft
import com.vitrinapos.labels.Codes
import com.vitrinapos.labels.LabelContent
import com.vitrinapos.labels.LabelLayout
import com.vitrinapos.labels.LabelOptions
import com.vitrinapos.labels.LabelPainter
import com.vitrinapos.labels.LabelPdf
import com.vitrinapos.labels.LabelTemplate
import com.vitrinapos.labels.PT_PER_MM
import com.vitrinapos.labels.SheetPreset
import com.vitrinapos.platform.DesktopPlatform
import com.vitrinapos.platform.PdfCanvas
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Dibuja la etiqueta en una imagen, como lo haría una etiquetadora de 203 dpi (8 puntos por mm). */
private class ImagePainter(val img: BufferedImage, override val dotsPerMm: Float) : LabelPainter {
    val g = img.createGraphics().apply {
        color = Color.WHITE; fillRect(0, 0, img.width, img.height); color = Color.BLACK
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    }
    val issues = mutableListOf<String>()
    private fun check(what: String, x: Float, y: Float, x2: Float, y2: Float) {
        val wMm = img.width / dotsPerMm
        val hMm = img.height / dotsPerMm
        if (x < -0.05f || y < -0.05f || x2 > wMm + 0.05f || y2 > hMm + 0.05f) issues += "$what fuera: ($x,$y)-($x2,$y2) en ${wMm}x$hMm"
    }
    private fun font(size: Float, bold: Boolean) = Font("SansSerif", if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size * dotsPerMm)
    override fun rect(x: Float, y: Float, w: Float, h: Float) {
        check("rect", x, y, x + w, y + h)
        // Se redondean los bordes (no el ancho) para que rectángulos vecinos no dejen rendijas, como en una impresora.
        val x1 = (x * dotsPerMm).roundToInt(); val y1 = (y * dotsPerMm).roundToInt()
        val x2 = ((x + w) * dotsPerMm).roundToInt(); val y2 = ((y + h) * dotsPerMm).roundToInt()
        g.fillRect(x1, y1, maxOf(1, x2 - x1), maxOf(1, y2 - y1))
    }
    override fun text(x: Float, y: Float, text: String, sizeMm: Float, bold: Boolean, align: PdfCanvas.TextAlign) {
        val w = textWidth(text, sizeMm, bold)
        val left = when (align) { PdfCanvas.TextAlign.Left -> x; PdfCanvas.TextAlign.Right -> x - w; PdfCanvas.TextAlign.Center -> x - w / 2 }
        check("texto '$text'", left, y - sizeMm * 0.75f, left + w, y)
        g.font = font(sizeMm, bold)
        g.drawString(text, left * dotsPerMm, y * dotsPerMm)
    }
    override fun textWidth(text: String, sizeMm: Float, bold: Boolean): Float =
        g.getFontMetrics(font(sizeMm, bold)).stringWidth(text) / dotsPerMm
}

class LabelTest {
    private val content = LabelContent("Audífonos Bluetooth Pro con micrófono", "$1,200.00", "7501234567893", "Mi Tienda")
    private val sizes = listOf(20f to 10f, 25f to 15f, 30f to 20f, 40f to 30f, 50f to 30f, 60f to 40f, 70f to 37f, 80f to 60f)

    private fun render(o: LabelOptions, c: LabelContent = content): ImagePainter {
        val dots = 8f
        val img = BufferedImage((o.widthMm * dots).roundToInt(), (o.heightMm * dots).roundToInt(), BufferedImage.TYPE_INT_RGB)
        return ImagePainter(img, dots).also { LabelLayout.draw(it, o, c) }
    }

    private fun decode(img: BufferedImage): String? = runCatching {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(img)))).text
    }.getOrNull()

    @Test
    fun todoCabeEnCadaTamano() {
        for (t in LabelTemplate.entries) for ((w, h) in sizes) {
            val p = render(LabelOptions(template = t, widthMm = w, heightMm = h))
            assertTrue(p.issues.isEmpty(), "${t.title} ${w}x$h: ${p.issues.take(3)}")
        }
    }

    @Test
    fun elCodigoSeLeeAl203dpi() {
        for (t in LabelTemplate.entries) for ((w, h) in listOf(20f to 10f, 25f to 15f, 30f to 20f, 40f to 30f, 50f to 30f, 60f to 40f, 80f to 60f)) {
            val p = render(LabelOptions(template = t, widthMm = w, heightMm = h))
            // Margen blanco alrededor para el lector
            val big = BufferedImage(p.img.width + 40, p.img.height + 40, BufferedImage.TYPE_INT_RGB)
            big.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, big.width, big.height); drawImage(p.img, 20, 20, null) }
            val got = decode(big)
            System.getProperty("labeldebug")?.let { d -> javax.imageio.ImageIO.write(big, "png", File(d, "${t.name}-${w.toInt()}x${h.toInt()}-${if (got == null) "NO" else "ok"}.png")) }
            if (System.getProperty("labeldebug") == null) assertEquals("7501234567893", got, "${t.title} ${w}x$h no se pudo leer")
        }
    }

    @Test
    fun codigos() {
        val c = Codes.newInternalEan { false }
        assertTrue(c.startsWith("200") && Codes.isEan13(c), c)
        assertTrue(Codes.isEan13("7501234567893"))
        assertTrue(!Codes.isEan13("7501234567890"))
        Codes.linear("FND-054") // Code 128 para códigos con letras
    }

    @Test
    fun pdfsYCodigoAutomatico(): Unit = runBlocking {
        System.setProperty("vitrina.dataDir", Files.createTempDirectory("vitrina-labels").toString())
        val g = AppGraph(DesktopPlatform())
        val id = g.repo.saveProduct(ProductDraft(null, "Sin código", "", null, null, 0, 5000, 3.0, 0.0, "und", true, true, null, ""))
        val code = g.repo.productById(id)!!.code
        assertTrue(Codes.isEan13(code), "se asigna un código al guardar: '$code'")

        val out = System.getProperty("screenshots")?.let(::File) ?: Files.createTempDirectory("pdf").toFile()
        out.mkdirs()
        val labels = List(30) { content.copy(name = "Producto ${it + 1}") }
        val o = LabelOptions(widthMm = 50f, heightMm = 30f)
        val (w, h) = LabelPdf.pageSizePt(o)
        val roll = File(out, "etiquetas-rollo-50x30.pdf")
        g.platform.writePdf(roll, w, h) { LabelPdf.drawRoll(it, o, labels.take(5)) }
        val sheet = SheetPreset.all[0]
        val a4 = File(out, "etiquetas-hoja-A4.pdf")
        g.platform.writePdf(a4, sheet.pageW * PT_PER_MM, sheet.pageH * PT_PER_MM) { LabelPdf.drawSheet(it, sheet, o, labels, startAt = 4) }
        assertTrue(roll.length() > 1000 && a4.length() > 1000)
        assertEquals(2, LabelPdf.sheetsNeeded(sheet, 30, 4))
        g.repo.close()
    }
}
