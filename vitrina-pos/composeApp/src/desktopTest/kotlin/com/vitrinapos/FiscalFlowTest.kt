package com.vitrinapos

import com.vitrinapos.data.AppGraph
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.EcfStatus
import com.vitrinapos.data.FiscalChoice
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.RepairDraft
import com.vitrinapos.data.RepairStatus
import com.vitrinapos.data.SampleData
import com.vitrinapos.data.SaleStatus
import com.vitrinapos.fiscal.DgiiClient
import com.vitrinapos.fiscal.XmlSigner
import com.vitrinapos.platform.DesktopPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Facturación electrónica de punta a punta, con la DGII simulada. */
class FiscalFlowTest {
    private val pw = "clave-de-prueba".toCharArray()
    private val p12: ByteArray by lazy {
        val f = File(Files.createTempDirectory("c").toFile(), "c.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "f", "-keyalg", "RSA", "-keysize", "2048", "-dname",
            "CN=MARIA PEREZ, SERIALNUMBER=00113918205, C=DO", "-validity", "365", "-storetype", "PKCS12",
            "-keystore", f.path, "-storepass", String(pw), "-keypass", String(pw)).redirectErrorStream(true).start()
        p.inputStream.readBytes(); check(p.waitFor() == 0)
        f.readBytes()
    }

    private fun platform(name: String): DesktopPlatform {
        System.setProperty("vitrina.dataDir", Files.createTempDirectory("vitrina-$name").toString())
        return DesktopPlatform()
    }

    /** DGII de mentira: acepta todo y cuenta lo que recibe. */
    class FakeDgii : DgiiClient.Http {
        val received = mutableListOf<String>()
        override fun get(url: String, headers: Map<String, String>): Pair<Int, String> = when {
            url.endsWith("/semilla") -> 200 to """<?xml version="1.0" encoding="utf-8"?><SemillaModel xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><valor>abc</valor><fecha>2026-11-26T10:00:00-04:00</fecha></SemillaModel>"""
            url.contains("consultaresultado") -> 200 to """{"trackId":"t","codigo":1,"estado":"Aceptado","mensajes":[]}"""
            else -> 404 to ""
        }
        override fun postXml(url: String, fileName: String, xml: String, headers: Map<String, String>): Pair<Int, String> {
            if (url.endsWith("validarsemilla")) {
                check(XmlSigner.verify(xml)) { "semilla mal firmada" }
                return 200 to """{"token":"tok","expira":"2099-01-01T00:00:00Z","expedido":"2026-11-26T00:00:00Z"}"""
            }
            check(headers["Authorization"] == "Bearer tok")
            check(XmlSigner.verify(xml)) { "documento mal firmado" }
            received += fileName
            return if (url.contains("recepcionfc")) 200 to """{"codigo":1,"estado":"Aceptado","mensajes":[],"encf":"x","secuenciaUtilizada":true}"""
            else 200 to """{"trackId":"track-${received.size}","error":"","mensaje":""}"""
        }
    }

    private suspend fun AppGraph.setupFiscal(shareCert: Boolean = false) {
        repo.saveSettings(repo.settings.value.copy(
            fiscalEnabled = true, fiscalRnc = "101010632", fiscalName = "MI TIENDA SRL", fiscalAddress = "Av. Principal #24",
            taxRate = 18.0, pricesIncludeTax = true, fiscalCertOnClients = shareCert, fiscalBlockSize = 20,
        ))
        repo.fiscal.installCertificate(p12, pw)
        val exp = System.currentTimeMillis() + 300L * 86_400_000L
        repo.saveFiscalRange(32, 1, 1000, exp)
        repo.saveFiscalRange(31, 1, 100, exp)
        repo.saveFiscalRange(34, 1, 100, null)
    }

    @Test
    fun equipoSolo(): Unit = runBlocking {
        val g = AppGraph(platform("fiscal-solo"))
        val repo = g.repo
        SampleData.load(repo)
        g.setupFiscal()
        assertTrue(repo.fiscal.missing().isEmpty(), repo.fiscal.missing().toString())
        val ps = repo.db.productQueries.selectActive().executeAsList()
        val cable = ps.first { it.code == "CBL-001" }

        // Venta de consumo: e-CF E32 firmado, válido y con su resumen.
        val s1 = repo.recordSale(listOf(CartLine.of(cable, 2.0)), null, 1000, PaymentMethods.CASH, 100000, null)
        val d1 = repo.ecfForSale(s1).single()
        assertEquals("E320000000001", d1.encf)
        assertEquals(EcfStatus.SIGNED, d1.status)
        EcfTest.validate("ecf32.xsd", d1.xml)
        assertTrue(XmlSigner.verify(d1.xml))
        assertEquals(repo.saleById(s1)!!.total, d1.total)
        EcfTest.validate("rfce32.xsd", repo.fiscal.sign(d1.summaryXml).xml)

        // El ticket de la venta lleva el QR de consulta de la DGII, el código de seguridad y la fecha de firma.
        val tk = com.vitrinapos.print.Ticket.forSale(repo.settings.value, repo.saleById(s1)!!, repo.saleItems(s1), ecf = d1)
        val qr = tk.lines.mapNotNull { it.qr }.single()
        assertTrue(qr.startsWith("https://fc.dgii.gov.do/testecf/ConsultaTimbreFC?RncEmisor=101010632&ENCF=E320000000001&MontoTotal="), qr)
        assertTrue(tk.preview().contains("Código de seguridad: ${d1.securityCode}"))
        val raw = tk.escPos(ascii = false, openDrawer = false)
        assertTrue(raw.toList().windowed(3).any { it == listOf<Byte>(0x1D, 0x28, 0x6B) }, "comando de QR de la impresora")
        assertTrue(String(raw, Charsets.ISO_8859_1).contains(qr), "el enlace completo va dentro del QR")
        System.getProperty("fiscalOut")?.let { File(it).apply { mkdirs() }.resolve("ticket-ecf.txt").writeText(tk.preview()) }

        // Crédito fiscal sin RNC: no se registra la venta.
        val before = repo.db.saleQueries.selectRecent(100).executeAsList().size
        assertFailsWith<IllegalStateException> { repo.recordSale(listOf(CartLine.of(cable)), null, 0, PaymentMethods.CASH, 100000, null, FiscalChoice(31)) }
        assertEquals(before, repo.db.saleQueries.selectRecent(100).executeAsList().size)
        // Con RNC: E31.
        val s2 = repo.recordSale(listOf(CartLine.of(cable)), null, 0, PaymentMethods.CARD, 0, null, FiscalChoice(31, "101-01063-2", "FERRETERÍA LA ESQUINA SRL"))
        val d2 = repo.ecfForSale(s2).single()
        assertEquals("E310000000001", d2.encf)
        assertEquals("101010632", d2.buyerRnc)
        EcfTest.validate("ecf31.xsd", d2.xml)

        // Anular: nota de crédito E34 que hace referencia a la factura.
        repo.voidSale(s1, "Devolución")
        assertEquals(SaleStatus.VOID, repo.saleById(s1)!!.status)
        val nc = repo.ecfForSale(s1).first { it.type == 34L }
        assertEquals("E340000000001", nc.encf)
        assertEquals(d1.encf, nc.refEncf)
        EcfTest.validate("ecf34.xsd", nc.xml)

        // Taller: el abono y la entrega son facturas separadas, sin líneas negativas.
        val rid = repo.saveRepair(RepairDraft(clientName = "Ana", brand = "Samsung", problem = "Pantalla", estimate = 300000, deposit = 100000))
        repo.setRepairStatus(rid, RepairStatus.READY)
        val labor = CartLine(null, "Reparación Samsung", "REP-0001", 1.0, 300000, 0, true, null, false)
        val delivery = repo.deliverRepair(rid, listOf(labor), true, PaymentMethods.CASH, 200000)
        val items = repo.saleItems(delivery)
        assertTrue(items.all { it.price >= 0 })
        assertEquals(200000, repo.saleById(delivery)!!.total)
        val dd = repo.ecfForSale(delivery).single()
        EcfTest.validate("ecf32.xsd", dd.xml)
        val depositSale = repo.salesByClient(repo.repairById(rid)!!.clientId!!).first { it.id != delivery }
        assertEquals(100000, repo.ecfForSale(depositSale.id).single().total)

        // Envío a la DGII (simulada): resumen para consumo y e-CF completo para el resto.
        val fake = FakeDgii()
        repo.fiscal.http = fake
        repo.fiscal.sendPending()
        repo.fiscal.sendPending()
        val docs = repo.db.fiscalQueries.recentDocs(50).executeAsList()
        assertTrue(docs.all { it.status == EcfStatus.ACCEPTED }, docs.joinToString { "${it.encf}=${it.status}:${it.message}" })
        assertTrue(fake.received.contains("101010632E320000000001.xml"))
        assertTrue(fake.received.contains("101010632E310000000001.xml"))

        // No se puede restaurar un respaldo con facturación electrónica.
        assertFailsWith<IllegalStateException> { g.importBackup(ByteArray(0).inputStream()) }
        repo.close()
    }

    @Test
    fun cajaSinConexionFirmaSola(): Unit = runBlocking {
        val main = AppGraph(platform("fiscal-main"))
        SampleData.load(main.repo)
        main.setupFiscal(shareCert = true)
        main.becomeServer()
        val caja = AppGraph(platform("fiscal-caja"))
        try {
        caja.connectTo("127.0.0.1", 8740, main.server!!.pairCode.value.code)
        withTimeout(20_000) { while (caja.repo.fiscalRanges.value.size < 3 || !caja.repo.settings.value.fiscalEnabled) delay(100) }

        // La caja recibe el certificado por la red cifrada y pide su bloque de números.
        caja.client!!.fetchFiscalCertificate()
        assertEquals("MARIA PEREZ", caja.repo.fiscal.certificate.value!!.holder)
        caja.repo.reserveFiscalBlock(32)
        caja.repo.fiscal.ensureOfflineNumbers()
        withTimeout(20_000) { while (caja.repo.fiscal.freeNumbers(com.vitrinapos.fiscal.EcfType.CONSUMO) < 20) delay(100) }

        val cable = main.repo.db.productQueries.selectActive().executeAsList().first { it.code == "CBL-001" }
        // En línea: firma el principal.
        val online = caja.repo.recordSale(listOf(CartLine.of(cable)), null, 0, PaymentMethods.CASH, 100000, null)
        assertEquals(main.repo.deviceId, main.repo.ecfForSale(online).single().deviceId)

        // Sin conexión: la caja firma con su bloque y entrega la factura al momento.
        main.server!!.stop()
        val offline = caja.repo.recordSale(listOf(CartLine.of(cable)), null, 0, PaymentMethods.CASH, 100000, null)
        assertTrue(offline < 0)
        val local = caja.repo.ecfForSale(offline).single()
        val block = caja.repo.fiscalBlocks.value.first { it.deviceId == caja.repo.deviceId && it.type == 32L }
        assertTrue(local.encf.takeLast(10).toLong() in block.fromNo..block.toNo, local.encf)
        assertTrue(XmlSigner.verify(local.xml))

        // Vuelve la red: el principal guarda ese mismo e-CF y lo envía.
        main.becomeServer()
        withTimeout(30_000) { while (caja.repo.db.syncQueries.outboxCount().executeAsOne() > 0L) delay(100) }
        val atMain = main.repo.db.fiscalQueries.docByEncf(local.encf).executeAsOne()
        assertEquals(local.xml, atMain.xml)
        assertTrue(atMain.summaryXml.isNotBlank())
        withTimeout(20_000) { while (caja.repo.db.fiscalQueries.docByEncf(local.encf).executeAsList().singleOrNull()?.id?.let { it > 0 } != true) delay(100) }

        } finally {
            caja.becomeSolo(); main.becomeSolo()
            caja.repo.close(); main.repo.close()
        }
    }
}
