package com.vitrinapos.net

import com.vitrinapos.data.Op
import com.vitrinapos.data.RemoteGateway
import com.vitrinapos.data.Repository
import com.vitrinapos.data.Time
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Base64

enum class LinkState { CONNECTING, ONLINE, OFFLINE, REVOKED }

data class NetStatus(val state: LinkState, val message: String = "", val lastSync: Long = 0)

/** Equipo conectado a un principal: recibe sus datos y le envía los cambios. */
class SyncClient(
    private val repo: Repository,
    val host: String,
    val port: Int,
    private val key: ByteArray,
) : RemoteGateway {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + repo.platform.io)
    private val pullLock = Mutex()
    private val flushLock = Mutex()
    private var loop: Job? = null

    private val _status = MutableStateFlow(NetStatus(LinkState.CONNECTING))
    val status: StateFlow<NetStatus> = _status

    private var lastSeq: Long
        get() = repo.metaGet("lastSeq")?.toLongOrNull() ?: 0
        set(v) = repo.metaPut("lastSeq", v.toString())

    fun start() {
        loop = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                try {
                    flushOutbox()
                    pull()
                    setOnline()
                    backoff = 1_000L
                    // Espera cambios del principal (responde en cuanto hay algo nuevo, o a los 25 s).
                    while (isActive) {
                        val resp = call(ApiRequest("wait", since = lastSeq), timeoutMs = 35_000)
                        apply(resp)
                        setOnline()
                        if (repo.pendingSales.value > 0) flushOutbox()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpStatusException) {
                    if (e.code == 401) {
                        _status.value = NetStatus(LinkState.REVOKED, "El equipo principal quitó el acceso a este equipo")
                        return@launch
                    }
                    _status.value = NetStatus(LinkState.OFFLINE, e.message ?: "Error del principal", _status.value.lastSync)
                } catch (e: Exception) {
                    _status.value = NetStatus(LinkState.OFFLINE, "Sin conexión con el equipo principal", _status.value.lastSync)
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        scope.cancel()
    }

    private fun setOnline() {
        _status.value = NetStatus(LinkState.ONLINE, "", Time.now())
    }

    /** Envía una solicitud cifrada al principal. */
    private suspend fun call(req: ApiRequest, timeoutMs: Int = 15_000): ApiResponse = withContext(repo.platform.io) {
        val body = Crypto.seal(key, json.encodeToString(ApiRequest.serializer(), req).toByteArray())
        val raw = try {
            MiniHttpClient.post(host, port, "/api", body, mapOf("X-Device" to repo.deviceId), timeoutMs)
        } catch (e: SocketTimeoutException) {
            throw IOException("El equipo principal no responde")
        }
        val resp = json.decodeFromString(ApiResponse.serializer(), String(Crypto.open(key, raw)))
        if (!resp.ok) throw HttpStatusException(500, resp.error)
        resp
    }

    /** Trae todos los cambios pendientes. */
    suspend fun pull() {
        while (true) {
            val resp = call(ApiRequest("changes", since = lastSeq))
            apply(resp)
            if (!resp.more) break
        }
    }

    private suspend fun apply(resp: ApiResponse) = pullLock.withLock {
        val epoch = repo.metaGet("epoch")
        if (resp.epoch.isNotBlank() && epoch != resp.epoch) {
            // El principal es otro o restauró un respaldo: se copian sus datos desde cero.
            withContext(repo.platform.io) { repo.clearSharedData() }
            repo.metaPut("epoch", resp.epoch)
            lastSeq = 0
            if (resp.last > 0 || resp.changes.isNotEmpty()) {
                // Lo recibido corresponde al punto anterior; se vuelve a pedir todo.
                return@withLock
            }
        }
        if (resp.changes.isEmpty()) {
            if (resp.last > lastSeq) lastSeq = resp.last
            return@withLock
        }
        withContext(repo.platform.io) {
            repo.applyRemoteChanges(resp.changes.map { Triple(it.t, it.k, it.row) })
        }
        lastSeq = resp.last
        fetchMissingImages(resp.changes)
    }

    private suspend fun fetchMissingImages(changes: List<Change>) {
        val names = changes.mapNotNull { c ->
            val row = c.row ?: return@mapNotNull null
            when (c.t) {
                "product", "quoteItem" -> row.str("imagePath")
                "repairOrder" -> row.str("photos")
                "setting" -> if (row.str("key") == "logoPath") row.str("content") else null
                else -> null
            }
        }.flatMap { it.split(',') }.filter { it.isNotBlank() && repo.imageFile(it) == null }.distinct()
        for (n in names) {
            runCatching {
                val resp = call(ApiRequest("imgGet", name = n), timeoutMs = 30_000)
                if (resp.data.isNotBlank()) File(repo.imagesDir, File(n).name).writeBytes(Base64.getDecoder().decode(resp.data))
            }
        }
        if (names.isNotEmpty()) repo.driver.notifyListeners("product", "setting", "repairOrder")
    }

    private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull

    /** Trae el certificado del principal (si lo comparte) y lo guarda cifrado en esta caja. */
    suspend fun fetchFiscalCertificate() {
        val resp = try {
            call(ApiRequest("fiscalCert"))
        } catch (e: HttpStatusException) {
            throw IOException(e.message)
        }
        repo.fiscal.installShared(Base64.getDecoder().decode(resp.data))
    }

    // ---------- RemoteGateway ----------

    override suspend fun execute(op: Op, opId: String): JsonElement {
        val resp = try {
            call(ApiRequest("op", opId = opId, op = op, user = repo.currentUser.value?.name ?: ""))
        } catch (e: HttpStatusException) {
            if (e.code == 401) {
                _status.value = NetStatus(LinkState.REVOKED, "El equipo principal quitó el acceso a este equipo")
                throw com.vitrinapos.data.OfflineException("Este equipo ya no tiene acceso al equipo principal. Pide que lo vuelvan a unir.")
            }
            throw e
        } catch (e: IOException) {
            _status.value = NetStatus(LinkState.OFFLINE, "Sin conexión con el equipo principal", _status.value.lastSync)
            throw com.vitrinapos.data.OfflineException("Sin conexión con el equipo principal. Revisa el Wi-Fi e intenta de nuevo.")
        }
        // Trae enseguida el resultado para que la pantalla lo muestre.
        runCatching { pull() }
        return resp.result
    }

    override suspend fun uploadImage(name: String, file: File) {
        try {
            call(ApiRequest("imgPut", name = name, data = Base64.getEncoder().encodeToString(file.readBytes())), timeoutMs = 30_000)
        } catch (e: HttpStatusException) {
            throw e
        } catch (e: IOException) {
            throw com.vitrinapos.data.OfflineException("Sin conexión con el equipo principal: no se pudo enviar la foto.")
        }
    }

    /** Envía las ventas hechas sin conexión, en orden. */
    suspend fun flushOutbox() = flushLock.withLock {
        for (row in withContext(repo.platform.io) { repo.outbox() }) {
            val op = Op.json.decodeFromString(Op.serializer(), row.payload)
            call(ApiRequest("op", opId = row.opId, op = op))
            withContext(repo.platform.io) { repo.outboxDone(row.opId, row.provisionalSaleId) }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Une este equipo a un principal. [secret] es el código de 6 números, o el código + clave del QR. */
        fun pair(host: String, port: Int, secret: String, req: PairRequest): PairResponse {
            val key = Crypto.keyFromSecret(secret)
            val body = Crypto.seal(key, json.encodeToString(PairRequest.serializer(), req).toByteArray())
            val raw = try {
                MiniHttpClient.post(host, port, "/pair", body, timeoutMs = 10_000)
            } catch (e: HttpStatusException) {
                throw IOException(if (e.code == 401) "El código no es correcto o ya venció. Revisa el código que muestra el equipo principal." else e.message)
            } catch (e: IOException) {
                throw IOException("No se pudo conectar con $host. Revisa que los dos equipos estén en la misma red y que la app esté abierta en el principal.")
            }
            return json.decodeFromString(PairResponse.serializer(), String(Crypto.open(key, raw)))
        }

        /** Busca equipos principales en la red local (responde cada principal con su nombre). */
        fun discover(timeoutMs: Int = 1500): List<FoundServer> {
            val found = LinkedHashMap<String, FoundServer>()
            runCatching {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.soTimeout = timeoutMs
                    val ask = SyncServer.DISCOVERY_ASK.toByteArray()
                    val targets = (listOf("255.255.255.255") + NetInfo.localAddresses().map { it.substringBeforeLast('.') + ".255" }).distinct()
                    targets.forEach { t -> runCatching { s.send(DatagramPacket(ask, ask.size, InetAddress.getByName(t), DISCOVERY_PORT)) } }
                    val buf = ByteArray(1024)
                    val end = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < end) {
                        val p = DatagramPacket(buf, buf.size)
                        try { s.receive(p) } catch (e: SocketTimeoutException) { break }
                        runCatching {
                            val info = json.decodeFromString(DiscoveryInfo.serializer(), String(p.data, 0, p.length))
                            val host = p.address.hostAddress ?: return@runCatching
                            found[info.serverId] = FoundServer(host, info)
                        }
                    }
                }
            }
            return found.values.toList()
        }

        /** Lee el texto de un QR de emparejamiento: vitrina://host:puerto?c=codigo. */
        fun parseQr(text: String): Triple<String, Int, String>? {
            val m = Regex("""vitrina://([^:/?]+):(\d+)\?c=([A-Za-z0-9]+)""").find(text.trim()) ?: return null
            return Triple(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3])
        }
    }
}
