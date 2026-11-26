package com.vitrinapos.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vitrinapos.net.Crypto
import com.vitrinapos.net.PairRequest
import com.vitrinapos.net.SyncClient
import com.vitrinapos.net.SyncServer
import com.vitrinapos.platform.Platform
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Mantiene el repositorio activo y el modo de red de este equipo. */
class AppGraph(val platform: Platform) {
    var repo by mutableStateOf(Repository(platform))
        private set
    var server by mutableStateOf<SyncServer?>(null)
        private set
    var client by mutableStateOf<SyncClient?>(null)
        private set

    init {
        startNetwork()
        repo.startFiscal()
    }

    val deviceName: String get() = repo.meta.value["deviceName"] ?: repo.metaGet("deviceName") ?: platform.defaultDeviceName

    fun setDeviceName(name: String) = repo.metaPut("deviceName", name.trim().ifBlank { platform.defaultDeviceName })

    private fun startNetwork() {
        when (repo.role) {
            NetRole.SERVER -> startServer()
            NetRole.CLIENT -> startClient()
        }
    }

    private fun startServer() {
        val r = repo
        val s = SyncServer(r, { "${r.settings.value.businessName} · $deviceName" }, platform.platformName)
        runCatching { s.start() }.onFailure { throw IllegalStateException("No se pudo abrir el puerto de red ${com.vitrinapos.net.SYNC_PORT}. ¿Hay otra copia de la app abierta?") }
        platform.setNetworkActive(true)
        server = s
    }

    private fun startClient() {
        val r = repo
        val host = r.metaGet("serverHost") ?: return
        val port = r.metaGet("serverPort")?.toIntOrNull() ?: com.vitrinapos.net.SYNC_PORT
        val key = r.metaGet("deviceKey")?.let { Crypto.unhex(it) } ?: return
        val c = SyncClient(r, host, port, key)
        r.remote = c
        client = c
        c.start()
    }

    private fun stopNetwork() {
        server?.stop()
        client?.stop()
        repo.remote = null
        server = null
        client = null
        platform.setNetworkActive(false)
    }

    /** Este equipo guarda los datos y los demás se conectan a él. */
    fun becomeServer() {
        stopNetwork()
        repo.metaPut("role", NetRole.SERVER)
        try { startServer() } catch (e: Exception) { repo.metaPut("role", NetRole.SOLO); throw e }
    }

    /** Este equipo trabaja solo. Si estaba conectado, se queda con una copia de los datos. */
    fun becomeSolo() {
        stopNetwork()
        repo.metaPut("role", NetRole.SOLO)
    }

    /**
     * Une este equipo a un principal. Los datos de este equipo se reemplazan por los del principal.
     * Se ejecuta fuera del hilo de la pantalla.
     */
    suspend fun connectTo(host: String, port: Int, secret: String) {
        if (repo.role != NetRole.CLIENT && repo.unsentFiscalDocs() > 0)
            throw IllegalStateException("Este equipo tiene comprobantes electrónicos sin enviar a la DGII. Envíalos antes de unirlo a otro equipo.")
        val resp = SyncClient.pair(host, port, secret, PairRequest(repo.deviceId, deviceName, platform.platformName))
        stopNetwork()
        val r = repo
        r.clearSharedData()
        r.metaPut("serverHost", host)
        r.metaPut("serverPort", port.toString())
        r.metaPut("serverName", resp.serverName)
        r.metaPut("deviceKey", resp.keyHex)
        r.metaPut("epoch", resp.epoch)
        r.metaPut("lastSeq", "0")
        r.metaPut("role", NetRole.CLIENT)
        startClient()
        client?.pull()
    }

    /** Escribe un .zip con la base de datos y las fotos. */
    fun exportBackup(out: OutputStream) {
        val r = repo
        r.checkpoint()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            platform.databaseFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            r.imagesDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                zip.putNextEntry(ZipEntry("images/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("info.txt"))
            zip.write("Respaldo de Vitrina POS\nFecha: ${Time.dateTime(Time.now())}\n".toByteArray())
            zip.closeEntry()
        }
    }

    /**
     * Restaura un respaldo. Primero lo extrae a una carpeta temporal y comprueba que tenga la base de datos;
     * solo entonces reemplaza los datos actuales. Este equipo conserva su identidad y su modo de red.
     */
    fun importBackup(input: InputStream) {
        // Restaurar un respaldo haría retroceder la numeración e-NCF y se repetirían comprobantes.
        if (repo.settings.value.fiscalEnabled || repo.ecfDocs.value.isNotEmpty())
            throw IllegalStateException("Con facturación electrónica no se puede restaurar un respaldo: se repetirían números de comprobantes ya enviados a la DGII.")
        val tmp = File(platform.dataDir, "restore_tmp").apply { deleteRecursively(); mkdirs() }
        var hasDb = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val target = when {
                    e.name == DB_ENTRY -> File(tmp, DB_ENTRY).also { hasDb = true }
                    e.name.startsWith("images/") -> {
                        val n = File(e.name).name
                        if (n.isBlank() || n.startsWith(".")) null else File(tmp, "images/$n")
                    }
                    else -> null
                } ?: continue
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
        require(hasDb) { "El archivo no es un respaldo de Vitrina POS." }
        val header = File(tmp, DB_ENTRY).inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        require(String(header, Charsets.US_ASCII).startsWith("SQLite format 3")) { "El respaldo está dañado." }

        val keepMeta = repo.db.syncQueries.metaAll().executeAsList()
        val wasServer = repo.role == NetRole.SERVER
        stopNetwork()
        repo.close()
        val dbFile = platform.databaseFile
        listOf("-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        File(tmp, DB_ENTRY).copyTo(dbFile, overwrite = true)
        val images = File(platform.dataDir, "images")
        images.deleteRecursively()
        images.mkdirs()
        File(tmp, "images").listFiles()?.forEach { it.copyTo(File(images, it.name), overwrite = true) }
        tmp.deleteRecursively()
        // Se conserva la identidad de este equipo; los conectados reciben todo de nuevo.
        Repository(platform).apply {
            keepMeta.forEach { metaPut(it.key, it.content) }
            metaPut("epoch", Crypto.randomToken(12))
            close()
        }
        repo = Repository(platform)
        repo.startFiscal()
        if (wasServer) runCatching { startServer() }
    }

    companion object {
        const val DB_ENTRY = "vitrina.db"
    }
}
