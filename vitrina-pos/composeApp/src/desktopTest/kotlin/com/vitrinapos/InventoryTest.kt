package com.vitrinapos

import com.vitrinapos.data.AppGraph
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.CountLine
import com.vitrinapos.data.MoveType
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.ProductDraft
import com.vitrinapos.data.PurchaseLine
import com.vitrinapos.data.ReturnLine
import com.vitrinapos.data.SampleData
import com.vitrinapos.data.UnitStatus
import com.vitrinapos.data.ncfProblem
import com.vitrinapos.platform.DesktopPlatform
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inventario 1.9: compras con NCF y costo promedio, IMEI, devoluciones, conteo, mermas, kardex y variantes. */
class InventoryTest {
    private fun graph(name: String): AppGraph {
        System.setProperty("vitrina.dataDir", Files.createTempDirectory("vitrina-$name").toString())
        return AppGraph(DesktopPlatform())
    }

    private fun failMessage(block: suspend () -> Unit): String = runBlocking { runCatching { block() }.exceptionOrNull()?.message ?: "" }

    private fun AppGraph.product(code: String) = repo.db.productQueries.selectActive().executeAsList().first { it.code == code }
    private fun AppGraph.productById(id: Long) = repo.db.productQueries.selectById(id).executeAsOne()

    @Test
    fun comprasConNcfYCostoPromedio(): Unit = runBlocking {
        val g = graph("compras")
        val repo = g.repo
        SampleData.load(repo)
        val p = g.product("CBL-001")
        val stock = p.stock
        val cost = p.cost
        val id = repo.savePurchase(null, "Báez", "101-01063-2", "B0100000123", com.vitrinapos.data.Time.now(), listOf(PurchaseLine(p.id, 10.0, cost + 10000)), 0, "Contado", "")
        val after = g.productById(p.id)
        assertEquals(stock + 10, after.stock)
        assertEquals(((stock * cost + 10 * (cost + 10000)) / (stock + 10)).roundToLong(), after.cost)
        // El kardex guarda el costo de la compra y el saldo.
        val move = repo.movesByProduct(p.id).first()
        assertEquals(MoveType.PURCHASE, move.type)
        assertEquals(cost + 10000, move.unitCost)
        assertEquals(stock + 10, move.balance)
        // El mismo NCF del mismo proveedor no se registra dos veces; un NCF mal escrito tampoco.
        assertTrue(failMessage { repo.savePurchase(null, "Báez", "101010632", "b0100000123", 0, listOf(PurchaseLine(p.id, 1.0, cost)), 0, "Contado", "") }.contains("Ya registraste"))
        assertTrue(failMessage { repo.savePurchase(null, "Báez", "101010632", "B01123", 0, listOf(PurchaseLine(p.id, 1.0, cost)), 0, "Contado", "") }.contains("NCF"))
        assertNull(ncfProblem("E310000000001"))
        assertNotNull(ncfProblem("X0100000001"))
        // Anular la compra saca la mercancía y devuelve el costo.
        assertTrue(failMessage { repo.voidPurchase(id, "") }.contains("motivo"))
        repo.voidPurchase(id, "Factura equivocada")
        val back = g.productById(p.id)
        assertEquals(stock, back.stock)
        assertTrue(kotlin.math.abs(back.cost - cost) <= 1, "costo ${back.cost} vs $cost")
        // Ya anulada, el NCF se puede volver a registrar.
        repo.savePurchase(null, "Báez", "101010632", "B0100000123", 0, listOf(PurchaseLine(p.id, 1.0, cost)), 0, "Contado", "")
        g.repo.close()
    }

    @Test
    fun imeiCompraVentaYDevolucion(): Unit = runBlocking {
        val g = graph("imei")
        val repo = g.repo
        SampleData.load(repo)
        repo.openCashSession(100000)
        val phoneId = repo.saveProduct(ProductDraft(null, "iPhone 13", "", null, null, 0, 3_500_000, 0.0, 0.0, "und", true, true, null, "", serialized = true))
        // Sin IMEI no entra.
        assertTrue(failMessage { repo.savePurchase(null, "Cedecom", "", "", 0, listOf(PurchaseLine(phoneId, 2.0, 2_800_000)), 0, "Contado", "") }.contains("IMEI"))
        repo.savePurchase(null, "Cedecom", "", "", 0, listOf(PurchaseLine(phoneId, 2.0, 2_800_000, listOf("356000000000011", "356000000000029"))), 0, "Contado", "")
        assertEquals(2.0, g.productById(phoneId).stock)
        assertTrue(failMessage {
            repo.savePurchase(null, "Cedecom", "", "", 0, listOf(PurchaseLine(phoneId, 1.0, 2_800_000, listOf("356000000000011"))), 0, "Contado", "")
        }.contains("ya está en existencia"))
        // El ajuste manual no aplica a productos con IMEI.
        assertTrue(failMessage { repo.adjustStock(phoneId, 5.0, "conteo") }.contains("IMEI"))
        val phone = g.productById(phoneId)
        // Vender sin elegir IMEI no se puede; con un IMEI que no existe tampoco.
        assertTrue(failMessage { repo.recordSale(listOf(CartLine.of(phone)), null, 0, PaymentMethods.CASH, 5_000_000, null) }.contains("IMEI"))
        assertTrue(failMessage { repo.recordSale(listOf(CartLine.of(phone, 1.0, listOf("999"))), null, 0, PaymentMethods.CASH, 5_000_000, null) }.contains("no está disponible"))
        val sale = repo.recordSale(listOf(CartLine.of(phone, 1.0, listOf("356000000000011"))), null, 0, PaymentMethods.CASH, 5_000_000, null)
        val unit = repo.searchUnits("0000011").first()
        assertEquals(UnitStatus.SOLD, unit.status)
        assertEquals(sale, unit.saleId)
        assertEquals("356000000000011", repo.saleItems(sale).first().serials)
        // Vendido no se puede vender otra vez.
        assertTrue(failMessage { repo.recordSale(listOf(CartLine.of(phone, 1.0, listOf("356000000000011"))), null, 0, PaymentMethods.CASH, 5_000_000, null) }.contains("no está disponible"))
        // Devolución: el equipo vuelve a la existencia y el dinero sale de la caja.
        val item = repo.saleItems(sale).first()
        repo.returnSale(sale, listOf(ReturnLine(item.id, 1.0, true, listOf("356000000000011"))), "No le gustó", PaymentMethods.CASH)
        assertEquals(UnitStatus.AVAILABLE, repo.searchUnits("0000011").first().status)
        assertEquals(2.0, g.productById(phoneId).stock)
        val session = repo.db.cashSessionQueries.selectOpen(repo.deviceId).executeAsOne()
        assertEquals(100000L, repo.cashSummary(session).expected)
        // Con devoluciones ya no se anula: se devuelve lo que falta.
        assertTrue(failMessage { repo.voidSale(sale, "x") }.contains("devoluciones"))
        // Registrar IMEI de mercancía que ya estaba (sin compra).
        repo.addUnits(phoneId, listOf("356000000000037"))
        assertEquals(3.0, g.productById(phoneId).stock)
        // Merma de una unidad por su IMEI.
        repo.stockLoss(phoneId, 1.0, "Dañado", "Pantalla rota", listOf("356000000000037"))
        assertEquals(UnitStatus.LOST, repo.searchUnits("0000037").first().status)
        assertEquals(2.0, g.productById(phoneId).stock)
        g.repo.close()
    }

    @Test
    fun devolucionParcialConteoYMermas(): Unit = runBlocking {
        val g = graph("conteo")
        val repo = g.repo
        SampleData.load(repo)
        repo.openCashSession(500000)
        val p = g.product("CBL-002")
        val stock0 = p.stock
        val sale = repo.recordSale(listOf(CartLine.of(p, 3.0)), null, 0, PaymentMethods.CASH, 5_000_000, null)
        val item = repo.saleItems(sale).first()
        repo.returnSale(sale, listOf(ReturnLine(item.id, 1.0)), "Defecto", PaymentMethods.CASH)
        assertEquals(stock0 - 2, g.productById(p.id).stock)
        assertTrue(failMessage { repo.returnSale(sale, listOf(ReturnLine(item.id, 3.0)), "Otra", PaymentMethods.CASH) }.contains("solo quedan 2"))
        // Devuelto dañado: no vuelve al inventario.
        repo.returnSale(sale, listOf(ReturnLine(item.id, 1.0, restock = false)), "Roto", PaymentMethods.CARD)
        assertEquals(stock0 - 2, g.productById(p.id).stock)
        val returned = repo.returnsBySale(sale).sumOf { it.total }
        assertEquals(2 * p.price, returned)

        // Conteo: se contó con stock S; mientras se contaba se vendieron 2.
        val before = g.productById(p.id).stock
        val counted = before - 1 // falta una unidad
        repo.recordSale(listOf(CartLine.of(p, 2.0)), null, 0, PaymentMethods.CASH, 5_000_000, null)
        val countId = repo.applyCount(listOf(CountLine(p.id, counted, before)), "Fin de mes")
        assertEquals(before - 1 - 2, g.productById(p.id).stock)
        val ci = repo.countItems(countId).single()
        assertEquals(counted, ci.counted)
        assertEquals(MoveType.COUNT, repo.movesByProduct(p.id).first().type)

        // Merma.
        assertTrue(failMessage { repo.stockLoss(p.id, 1.0, "", "") }.contains("motivo"))
        repo.stockLoss(p.id, 1.0, "Dañado", "Se mojó")
        val losses = repo.lossesBetween(0, Long.MAX_VALUE)
        assertEquals(p.cost, losses.sumOf { (-it.qty * it.unitCost).roundToLong() })

        // Kardex: el saldo de cada movimiento cuadra con la suma.
        val (opening, moves) = repo.kardex(p.id, 0, Long.MAX_VALUE)
        assertEquals(0.0, opening)
        var bal = 0.0
        moves.forEach { m -> bal += m.qty; assertEquals(bal, m.balance, 1e-9) }
        assertEquals(g.productById(p.id).stock, bal, 1e-9)
        g.repo.close()
    }

    @Test
    fun variantes(): Unit = runBlocking {
        val g = graph("variantes")
        val repo = g.repo
        val a = repo.saveProduct(ProductDraft(null, "Funda iPhone 13", "", null, null, 0, 50000, 5.0, 0.0, "und", true, true, null, "", variantName = "Negra"))
        val b = repo.saveProduct(ProductDraft(null, "Funda iPhone 13", "", null, null, 0, 55000, 3.0, 0.0, "und", true, true, null, "", variantGroup = "g$a", variantName = "Roja"))
        assertEquals("g$a", g.productById(a).variantGroup)
        assertEquals("g$a", g.productById(b).variantGroup)
        assertEquals("Funda iPhone 13 · Roja", CartLine.of(g.productById(b)).name)
        g.repo.close()
    }
}
