package com.vitrinapos

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.vitrinapos.data.AppGraph
import com.vitrinapos.data.AppSettings
import com.vitrinapos.data.Condition
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.RepairDraft
import com.vitrinapos.data.RepairStatus
import com.vitrinapos.data.SaleStatus
import com.vitrinapos.data.UnlockTypes
import com.vitrinapos.data.code
import com.vitrinapos.data.parseRepairCode
import com.vitrinapos.labels.LabelContent
import com.vitrinapos.labels.LabelLayout
import com.vitrinapos.labels.LabelPainter
import com.vitrinapos.labels.LabelOptions
import com.vitrinapos.labels.RepairLabel
import com.vitrinapos.platform.DesktopPlatform
import com.vitrinapos.platform.PdfCanvas
import com.vitrinapos.print.Ticket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepairTest {
    private fun platform(name: String): DesktopPlatform {
        System.setProperty("vitrina.dataDir", Files.createTempDirectory("vitrina-$name").toString())
        return DesktopPlatform()
    }

    private val draft = RepairDraft(
        clientName = "María Pérez", clientPhone = "809-555-0101", brand = "Samsung", model = "Galaxy A54",
        serial = "356789012345678", color = "Negro", unlockType = UnlockTypes.PIN, unlockCode = "4821",
        problem = "Pantalla rota, no da imagen", conditionText = Condition.format(mapOf("Pantalla" to Condition.BAD, "Carga" to Condition.OK)),
        accessories = "Cargador,Funda", estimate = 350000, deposit = 100000, diagnosisFee = 30000, warrantyDays = 30,
    )

    @Test
    fun recibirCambiarEstadoYAbono(): Unit = runBlocking {
        val g = AppGraph(platform("taller"))
        val repo = g.repo
        val id = repo.saveRepair(draft, PaymentMethods.TRANSFER)
        val r = repo.repairById(id)!!
        assertEquals("REP-0001", r.code)
        assertEquals(RepairStatus.RECEIVED, r.status)
        // El cliente se registra solo.
        val client = repo.db.clientQueries.selectById(r.clientId!!).executeAsOne()
        assertEquals("María Pérez", client.name)
        // El abono es una venta normal (Caja, Ventas, Reportes).
        val sales = repo.salesByClient(client.id)
        assertEquals(1, sales.size)
        assertEquals(100000, sales[0].total)
        assertEquals(PaymentMethods.TRANSFER, sales[0].paymentMethod)
        assertEquals(SaleStatus.OK, sales[0].status)
        assertTrue(repo.saleItems(sales[0].id).single().name.contains("REP-0001"))

        // Segundo equipo del mismo cliente, sin abono: no crea venta.
        val id2 = repo.saveRepair(draft.copy(clientId = client.id, deposit = 0, model = "Galaxy S21"))
        assertEquals("REP-0002", repo.repairById(id2)!!.code)
        assertEquals(1, repo.salesByClient(client.id).size)
        assertEquals(1, repo.db.clientQueries.selectAll().executeAsList().count { it.name == "María Pérez" })

        // Cambios de estado con historial.
        repo.setRepairStatus(id, RepairStatus.DIAGNOSIS)
        repo.setRepairStatus(id, RepairStatus.READY, "Se cambió la pantalla")
        repo.setRepairStatus(id, RepairStatus.READY, "Cliente avisado por teléfono")
        assertEquals(RepairStatus.READY, repo.repairById(id)!!.status)
        val ev = repo.repairEvents(id)
        assertEquals(4, ev.size)
        assertEquals(setOf(RepairStatus.RECEIVED, RepairStatus.DIAGNOSIS, RepairStatus.READY), ev.map { it.status }.toSet())
        assertTrue(ev.any { it.note == "Cliente avisado por teléfono" })

        // Editar no cambia el número ni duplica el abono.
        repo.saveRepair(draft.copy(id = id, clientId = client.id, problem = "Pantalla rota y botón de encendido"))
        assertEquals("REP-0001", repo.repairById(id)!!.code)
        assertEquals("Pantalla rota y botón de encendido", repo.repairById(id)!!.problem)
        assertEquals(1, repo.salesByClient(client.id).size)

        // Buscar por el código de la etiqueta.
        assertEquals(1L, parseRepairCode("rep-0001"))
        assertEquals(1L, parseRepairCode(" REP-0001 "))
        assertNull(parseRepairCode("7501234567893"))
        assertEquals(id, repo.repairByNumber(1)!!.id)

        // Comprobante: lleva el código y el QR, y nunca el PIN del equipo.
        val t = Ticket.forRepair(AppSettings(), repo.repairById(id)!!)
        val text = t.preview()
        assertTrue("REP-0001" in text)
        assertTrue("María Pérez" in text)
        assertFalse("4821" in text, "el PIN no debe imprimirse")
        assertTrue(t.lines.any { it.qr == "REP-0001" })
        val bytes = t.escPos(ascii = false, openDrawer = false)
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("REP-0001"))
        // Comando de QR nativo (GS ( k)
        assertTrue(bytes.toList().windowed(3).any { it == listOf<Byte>(0x1D, 0x28, 0x6B) })
        g.repo.close()
    }

    @Test
    fun etiquetaDelEquipoSeLee() {
        val c = LabelContent(
            "María Pérez", "", "REP-0042", "Mi Tienda",
            repair = RepairLabel("REP-0042", "María Pérez", "809-555-0101", "Samsung Galaxy A54", "356789012345678", "26/11/2026", "Pantalla rota"),
        )
        for ((w, h) in listOf(30f to 20f, 40f to 30f, 50f to 30f, 50f to 25f, 60f to 40f, 80f to 50f)) {
            val dots = 8f
            val img = BufferedImage((w * dots).roundToInt(), (h * dots).roundToInt(), BufferedImage.TYPE_INT_RGB)
            val p = Painter(img, dots)
            LabelLayout.draw(p, LabelOptions(widthMm = w, heightMm = h), c)
            assertTrue(p.issues.isEmpty(), "${w}x$h: ${p.issues.take(3)}")
            val big = BufferedImage(img.width + 40, img.height + 40, BufferedImage.TYPE_INT_RGB)
            big.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, big.width, big.height); drawImage(img, 20, 20, null) }
            System.getProperty("labeldebug")?.let { javax.imageio.ImageIO.write(big, "png", java.io.File(it, "rep-${w.toInt()}x${h.toInt()}.png")) }
            val got = runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(big)))).text }.getOrNull()
            assertEquals("REP-0042", got, "${w}x$h no se pudo leer")
            assertTrue(p.texts.any { it.contains("María") }, "${w}x$h sin dueño")
        }
    }

    @Test
    fun recibirDesdeOtroEquipo(): Unit = runBlocking {
        val main = AppGraph(platform("taller-principal"))
        main.becomeServer()
        val other = AppGraph(platform("taller-caja"))
        other.connectTo("127.0.0.1", 8740, main.server!!.pairCode.value.code)
        val id = other.repo.saveRepair(draft)
        assertNotNull(main.repo.repairById(id))
        assertEquals(1, main.repo.db.saleQueries.selectRecent(10).executeAsList().size)
        main.repo.setRepairStatus(id, RepairStatus.READY)
        withTimeout(20_000) { while (other.repo.repairs.value.firstOrNull { it.id == id }?.status != RepairStatus.READY) delay(100) }
        withTimeout(20_000) { while (other.repo.repairEvents(id).size < 2) delay(100) }
        other.becomeSolo()
        main.becomeSolo()
        other.repo.close()
        main.repo.close()
    }
}

private class Painter(val img: BufferedImage, override val dotsPerMm: Float) : LabelPainter {
    val g = img.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, img.width, img.height); color = Color.BLACK }
    val issues = mutableListOf<String>()
    val texts = mutableListOf<String>()
    private fun check(what: String, x: Float, y: Float, x2: Float, y2: Float) {
        val wMm = img.width / dotsPerMm
        val hMm = img.height / dotsPerMm
        if (x < -0.05f || y < -0.05f || x2 > wMm + 0.05f || y2 > hMm + 0.05f) issues += "$what fuera: ($x,$y)-($x2,$y2)"
    }
    private fun font(size: Float, bold: Boolean) = Font("SansSerif", if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size * dotsPerMm)
    override fun rect(x: Float, y: Float, w: Float, h: Float) {
        check("rect", x, y, x + w, y + h)
        val x1 = (x * dotsPerMm).roundToInt(); val y1 = (y * dotsPerMm).roundToInt()
        val x2 = ((x + w) * dotsPerMm).roundToInt(); val y2 = ((y + h) * dotsPerMm).roundToInt()
        g.fillRect(x1, y1, maxOf(1, x2 - x1), maxOf(1, y2 - y1))
    }
    override fun text(x: Float, y: Float, text: String, sizeMm: Float, bold: Boolean, align: PdfCanvas.TextAlign) {
        texts += text
        val w = textWidth(text, sizeMm, bold)
        val left = when (align) { PdfCanvas.TextAlign.Left -> x; PdfCanvas.TextAlign.Right -> x - w; PdfCanvas.TextAlign.Center -> x - w / 2 }
        check("texto '$text'", left, y - sizeMm * 0.75f, left + w, y)
        g.font = font(sizeMm, bold)
        g.drawString(text, left * dotsPerMm, y * dotsPerMm)
    }
    override fun textWidth(text: String, sizeMm: Float, bold: Boolean): Float = g.getFontMetrics(font(sizeMm, bold)).stringWidth(text) / dotsPerMm
}
