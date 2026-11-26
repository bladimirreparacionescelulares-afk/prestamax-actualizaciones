package com.vitrinapos

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.vitrinapos.data.AppGraph
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.QuoteStatus
import com.vitrinapos.data.ProductDraft
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

        // ---- Etiquetas ----
        val ps = repo.products.value
        val job = listOf(
            AppState.LabelItem(ps.first { it.code == "AUD-210" }.id, 7),
            AppState.LabelItem(ps.first { it.code == "CRG-020" }.id, 2),
            AppState.LabelItem(ps.first { it.code == "PWB-100" }.id, 3),
        )
        shoot(graph, File(outDir, "18-etiquetas.png"), 412, 900, 2f) { app -> app.selectTab(Screen.More); app.labelJob.addAll(job); app.open(Screen.Labels) }
        shoot(graph, File(outDir, "19-diseno-etiqueta.png"), 412, 900, 2f) { app -> app.selectTab(Screen.More); app.openLabelsFor(job) }
        runBlocking { repo.saveSettings(repo.settings.value.copy(labelPrinterId = "net:192.168.1.50:9100", labelPrinterName = "Xprinter XP-365B")) }
        shoot(graph, File(outDir, "23-etiquetadora.png"), 412, 900, 2f) { app -> app.selectTab(Screen.More); app.open(Screen.LabelPrinter) }
        shoot(graph, File(outDir, "24-diseno-imprimir.png"), 412, 900, 2f) { app -> app.selectTab(Screen.More); app.openLabelsFor(job) }
        // ---- Taller ----
        val repId = runBlocking { seedRepairs(repo) }
        shoot(graph, File(outDir, "25-taller.png"), 412, 900, 2f) { it.selectTab(Screen.Taller) }
        shoot(graph, File(outDir, "26-recibir-equipo.png"), 412, 900, 2f) { it.selectTab(Screen.Taller); it.open(Screen.RepairReceive()) }
        shoot(graph, File(outDir, "27-orden.png"), 412, 900, 2f) { it.selectTab(Screen.Taller); it.open(Screen.RepairDetail(repId)) }
        shoot(graph, File(outDir, "28-windows-taller.png"), 1366, 800, 1f) { it.selectTab(Screen.Taller) }
        shoot(graph, File(outDir, "29-inicio-taller.png"), 412, 900, 2f) { it.selectTab(Screen.Home) }
        shoot(graph, File(outDir, "30-entregar.png"), 412, 900, 2f) { it.selectTab(Screen.Taller); it.open(Screen.RepairDetail(readyId)); it.open(Screen.RepairDeliver(readyId)) }
        shoot(graph, File(outDir, "31-entregado-garantia.png"), 412, 900, 2f) { it.selectTab(Screen.Taller); it.open(Screen.RepairDetail(doneId)) }
        shoot(graph, File(outDir, "32-estados.png"), 412, 900, 2f) { it.selectTab(Screen.More); it.open(Screen.RepairStatuses) }
        shoot(graph, File(outDir, "33-listos.png"), 412, 900, 2f) { it.selectTab(Screen.Taller); it.open(Screen.RepairDetail(readyId)) }

        // ---- Facturación electrónica ----
        shoot(graph, File(outDir, "34-fe-inicio.png"), 412, 900, 2f) { it.selectTab(Screen.More); it.open(Screen.Fiscal) }
        shoot(graph, File(outDir, "35-fe-certificado-guia.png"), 412, 900, 2f) { it.selectTab(Screen.More); it.open(Screen.FiscalCert) }
        runBlocking {
            repo.saveSettings(repo.settings.value.copy(
                fiscalKind = "JURIDICA", fiscalRnc = "101010632", fiscalName = "MI TIENDA DE ACCESORIOS SRL", fiscalTradeName = "Mi Tienda",
                fiscalAddress = "Av. Principal #24, Santo Domingo", fiscalSteps = "cert,ofv,solicitud,firmante",
            ))
            repo.fiscal.installCertificate(EcfFlowCert.p12, EcfFlowCert.pw)
            val exp = Time.now() + 400L * 86_400_000L
            repo.saveFiscalRange(32, 1, 1000, exp); repo.saveFiscalRange(31, 1, 200, exp); repo.saveFiscalRange(34, 1, 200, null)
            repo.saveSettings(repo.settings.value.copy(fiscalEnabled = true))
        }
        shoot(graph, File(outDir, "36-fe-certificado-listo.png"), 412, 900, 2f) { it.selectTab(Screen.More); it.open(Screen.FiscalCert) }
        shoot(graph, File(outDir, "37-fe-tramite.png"), 412, 900, 2f) { it.selectTab(Screen.More); it.open(Screen.FiscalDgii) }
        shoot(graph, File(outDir, "38-fe-datos.png"), 412, 900, 2f) { it.selectTab(Screen.More); it.open(Screen.FiscalEmitter) }
        val ps2 = repo.products.value
        val fsale = runBlocking {
            repo.recordSale(listOf(CartLine.of(ps2.first { it.code == "CRG-020" }, 2.0), CartLine.of(ps2.first { it.code == "CBL-001" })), null, 0,
                PaymentMethods.CARD, 0, null, com.vitrinapos.data.FiscalChoice(31, "101010632", "FERRETERÍA LA ESQUINA SRL"), allowNoStock = true)
        }
        runBlocking {
            repo.recordSale(listOf(CartLine.of(ps2.first { it.code == "AUD-210" })), null, 0, PaymentMethods.CASH, 200000, null)
            repo.recordSale(listOf(CartLine.of(ps2.first { it.code == "MIC-054" })), null, 0, PaymentMethods.CASH, 50000, null)
        }
        shoot(graph, File(outDir, "39-fe-inicio-activo.png"), 412, 900, 2f) { it.selectTab(Screen.More); it.open(Screen.Fiscal) }
        shoot(graph, File(outDir, "40-fe-secuencias.png"), 412, 900, 2f) { it.selectTab(Screen.More); it.open(Screen.FiscalSequences) }
        shoot(graph, File(outDir, "41-fe-cobro.png"), 412, 900, 2f) { app -> app.selectTab(Screen.Pos); addCart(app, repo); app.open(Screen.Checkout) }
        shoot(graph, File(outDir, "42-fe-venta.png"), 412, 1400, 2f) { it.selectTab(Screen.Home); it.open(Screen.SaleDetail(fsale)) }
        shoot(graph, File(outDir, "43-fe-comprobantes.png"), 412, 900, 2f) { it.selectTab(Screen.More); it.open(Screen.FiscalDocs) }
        // Las capturas de red siguientes son de una caja sin certificado.
        runBlocking { repo.saveSettings(repo.settings.value.copy(fiscalEnabled = false)) }
        val svc = runBlocking {
            repo.saveProduct(com.vitrinapos.data.ProductDraft(null, "Cambio de pantalla (mano de obra)", "", null, null, 0, 150000, 0.0, 0.0, "servicio", true, false, null, "", isService = true))
        }
        shoot(graph, File(outDir, "46-servicio.png"), 412, 900, 2f) { it.selectTab(Screen.Catalog); it.open(Screen.ProductForm(svc)) }
        shoot(graph, File(outDir, "22-pregunta-etiqueta.png"), 412, 900, 2f) { app -> app.selectTab(Screen.Catalog); app.labelPrompt = ps.first { it.code == "AUD-210" }.id }

        // ---- Reglas 1.8: caja y stock ----
        runBlocking {
            repo.cashMove(com.vitrinapos.data.CashMoveType.IN, 50000, "Cambio traído del banco")
            repo.cashMove(com.vitrinapos.data.CashMoveType.EXPENSE, 15000, "Botellón de agua y café")
            repo.cashMove(com.vitrinapos.data.CashMoveType.OUT, 300000, "Retiro del dueño")
        }
        shoot(graph, File(outDir, "47-caja-movimientos.png"), 412, 1300, 2f) { it.selectTab(Screen.More); it.open(Screen.Cash) }
        val few = repo.products.value.first { it.code == "AUD-305" } // segunda tarjeta, 4 en stock
        shoot(graph, File(outDir, "48-tope-stock.png"), 412, 900, 2f) { app ->
            app.selectTab(Screen.Pos); app.clearCart(); repeat(few.stock.toInt() + 2) { app.addToCart(few) }
        }
        runBlocking { repo.closeCashSession(repo.db.cashSessionQueries.selectOpen(repo.deviceId).executeAsOne(), 0, "") }
        shoot(graph, File(outDir, "49-cobro-caja-cerrada.png"), 412, 900, 2f) { app -> app.selectTab(Screen.Pos); addCart(app, repo); app.open(Screen.Checkout) }
        runBlocking { repo.openCashSession(200000) }

        // ---- Inventario 1.9 ----
        val inv = runBlocking {
            val sup = repo.suppliers.value.firstOrNull()
            val phoneId = repo.saveProduct(com.vitrinapos.data.ProductDraft(null, "Samsung Galaxy A15", "", null, sup?.id, 0, 1_250_000, 0.0, 1.0, "und", true, true, null, "", serialized = true))
            val cbl = repo.products.value.first { it.code == "CBL-001" }
            val pid = repo.savePurchase(
                sup, sup?.name ?: "Cedecom", "131234567", "B0100004521", com.vitrinapos.data.Time.now(),
                listOf(
                    com.vitrinapos.data.PurchaseLine(phoneId, 3.0, 950_000, listOf("356789104523311", "356789104523329", "356789104523337")),
                    com.vitrinapos.data.PurchaseLine(cbl.id, 20.0, cbl.cost + 2_000),
                ),
                (3 * 950_000 + 20 * (cbl.cost + 2_000)) * 18 / 100, "Crédito", "",
            )
            val fundaA = repo.saveProduct(com.vitrinapos.data.ProductDraft(null, "Funda silicona iPhone 13", "", null, null, 15000, 45000, 6.0, 2.0, "und", true, true, null, "", variantName = "Negra"))
            repo.saveProduct(com.vitrinapos.data.ProductDraft(null, "Funda silicona iPhone 13", "", null, null, 15000, 45000, 4.0, 2.0, "und", true, true, null, "", variantGroup = "g$fundaA", variantName = "Roja"))
            repo.saveProduct(com.vitrinapos.data.ProductDraft(null, "Funda silicona iPhone 13", "", null, null, 15000, 50000, 2.0, 2.0, "und", true, true, null, "", variantGroup = "g$fundaA", variantName = "Transparente MagSafe"))
            val phone = repo.db.productQueries.selectById(phoneId).executeAsOne()
            val sale = repo.recordSale(listOf(CartLine.of(phone, 1.0, listOf("356789104523311")), CartLine.of(cbl, 2.0)), null, 0, PaymentMethods.CASH, 2_000_000, null)
            val item = repo.saleItems(sale).first { it.productId == cbl.id }
            repo.returnSale(sale, listOf(com.vitrinapos.data.ReturnLine(item.id, 1.0)), "El cable vino defectuoso", PaymentMethods.CASH)
            repo.stockLoss(cbl.id, 1.0, "Dañado", "Se cayó en el mostrador")
            val ps3 = repo.products.value
            repo.saveCountDraft(ps3.filter { it.trackStock == 1L && it.serialized == 0L }.take(5).mapIndexed { i, p -> p.id to ((p.stock + listOf(0, -1, 0, 2, 0)[i]).coerceAtLeast(0.0) to p.stock) }.toMap())
            listOf(phoneId, pid, sale, fundaA, cbl.id)
        }
        val (phoneId, purchaseId, retSale, fundaA, cblId) = inv
        Thread.sleep(500)
        shoot(graph, File(outDir, "50-inventario.png"), 412, 1100, 2f) { it.selectTab(Screen.More); it.open(Screen.Inventory) }
        shoot(graph, File(outDir, "51-compra.png"), 412, 1300, 2f) { it.selectTab(Screen.More); it.open(Screen.Purchases); it.open(Screen.PurchaseDetail(purchaseId)) }
        shoot(graph, File(outDir, "52-conteo.png"), 412, 900, 2f) { it.selectTab(Screen.More); it.open(Screen.StockCount) }
        shoot(graph, File(outDir, "53-kardex.png"), 412, 1200, 2f) { it.selectTab(Screen.Catalog); it.open(Screen.Kardex(cblId)) }
        shoot(graph, File(outDir, "54-imei-producto.png"), 412, 900, 2f) { it.selectTab(Screen.Catalog); it.open(Screen.ProductUnits(phoneId)) }
        shoot(graph, File(outDir, "55-elegir-imei.png"), 412, 900, 2f) { app -> app.selectTab(Screen.Pos); app.clearCart(); app.unitPicker = phoneId }
        shoot(graph, File(outDir, "56-variantes.png"), 412, 900, 2f) { app -> app.selectTab(Screen.Pos); app.clearCart(); app.variantPicker = "g$fundaA" }
        shoot(graph, File(outDir, "57-devolucion.png"), 412, 1100, 2f) { it.selectTab(Screen.Home); it.open(Screen.SaleReturn(retSale)) }
        shoot(graph, File(outDir, "58-venta-con-devolucion.png"), 412, 1500, 2f) { it.selectTab(Screen.Home); it.open(Screen.SaleDetail(retSale)) }
        shoot(graph, File(outDir, "59-producto-imei.png"), 412, 1500, 2f) { it.selectTab(Screen.Catalog); it.open(Screen.ProductForm(phoneId)) }
        shoot(graph, File(outDir, "60-reportes-19.png"), 412, 1400, 2f) { it.selectTab(Screen.More); it.open(Screen.Reports) }

        // ---- Trabajo en red y usuarios ----
        graph.becomeServer()
        runBlocking {
            graph.repo.saveUser(null, "Bladimir", "1234", com.vitrinapos.data.Roles.ADMIN, true)
            graph.repo.saveUser(null, "Pedro", "5678", com.vitrinapos.data.Roles.SELLER, true)
            graph.repo.saveUser(null, "Luis", "2468", com.vitrinapos.data.Roles.TECH, true)
        }
        val admin = graph.repo.db.appUserQueries.selectAll().executeAsList().first { it.name == "Bladimir" }
        // Un segundo equipo conectado al principal
        System.setProperty("vitrina.dataDir", Files.createTempDirectory("vitrina-shots-2").toString())
        val other = AppGraph(DesktopPlatform())
        other.setDeviceName("Celular de Pedro")
        runBlocking { other.connectTo("127.0.0.1", com.vitrinapos.net.SYNC_PORT, graph.server!!.pairCode.value.code) }
        Thread.sleep(1500)
        shoot(graph, File(outDir, "13-red-principal.png"), 412, 900, 2f) { it.repo.currentUser.value = admin; it.selectTab(Screen.More); it.open(Screen.Network) }
        shoot(graph, File(outDir, "14-usuarios.png"), 412, 900, 2f) { it.repo.currentUser.value = admin; it.selectTab(Screen.More); it.open(Screen.Users) }
        val pedroId = graph.repo.db.appUserQueries.selectAll().executeAsList().first { it.name == "Pedro" }.id
        runBlocking { graph.repo.setUserPermissions(pedroId, com.vitrinapos.data.Permissions.defaults(com.vitrinapos.data.Roles.SELLER) + com.vitrinapos.data.Permission.WORKSHOP - com.vitrinapos.data.Permission.CASH) }
        shoot(graph, File(outDir, "44-permisos-usuario.png"), 412, 1500, 2f) { it.repo.currentUser.value = admin; it.selectTab(Screen.More); it.open(Screen.UserDetail(pedroId)) }
        shoot(graph, File(outDir, "45-vendedor-mas.png"), 412, 900, 2f) { app ->
            app.repo.currentUser.value = graph.repo.db.appUserQueries.selectById(pedroId).executeAsOne(); app.selectTab(Screen.More)
        }
        shoot(graph, File(outDir, "15-entrar.png"), 412, 900, 2f) { it.repo.currentUser.value = null }
        val pedro = other.repo.db.appUserQueries.selectAll().executeAsList().first { it.name == "Pedro" }
        shoot(other, File(outDir, "16-conectado.png"), 412, 900, 2f) { it.repo.currentUser.value = pedro; it.selectTab(Screen.Home) }
        // Sin red: la venta queda pendiente (la caja del celular se abrió antes)
        runBlocking { other.repo.openCashSession(0) }
        Thread.sleep(1500)
        graph.server!!.stop()
        runBlocking {
            other.repo.currentUser.value = pedro
            val p = other.repo.products.value.first { it.code == "FND-054" }
            other.repo.recordSale(listOf(CartLine.of(p)), null, 0, PaymentMethods.CASH, 30000, null)
        }
        shoot(other, File(outDir, "17-conectado-sin-red.png"), 412, 900, 2f) { it.repo.currentUser.value = pedro; it.selectTab(Screen.Home) }
        other.becomeSolo(); other.repo.close()
        graph.becomeSolo()

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

    private suspend fun seedRepairs(repo: Repository): Long {
        val day = 86_400_000L
        val base = com.vitrinapos.data.RepairDraft(diagnosisFee = 30000, warrantyDays = 30)
        val clients = repo.db.clientQueries.selectAll().executeAsList()
        val id = repo.saveRepair(base.copy(
            clientName = "María Pérez", clientPhone = "809-555-0101", brand = "Samsung", model = "Galaxy A54", serial = "356789012345678",
            color = "Negro", unlockType = com.vitrinapos.data.UnlockTypes.PATTERN, unlockCode = "1-2-3-6-9", problem = "Pantalla rota, no da imagen",
            conditionText = com.vitrinapos.data.Condition.format(mapOf("Pantalla" to "mal", "Carga" to "ok", "Cámaras" to "ok", "Botones" to "ok")),
            accessories = "Cargador,Funda", estimate = 350000, deposit = 100000, estimatedAt = Time.now() + 2 * day, technician = "Luis",
        ))
        repo.setRepairStatus(id, com.vitrinapos.data.RepairStatus.DIAGNOSIS, "Display dañado, táctil funciona")
        repo.setRepairStatus(id, com.vitrinapos.data.RepairStatus.REPAIRING)
        val late = repo.saveRepair(base.copy(clientId = clients[0].id, clientName = clients[0].name, clientPhone = clients[0].phone, brand = "Apple", model = "iPhone 12",
            problem = "No carga", estimate = 180000, estimatedAt = Time.now() - 3 * day))
        repo.setRepairStatus(late, com.vitrinapos.data.RepairStatus.PARTS, "Se pidió el puerto de carga")
        val ready = repo.saveRepair(base.copy(clientName = "José Rodríguez", clientPhone = "829-555-0177", deviceType = com.vitrinapos.data.DeviceTypes.LAPTOP,
            brand = "HP", model = "Pavilion 15", serial = "5CD1234XYZ", problem = "Muy lenta, limpieza y cambio a SSD", estimate = 450000, deposit = 200000, estimatedAt = Time.now()))
        repo.setRepairStatus(ready, com.vitrinapos.data.RepairStatus.READY)
        // Uno entregado con garantía y otro listo desde hace 4 meses (abandonado).
        val done = repo.saveRepair(base.copy(clientName = "Carlos Méndez", clientPhone = "809-555-0144", brand = "Xiaomi", model = "Redmi Note 12",
            problem = "Cambio de batería", estimate = 150000, deposit = 50000, estimatedAt = Time.now() - day))
        repo.setRepairStatus(done, com.vitrinapos.data.RepairStatus.READY)
        repo.deliverRepair(done, listOf(com.vitrinapos.data.CartLine(null, "Cambio de batería", "REP-0004", 1.0, 150000, 0, true, null, false)), true, PaymentMethods.CASH, 100000)
        val old = repo.saveRepair(base.copy(clientName = "Pedro Almonte", clientPhone = "829-555-0190", brand = "Motorola", model = "Moto G32",
            problem = "No enciende", estimate = 120000))
        repo.setRepairStatus(old, com.vitrinapos.data.RepairStatus.READY)
        repo.driver.execute(null, "UPDATE repairOrder SET updatedAt = ?, createdAt = ? WHERE id = ?", 3) {
            bindLong(0, Time.now() - 120 * day); bindLong(1, Time.now() - 130 * day); bindLong(2, old)
        }
        repo.driver.notifyListeners("repairOrder")
        repo.saveRepair(base.copy(clientName = "Ana Castillo", clientPhone = "849-555-0123", deviceType = com.vitrinapos.data.DeviceTypes.TABLET, brand = "Lenovo",
            model = "Tab M10", problem = "Batería se descarga rápido", estimatedAt = Time.now() + day))
        doneId = done
        readyId = ready
        return id
    }
    private var doneId = 0L
    private var readyId = 0L

    private suspend fun seed(repo: Repository) {
        SampleData.load(repo)
        repo.openCashSession(200000)
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
                ProductDraft(p.id, p.name, p.code, p.categoryId, p.supplierId, p.cost, p.price, 0.0, p.minStock, p.unit,
                    p.taxable == 1L, p.trackStock == 1L, name, p.description),
            )
        }
        val ps = repo.db.productQueries.selectActive().executeAsList()
        fun line(code: String, q: Double = 1.0) = CartLine.of(ps.first { it.code == code }, q)
        val clients = repo.db.clientQueries.selectAll().executeAsList()
        // Ventas de ayer (insertadas con fecha anterior)
        val yesterday = Time.addDays(Time.startOfDay(), -1) + 10 * 3_600_000L
        repo.db.saleQueries.insert(1, null, "Consumidor final", 1450000, 0, 221186, 1450000, 800000, 1, PaymentMethods.CASH, 1450000, 0, null, yesterday, repo.deviceId, null, "")
        repo.recordSale(listOf(line("FND-054", 2.0), line("MIC-054", 2.0)), clients[0], 0, PaymentMethods.CARD, 0, null)
        repo.recordSale(listOf(line("PWB-100"), line("CBL-002")), null, 0, PaymentMethods.TRANSFER, 0, null)
        repo.recordSale(listOf(line("AUD-305"), line("CRG-110", 2.0)), clients[1], 0, PaymentMethods.CASH, 250000, null)
        repo.recordSale(listOf(line("CRG-020"), line("AUD-210"), line("CBL-001")), null, 0, PaymentMethods.CASH, 250000, null)
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

/** Certificado de prueba para las capturas (hecho con keytool). */
object EcfFlowCert {
    val pw = "clave-de-prueba".toCharArray()
    val p12: ByteArray by lazy {
        val f = File(java.nio.file.Files.createTempDirectory("c").toFile(), "c.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "f", "-keyalg", "RSA", "-keysize", "2048", "-dname",
            "CN=MARIA PEREZ RODRIGUEZ, SERIALNUMBER=IDCDO-00113918205, O=AVANSI (PRUEBA), C=DO", "-validity", "365", "-storetype", "PKCS12",
            "-keystore", f.path, "-storepass", String(pw), "-keypass", String(pw)).redirectErrorStream(true).start()
        p.inputStream.readBytes(); check(p.waitFor() == 0)
        f.readBytes()
    }
}
