package com.vitrinapos.net

import com.vitrinapos.AppInfo
import com.vitrinapos.data.OfflineException
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/** Dirección del servidor de Vitrina POS en la nube. */
object Cloud {
    const val DEFAULT_URL = "https://api.facturaprord.online"
    /** La nube está publicada en api.facturaprord.online (2.0d). */
    const val ENABLED = true
}

/**
 * Equipo conectado a la nube. Igual que con el equipo principal de la red local: envía cada operación,
 * recibe los cambios, guarda una copia y vende sin conexión. Además maneja la sesión del equipo (1 hora,
 * se renueva sola con su llave) y la del usuario que entró con su PIN (8 horas).
 */
class CloudClient(
    private val repo: Repository,
    val baseUrl: String,
    val deviceId: String,
    private val deviceSecret: String,
) : RemoteGateway {
    private val scope = CoroutineScope(SupervisorJob() + repo.platform.io)
    private val pullLock = Mutex()
    private val flushLock = Mutex()
    private val tokenLock = Mutex()
    private var loop: Job? = null

    private val _status = MutableStateFlow(NetStatus(LinkState.CONNECTING))
    val status: StateFlow<NetStatus> = _status

    /** Plan, módulos y estado del pago del negocio (se actualiza con cada ficha). */
    private val _plan = MutableStateFlow<Entitlements?>(repo.metaGet("cloud.plan")?.let { runCatching { json.decodeFromString(Entitlements.serializer(), it) }.getOrNull() })
    val plan: StateFlow<Entitlements?> = _plan

    /** Avisa que hace falta volver a entrar con el PIN (la sesión del usuario venció en la nube). */
    private val _needPin = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val needPin: SharedFlow<String> = _needPin

    private var deviceToken: String? = null
    private var deviceTokenExp = 0L
    @Volatile private var userToken: Pair<Long, String>? = null
    private var userTokenExp = 0L

    private var lastSeq: Long
        get() = repo.metaGet("lastSeq")?.toLongOrNull() ?: 0
        set(v) = repo.metaPut("lastSeq", v.toString())

    fun start() {
        loop = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                try {
                    ensureDeviceToken()
                    flushOutbox()
                    pull()
                    setOnline()
                    backoff = 1_000L
                    while (isActive) {
                        // Espera cambios de la nube (responde en cuanto hay algo nuevo, o a los 25 s).
                        val since = lastSeq
                        val resp = get("/v1/cambios?desde=$since&esperar=1", ApiResponse.serializer(), timeoutMs = 35_000)
                        if (apply(resp, since) || resp.more) pull()
                        setOnline()
                        if (repo.pendingSales.value > 0) flushOutbox()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpStatusException) {
                    if (e.code == 401 && deviceToken == null) {
                        _status.value = NetStatus(LinkState.REVOKED, "Este equipo ya no tiene acceso a la nube. Entra de nuevo con la cuenta del negocio.")
                        return@launch
                    }
                    _status.value = NetStatus(LinkState.OFFLINE, e.message ?: "Error de la nube", _status.value.lastSync)
                } catch (e: Exception) {
                    _status.value = NetStatus(LinkState.OFFLINE, "Sin conexión con la nube", _status.value.lastSync)
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
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

    // ---------- Sesiones ----------

    /** Pide (o renueva) la ficha del equipo con su llave. */
    suspend fun ensureDeviceToken(): String = tokenLock.withLock {
        deviceToken?.takeIf { Time.now() < deviceTokenExp - 5 * 60_000 }?.let { return@withLock it }
        val r = try {
            send("POST", "/v1/sesion/ficha", json.encodeToString(TokenRequest.serializer(), TokenRequest(deviceId, deviceSecret)), auth = false, timeoutMs = 15_000)
                .let { json.decodeFromString(TokenResponse.serializer(), it) }
        } catch (e: HttpStatusException) {
            if (e.code == 401) { deviceToken = null; throw HttpStatusException(401, e.message ?: "Sin acceso") }
            throw e
        }
        deviceToken = r.token
        deviceTokenExp = r.expires
        _plan.value = r.entitlements
        repo.metaPut("cloud.plan", json.encodeToString(Entitlements.serializer(), r.entitlements))
        repo.metaPut("cloud.deviceName", r.deviceName)
        r.token
    }

    /** El usuario entró con su PIN en este equipo: se pide su sesión en la nube (si hay conexión). */
    suspend fun loginUser(userId: Long, pin: String): Boolean = try {
        ensureDeviceToken()
        val r = json.decodeFromString(UserTokenResponse.serializer(),
            send("POST", "/v1/sesion/usuario", json.encodeToString(UserLoginRequest.serializer(), UserLoginRequest(userId, pin))))
        userToken = userId to r.token
        userTokenExp = r.expires
        true
    } catch (e: IOException) { false }

    fun logoutUser() { userToken = null; pinMemo = null }

    /** PIN del usuario que entró (solo en memoria): con él se renueva su sesión en la nube cuando vence. */
    @Volatile private var pinMemo: Pair<Long, String>? = null

    /** Lo llama la app cuando alguien entra con su PIN en este equipo. */
    fun userEntered(userId: Long, pin: String) {
        pinMemo = userId to pin
        userToken = null
        scope.launch { runCatching { loginUser(userId, pin) } }
    }

    /** Si la sesión del usuario actual venció, se renueva con el PIN recordado. */
    private suspend fun renewUser(): Boolean {
        val cur = repo.currentUser.value ?: return false
        val m = pinMemo?.takeIf { it.first == cur.id } ?: return false
        return runCatching { loginUser(m.first, m.second) }.getOrDefault(false)
    }

    private fun userHeader(): String? {
        val cur = repo.currentUser.value ?: return null
        val t = userToken?.takeIf { it.first == cur.id && Time.now() < userTokenExp } ?: return null
        return t.second
    }

    // ---------- HTTP ----------

    private suspend fun <T> get(path: String, s: KSerializer<T>, timeoutMs: Int = 15_000): T =
        json.decodeFromString(s, send("GET", path, null, timeoutMs = timeoutMs))

    private suspend fun send(method: String, path: String, body: String?, auth: Boolean = true, timeoutMs: Int = 15_000, retry: Boolean = true): String {
        val token = if (auth) ensureDeviceToken() else null
        return try {
            withContext(repo.platform.io) { http(baseUrl, method, path, body?.toByteArray(), "application/json", token, if (auth) userHeader() else null, timeoutMs) }.let { String(it) }
        } catch (e: HttpStatusException) {
            // La ficha del equipo venció antes de tiempo (por ejemplo, el servidor se reinició con otra firma): se pide otra una vez.
            if (e.code == 401 && auth && retry && e.message?.contains("sesión del equipo") == true) {
                deviceToken = null
                return send(method, path, body, auth, timeoutMs, retry = false)
            }
            throw e
        }
    }

    // ---------- Cambios ----------

    suspend fun pull() {
        while (true) {
            val since = lastSeq
            val resp = get("/v1/cambios?desde=$since", ApiResponse.serializer())
            val again = apply(resp, since)
            if (!again && !resp.more) break
        }
    }

    /** Aplica una respuesta de cambios pedida "desde" [since]. Devuelve true si hay que volver a pedir desde el inicio. */
    private suspend fun apply(resp: ApiResponse, since: Long): Boolean = pullLock.withLock {
        val epoch = repo.metaGet("epoch")
        if (resp.epoch.isNotBlank() && epoch != resp.epoch) {
            // Los datos de la nube son otros (se subieron datos o se restauró un respaldo): se copian desde cero.
            withContext(repo.platform.io) { repo.clearSharedData() }
            repo.metaPut("epoch", resp.epoch)
            lastSeq = 0
            if (since > 0) return@withLock true
        }
        if (resp.changes.isEmpty()) {
            if (resp.last > lastSeq) lastSeq = resp.last
            return@withLock false
        }
        withContext(repo.platform.io) { repo.applyRemoteChanges(resp.changes.map { Triple(it.t, it.k, it.row) }) }
        lastSeq = resp.last
        fetchMissingImages(resp.changes)
        false
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
        for (n in names) runCatching {
            val token = ensureDeviceToken()
            val bytes = withContext(repo.platform.io) { http(baseUrl, "GET", "/v1/fotos/" + enc(File(n).name), null, null, token, null, 30_000) }
            File(repo.imagesDir, File(n).name).writeBytes(bytes)
        }
        if (names.isNotEmpty()) repo.driver.notifyListeners("product", "setting", "repairOrder")
    }

    private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull

    // ---------- RemoteGateway ----------

    override suspend fun execute(op: Op, opId: String): JsonElement {
        val body = json.encodeToString(OpRequest.serializer(), OpRequest(opId, op))
        val resp = try {
            try {
                json.decodeFromString(ApiResponse.serializer(), send("POST", "/v1/operaciones", body))
            } catch (e: HttpStatusException) {
                if (e.code == 401 && e.message?.contains("PIN") == true && renewUser())
                    json.decodeFromString(ApiResponse.serializer(), send("POST", "/v1/operaciones", body))
                else throw e
            }
        } catch (e: HttpStatusException) {
            when {
                e.code == 401 && e.message?.contains("PIN") == true -> { userToken = null; _needPin.tryEmit(e.message ?: "") }
                e.code == 401 -> _status.value = NetStatus(LinkState.REVOKED, e.message ?: "Este equipo ya no tiene acceso")
            }
            throw e
        } catch (e: IOException) {
            _status.value = NetStatus(LinkState.OFFLINE, "Sin conexión con la nube", _status.value.lastSync)
            throw OfflineException("Sin conexión con la nube. Revisa el internet e intenta de nuevo.")
        }
        runCatching { pull() }
        return resp.result
    }

    override suspend fun uploadImage(name: String, file: File) {
        try {
            val token = ensureDeviceToken()
            withContext(repo.platform.io) { http(baseUrl, "PUT", "/v1/fotos/" + enc(name), file.readBytes(), "image/jpeg", token, null, 60_000) }
        } catch (e: HttpStatusException) {
            throw e
        } catch (e: IOException) {
            throw OfflineException("Sin conexión con la nube: no se pudo enviar la foto.")
        }
    }

    /** Envía las ventas hechas sin conexión, en orden. */
    suspend fun flushOutbox() = flushLock.withLock {
        for (row in withContext(repo.platform.io) { repo.outbox() }) {
            val op = Op.json.decodeFromString(Op.serializer(), row.payload)
            send("POST", "/v1/operaciones", json.encodeToString(OpRequest.serializer(), OpRequest(row.opId, op)))
            withContext(repo.platform.io) { repo.outboxDone(row.opId, row.provisionalSaleId) }
        }
    }

    // ---------- Equipos ----------

    suspend fun devices(): List<Device> = get("/v1/equipos", ListSerializer(Device.serializer()))

    suspend fun revokeDevice(id: String) { send("POST", "/v1/equipos/${enc(id)}/quitar", "{}") }

    companion object {
        val json = Json(Op.json) { explicitNulls = false }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /**
         * Una llamada HTTP(S). Los errores del servidor llegan como [HttpStatusException] con su mensaje en español;
         * sin red, como IOException.
         */
        fun http(base: String, method: String, path: String, body: ByteArray?, contentType: String?, token: String?, user: String?, timeoutMs: Int): ByteArray {
            val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 10_000
                c.readTimeout = timeoutMs
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("User-Agent", "VitrinaPOS/${AppInfo.VERSION}")
                token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                user?.let { c.setRequestProperty("X-Usuario", it) }
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", contentType ?: "application/json")
                    // En "streaming" Java pierde el mensaje de las respuestas 401: solo se usa para archivos grandes.
                    if (body.size > 4_000_000) c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                if (code in 200..299) return c.inputStream.use { it.readBytes() }
                val err = runCatching { c.errorStream?.use { String(it.readBytes()) } }.getOrNull().orEmpty()
                val msg = runCatching { json.decodeFromString(ErrorBody.serializer(), err).error }.getOrNull() ?: "La nube respondió $code"
                throw HttpStatusException(code, msg)
            } catch (e: SocketTimeoutException) {
                throw IOException("La nube no responde")
            } finally {
                c.disconnect()
            }
        }

        private fun <T> post(base: String, path: String, body: String, s: KSerializer<T>, token: String? = null): T =
            json.decodeFromString(s, String(http(base, "POST", path, body.toByteArray(), "application/json", token, null, 30_000)))

        // ---------- Sin sesión (crear cuenta, entrar, contraseña) ----------

        fun signup(base: String, r: SignupRequest): DeviceCredentials =
            post(base, "/v1/cuentas", json.encodeToString(SignupRequest.serializer(), r), DeviceCredentials.serializer())

        fun login(base: String, r: LoginRequest): DeviceCredentials =
            post(base, "/v1/sesion/equipo", json.encodeToString(LoginRequest.serializer(), r), DeviceCredentials.serializer())

        fun ownerLogin(base: String, r: OwnerLoginRequest): OwnerTokenResponse =
            post(base, "/v1/sesion/dueno", json.encodeToString(OwnerLoginRequest.serializer(), r), OwnerTokenResponse.serializer())

        fun forgotPassword(base: String, email: String): Message =
            post(base, "/v1/contrasena/olvide", json.encodeToString(ForgotRequest.serializer(), ForgotRequest(email)), Message.serializer())

        fun resetPassword(base: String, r: ResetRequest): Message =
            post(base, "/v1/contrasena/restablecer", json.encodeToString(ResetRequest.serializer(), r), Message.serializer())

        fun changePassword(base: String, r: ChangePasswordRequest): Message =
            post(base, "/v1/contrasena/cambiar", json.encodeToString(ChangePasswordRequest.serializer(), r), Message.serializer())

        /** Sube la base de un equipo a un negocio recién creado ("Pasar mis datos a la nube"). */
        fun uploadDatabase(base: String, ownerToken: String, file: File): Message =
            json.decodeFromString(Message.serializer(), String(http(base, "POST", "/v1/migrar", file.readBytes(), "application/octet-stream", ownerToken, null, 300_000)))

        /** Sube una foto con la ficha de un equipo recién autorizado (durante "Pasar mis datos a la nube"). */
        fun uploadImageWith(base: String, deviceToken: String, file: File) {
            http(base, "PUT", "/v1/fotos/" + enc(file.name), file.readBytes(), "image/jpeg", deviceToken, null, 60_000)
        }

        fun deviceToken(base: String, creds: DeviceCredentials): TokenResponse =
            post(base, "/v1/sesion/ficha", json.encodeToString(TokenRequest.serializer(), TokenRequest(creds.deviceId, creds.deviceSecret)), TokenResponse.serializer())
    }
}
