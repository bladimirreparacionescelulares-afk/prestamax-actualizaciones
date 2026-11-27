package com.vitrinapos

import com.vitrinapos.data.AppGraph
import com.vitrinapos.data.BusinessType
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.ProductDraft
import com.vitrinapos.data.PurchaseLine
import com.vitrinapos.data.ReturnLine
import com.vitrinapos.data.SampleData
import com.vitrinapos.platform.DesktopPlatform
import com.vitrinapos.ui.components.matches
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Versión 1.10: tipo de negocio, actividad, unir productos, topes de caja, devoluciones y mermas. */
class ControlTest {
    private fun graph(name: String): AppGraph {
        System.setProperty("vitrina.dataDir", Files.createTempDirectory("vitrina-$name").toString())
        return AppGraph(DesktopPlatform())
    }

    private fun failMessage(block: suspend () -> Unit): String = runBlocking { runCatching { block() }.exceptionOrNull()?.message ?: "" }

    @Test
    fun tipoDeNegocioYModulos() {
        val colmado = BusinessType.COLMADO.apply(com.vitrinapos.data.AppSettings())
        assertFalse(colmado.modTaller); assertFalse(colmado.modImei); assertEquals("COLMADO", colmado.businessType)
        val phones = BusinessType.PHONES.apply(com.vitrinapos.data.AppSettings())
        assertTrue(phones.modTaller && phones.modImei && phones.modVariants)
        // Los módulos viajan en la configuración compartida.
        val back = com.vitrinapos.data.AppSettings.fromMap(colmado.toMap())
        assertFalse(back.modTaller)
        // Instalaciones anteriores (sin esos ajustes) quedan con todo encendido.
        assertTrue(com.vitrinapos.data.AppSettings.fromMap(emptyMap()).modTaller)
    }

    @Test
    fun actividadYUnirProductos(): Unit = runBlocking {
        val g = graph("actividad")
        val repo = g.repo
        SampleData.load(repo)
        repo.openCashSession(100000)
        val a = repo.saveProduct(ProductDraft(null, "SIM Card de Claro (Báez)", "", null, null, 5500, 10000, 29.0, 0.0, "und", true, true, null, ""))
        val b = repo.saveProduct(ProductDraft(null, "SIM Card de Claro (Cedecom)", "", null, null, 6000, 10000, 21.0, 0.0, "und", true, true, null, ""))
        val pa = repo.productById(a)!!
        val pb = repo.productById(b)!!
        // Cambio de precio: queda anotado con el valor anterior.
        repo.saveProduct(ProductDraft(a, pa.name, pa.code, null, null, pa.cost, 12000, 0.0, 0.0, "und", true, true, null, ""))
        val sale = repo.recordSale(listOf(CartLine.of(repo.productById(b)!!, 1.0)), null, 0, PaymentMethods.CASH, 10000, null)
        repo.voidSale(sale, "Error al cobrar")
        val log = repo.activity(0, Long.MAX_VALUE)
        assertTrue(log.any { it.action == "Cambió el precio" && it.detail.contains("100.00") && it.detail.contains("120.00") }, log.joinToString { it.action })
        assertTrue(log.any { it.action.startsWith("Anuló la venta") && it.detail.contains("Error al cobrar") })
        assertTrue(log.any { it.action == "Abrió la caja" })
        // Una operación rechazada no deja rastro.
        assertTrue(failMessage { repo.adjustStock(a, -1.0, "x") }.contains("negativo"))
        assertTrue(repo.activity(0, Long.MAX_VALUE).none { it.action == "Ajustó existencias" })

        // Unir: existencias sumadas, costo promedio, historial y código del otro.
        repo.mergeProducts(a, listOf(b), "SIM Card Claro")
        val merged = repo.productById(a)!!
        assertEquals("SIM Card Claro", merged.name)
        assertEquals(29.0 + 21.0, merged.stock)   // la venta anulada devolvió su unidad
        assertEquals(((29 * 5500 + 21 * 6000) / 50.0).toLong(), merged.cost)
        assertEquals(0L, repo.productById(b)!!.active)
        // Se lee de la base (la lista en memoria se actualiza un instante después).
        assertEquals(merged.id, repo.productForCode(pb.code, repo.db.productQueries.selectActive().executeAsList())?.id)
        assertEquals(a, repo.saleItems(sale).single().productId)
        assertTrue(repo.activity(0, Long.MAX_VALUE).any { it.action == "Unió productos" })
        // El código del producto unido no se puede volver a usar en otro.
        assertTrue(failMessage { repo.saveProduct(ProductDraft(null, "Otro", pb.code, null, null, 0, 100, 0.0, 0.0, "und", true, true, null, "")) }.contains("ya lo tiene"))
        // Búsqueda sin acentos y con varias palabras.
        assertTrue(merged.matches("sim claro"))
        assertTrue(repo.products.value.first { it.code == "AUD-210" }.matches("audifonos pro"))
        g.repo.close()
    }

    @Test
    fun topesDeCajaDevolucionYMerma(): Unit = runBlocking {
        val g = graph("topes")
        val repo = g.repo
        SampleData.load(repo)
        repo.saveSettings(repo.settings.value.copy(cashDiffLimit = 10000, approvalAmount = 50000, returnMaxDays = 30))
        repo.openCashSession(100000)
        val cable = repo.products.value.first { it.code == "CBL-001" }
        val sale = repo.recordSale(listOf(CartLine.of(cable, 2.0)), null, 0, PaymentMethods.CASH, 100000, null)
        // Devolución vieja: hace falta un administrador.
        repo.driver.execute(null, "UPDATE sale SET createdAt = ? WHERE id = ?", 2) { bindLong(0, System.currentTimeMillis() - 40L * 86_400_000); bindLong(1, sale) }
        val item = repo.saleItems(sale).single()
        assertTrue(failMessage { repo.returnSale(sale, listOf(ReturnLine(item.id, 1.0)), "Defecto", PaymentMethods.CARD) }.contains("administrador"))
        repo.returnSale(sale, listOf(ReturnLine(item.id, 1.0)), "Defecto", PaymentMethods.CARD, authorizedBy = "Ana")
        assertTrue(repo.activity(0, Long.MAX_VALUE).any { it.action.startsWith("Devolución") && it.detail.contains("autorizó Ana") })
        // Merma grande: hace falta un administrador.
        val phone = repo.saveProduct(ProductDraft(null, "Equipo caro", "", null, null, 900000, 1200000, 3.0, 0.0, "und", true, true, null, ""))
        assertTrue(failMessage { repo.stockLoss(phone, 1.0, "Dañado", "") }.contains("administrador"))
        repo.stockLoss(phone, 1.0, "Dañado", "", authorizedBy = "Ana")
        // Cierre con diferencia grande: pide administrador y motivo.
        val s = repo.db.cashSessionQueries.selectOpen(repo.deviceId).executeAsOne()
        assertTrue(failMessage { repo.closeCashSession(s, 0, "") }.contains("administrador"))
        assertTrue(failMessage { repo.closeCashSession(s, 0, "", authorizedBy = "Ana") }.contains("por qué"))
        val expected = repo.cashSummary(s).expected
        repo.closeCashSession(s, expected - 5000, "") // faltan 50: dentro del tope
        assertNotNull(repo.db.cashSessionQueries.selectById(s.id).executeAsOne().closedAt)
        g.repo.close()
    }

    @Test
    fun compraConProductoUnido(): Unit = runBlocking {
        val g = graph("compraunida")
        val repo = g.repo
        val a = repo.saveProduct(ProductDraft(null, "Mica 13", "111", null, null, 1000, 25000, 5.0, 0.0, "und", true, true, null, ""))
        val b = repo.saveProduct(ProductDraft(null, "Mica 13 (otro)", "222", null, null, 1200, 25000, 5.0, 0.0, "und", true, true, null, ""))
        repo.savePurchase(null, "Báez", "", "", 0, listOf(PurchaseLine(b, 2.0, 1100)), 0, "Contado", "")
        repo.mergeProducts(a, listOf(b), "")
        val (_, moves) = repo.kardex(a, 0, Long.MAX_VALUE)
        var bal = 0.0
        moves.forEach { m -> bal += m.qty; assertEquals(bal, m.balance, 1e-9) }
        assertEquals(12.0, repo.productById(a)!!.stock)
        g.repo.close()
    }
}
