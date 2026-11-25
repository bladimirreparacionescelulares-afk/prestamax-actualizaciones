package com.vitrinapos

import com.vitrinapos.data.AppGraph
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.NetRole
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.SampleData
import com.vitrinapos.net.LinkState
import com.vitrinapos.platform.DesktopPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Dos copias de la app en este equipo: una es la principal y la otra se conecta a ella. */
class NetworkTest {
    private fun platform(name: String): DesktopPlatform {
        System.setProperty("vitrina.dataDir", Files.createTempDirectory("vitrina-$name").toString())
        return DesktopPlatform()
    }

    private suspend fun waitUntil(what: String, check: () -> Boolean) {
        withTimeout(20_000) { while (!check()) delay(100) }
        assertTrue(check(), what)
    }

    @Test
    fun principalYEquipoConectado(): Unit = runBlocking {
        val main = AppGraph(platform("principal"))
        SampleData.load(main.repo)
        main.becomeServer()
        val server = main.server!!

        // Un código equivocado no une el equipo.
        val other = AppGraph(platform("caja2"))
        assertFailsWith<java.io.IOException> { other.connectTo("127.0.0.1", 8740, "000000") }

        // Con el código correcto recibe todos los datos del principal.
        other.connectTo("127.0.0.1", 8740, server.pairCode.value.code)
        assertEquals(NetRole.CLIENT, other.repo.role)
        waitUntil("recibe los productos") { other.repo.db.productQueries.selectActive().executeAsList().size == 11 }
        assertEquals(1, server.devices.size)

        // Un cliente creado en el equipo conectado queda guardado en el principal.
        val clientId = other.repo.saveClient(null, "Cliente desde caja 2", "809-000-0000", "", "", "", "")
        assertEquals("Cliente desde caja 2", main.repo.db.clientQueries.selectById(clientId).executeAsOne().name)

        // Venta desde el equipo conectado: descuenta el stock en el principal.
        val cable = main.repo.db.productQueries.selectActive().executeAsList().first { it.code == "CBL-001" }
        val saleId = other.repo.recordSale(listOf(CartLine.of(cable, 2.0)), null, 0, PaymentMethods.CASH, 100000, null)
        assertTrue(saleId > 0)
        assertEquals(cable.stock - 2, main.repo.db.productQueries.selectById(cable.id).executeAsOne().stock)
        assertEquals(other.repo.deviceId, main.repo.db.saleQueries.selectById(saleId).executeAsOne().deviceId)

        // Un cambio hecho en el principal llega solo al otro equipo.
        main.repo.saveCategory(null, "Nueva desde principal", 0xFF0E7C66)
        waitUntil("llega la categoría") { other.repo.categories.value.any { it.name == "Nueva desde principal" } }

        // Sin conexión: la venta se guarda como pendiente y se envía al volver.
        server.stop()
        val offlineId = other.repo.recordSale(listOf(CartLine.of(cable, 1.0)), null, 0, PaymentMethods.CASH, 30000, null)
        assertTrue(offlineId < 0, "venta provisional")
        assertEquals(1L, other.repo.db.syncQueries.outboxCount().executeAsOne())
        // Otros cambios sin conexión avisan con un error claro.
        assertFailsWith<java.io.IOException> { other.repo.saveClient(null, "No debe guardarse", "", "", "", "", "") }

        main.becomeServer()
        waitUntil("se envía la venta pendiente") { other.repo.db.syncQueries.outboxCount().executeAsOne() == 0L }
        waitUntil("el principal tiene las 2 ventas") { main.repo.db.saleQueries.selectRecent(10).executeAsList().size == 2 }
        assertEquals(cable.stock - 3, main.repo.db.productQueries.selectById(cable.id).executeAsOne().stock)
        waitUntil("el equipo conectado ya no tiene ventas provisionales") {
            other.repo.db.saleQueries.selectRecent(10).executeAsList().let { s -> s.size == 2 && s.all { it.id > 0 } }
        }
        waitUntil("stock igual en los dos") {
            other.repo.db.productQueries.selectById(cable.id).executeAsOne().stock == cable.stock - 3
        }

        // El principal quita el acceso.
        val newServer = main.server!!
        newServer.revoke(other.repo.deviceId)
        assertFailsWith<Exception> { other.repo.saveClient(null, "Bloqueado", "", "", "", "", "") }
        waitUntil("el equipo sabe que perdió el acceso") { other.client!!.status.value.state == LinkState.REVOKED }

        other.becomeSolo()
        main.becomeSolo()
        other.repo.close()
        main.repo.close()
    }
}
