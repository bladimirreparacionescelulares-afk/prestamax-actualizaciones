package com.vitrinapos

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.vitrinapos.data.AppGraph
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.QuoteStatus
import com.vitrinapos.data.Repository
import com.vitrinapos.data.SampleData
import com.vitrinapos.data.Time
import com.vitrinapos.platform.DesktopPlatform
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Genera capturas de las pantallas con datos de ejemplo.
 * Se ejecuta solo con -Dscreenshots=<carpeta>.
 */
class Screenshots {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun render() {
        val outDir = System.getProperty("screenshots")?.let(::File) ?: return
        outDir.mkdirs()
        val dir = Files.createTempDirectory("vitrina-shots").toFile()
        System.setProperty("vitrina.dataDir", dir.path)
        val graph = AppGraph(DesktopPlatform())
        val repo = graph.repo
        runBlocking { seed(repo) }

        val quoteId = repo.db.quoteQueries.selectAll().executeAsList().first().id
        val saleId = repo.db.saleQueries.selectRecent(1).executeAsList().first().id
        val shots = listOf<Pair<String, (AppState) -> Unit>>(
            "01-inicio" to { it.selectTab(Screen.Home) },
            "02-vender" to { app -> app.selectTab(Screen.Pos); addCart(app, repo) },
            "03-cobro" to { app -> app.selectTab(Screen.Pos); addCart(app, repo); app.open(Screen.Checkout) },
            "04-catalogo" to { it.selectTab(Screen.Catalog) },
            "05-nuevo-producto" to { it.selectTab(Screen.Catalog); it.open(Screen.ProductForm(repo.products.value.first { p -> p.code == "AUD-210" }.id)) },
            "06-clientes" to { it.selectTab(Screen.Clients) },
            "07-mas" to { it.selectTab(Screen.More) },
            "08-cotizacion" to { it.selectTab(Screen.More); it.open(Screen.QuoteEditor(quoteId)) },
            "09-venta-ticket" to { it.selectTab(Screen.Home); it.open(Screen.SaleDetail(saleId)) },
            "10-reportes" to { it.selectTab(Screen.More); it.open(Screen.Reports) },
            "11-configuracion" to { it.selectTab(Screen.More); it.open(Screen.Settings) },
            "12-proveedores" to { it.selectTab(Screen.More); it.open(Screen.Suppliers) },
        )
        shots.forEach { (name, setup) -> shoot(graph, File(outDir, "$name.png"), 412, 900, 2f, setup) }
        shoot(graph, File(outDir, "20-windows-vender.png"), 1366, 800, 1f) { app -> app.selectTab(Screen.Pos); addCart(app, repo) }
        shoot(graph, File(outDir, "21-windows-inicio.png"), 1366, 800, 1f) { it.selectTab(Screen.Home) }

        // PDF de la cotización
        runBlocking {
            val q = repo.quoteById(quoteId)!!
            val items = repo.quoteItems(quoteId)
            graph.platform.writePdf(File(outDir, "cotizacion-A4.pdf")) { c ->
                com.vitrinapos.pdf.QuotePdf.draw(c, repo.settings.value, q, items, repo.clients.value.firstOrNull { it.id == q.clientId }, null, { repo.imageFile(it) })
            }
        }
        graph.repo.close()
    }

    private fun addCart(app: AppState, repo: Repository) {
        app.clearCart()
        val ps = repo.products.value
        app.cart += CartLine.of(ps.first { it.code == "CRG-020" })
        app.cart += CartLine.of(ps.first { it.code == "AUD-210" })
        app.cart += CartLine.of(ps.first { it.code == "CBL-001" })
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun shoot(graph: AppGraph, file: File, w: Int, h: Int, density: Float, setup: (AppState) -> Unit) {
        ImageComposeScene((w * density).toInt(), (h * density).toInt(), Density(density)) {
            App(graph, onReady = setup)
        }.use { scene ->
            var t = 0L
            repeat(40) {
                scene.render(t); t += 50_000_000L
                Thread.sleep(40)
            }
            val img = scene.render(t)
            file.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    private suspend fun seed(repo: Repository) {
        SampleData.load(repo)
        repo.saveSettings(
            repo.settings.value.copy(
                businessName = "Mi Tienda", businessTagline = "Accesorios para celulares", address = "Av. Principal #24, Santo Domingo",
                phone = "809-555-0100", taxId = "131000000", onboarded = true, printerId = "PT-210", printerName = "PT-210", sellerName = "Vendedor 1",
            ),
        )
        // Fotos de ejemplo dibujadas para cada producto
        val kinds = mapOf(
            "CRG-020" to "charger", "CRG-110" to "car", "AUD-210" to "headphones", "AUD-305" to "speaker", "FND-054" to "case",
            "FND-113" to "case2", "MIC-113" to "glass", "MIC-054" to "glass2", "PWB-100" to "powerbank", "CBL-001" to "cable", "CBL-002" to "cable2",
        )
        repo.products.value.ifEmpty { repo.db.productQueries.selectActive().executeAsList() }
        for (p in repo.db.productQueries.selectActive().executeAsList()) {
            val kind = kinds[p.code] ?: continue
            val tmp = File.createTempFile("img", ".png")
            ImageIO.write(drawProduct(kind), "png", tmp)
            val name = repo.importImage(tmp)
            repo.saveProduct(
                Repository.ProductDraft(p.id, p.name, p.code, p.categoryId, p.supplierId, p.cost, p.price, 0.0, p.minStock, p.unit,
                    p.taxable == 1L, p.trackStock == 1L, name, p.description),
            )
        }
        val ps = repo.db.productQueries.selectActive().executeAsList()
        fun line(code: String, q: Double = 1.0) = CartLine.of(ps.first { it.code == code }, q)
        val clients = repo.db.clientQueries.selectAll().executeAsList()
        // Ventas de ayer (insertadas con fecha anterior)
        val yesterday = Time.addDays(Time.startOfDay(), -1) + 10 * 3_600_000L
        repo.db.saleQueries.insert(1, null, "Consumidor final", 1450000, 0, 221186, 1450000, 800000, 1, PaymentMethods.CASH, 1450000, 0, null, yesterday)
        repo.recordSale(listOf(line("FND-054", 2.0), line("MIC-054", 2.0)), clients[0], 0, PaymentMethods.CARD, 0, null)
        repo.recordSale(listOf(line("PWB-100"), line("CBL-002")), null, 0, PaymentMethods.TRANSFER, 0, null)
        repo.recordSale(listOf(line("AUD-305"), line("CRG-110", 2.0)), clients[1], 0, PaymentMethods.CASH, 250000, null)
        repo.recordSale(listOf(line("CRG-020"), line("AUD-210"), line("CBL-001")), null, 0, PaymentMethods.CASH, 250000, null)
        repo.openCashSession(200000)
        repo.saveQuote(null, clients[2], clients[2].name, listOf(line("CRG-020", 10.0).copy(price = 78000), line("CBL-001", 10.0).copy(price = 28000), line("PWB-100", 4.0).copy(price = 135000)),
            0, 15, "", QuoteStatus.SENT)
    }

    /** Ilustraciones simples de productos para que el catálogo se vea con fotos. */
    private fun drawProduct(kind: String): BufferedImage {
        val size = 600
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val (a, b) = when (kind) {
            "charger", "car" -> Color(0xFFE4D6) to Color(0xF7B79A)
            "headphones", "speaker" -> Color(0xF1E6FF) to Color(0xC9AEF2)
            "case", "case2" -> Color(0xE6F6E9) to Color(0xA8DDB5)
            "glass", "glass2" -> Color(0xDCEBFF) to Color(0xA9C8F5)
            "powerbank" -> Color(0xFFF4CF) to Color(0xF2D27A)
            else -> Color(0xE4F4F7) to Color(0x9FD3DD)
        }
        g.paint = GradientPaint(0f, 0f, a, size.toFloat(), size.toFloat(), b)
        g.fillRect(0, 0, size, size)
        g.color = Color(0, 0, 0, 28)
        g.fill(Ellipse2D.Double(150.0, 470.0, 300.0, 50.0))
        val dark = Color(0x2B2F33)
        val white = Color(0xFAFAFA)
        fun shade(c: Color) { g.color = c }
        when (kind) {
            "charger" -> {
                shade(white); g.fill(RoundRectangle2D.Double(200.0, 200.0, 200.0, 240.0, 60.0, 60.0))
                shade(Color(0xB0B5BA)); g.fillRect(250, 130, 26, 80); g.fillRect(324, 130, 26, 80)
                shade(dark); g.fill(RoundRectangle2D.Double(270.0, 380.0, 60.0, 24.0, 12.0, 12.0))
            }
            "car" -> {
                shade(dark); g.fill(RoundRectangle2D.Double(250.0, 140.0, 100.0, 300.0, 50.0, 50.0))
                shade(Color(0x5AA9E6)); g.fillRect(270, 190, 60, 16); g.fillRect(270, 230, 60, 16)
                shade(Color(0xB0B5BA)); g.fill(RoundRectangle2D.Double(235.0, 420.0, 130.0, 50.0, 20.0, 20.0))
            }
            "headphones" -> {
                g.stroke = BasicStroke(36f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); shade(dark)
                g.draw(Arc2D.Double(160.0, 140.0, 280.0, 300.0, 0.0, 180.0, Arc2D.OPEN))
                g.fill(RoundRectangle2D.Double(130.0, 270.0, 90.0, 160.0, 40.0, 40.0)); g.fill(RoundRectangle2D.Double(380.0, 270.0, 90.0, 160.0, 40.0, 40.0))
                shade(Color(0x8B5CF6)); g.fill(RoundRectangle2D.Double(150.0, 300.0, 50.0, 100.0, 24.0, 24.0)); g.fill(RoundRectangle2D.Double(400.0, 300.0, 50.0, 100.0, 24.0, 24.0))
            }
            "speaker" -> {
                shade(Color(0x3B3F5C)); g.fill(RoundRectangle2D.Double(150.0, 200.0, 300.0, 240.0, 80.0, 80.0))
                shade(Color(0x1E2033)); g.fill(Ellipse2D.Double(230.0, 250.0, 140.0, 140.0))
                shade(Color(0x8B5CF6)); g.fill(Ellipse2D.Double(275.0, 295.0, 50.0, 50.0))
            }
            "case", "case2" -> {
                shade(if (kind == "case") Color(0x2F9E62) else Color(0x1F2937)); g.fill(RoundRectangle2D.Double(200.0, 110.0, 200.0, 380.0, 60.0, 60.0))
                shade(Color(0, 0, 0, 60)); g.fill(RoundRectangle2D.Double(225.0, 135.0, 90.0, 110.0, 30.0, 30.0))
                shade(Color(0x111111)); g.fill(Ellipse2D.Double(240.0, 150.0, 36.0, 36.0)); g.fill(Ellipse2D.Double(240.0, 195.0, 36.0, 36.0))
            }
            "glass", "glass2" -> {
                shade(Color(255, 255, 255, 170)); g.fill(RoundRectangle2D.Double(190.0, 110.0, 220.0, 390.0, 50.0, 50.0))
                g.stroke = BasicStroke(6f); shade(Color(0x2F6FD6)); g.draw(RoundRectangle2D.Double(190.0, 110.0, 220.0, 390.0, 50.0, 50.0))
                shade(Color(255, 255, 255, 220)); g.fill(Path2D.Double().apply { moveTo(220.0, 160.0); lineTo(300.0, 160.0); lineTo(220.0, 300.0); closePath() })
            }
            "powerbank" -> {
                shade(Color(0x3A3F44)); g.fill(RoundRectangle2D.Double(190.0, 130.0, 220.0, 360.0, 40.0, 40.0))
                shade(Color(0x22C55E)); repeat(4) { i -> g.fill(RoundRectangle2D.Double(270.0, 380.0 - i * 40.0, 60.0, 26.0, 8.0, 8.0)) }
                shade(Color(0xB0B5BA)); g.fillRect(280, 115, 40, 20)
            }
            else -> {
                g.stroke = BasicStroke(22f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); shade(white)
                g.draw(Path2D.Double().apply { moveTo(170.0, 180.0); curveTo(470.0, 140.0, 130.0, 460.0, 430.0, 420.0) })
                shade(if (kind == "cable") Color(0xE5E7EB) else Color(0x374151))
                g.fill(RoundRectangle2D.Double(130.0, 150.0, 60.0, 90.0, 18.0, 18.0)); g.fill(RoundRectangle2D.Double(410.0, 370.0, 60.0, 90.0, 18.0, 18.0))
            }
        }
        g.dispose()
        return img
    }
}

private fun Graphics2D.noop() = Unit
