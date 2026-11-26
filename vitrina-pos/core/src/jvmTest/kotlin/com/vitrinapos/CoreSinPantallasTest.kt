package com.vitrinapos

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.ProductDraft
import com.vitrinapos.data.Repository
import com.vitrinapos.data.RuleException
import com.vitrinapos.db.VitrinaDb
import com.vitrinapos.platform.CorePlatform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Plataforma mínima, como la tendrá el servidor: sin pantallas, sin impresoras, solo disco y base de datos. */
class ServerLikePlatform(override val dataDir: File) : CorePlatform {
    override val platformName = "Servidor"
    override val defaultDeviceName = "servidor"
    override fun setNetworkActive(active: Boolean) {}
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val databaseFile = File(dataDir, "negocio.db")
    override fun createDriver(): SqlDriver =
        JdbcSqliteDriver("jdbc:sqlite:${databaseFile.path}", Properties().apply { put("journal_mode", "WAL"); put("busy_timeout", "8000") }, VitrinaDb.Schema)
    override fun saveCompressedJpeg(src: File, dest: File, maxSide: Int, quality: Int) = src.copyTo(dest, overwrite = true).exists()
    override fun protect(data: ByteArray) = data
    override fun unprotect(data: ByteArray) = data
}

class CoreSinPantallasTest {
    @Test
    fun elNucleoTrabajaSinPantallas(): Unit = runBlocking {
        val repo = Repository(ServerLikePlatform(Files.createTempDirectory("negocio").toFile()))
        val id = repo.saveProduct(ProductDraft(null, "Cargador USB-C", "", null, null, 30000, 85000, 5.0, 1.0, "und", true, true, null, ""))
        val p = repo.productById(id)!!
        // Las mismas reglas que en la app: sin caja abierta no se cobra.
        assertFailsWith<RuleException> { repo.recordSale(listOf(CartLine.of(p, 1.0)), null, 0, PaymentMethods.CASH, 85000, null) }
        repo.openCashSession(100000)
        val sale = repo.recordSale(listOf(CartLine.of(p, 2.0)), null, 0, PaymentMethods.CASH, 200000, null)
        assertEquals(3.0, repo.productById(id)!!.stock)
        assertEquals(170000L, repo.saleById(sale)!!.total)
        assertTrue(repo.activity(0, Long.MAX_VALUE).any { it.action == "Abrió la caja" })
        assertEquals(AppInfo.VERSION, AppInfo.VERSION.trim())
        repo.close()
    }
}
