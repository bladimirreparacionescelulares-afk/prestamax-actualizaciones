package com.vitrinapos

import com.vitrinapos.data.AppGraph
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.platform.DesktopPlatform
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test

/** Prueba manual contra un teléfono principal real: -DphoneCode=123456 -DphonePort=18740 */
class PhoneTest {
    @Test
    fun conectarAlTelefono(): Unit = runBlocking {
        val code = System.getProperty("phoneCode") ?: return@runBlocking
        val port = System.getProperty("phonePort")?.toInt() ?: 8740
        System.setProperty("vitrina.dataDir", Files.createTempDirectory("vitrina-pc").toString())
        val pc = AppGraph(DesktopPlatform())
        pc.setDeviceName("PC de prueba")
        pc.connectTo("127.0.0.1", port, code)
        Thread.sleep(2000)
        val products = pc.repo.db.productQueries.selectActive().executeAsList()
        val sales = pc.repo.db.saleQueries.selectRecent(10).executeAsList()
        println("RESULTADO productos=${products.size} ventas=${sales.size} total=${sales.sumOf { it.total }}")
        val p = products.first { it.code == "CBL-001" }
        val id = pc.repo.recordSale(listOf(CartLine.of(p, 2.0)), null, 0, PaymentMethods.CASH, 60000, null)
        println("RESULTADO venta desde PC id=$id stock cable ahora=${pc.repo.db.productQueries.selectById(p.id).executeAsOne().stock} (antes ${p.stock})")
        pc.becomeSolo()
        pc.repo.close()
    }
}
