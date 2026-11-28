package com.vitrinapos

import com.vitrinapos.data.CartLine
import com.vitrinapos.data.EntryLine
import com.vitrinapos.data.MAIN_BRANCH
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.ProductDraft
import com.vitrinapos.data.Repository
import com.vitrinapos.data.Roles
import com.vitrinapos.data.RuleException
import com.vitrinapos.data.TransferLine
import com.vitrinapos.data.TransferStatus
import com.vitrinapos.data.UnitStatus
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Sucursales (2.2a): cada una con su existencia; el total del producto es la suma de todas. */
class BranchesTest {
    private fun repo() = Repository(ServerLikePlatform(Files.createTempDirectory("sucursales").toFile()))
    private fun draft(name: String, code: String, stock: Double) = ProductDraft(null, name, code, null, null, 30000, 85000, stock, 1.0, "und", true, true, null, "")

    @Test
    fun cadaSucursalTieneSuExistencia(): Unit = runBlocking {
        val r = repo()
        assertEquals(listOf("Principal"), r.branches.value.map { it.name }, "toda base nueva tiene la Principal")
        val pid = r.saveProduct(draft("Cargador USB-C", "CRG-1", 10.0))
        r.openCashSession(100000)
        val p = r.productById(pid)!!
        val s1 = r.recordSale(listOf(CartLine.of(p, 2.0)), null, 0, PaymentMethods.CASH, 200000, null)
        assertEquals(8.0, r.productById(pid)!!.stock)

        // Sucursal nueva: empieza en cero.
        val norte = r.saveBranch(null, "Sucursal Norte", "Santiago", "809-555-0199")
        assertFailsWith<RuleException> { r.saveBranch(null, "sucursal norte", "", "") }
        r.setThisDeviceBranch(norte)
        assertEquals(0.0, r.productById(pid)!!.stock)
        val err = assertFailsWith<RuleException> { r.recordSale(listOf(CartLine.of(p, 1.0)), null, 0, PaymentMethods.CASH, 100000, null) }
        assertTrue(err.message!!.contains("esta sucursal"), err.message)

        // Entra mercancía a Norte y se vende allá.
        r.registerEntry(listOf(EntryLine(pid, 5.0, null)), null, "Pedido")
        val s2 = r.recordSale(listOf(CartLine.of(r.productById(pid)!!, 1.0)), null, 0, PaymentMethods.CASH, 100000, null)
        assertEquals(4.0, r.productById(pid)!!.stock)
        assertEquals(mapOf(MAIN_BRANCH to 8.0, norte to 4.0), r.stockByBranch(pid))
        assertEquals(12.0, r.db.productQueries.selectById(pid).executeAsOne().stock, "el total es la suma de las sucursales")
        assertEquals(norte, r.saleById(s2)!!.branchId)
        assertEquals(MAIN_BRANCH, r.saleById(s1)!!.branchId)

        // Ajuste en Norte: solo cambia Norte.
        r.adjustStock(pid, 3.0, "Conteo rápido")
        assertEquals(mapOf(MAIN_BRANCH to 8.0, norte to 3.0), r.stockByBranch(pid))

        // Anular una venta de la Principal desde un equipo de Norte: vuelve a la Principal.
        r.voidSale(s1, "Prueba")
        assertEquals(mapOf(MAIN_BRANCH to 10.0, norte to 3.0), r.stockByBranch(pid))

        // El kardex es de la sucursal: saldo de Norte.
        val (_, moves) = r.kardex(pid, 0, Long.MAX_VALUE)
        assertTrue(moves.all { it.branchId == norte } && moves.isNotEmpty())
        assertEquals(3.0, moves.last().balance)
        val (_, mainMoves) = r.kardex(pid, 0, Long.MAX_VALUE, MAIN_BRANCH)
        assertEquals(10.0, mainMoves.last().balance)

        // Ventas por sucursal (para los reportes) y existencia por sucursal.
        val sales = r.salesByBranch(0, Long.MAX_VALUE)
        assertEquals(1L, sales[norte]?.first)
        assertTrue(MAIN_BRANCH !in sales, "la venta de la Principal se anuló")

        // Desactivar una sucursal con mercancía no se permite; la Principal nunca.
        assertFailsWith<RuleException> { r.saveBranch(norte, "Sucursal Norte", "", "", active = false) }
        assertFailsWith<RuleException> { r.saveBranch(MAIN_BRANCH, "Principal", "", "", active = false) }
        assertTrue(r.activity(0, Long.MAX_VALUE).any { it.action == "Creó una sucursal" && it.detail == "Sucursal Norte" })
        r.close()
    }

    @Test
    fun unirProductosSumaPorSucursalYUsuariosPorSucursal(): Unit = runBlocking {
        val r = repo()
        val a = r.saveProduct(draft("Mica 13", "MIC-A", 4.0))
        val b = r.saveProduct(draft("Mica iPhone 13", "MIC-B", 1.0))
        val norte = r.saveBranch(null, "Norte", "", "")
        r.setThisDeviceBranch(norte)
        r.registerEntry(listOf(EntryLine(b, 6.0, null)), null, "")
        r.mergeProducts(a, listOf(b), "Mica iPhone 13")
        assertEquals(mapOf(MAIN_BRANCH to 5.0, norte to 6.0), r.stockByBranch(a))
        assertEquals(11.0, r.db.productQueries.selectById(a).executeAsOne().stock)

        // Usuarios por sucursal: Ana solo en Norte; el administrador en todas.
        val admin = r.saveUser(null, "Bladimir", "1234", Roles.ADMIN, true)
        val ana = r.saveUser(null, "Ana", "5678", Roles.SELLER, true)
        r.setUserBranches(ana, listOf(norte))
        val users = r.db.appUserQueries.selectAll().executeAsList()
        val anaRow = users.first { it.id == ana }
        assertTrue(r.worksAt(anaRow, norte) && !r.worksAt(anaRow, MAIN_BRANCH))
        assertTrue(r.worksAt(users.first { it.id == admin }, MAIN_BRANCH))
        assertEquals(listOf("Ana", "Bladimir"), r.db.branchQueries.usersOfBranch(norte.toString()).executeAsList().map { it.name })
        assertEquals(listOf("Bladimir"), r.db.branchQueries.usersOfBranch(MAIN_BRANCH.toString()).executeAsList().filter { it.branches.isNotEmpty() || it.role == Roles.ADMIN }.map { it.name })
        r.close()
    }

    @Test
    fun transferenciasEntreSucursales(): Unit = runBlocking {
        val r = repo()
        val pid = r.saveProduct(draft("Cargador USB-C", "CRG-1", 20.0))
        val norte = r.saveBranch(null, "Norte", "", "")
        // Desde la Principal se envían 10 a Norte: salen ya de la Principal, Norte todavía no los tiene.
        assertFailsWith<RuleException> { r.sendTransfer(MAIN_BRANCH, listOf(TransferLine(pid, 1.0)), "") }
        assertFailsWith<RuleException> { r.sendTransfer(norte, listOf(TransferLine(pid, 25.0)), "") }
        val t1 = r.sendTransfer(norte, listOf(TransferLine(pid, 10.0)), "Pedido del lunes")
        assertEquals(10.0, r.stockByBranch(pid)[MAIN_BRANCH]); assertEquals(0.0, r.stockByBranch(pid)[norte] ?: 0.0)
        assertEquals(10.0, r.db.productQueries.selectById(pid).executeAsOne().stock, "en camino no cuenta en ninguna")
        // Se recibe en Norte, no en la Principal.
        assertFailsWith<RuleException> { r.receiveTransfer(t1) }
        r.setThisDeviceBranch(norte)
        r.receiveTransfer(t1)
        assertEquals(mapOf(MAIN_BRANCH to 10.0, norte to 10.0), r.stockByBranch(pid))
        assertEquals(TransferStatus.RECEIVED, r.db.transferQueries.selectById(t1).executeAsOne().status)
        assertFailsWith<RuleException> { r.receiveTransfer(t1) }

        // Llegó diferente: de 4 llegaron 3; hay que decir qué pasó.
        r.setThisDeviceBranch(MAIN_BRANCH)
        val t2 = r.sendTransfer(norte, listOf(TransferLine(pid, 4.0)), "")
        r.setThisDeviceBranch(norte)
        val item = r.transferItems(t2).single()
        assertFailsWith<RuleException> { r.receiveTransfer(t2, mapOf(item.id to 3.0), "") }
        r.receiveTransfer(t2, mapOf(item.id to 3.0), "Una caja vino rota")
        assertEquals(mapOf(MAIN_BRANCH to 6.0, norte to 13.0), r.stockByBranch(pid))
        assertEquals(TransferStatus.DIFFERENT, r.db.transferQueries.selectById(t2).executeAsOne().status)

        // Cancelar: solo la que envió y mientras esté en camino; todo vuelve.
        val t3 = r.sendTransfer(MAIN_BRANCH, listOf(TransferLine(pid, 2.0)), "")
        assertEquals(11.0, r.stockByBranch(pid)[norte])
        assertFailsWith<RuleException> { r.cancelTransfer(t3, "") }
        r.cancelTransfer(t3, "Me equivoqué")
        assertEquals(mapOf(MAIN_BRANCH to 6.0, norte to 13.0), r.stockByBranch(pid))

        // Kardex de Norte: entradas por transferencia y salida/regreso de la cancelada.
        val (_, moves) = r.kardex(pid, 0, Long.MAX_VALUE)
        assertTrue(moves.any { it.type == com.vitrinapos.data.MoveType.TRANSFER_IN && it.qty == 10.0 })
        assertEquals(13.0, moves.last().balance)
        assertTrue(r.activity(0, Long.MAX_VALUE).any { it.action == "Recibió una transferencia con diferencia" })
        r.close()
    }

    @Test
    fun transferenciaConImei(): Unit = runBlocking {
        val r = repo()
        val pid = r.saveProduct(draft("Samsung A15", "A15", 0.0).copy(serialized = true))
        r.addUnits(pid, listOf("IMEI-1", "IMEI-2", "IMEI-3"))
        val norte = r.saveBranch(null, "Norte", "", "")
        assertFailsWith<RuleException> { r.sendTransfer(norte, listOf(TransferLine(pid, 2.0, listOf("IMEI-1"))), "") }
        val t = r.sendTransfer(norte, listOf(TransferLine(pid, 2.0, listOf("IMEI-1", "IMEI-2"))), "")
        assertEquals(UnitStatus.IN_TRANSIT, r.db.inventoryQueries.unitBySerial("IMEI-1").executeAsList().single().status)
        r.setThisDeviceBranch(norte)
        r.receiveTransfer(t)
        val u = r.db.inventoryQueries.unitBySerial("IMEI-2").executeAsList().single()
        assertEquals(UnitStatus.AVAILABLE, u.status)
        assertEquals(norte, u.branchId)
        assertEquals(mapOf(MAIN_BRANCH to 1.0, norte to 2.0), r.stockByBranch(pid))
        r.close()
    }

    @Test
    fun datosAnterioresQuedanEnLaPrincipal(): Unit = runBlocking {
        val r = repo()
        val pid = r.saveProduct(draft("Funda A54", "FND-1", 7.0))
        // Como una base de antes de las sucursales (o un dato que mandó un equipo viejo): sin filas por sucursal.
        r.db.branchQueries.deleteOfProduct(pid)
        assertEquals(7.0, r.productById(pid)!!.stock, "sin filas, todo es de la Principal")
        val norte = r.saveBranch(null, "Norte", "", "")
        r.setThisDeviceBranch(norte)
        assertEquals(0.0, r.productById(pid)!!.stock)
        r.registerEntry(listOf(EntryLine(pid, 2.0, null)), null, "")
        assertEquals(mapOf(MAIN_BRANCH to 7.0, norte to 2.0), r.stockByBranch(pid), "lo de antes sigue en la Principal")
        r.close()
    }
}
