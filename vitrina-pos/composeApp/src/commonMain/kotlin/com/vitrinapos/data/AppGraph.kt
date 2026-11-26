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

    /**
     * Escribe un .zip con la base de datos y las fotos. La base (y el certificado de facturación, si hay)
     * van cifrados con la contraseña: sin ella el respaldo no se puede abrir.
     */
    fun exportBackup(out: OutputStream, password: String) {
        require(password.length >= 6) { "La contraseña del respaldo lleva al menos 6 caracteres" }
        val r = repo
        r.checkpoint()
        val salt = Crypto.randomBytes(16)
        val key = Crypto.keyFromPassword(password, salt)
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(DB_ENC_ENTRY))
            zip.write(ENC_MAGIC.toByteArray(Charsets.US_ASCII))
            zip.write(salt)
            zip.write(Crypto.seal(key, platform.databaseFile.readBytes()))
            zip.closeEntry()
            r.fiscal.certificateForDevices()?.let { cert ->
                zip.putNextEntry(ZipEntry(CERT_ENTRY))
                zip.write(Crypto.seal(key, cert))
                zip.closeEntry()
            }
            r.imagesDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                zip.putNextEntry(ZipEntry("images/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("info.txt"))
            zip.write("Respaldo de Vitrina POS\nFecha: ${Time.dateTime(Time.now())}\nLos datos van cifrados: se abren con la contraseña que se puso al crearlo.\n".toByteArray())
            zip.closeEntry()
        }
    }

    /**
     * Restaura un respaldo. Primero lo extrae a una carpeta temporal y comprueba que tenga la base de datos;
     * solo entonces reemplaza los datos actuales. Este equipo conserva su identidad y su modo de red.
     */
    fun importBackup(input: InputStream, password: String = "") {
        // Restaurar un respaldo haría retroceder la numeración e-NCF y se repetirían comprobantes.
        if (repo.settings.value.fiscalEnabled || repo.ecfDocs.value.isNotEmpty())
            throw IllegalStateException("Con facturación electrónica no se puede restaurar un respaldo: se repetirían números de comprobantes ya enviados a la DGII.")
        val tmp = File(platform.dataDir, "restore_tmp").apply { deleteRecursively(); mkdirs() }
        var hasDb = false
        var cert: ByteArray? = null
        var backupKey: ByteArray? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (e.name == DB_ENC_ENTRY) {
                    val all = zip.readBytes()
                    val magic = ENC_MAGIC.length
                    require(all.size > magic + 16 && String(all, 0, magic, Charsets.US_ASCII) == ENC_MAGIC) { "El respaldo está dañado." }
                    if (password.isBlank()) throw IllegalStateException("Este respaldo tiene contraseña. Escríbela para restaurarlo.")
                    val key = Crypto.keyFromPassword(password, all.copyOfRange(magic, magic + 16)).also { backupKey = it }
                    val plain = runCatching { Crypto.open(key, all.copyOfRange(magic + 16, all.size)) }
                        .getOrElse { throw IllegalStateException("La contraseña del respaldo no es correcta.") }
                    File(tmp, DB_ENTRY).writeBytes(plain)
                    hasDb = true
                    // El certificado se guarda con la llave de este equipo.
                    continue
                }
                if (e.name == CERT_ENTRY) { cert = zip.readBytes(); continue }
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
        // El certificado de facturación del respaldo se guarda con la llave de este equipo.
        val k = backupKey
        if (cert != null && k != null) runCatching { repo.fiscal.installShared(Crypto.open(k, cert!!)) }
        repo.startFiscal()
        if (wasServer) runCatching { startServer() }
    }

    companion object {
        const val DB_ENC_ENTRY = "vitrina.db.cifrado"
        const val CERT_ENTRY = "certificado.cifrado"
        const val ENC_MAGIC = "VPOS-CIFRADO-1"
        const val DB_ENTRY = "vitrina.db"
    }
}
