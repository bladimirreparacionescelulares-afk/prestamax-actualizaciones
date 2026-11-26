package com.vitrinapos.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.vitrinapos.db.VitrinaDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.awt.Desktop
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import java.util.Properties
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.print.DocFlavor
import javax.print.PrintServiceLookup
import javax.print.SimpleDoc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DesktopPlatform : Platform {
    override val isDesktop = true
    override val platformName: String = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "Windows" else System.getProperty("os.name").orEmpty().ifBlank { "PC" }
    override val defaultDeviceName: String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "PC"

    override fun setNetworkActive(active: Boolean) {}

    override val io: CoroutineDispatcher = Dispatchers.IO

    override val dataDir: File = run {
        val custom = System.getProperty("vitrina.dataDir")
        val base = when {
            custom != null -> File(custom)
            System.getenv("APPDATA") != null -> File(System.getenv("APPDATA"), "VitrinaPOS")
            else -> File(System.getProperty("user.home"), ".vitrinapos")
        }
        base.apply { mkdirs() }
    }

    override val databaseFile: File get() = File(dataDir, "vitrina.db")

    override fun createDriver(): SqlDriver =
        JdbcSqliteDriver(
            "jdbc:sqlite:${databaseFile.absolutePath}",
            // Varios equipos pueden escribir a la vez: se espera en vez de fallar.
            Properties().apply { put("busy_timeout", "8000"); put("journal_mode", "WAL") },
            VitrinaDb.Schema,
        )

    override fun decodeImage(file: File, maxSide: Int): ImageBitmap? = runCatching {
        val img = ImageIO.read(file) ?: return null
        scale(img, maxSide).toComposeImageBitmap()
    }.getOrNull()

    override fun saveCompressedJpeg(src: File, dest: File, maxSide: Int, quality: Int): Boolean = runCatching {
        val img = ImageIO.read(src) ?: return false
        val scaled = scale(img, maxSide)
        val rgb = BufferedImage(scaled.width, scaled.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            color = java.awt.Color.WHITE
            fillRect(0, 0, rgb.width, rgb.height)
            drawImage(scaled, 0, 0, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        dest.parentFile?.mkdirs()
        ImageIO.createImageOutputStream(dest).use { out ->
            writer.output = out
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality / 100f
            }
            writer.write(null, IIOImage(rgb, null, null), params)
            writer.dispose()
        }
        true
    }.getOrDefault(false)

    private fun scale(img: BufferedImage, maxSide: Int): BufferedImage {
        val longest = max(img.width, img.height)
        if (longest <= maxSide) return img
        val f = maxSide.toDouble() / longest
        val w = (img.width * f).roundToInt().coerceAtLeast(1)
        val h = (img.height * f).roundToInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, if (img.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(img, 0, 0, w, h, null)
            dispose()
        }
        return out
    }

    override suspend fun listPrinters(): List<PrinterDevice> =
        PrintServiceLookup.lookupPrintServices(null, null).map { PrinterDevice(it.name, it.name, "Impresora de Windows") }

    override suspend fun printRaw(printer: PrinterDevice, bytes: ByteArray) {
        val service = PrintServiceLookup.lookupPrintServices(null, null).firstOrNull { it.name == printer.id }
            ?: error("La impresora \"${printer.name}\" no está disponible")
        service.createPrintJob().print(SimpleDoc(bytes, DocFlavor.BYTE_ARRAY.AUTOSENSE, null), null)
    }

    override fun writePdf(file: File, widthPt: Float, heightPt: Float, draw: (PdfCanvas) -> Unit) {
        file.parentFile?.mkdirs()
        PDDocument().use { doc ->
            val canvas = PdfBoxCanvas(doc, PDRectangle(widthPt, heightPt))
            try { draw(canvas) } finally { canvas.close() }
            doc.save(file)
        }
    }

    override fun scanFeedback(ok: Boolean) {
        runCatching { Toolkit.getDefaultToolkit().beep() }
    }

    override fun shareFile(file: File, mime: String, title: String) {
        // En Windows no hay hoja de compartir: se abre la carpeta para adjuntar el archivo donde se quiera.
        runCatching { Desktop.getDesktop().open(file.parentFile) }
    }

    override fun shareText(text: String, title: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    override fun openFile(file: File) {
        runCatching { Desktop.getDesktop().open(file) }
    }

    override fun openUrl(url: String): Boolean = runCatching { Desktop.getDesktop().browse(java.net.URI(url)) }.isSuccess
}

/** PDF con PDFBox; convierte las coordenadas (PDFBox cuenta desde abajo). */
private class PdfBoxCanvas(private val doc: PDDocument, private val size: PDRectangle) : PdfCanvas {
    override val pageWidth = size.width
    override val pageHeight = size.height
    private val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    private var stream: PDPageContentStream? = null
    private val images = HashMap<String, PDImageXObject?>()

    init { newPage() }

    override fun newPage() {
        stream?.close()
        val page = PDPage(size)
        doc.addPage(page)
        stream = PDPageContentStream(doc, page)
    }

    fun close() { stream?.close(); stream = null }

    private val s get() = stream!!
    private fun fy(y: Float) = pageHeight - y

    private fun setFill(color: Long) {
        s.setNonStrokingColor(((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f)
    }

    private fun setStroke(color: Long) {
        s.setStrokingColor(((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f)
    }

    /** Helvetica solo tiene los caracteres de Windows-1252; el resto se reemplaza. */
    private fun clean(text: String, font: PDType1Font): String = buildString {
        text.forEach { c ->
            val ok = runCatching { font.encode(c.toString()); true }.getOrDefault(false)
            append(if (ok) c else '?')
        }
    }

    override fun text(x: Float, y: Float, text: String, size: Float, bold: Boolean, color: Long, align: PdfCanvas.TextAlign) {
        val font = if (bold) this.bold else regular
        val t = clean(text, font)
        val w = font.getStringWidth(t) / 1000f * size
        val px = when (align) { PdfCanvas.TextAlign.Left -> x; PdfCanvas.TextAlign.Right -> x - w; PdfCanvas.TextAlign.Center -> x - w / 2 }
        s.beginText()
        s.setFont(font, size)
        setFill(color)
        s.newLineAtOffset(px, fy(y))
        s.showText(t)
        s.endText()
    }

    override fun textWidth(text: String, size: Float, bold: Boolean): Float {
        val font = if (bold) this.bold else regular
        return font.getStringWidth(clean(text, font)) / 1000f * size
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Long, radius: Float) {
        setFill(color)
        val bx = x
        val by = fy(y + h)
        val r = min(radius, min(w, h) / 2)
        if (r <= 0f) {
            s.addRect(bx, by, w, h)
        } else {
            val k = 0.5523f * r
            s.moveTo(bx + r, by)
            s.lineTo(bx + w - r, by)
            s.curveTo(bx + w - r + k, by, bx + w, by + r - k, bx + w, by + r)
            s.lineTo(bx + w, by + h - r)
            s.curveTo(bx + w, by + h - r + k, bx + w - r + k, by + h, bx + w - r, by + h)
            s.lineTo(bx + r, by + h)
            s.curveTo(bx + r - k, by + h, bx, by + h - r + k, bx, by + h - r)
            s.lineTo(bx, by + r)
            s.curveTo(bx, by + r - k, bx + r - k, by, bx + r, by)
            s.closePath()
        }
        s.fill()
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Long, width: Float) {
        setStroke(color)
        s.setLineWidth(width)
        s.moveTo(x1, fy(y1))
        s.lineTo(x2, fy(y2))
        s.stroke()
    }

    override fun image(file: File, x: Float, y: Float, w: Float, h: Float) {
        val img = images.getOrPut(file.path) {
            runCatching {
                // Reducir antes de incrustar para que el PDF pese poco.
                val src = ImageIO.read(file) ?: return@runCatching null
                val f = 300.0 / max(src.width, src.height)
                val scaled = if (f < 1) {
                    val out = BufferedImage((src.width * f).roundToInt(), (src.height * f).roundToInt(), BufferedImage.TYPE_INT_RGB)
                    out.createGraphics().apply {
                        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        drawImage(src, 0, 0, out.width, out.height, null); dispose()
                    }
                    out
                } else src
                JPEGFactory.createFromImage(doc, scaled, 0.85f)
            }.getOrNull()
        } ?: return
        // Ajustar sin deformar, centrado en la caja.
        val ratio = min(w / img.width, h / img.height)
        val dw = img.width * ratio
        val dh = img.height * ratio
        s.drawImage(img, x + (w - dw) / 2, fy(y + (h + dh) / 2), dw, dh)
    }
}
