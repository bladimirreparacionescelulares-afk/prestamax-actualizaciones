package com.vitrinapos

import com.vitrinapos.data.AppGraph
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.CashMoveType
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.ProductDraft
import com.vitrinapos.data.Repository
import com.vitrinapos.data.Roles
import com.vitrinapos.data.SampleData
import com.vitrinapos.net.Crypto
import com.vitrinapos.platform.DesktopPlatform
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reglas del negocio que el equipo principal hace cumplir (versión 1.8). */
class RulesTest {
    private fun graph(name: String): AppGraph {
        System.setProperty("vitrina.dataDir", Files.createTempDirectory("vitrina-$name").toString())
        return AppGraph(DesktopPlatform())
    }

    private fun failMessage(block: suspend () -> Unit): String = runBlocking { runCatching { block() }.exceptionOrNull()?.message ?: "" }

    @Test
    fun cajaStockYCantidades(): Unit = runBlocking {
        val g = graph("reglas")
        val repo = g.repo
        SampleData.load(repo)
        val cable = repo.db.productQueries.selectActive().executeAsList().first { it.code == "CBL-001" }

        // Caja cerrada: no se cobra.
        assertTrue(failMessage { repo.recordSale(listOf(CartLine.of(cable)), null, 0, PaymentMethods.CASH, 100000, null) }.contains("caja"))
        // Con el interruptor apagado, sí.
        repo.saveSettings(repo.settings.value.copy(requireCash = false))
        repo.recordSale(listOf(CartLine.of(cable)), null, 0, PaymentMethods.CARD, 0, null)
        repo.saveSettings(repo.settings.value.copy(requireCash = true))
        repo.openCashSession(100000)
        repo.openCashSession(500000) // abrir dos veces no crea otra caja
        assertEquals(100000, repo.openCash.value?.openingAmount ?: repo.db.cashSessionQueries.selectOpen(repo.deviceId).executeAsOne().openingAmount)

        // No se vende más de lo que hay, salvo con permiso.
        val stock = repo.db.productQueries.selectById(cable.id).executeAsOne().stock
        val msg = failMessage { repo.recordSale(listOf(CartLine.of(cable, stock + 1)), null, 0, PaymentMethods.CASH, 99_999_999, null) }
        assertTrue(msg.contains("solo quedan") || msg.contains("no hay existencias"), msg)
        repo.recordSale(listOf(CartLine.of(cable, stock + 1)), null, 0, PaymentMethods.CASH, 99_999_999, null, allowNoStock = true)

        // Cantidades y precios válidos.
        assertTrue(failMessage { repo.recordSale(listOf(CartLine.of(cable, -1.0)), null, 0, PaymentMethods.CASH, 0, null) }.contains("cantidad"))
        assertTrue(failMessage { repo.adjustStock(cable.id, -3.0, "conteo") }.contains("negativo"))
        assertTrue(failMessage { repo.adjustStock(cable.id, 3.0, " ") }.contains("motivo"))
        assertTrue(failMessage { repo.registerEntry(listOf(com.vitrinapos.data.EntryLine(cable.id, -5.0, null)), null, "") }.contains("positivas"))
        assertTrue(failMessage {
            repo.saveProduct(ProductDraft(null, "Gratis", "", null, null, 0, 0, 0.0, 0.0, "und", true, true, null, ""))
        }.contains("precio"))
        assertTrue(failMessage {
            repo.saveProduct(ProductDraft(null, "Copia", cable.code, null, null, 0, 1000, 0.0, 0.0, "und", true, true, null, ""))
        }.contains("ya lo tiene"))
        g.repo.close()
    }

    @Test
    fun movimientosDeCajaYDevolucion(): Unit = runBlocking {
        val g = graph("caja")
        val repo = g.repo
        SampleData.load(repo)
        val cable = repo.db.productQueries.selectActive().executeAsList().first { it.code == "CBL-001" }
        repo.openCashSession(100000)
        val sale = repo.recordSale(listOf(CartLine.of(cable)), null, 0, PaymentMethods.CASH, 100000, null)
        val price = repo.saleById(sale)!!.total
        repo.cashMove(CashMoveType.IN, 20000, "Cambio")
        repo.cashMove(CashMoveType.EXPENSE, 5000, "Agua")
        assertTrue(failMessage { repo.cashMove(CashMoveType.OUT, 10_000_000, "Retiro") }.contains("efectivo"))
        assertTrue(failMessage { repo.cashMove(CashMoveType.OUT, 1000, "") }.contains("motivo"))
        val s1 = repo.db.cashSessionQueries.selectOpen(repo.deviceId).executeAsOne()
        assertEquals(100000 + price + 20000 - 5000, repo.cashSummary(s1).expected)
        // Se cierra la caja; al día siguiente se anula esa venta: el dinero sale de la caja nueva.
        repo.closeCashSession(s1, 100000 + price + 15000, "")
        repo.openCashSession(50000)
        val s2 = repo.db.cashSessionQueries.selectOpen(repo.deviceId).executeAsOne()
        // La venta se marca como de antes de abrir la caja nueva.
        repo.driver.execute(null, "UPDATE sale SET createdAt = ? WHERE id = ?", 2) { bindLong(0, s2.openedAt - 60_000); bindLong(1, sale) }
        repo.voidSale(sale, "Devolución")
        val sum = repo.cashSummary(s2)
        assertEquals(price, sum.refunds)
        assertEquals(50000 - price, sum.expected)
        g.repo.close()
    }

    @Test
    fun pinConSalYBloqueo(): Unit = runBlocking {
        val g = graph("pin")
        val repo = g.repo
        repo.saveUser(null, "Ana", "4321", Roles.ADMIN, true)
        repo.saveUser(null, "Luis", "1111", Roles.SELLER, true)
        val ana = repo.db.appUserQueries.selectAll().executeAsList().first { it.name == "Ana" }
        assertTrue(ana.pinHash.startsWith("p2$"), "huella con sal")
        assertFalse(ana.pinHash.contains("4321"))
        // Dos usuarios con el mismo PIN tienen huellas distintas (sal).
        assertTrue(Crypto.pinHash("4321") != Crypto.pinHash("4321"))
        assertTrue(repo.pinAttempt(ana, "4321", true) is Repository.PinResult.Ok)
        // Probar el 4.º, 5.º número… no cuenta; cada intento terminado sí.
        repeat(10) { assertTrue(repo.pinAttempt(ana, "0000", false) is Repository.PinResult.Wrong) }
        repeat(4) { assertTrue(repo.pinAttempt(ana, "0000", true) is Repository.PinResult.Wrong) }
        assertTrue(repo.pinAttempt(ana, "0000", true) is Repository.PinResult.Locked)
        // Bloqueado: ni el PIN correcto entra.
        assertTrue(repo.pinAttempt(ana, "4321", true) is Repository.PinResult.Locked)
        // La huella de las versiones anteriores todavía se reconoce.
        assertTrue(Crypto.verifyPin(Crypto.legacyPinHash("2468"), "2468"))
        // Autorización de administrador.
        assertEquals("Ana", repo.authorizeAdmin("4321").second)
        assertEquals(null, repo.authorizeAdmin("1111").second)
        g.repo.close()
    }
}
