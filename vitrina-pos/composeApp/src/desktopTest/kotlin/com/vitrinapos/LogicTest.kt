package com.vitrinapos

import com.vitrinapos.data.AppGraph
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.LineInput
import com.vitrinapos.data.Money
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.QuoteStatus
import com.vitrinapos.data.SaleStatus
import com.vitrinapos.data.SampleData
import com.vitrinapos.data.Totals
import com.vitrinapos.pdf.QuotePdf
import com.vitrinapos.platform.DesktopPlatform
import com.vitrinapos.print.Ticket
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogicTest {
    @Test
    fun money() {
        assertEquals("$1,234.50", Money.format(123450))
        assertEquals("-$0.05", Money.format(-5))
        assertEquals("RD$1,000,000.00", Money.format(100000000, "RD$"))
        assertEquals(123450L, Money.parse("1,234.50"))
        assertEquals(123450L, Money.parse("1234,50"))
        assertEquals(123400000L, Money.parse("1,234,000"))
        assertEquals(150L, Money.parse("1.5"))
        assertEquals(null, Money.parse("abc"))
        assertEquals("1250.5".let { Money.parse(it) }, 125050L)
        assertEquals("1200", Money.toInput(120000))
    }

    @Test
    fun totalsIncludedTax() {
        // 1,180.00 con ITBIS 18 % incluido -> impuesto 180.00
        val t = Totals.compute(listOf(LineInput(1.0, 118000, true)), 0, 18.0, true)
        assertEquals(118000, t.total)
        assertEquals(18000, t.tax)
        // Con descuento de 118.00 el impuesto baja proporcionalmente
        val d = Totals.compute(listOf(LineInput(1.0, 118000, true)), 11800, 18.0, true)
        assertEquals(106200, d.total)
        assertEquals(16200, d.tax)
    }

    @Test
    fun totalsAddedTax() {
        val t = Totals.compute(listOf(LineInput(2.0, 50000, true), LineInput(1.0, 10000, false)), 0, 18.0, false)
        assertEquals(110000, t.gross)
        assertEquals(18000, t.tax)
        assertEquals(128000, t.total)
    }

    @Test
    fun ticketFitsPaper() {
        val s = com.vitrinapos.data.AppSettings(paperWidthMm = 58)
        val t = Ticket.test(s)
        t.preview().lines().forEach { assertTrue(it.length <= 32, "Línea muy larga en 58 mm: '$it'") }
        val bytes = t.escPos(ascii = false, openDrawer = false)
        assertTrue(bytes.size > 50)
        assertEquals("Cafe nino", Ticket.stripAccents("Café niño"))
    }

    @Test
    fun salesStockBackupAndPdf(): Unit = runBlocking {
        val dir = Files.createTempDirectory("vitrina-test").toFile()
        System.setProperty("vitrina.dataDir", dir.path)
        val graph = AppGraph(DesktopPlatform())
        val repo = graph.repo
        SampleData.load(repo)
        val products = repo.db.productQueries.selectActive().executeAsList()
        assertEquals(11, products.size)
        val charger = products.first { it.code == "CRG-110" }
        val cable = products.first { it.code == "CBL-001" }

        // Venta: 2 cargadores + 1 cable, en efectivo
        val lines = listOf(CartLine.of(charger, 2.0), CartLine.of(cable, 1.0))
        val saleId = repo.recordSale(lines, null, 0, PaymentMethods.CASH, 200000, null)
        val sale = repo.saleById(saleId)!!
        assertEquals(150000, sale.total)
        assertEquals(50000, sale.changeDue)
        assertEquals(1, sale.number)
        assertEquals(charger.stock - 2, repo.productById(charger.id)!!.stock)

        // Ticket de 80 mm de la venta
        val ticket = Ticket.forSale(repo.settings.value, sale, repo.saleItems(saleId))
        ticket.preview().lines().forEach { assertTrue(it.length <= 48, "Línea muy larga en 80 mm: '$it'") }
        println(ticket.preview())

        // Anular devuelve el stock
        repo.voidSale(saleId)
        assertEquals(SaleStatus.VOID, repo.saleById(saleId)!!.status)
        assertEquals(charger.stock, repo.productById(charger.id)!!.stock)

        // Cotización + PDF A4
        val quoteId = repo.saveQuote(null, null, "Taller Centro", lines, 0, 15, "", QuoteStatus.DRAFT)
        val q = repo.quoteById(quoteId)!!
        assertEquals("COT-0001", QuotePdf.quoteCode(q.number))
        val pdf = File(dir, "cot.pdf")
        val qItems = repo.quoteItems(quoteId)
        graph.platform.writePdf(pdf) { c -> QuotePdf.draw(c, repo.settings.value, q, qItems, null, null, { null }) }
        assertTrue(pdf.length() > 1000)

        // Respaldo y restauración
        val backup = ByteArrayOutputStream().also { graph.exportBackup(it) }.toByteArray()
        repo.saveClient(null, "Cliente nuevo", "", "", "", "", "")
        assertEquals(4, graph.repo.db.clientQueries.selectAll().executeAsList().size)
        graph.importBackup(ByteArrayInputStream(backup))
        assertEquals(3, graph.repo.db.clientQueries.selectAll().executeAsList().size)
        assertEquals(11, graph.repo.db.productQueries.selectActive().executeAsList().size)
        graph.repo.close()
        dir.deleteRecursively()
    }
}

class TicketTest {
    @Test
    fun bigTotalFallsBackWhenTooLong() {
        val t = Ticket(32)
        t.pair("TOTAL", "$123,456.00", Ticket.Style.Big)
        assertEquals(Ticket.Style.Bold, t.lines.last().style)
        assertTrue(t.lines.last().text.startsWith("TOTAL"))
        t.pair("TOTAL", "$950.00", Ticket.Style.Big)
        assertEquals(Ticket.Style.Big, t.lines.last().style)
    }
}

class PermissionsTest {
    @Test
    fun rolesYPermisosPorUsuario() {
        val perms = com.vitrinapos.data.Permissions
        // El administrador siempre tiene todo, aunque tenga algo guardado.
        assertEquals(com.vitrinapos.data.Permission.entries.toSet(), perms.effective(com.vitrinapos.data.Roles.ADMIN, "SELL"))
        // El vendedor trae lo suyo y no anula, ni configura, ni ve reportes.
        val seller = perms.effective(com.vitrinapos.data.Roles.SELLER, "")
        assertTrue(com.vitrinapos.data.Permission.SELL in seller && com.vitrinapos.data.Permission.CASH in seller)
        assertTrue(com.vitrinapos.data.Permission.VOID_SALES !in seller && com.vitrinapos.data.Permission.SETTINGS !in seller && com.vitrinapos.data.Permission.REPORTS !in seller && com.vitrinapos.data.Permission.WORKSHOP !in seller)
        // El técnico además trabaja el taller.
        assertTrue(com.vitrinapos.data.Permission.WORKSHOP in perms.effective(com.vitrinapos.data.Roles.TECH, ""))
        // Personalizado: solo lo que el administrador encendió; "NONE" = nada.
        assertEquals(setOf(com.vitrinapos.data.Permission.WORKSHOP, com.vitrinapos.data.Permission.REPORTS), perms.effective(com.vitrinapos.data.Roles.SELLER, perms.encode(setOf(com.vitrinapos.data.Permission.REPORTS, com.vitrinapos.data.Permission.WORKSHOP))))
        assertTrue(perms.effective(com.vitrinapos.data.Roles.SELLER, "NONE").isEmpty())
        // Cada pantalla pide su permiso.
        assertEquals(listOf(com.vitrinapos.data.Permission.SELL), requiredPermission(Screen.Pos))
        assertTrue(requiredPermission(Screen.Home).isEmpty())
        assertTrue(com.vitrinapos.data.Permission.SELL in requiredPermission(Screen.SaleDetail(1)))
    }
}
