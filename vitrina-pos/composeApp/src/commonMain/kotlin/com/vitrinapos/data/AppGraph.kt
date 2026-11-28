package com.vitrinapos.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vitrinapos.net.Cloud
import com.vitrinapos.net.CloudClient
import com.vitrinapos.net.Crypto
import com.vitrinapos.net.DeviceCredentials
import com.vitrinapos.net.LoginRequest
import com.vitrinapos.net.NewBusinessRequest
import com.vitrinapos.net.OwnerLoginRequest
import com.vitrinapos.net.SignupRequest
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
    /** Conexión con la nube (null si este equipo no está en la nube). */
    var cloud by mutableStateOf<CloudClient?>(null)
        private set

    init {
        startNetwork()
        // La facturación electrónica desde la nube llega más adelante: en la nube no se firma desde el equipo.
        if (repo.role != NetRole.CLOUD) repo.startFiscal()
    }

    val deviceName: String get() = repo.meta.value["deviceName"] ?: repo.metaGet("deviceName") ?: platform.defaultDeviceName

    fun setDeviceName(name: String) = repo.metaPut("deviceName", name.trim().ifBlank { platform.defaultDeviceName })

    private fun startNetwork() {
        when (repo.role) {
            NetRole.SERVER -> startServer()
            NetRole.CLIENT -> startClient()
            NetRole.CLOUD -> startCloud()
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

    private fun startCloud() {
        val r = repo
        val id = r.metaGet("cloud.device")?.takeIf { it.isNotBlank() } ?: return
        val secret = r.metaGet("cloud.secret")?.takeIf { it.isNotBlank() }?.let { runCatching { String(platform.unprotect(Crypto.unhex(it)), Charsets.UTF_8) }.getOrNull() } ?: return
        val c = CloudClient(r, cloudUrl, id, secret)
        r.remote = c
        cloud = c
        c.start()
    }

    /** Dirección de la nube (se puede cambiar para pruebas con la propiedad vitrina.cloudUrl). */
    val cloudUrl: String get() = repo.metaGet("cloud.url")?.takeIf { it.isNotBlank() } ?: System.getProperty("vitrina.cloudUrl") ?: Cloud.DEFAULT_URL

    private fun stopNetwork() {
        server?.stop()
        client?.stop()
        cloud?.stop()
        repo.remote = null
        server = null
        client = null
        cloud = null
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

    // ================= Nube =================

    /** Si se muestran las opciones de la nube en este equipo. */
    val cloudAvailable: Boolean get() = Cloud.ENABLED || repo.role == NetRole.CLOUD || System.getProperty("vitrina.cloudUrl") != null

    /** Correo de la cuenta con la que este equipo entró a la nube. */
    val cloudEmail: String? get() = repo.metaGet("cloud.email")

    private fun checkCanJoin() {
        if (repo.role != NetRole.CLIENT && repo.role != NetRole.CLOUD && repo.unsentFiscalDocs() > 0)
            throw IllegalStateException("Este equipo tiene comprobantes electrónicos sin enviar a la DGII. Envíalos antes de pasar a la nube.")
    }

    /**
     * Deja este equipo trabajando con la nube: adopta el identificador que le dio el servidor, guarda su llave
     * (protegida con la del equipo) y recibe los datos del negocio. Los datos locales se reemplazan.
     */
    private suspend fun join(creds: DeviceCredentials, email: String, url: String) {
        stopNetwork()
        val old = repo
        old.clearSharedData()
        old.metaPut("deviceId", creds.deviceId)
        old.metaPut("cloud.url", url)
        old.metaPut("cloud.email", email)
        old.metaPut("cloud.device", creds.deviceId)
        old.metaPut("cloud.secret", Crypto.hex(platform.protect(creds.deviceSecret.toByteArray(Charsets.UTF_8))))
        old.metaPut("cloud.business", creds.businessId.toString())
        old.metaPut("cloud.businessName", creds.businessName)
        old.metaPut("epoch", "")
        old.metaPut("lastSeq", "0")
        old.metaPut("role", NetRole.CLOUD)
        old.currentUser.value = null
        old.close()
        repo = Repository(platform)
        signedOut = false
        startCloud()
        cloud?.pull()
    }

    /** Crea la cuenta y el negocio en la nube; este equipo queda autorizado. Se ejecuta fuera del hilo de la pantalla. */
    suspend fun cloudSignup(r: SignupRequest, url: String = cloudUrl) {
        checkCanJoin()
        val creds = CloudClient.signup(url, r.copy(deviceName = r.deviceName.ifBlank { deviceName }, platform = platform.platformName))
        join(creds, r.email.trim().lowercase(), url)
    }

    /** Entra con la cuenta del negocio: este equipo se autoriza y recibe los datos de la nube. */
    suspend fun cloudLogin(email: String, password: String, businessId: Long? = null, url: String = cloudUrl, branchId: Long? = null): DeviceCredentials {
        checkCanJoin()
        val creds = CloudClient.login(url, LoginRequest(email.trim(), password, deviceName, platform.platformName, businessId, branchId))
        join(creds, email.trim().lowercase(), url)
        return creds
    }

    /** Otro negocio con la misma cuenta; este equipo queda trabajando en él. */
    suspend fun cloudNewBusiness(r: NewBusinessRequest, url: String = cloudUrl) {
        checkCanJoin()
        val creds = CloudClient.newBusiness(url, r.copy(deviceName = r.deviceName.ifBlank { deviceName }, platform = platform.platformName))
        join(creds, r.email.trim().lowercase(), url)
    }

    /** Ventas hechas sin conexión que todavía no llegan a la nube. */
    fun unsentSales(): Long = repo.db.syncQueries.outboxCount().executeAsOne()

    /** Intenta enviar ya las ventas pendientes. Devuelve cuántas quedan. */
    suspend fun sendPendingNow(): Long {
        runCatching { cloud?.flushOutbox() }
        return unsentSales()
    }

    /** La sesión de la cuenta se acaba de cerrar en este equipo (para el aviso en "Iniciar sesión"). */
    var signedOut by mutableStateOf(false)

    /** Correo de la última cuenta que se usó en este equipo (se queda escrito en "Iniciar sesión"). */
    val lastAccountEmail: String
        get() = cloudEmail?.takeIf { it.isNotBlank() } ?: runCatching { File(platform.dataDir, LAST_ACCOUNT).readText().trim() }.getOrDefault("")

    /**
     * "Cerrar sesión de la cuenta". Los datos siguen en la nube: aquí solo se quita la copia de este equipo
     * (para que nadie más los vea y para poder entrar a otro negocio). Si hay ventas sin enviar, no se cierra.
     */
    suspend fun signOutAccount() {
        if (sendPendingNow() > 0)
            throw IllegalStateException("Este equipo tiene ventas hechas sin internet que todavía no llegan a la nube. Conéctate a internet para enviarlas.")
        val email = cloudEmail.orEmpty()
        // El equipo se da de baja en la nube (deja de contar en el plan). Sin internet se cierra igual;
        // el dueño lo puede quitar después en Trabajo en red → Equipos.
        runCatching { cloud?.leave() }
        cloud?.logoutUser()
        stopNetwork()
        repo.currentUser.value = null
        repo.close()
        val db = platform.databaseFile
        listOf("", "-wal", "-shm", "-journal").forEach { File(db.path + it).delete() }
        File(platform.dataDir, "images").deleteRecursively()
        runCatching { File(platform.dataDir, LAST_ACCOUNT).writeText(email) }
        repo = Repository(platform)
        signedOut = true
    }

    /**
     * "Pasar mis datos a la nube": crea la cuenta, sube la base y las fotos de este equipo y lo deja en la nube.
     * [progress] recibe el paso en curso.
     */
    suspend fun migrateToCloud(r: SignupRequest, url: String = cloudUrl, progress: (String) -> Unit = {}) {
        checkCanJoin()
        if (repo.role == NetRole.CLIENT) throw IllegalStateException("Este equipo está conectado a un principal. Pasa los datos desde el equipo principal.")
        if (repo.settings.value.fiscalEnabled)
            throw IllegalStateException("La facturación electrónica en la nube llega en una próxima versión. Por ahora, este negocio sigue en este equipo.")
        progress("Creando la cuenta…")
        val creds = CloudClient.signup(url, r.copy(deviceName = r.deviceName.ifBlank { deviceName }, platform = platform.platformName))
        val owner = CloudClient.ownerLogin(url, OwnerLoginRequest(r.email.trim(), r.password))
        progress("Subiendo los datos…")
        repo.checkpoint()
        val copy = File(platform.dataDir, "subida_nube.db")
        try {
            platform.databaseFile.copyTo(copy, overwrite = true)
            CloudClient.uploadDatabase(url, owner.token, copy)
        } finally { copy.delete() }
        val photos = repo.imagesDir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (photos.isNotEmpty()) {
            val token = CloudClient.deviceToken(url, creds).token
            photos.forEachIndexed { i, f ->
                progress("Subiendo fotos… ${i + 1} de ${photos.size}")
                runCatching { CloudClient.uploadImageWith(url, token, f) }
            }
        }
        progress("Recibiendo los datos de la nube…")
        join(creds, r.email.trim().lowercase(), url)
    }

    /** Este equipo sale de la nube y se queda trabajando solo con una copia de los datos. */
    fun leaveCloud() {
        cloud?.logoutUser()
        stopNetwork()
        listOf("cloud.secret", "cloud.device").forEach { repo.metaPut(it, "") }
        repo.metaPut("role", NetRole.SOLO)
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
        if (repo.role == NetRole.CLOUD)
            throw IllegalStateException("Este equipo trabaja con la nube: los respaldos se guardan allá cada noche. Para restaurar uno, comunícate con Vitrina POS.")
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
        const val LAST_ACCOUNT = "ultima-cuenta.txt"
    }
}
