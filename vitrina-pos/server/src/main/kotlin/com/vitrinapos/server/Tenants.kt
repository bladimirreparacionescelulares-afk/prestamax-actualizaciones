package com.vitrinapos.server

import com.vitrinapos.net.*
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.vitrinapos.data.Repository
import com.vitrinapos.db.VitrinaDb
import com.vitrinapos.platform.CorePlatform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO

/** El "equipo" de un negocio dentro del servidor: su carpeta, su base y la llave maestra del servidor. */
class ServerPlatform(override val dataDir: File, private val masterKey: ByteArray) : CorePlatform {
    override val platformName = "Servidor"
    override val defaultDeviceName = "Nube"
    override fun setNetworkActive(active: Boolean) {}
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val databaseFile = File(dataDir, "negocio.db")

    init { dataDir.mkdirs() }

    override fun createDriver(): SqlDriver =
        JdbcSqliteDriver("jdbc:sqlite:${databaseFile.path}", Properties().apply { put("journal_mode", "WAL"); put("busy_timeout", "8000") }, VitrinaDb.Schema)

    override fun saveCompressedJpeg(src: File, dest: File, maxSide: Int, quality: Int): Boolean = runCatching {
        val img = ImageIO.read(src) ?: return false
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(img.width, img.height))
        val w = (img.width * scale).toInt().coerceAtLeast(1); val h = (img.height * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, w, h, null); dispose()
        }
        ImageIO.write(out, "jpg", dest)
    }.getOrDefault(false)

    // El certificado de facturación de cada negocio se guarda cifrado con la llave maestra del servidor.
    override fun protect(data: ByteArray): ByteArray {
        val iv = Secure.bytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(128, iv)) }
        return iv + c.doFinal(data)
    }

    override fun unprotect(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(128, data, 0, 12)) }
        return c.doFinal(data, 12, data.size - 12)
    }
}

/** Abre (y mantiene abiertas) las bases de los negocios. Cada negocio, su propia carpeta. */
class Tenants(private val root: File, private val masterKey: ByteArray, private val maxOpen: Int = 200) : AutoCloseable {
    private val log = LoggerFactory.getLogger("negocios")
    private val open = object : LinkedHashMap<Long, Repository>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Repository>): Boolean {
            if (size > maxOpen) { runCatching { eldest.value.close() }; return true }
            return false
        }
    }

    fun dir(businessId: Long) = File(root, "negocio-$businessId")
    fun exists(businessId: Long) = File(dir(businessId), "negocio.db").exists()

    /** La base del negocio (se crea la primera vez). */
    fun repo(businessId: Long): Repository = synchronized(open) {
        open.getOrPut(businessId) { Repository(ServerPlatform(dir(businessId), masterKey)) }
    }

    override fun close() = synchronized(open) { open.values.forEach { runCatching { it.close() } }; open.clear() }

    /** ¿El negocio está recién creado (sin productos ni ventas)? Solo entonces se puede reemplazar con los datos de un equipo. */
    fun isFresh(businessId: Long): Boolean {
        val r = repo(businessId)
        return r.db.saleQueries.selectRecent(1).executeAsList().isEmpty() && r.db.productQueries.selectActive().executeAsList().isEmpty()
    }

    /**
     * "Pasar mis datos a la nube": reemplaza la base del negocio por la que sube un equipo.
     * Se revisa que sea una base de Vitrina POS, se pone al día su esquema, se quita lo propio de aquel equipo
     * y se anota cada fila para que los equipos de la nube la reciban.
     */
    fun replaceWith(businessId: Long, bytes: ByteArray) {
        require(bytes.size > 100 && String(bytes, 0, 15, Charsets.US_ASCII) == "SQLite format 3") { "El archivo no es una base de Vitrina POS" }
        val dir = dir(businessId)
        val tmp = File(dir, "subida.db")
        tmp.writeBytes(bytes)
        try {
            JdbcSqliteDriver("jdbc:sqlite:${tmp.path}", Properties(), VitrinaDb.Schema).use { d ->
                fun run(sql: String) = d.execute(null, sql, 0)
                // Debe tener las tablas de la app.
                d.executeQuery(null, "SELECT count(*) FROM product", { c -> c.next(); QueryResult.Value(c.getLong(0)) }, 0)
                listOf("meta", "outbox", "appliedOp", "device", "changeLog").forEach { run("DELETE FROM $it") }
                com.vitrinapos.net.RowCodec.tables.values.forEach { t ->
                    run("INSERT INTO changeLog(tbl, rowKey) SELECT '${t.name}', ${t.key} FROM ${t.name}")
                }
                run("INSERT INTO meta(key, content) VALUES ('epoch', '${Secure.token(9)}')")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw IllegalArgumentException("El archivo no es una base de Vitrina POS válida: ${e.message}")
        }
        synchronized(open) {
            open.remove(businessId)?.let { runCatching { it.close() } }
            val db = File(dir, "negocio.db")
            listOf("-wal", "-shm", "-journal").forEach { File(db.path + it).delete() }
            if (!tmp.renameTo(db)) { tmp.copyTo(db, overwrite = true); tmp.delete() }
        }
        log.info("Negocio {}: datos subidos desde un equipo", businessId)
    }

    // ---------- Respaldos ----------

    private val backupsRoot get() = File(root, "respaldos")

    /** Copia consistente de la base del negocio (aunque se esté vendiendo en ese momento). */
    fun backup(businessId: Long, day: LocalDate = LocalDate.now(ZONE)): File {
        val dest = File(File(backupsRoot, "negocio-$businessId"), "$day.db")
        dest.parentFile.mkdirs()
        if (dest.exists()) dest.delete()
        val r = repo(businessId)
        synchronized(r.writeLock) {
            r.driver.execute(null, "VACUUM INTO ?", 1) { bindString(0, dest.path) }
        }
        return dest
    }

    /** Respaldo de todos los negocios; se guardan los últimos [keepDays] días. */
    fun backupAll(ids: List<Long>, keepDays: Int = 30) {
        val today = LocalDate.now(ZONE)
        for (id in ids) {
            if (!exists(id)) continue
            runCatching { backup(id, today) }.onFailure { log.error("No se pudo respaldar el negocio $id", it) }
            File(backupsRoot, "negocio-$id").listFiles()?.filter { f ->
                val d = runCatching { LocalDate.parse(f.nameWithoutExtension) }.getOrNull()
                d != null && d.isBefore(today.minusDays(keepDays.toLong()))
            }?.forEach { it.delete() }
        }
    }

    fun latestBackup(businessId: Long): File? = File(backupsRoot, "negocio-$businessId").listFiles()?.filter { it.extension == "db" }?.maxByOrNull { it.name }

    /** Comprueba que una base esté sana (para revisar los respaldos). */
    fun integrityOk(file: File): Boolean = runCatching {
        JdbcSqliteDriver("jdbc:sqlite:${file.path}").use { d ->
            d.executeQuery(null, "PRAGMA integrity_check", { c -> c.next(); QueryResult.Value(c.getString(0)) }, 0).value == "ok"
        }
    }.getOrDefault(false)

    companion object {
        val ZONE: ZoneId = ZoneId.of("America/Santo_Domingo")
    }
}
