package com.vitrinapos.net

import com.vitrinapos.data.Op
import com.vitrinapos.data.Repository
import com.vitrinapos.data.Time
import com.vitrinapos.db.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.Base64

/**
 * El equipo principal: guarda los datos y atiende a los demás equipos de la red.
 */
class SyncServer(private val repo: Repository, private val serverName: () -> String, private val platformName: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var http: MiniHttpServer? = null
    private var udp: DatagramSocket? = null
    @Volatile private var running = false

    /** Código de 6 números para unir equipos; cambia cada 10 minutos y después de cada uso. */
    private val _pairCode = MutableStateFlow(newCode())
    val pairCode: StateFlow<PairCode> = _pairCode

    data class PairCode(val code: String, val secret: String, val createdAt: Long) {
        /** Lo que lleva el QR: dirección y clave larga. */
        fun qrText(host: String) = "vitrina://$host:$SYNC_PORT?c=$code$secret"
    }

    private fun newCode() = PairCode(Crypto.randomDigits(6), Crypto.randomToken(10), Time.now())

    fun refreshCode() { _pairCode.value = newCode() }

    val epoch: String = repo.metaGet("epoch") ?: Crypto.randomToken(12).also { repo.metaPut("epoch", it) }

    private val keys = HashMap<String, ByteArray>()

    val devices: List<Device> get() = repo.db.syncQueries.deviceAll().executeAsList()

    fun revoke(id: String) {
        synchronized(repo.writeLock) { repo.db.syncQueries.deviceRevoke(id) }
        synchronized(keys) { keys.remove(id) }
    }

    fun forget(id: String) {
        synchronized(repo.writeLock) { repo.db.syncQueries.deviceDelete(id) }
        synchronized(keys) { keys.remove(id) }
    }

    fun start() {
        if (running) return
        running = true
        http = MiniHttpServer(SYNC_PORT, ::handle).also { it.start() }
        startDiscovery()
    }

    fun stop() {
        running = false
        http?.stop(); http = null
        runCatching { udp?.close() }; udp = null
    }

    private fun handle(path: String, headers: Map<String, String>, body: ByteArray, remote: String): Pair<Int, ByteArray> = when (path) {
        "/pair" -> pair(body)
        "/api" -> api(headers["x-device"] ?: "", body)
        "/hello" -> 200 to json.encodeToString(DiscoveryInfo.serializer(), info()).toByteArray()
        else -> 404 to ByteArray(0)
    }

    private fun info() = DiscoveryInfo(serverName(), SYNC_PORT, repo.deviceId, platformName)

    // ---------- Emparejar ----------

    private fun pair(body: ByteArray): Pair<Int, ByteArray> {
        val code = _pairCode.value
        if (Time.now() - code.createdAt > 10 * 60_000) refreshCode()
        val current = _pairCode.value
        // El QR trae la clave larga; si se escribe a mano, solo el código de 6 números.
        val candidates = listOf(current.code + current.secret, current.code).map { Crypto.keyFromSecret(it) }
        for (key in candidates) {
            val plain = runCatching { Crypto.open(key, body) }.getOrNull() ?: continue
            val req = json.decodeFromString(PairRequest.serializer(), String(plain))
            val deviceKey = Crypto.randomBytes(32)
            val now = Time.now()
            synchronized(repo.writeLock) { repo.db.syncQueries.deviceAdd(req.deviceId, req.name.take(60), req.platform.take(20), Crypto.hex(deviceKey), now, now) }
            synchronized(keys) { keys[req.deviceId] = deviceKey }
            refreshCode()
            val resp = PairResponse(Crypto.hex(deviceKey), repo.deviceId, serverName(), epoch)
            return 200 to Crypto.seal(key, json.encodeToString(PairResponse.serializer(), resp).toByteArray())
        }
        return 401 to "Código incorrecto o vencido".toByteArray()
    }

    private fun keyFor(deviceId: String): ByteArray? {
        synchronized(keys) { keys[deviceId]?.let { return it } }
        val d = repo.db.syncQueries.deviceGet(deviceId).executeAsOneOrNull() ?: return null
        if (d.revoked == 1L) return null
        return Crypto.unhex(d.keyHex).also { synchronized(keys) { keys[deviceId] = it } }
    }

    // ---------- Pedidos de los equipos ----------

    private fun api(deviceId: String, body: ByteArray): Pair<Int, ByteArray> {
        val key = keyFor(deviceId) ?: return 401 to "Este equipo no está autorizado".toByteArray()
        val req = runCatching { json.decodeFromString(ApiRequest.serializer(), String(Crypto.open(key, body))) }
            .getOrElse { return 401 to "No se pudo leer el pedido".toByteArray() }
        synchronized(repo.writeLock) { repo.db.syncQueries.deviceSeen(Time.now(), deviceId) }
        val resp = runCatching { answer(req, deviceId) }.getOrElse { ApiResponse(ok = false, error = it.message ?: "Error en el equipo principal") }
        return 200 to Crypto.seal(key, json.encodeToString(ApiResponse.serializer(), resp).toByteArray())
    }

    private fun answer(req: ApiRequest, deviceId: String = ""): ApiResponse = when (req.type) {
        "ping" -> ApiResponse(epoch = epoch, last = repo.maxSeq())
        "changes" -> changes(req.since)
        "wait" -> {
            if (repo.maxSeq() <= req.since) {
                runBlocking { withTimeoutOrNull(25_000) { repo.changeSignal.first { it > req.since } } }
            }
            changes(req.since)
        }
        "op" -> {
            val op = req.op ?: error("Operación vacía")
            val previous = if (req.opId.isNotBlank()) repo.appliedResult(req.opId) else null
            if (previous != null) {
                ApiResponse(result = Json.parseToJsonElement(previous))
            } else {
                val device = repo.db.syncQueries.deviceGet(deviceId).executeAsOneOrNull()?.name ?: ""
                val result = repo.applyLocal(op, req.user, device)
                if (req.opId.isNotBlank()) repo.saveAppliedResult(req.opId, result.toString())
                ApiResponse(result = result, last = repo.maxSeq())
            }
        }
        "fiscalCert" -> {
            // Solo si el administrador permitió firmar en las cajas; viaja dentro del canal cifrado del equipo.
            val raw = repo.fiscal.certificateForDevices()
            when {
                !repo.settings.value.fiscalCertOnClients -> ApiResponse(ok = false, error = "El equipo principal no comparte el certificado con las cajas.")
                raw == null -> ApiResponse(ok = false, error = "El equipo principal no tiene certificado digital.")
                else -> ApiResponse(data = Base64.getEncoder().encodeToString(raw))
            }
        }
        "imgGet" -> {
            val f = repo.imageFile(req.name) ?: return ApiResponse(ok = false, error = "No existe la imagen")
            ApiResponse(data = Base64.getEncoder().encodeToString(f.readBytes()))
        }
        "imgPut" -> {
            val name = File(req.name).name
            require(name.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) { "Nombre de imagen no válido" }
            File(repo.imagesDir, name).writeBytes(Base64.getDecoder().decode(req.data))
            ApiResponse()
        }
        else -> ApiResponse(ok = false, error = "Pedido desconocido: ${req.type}")
    }

    private fun changes(since: Long): ApiResponse {
        val limit = 400L
        val rows = repo.changesSince(since, limit)
        val list = rows.map { Change(it.tbl, it.rowKey, repo.readRow(it.tbl, it.rowKey)) }
        val last = rows.maxOfOrNull { it.seq ?: since } ?: since
        return ApiResponse(epoch = epoch, last = last, more = rows.size.toLong() == limit, changes = list)
    }

    // ---------- Para que los equipos encuentren al principal ----------

    private fun startDiscovery() {
        Thread({
            runCatching {
                val s = DatagramSocket(null as java.net.SocketAddress?)
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(DISCOVERY_PORT))
                udp = s
                val buf = ByteArray(512)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (e: Exception) { break }
                    if (String(p.data, 0, p.length) == DISCOVERY_ASK) {
                        val out = json.encodeToString(DiscoveryInfo.serializer(), info()).toByteArray()
                        runCatching { s.send(DatagramPacket(out, out.size, p.address, p.port)) }
                    }
                }
            }
        }, "vitrina-discovery").apply { isDaemon = true }.start()
    }

    companion object {
        const val DISCOVERY_ASK = "VITRINA-POS?"
    }
}
