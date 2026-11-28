package com.vitrinapos.server

import com.vitrinapos.net.*
import com.vitrinapos.AppInfo
import com.vitrinapos.data.AppSettings
import com.vitrinapos.data.BusinessType
import com.vitrinapos.data.Op
import com.vitrinapos.data.Permission
import com.vitrinapos.data.Repository
import com.vitrinapos.data.Roles
import com.vitrinapos.data.RuleException
import com.vitrinapos.db.AppUser
import com.vitrinapos.net.ApiResponse
import com.vitrinapos.net.Change
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// ================= Configuración =================

data class Config(
    val databaseUrl: String,
    val dataDir: File,
    /** Firma de las sesiones (32 bytes o más). */
    val tokenSecret: ByteArray,
    /** Llave maestra (32 bytes) para cifrar el certificado de facturación de cada negocio. */
    val masterKey: ByteArray,
    /** Ficha del panel administrativo (solo el dueño de Vitrina POS). Vacía = panel apagado. */
    val adminToken: String,
    val trialPlan: String = "pro",
    val trialDays: Int = 14,
)

class ApiError(val status: HttpStatusCode, message: String) : RuntimeException(message)

// ================= Pedidos y respuestas =================

@Serializable data class SignupRequest(
    val email: String, val password: String, val name: String, val businessName: String, val businessType: String = "",
    val ownerPin: String, val deviceName: String = "", val platform: String = "",
)
@Serializable data class LoginRequest(val email: String, val password: String, val deviceName: String, val platform: String = "", val businessId: Long? = null)
@Serializable data class DeviceCredentials(val deviceId: String, val deviceSecret: String, val businessId: Long, val businessName: String, val mustChangePassword: Boolean = false)
@Serializable data class TokenRequest(val deviceId: String, val deviceSecret: String)
@Serializable data class TokenResponse(val token: String, val expires: Long, val epoch: String, val entitlements: Entitlements, val deviceName: String)
@Serializable data class UserLoginRequest(val userId: Long, val pin: String)
@Serializable data class UserTokenResponse(val token: String, val expires: Long, val userName: String, val role: String)
@Serializable data class OwnerLoginRequest(val email: String, val password: String, val businessId: Long? = null)
@Serializable data class OwnerTokenResponse(val token: String, val expires: Long, val businessId: Long, val mustChangePassword: Boolean)
@Serializable data class OpRequest(val opId: String, val op: Op)
@Serializable data class ForgotRequest(val email: String)
@Serializable data class ResetRequest(val email: String, val code: String, val newPassword: String)
@Serializable data class ChangePasswordRequest(val email: String, val oldPassword: String, val newPassword: String)
@Serializable data class Message(val ok: Boolean = true, val message: String = "")
@Serializable data class ErrorBody(val ok: Boolean = false, val error: String)
@Serializable data class RenameRequest(val name: String)

// Panel administrativo
@Serializable data class AdminBusiness(
    val id: Long, val name: String, val type: String, val accountId: Long, val email: String, val plan: String, val state: String,
    val trialUntil: Long?, val paidUntil: Long?, val devices: Int, val extras: List<String>, val suspended: Boolean,
)
@Serializable data class PlanChange(val plan: String)
@Serializable data class PaidUntil(val until: Long)
@Serializable data class TrialDays(val days: Int)
@Serializable data class Suspend(val suspended: Boolean)
@Serializable data class ModuleChange(val module: String, val add: Boolean, val monthly: Long = 0)
@Serializable data class PlanPrices(val monthly: Long, val yearly: Long)
@Serializable data class TempPassword(val email: String, val temporaryPassword: String)
@Serializable data class AdminLogEntry(val action: String, val detail: String, val at: Long)

// ================= Servidor =================

class VitrinaServer(val config: Config, val control: ControlStore, val tenants: Tenants, val mailer: Mailer) {
    private val log = LoggerFactory.getLogger("vitrina")

    fun entitlements(businessId: Long): Entitlements {
        val biz = control.business(businessId) ?: throw ApiError(HttpStatusCode.NotFound, "El negocio no existe")
        val sub = control.subscription(businessId)
        val plan = sub?.let { control.plan(it.planId) }
        val state = Rules.state(sub, biz.suspended)
        val modules = ((plan?.modules ?: listOf(Modules.SALES)) + control.extraModules(businessId)).distinct().sorted()
        val until = sub?.let { maxOf(it.paidUntil ?: 0, it.trialUntil ?: 0) }?.takeIf { it > 0 }
        return Entitlements(plan?.id ?: "", plan?.name ?: "", state.name, until, modules, plan?.maxDevices ?: 1, plan?.maxUsers ?: 1)
    }

    fun epoch(repo: Repository): String = repo.metaGet("epoch") ?: Secure.token(9).also { repo.metaPut("epoch", it) }

    /** Crea la cuenta, el negocio (con su base, sus módulos y el administrador) y, si se pide, autoriza el equipo. */
    suspend fun signup(r: SignupRequest): DeviceCredentials? {
        val email = r.email.trim().lowercase()
        if (!EMAIL.matches(email)) throw ApiError(HttpStatusCode.BadRequest, "Escribe un correo válido")
        Secure.passwordProblem(r.password)?.let { throw ApiError(HttpStatusCode.BadRequest, it) }
        if (r.name.isBlank()) throw ApiError(HttpStatusCode.BadRequest, "Escribe tu nombre")
        if (r.businessName.isBlank()) throw ApiError(HttpStatusCode.BadRequest, "Escribe el nombre del negocio")
        if (!r.ownerPin.matches(Regex("\\d{4,6}"))) throw ApiError(HttpStatusCode.BadRequest, "El PIN lleva de 4 a 6 números")
        if (control.accountByEmail(email) != null) throw ApiError(HttpStatusCode.Conflict, "Ya hay una cuenta con ese correo. Entra o recupera la contraseña.")
        val type = BusinessType.of(r.businessType)
        val (accountId, businessId) = control.createAccount(email, Secure.hashPassword(r.password), r.name, r.businessName, type?.name ?: "", config.trialPlan, config.trialDays)
        seedBusiness(businessId, r.businessName, type, r.name, r.ownerPin)
        log.info("Cuenta nueva {} · negocio {}", accountId, businessId)
        return if (r.deviceName.isNotBlank()) registerDevice(businessId, r.deviceName, r.platform, false) else null
    }

    /** Prepara la base de un negocio nuevo: módulos según el tipo, datos básicos y el administrador con su PIN. */
    private suspend fun seedBusiness(businessId: Long, businessName: String, type: BusinessType?, ownerName: String, ownerPin: String) {
        val repo = tenants.repo(businessId)
        val base = AppSettings(businessName = businessName.trim(), currencySymbol = "RD$", onboarded = true)
        repo.saveSettings(type?.apply(base) ?: base)
        repo.saveUser(null, ownerName.trim(), ownerPin, Roles.ADMIN, true)
    }

    /** Los negocios de la cuenta, para elegir con cuál trabajar. */
    fun businessChoices(acc: Account): List<BusinessChoice> = control.businessesOf(acc.id).map { b ->
        val e = entitlements(b.id)
        BusinessChoice(b.id, b.name, e.planName, e.state, control.activeDeviceCount(b.id))
    }

    /** Otro negocio con la misma cuenta; autoriza este equipo en él. */
    suspend fun newBusiness(r: NewBusinessRequest): DeviceCredentials {
        val acc = checkOwner(r.email, r.password)
        if (r.businessName.isBlank()) throw ApiError(HttpStatusCode.BadRequest, "Escribe el nombre del negocio")
        if (r.ownerName.isBlank()) throw ApiError(HttpStatusCode.BadRequest, "Escribe tu nombre")
        if (!r.ownerPin.matches(Regex("\\d{4,6}"))) throw ApiError(HttpStatusCode.BadRequest, "El PIN lleva de 4 a 6 números")
        if (control.businessesOf(acc.id).size >= MAX_BUSINESSES) throw ApiError(HttpStatusCode.Forbidden, "Esta cuenta ya tiene $MAX_BUSINESSES negocios")
        val type = BusinessType.of(r.businessType)
        val id = control.createBusiness(acc.id, r.businessName, type?.name ?: "", config.trialPlan, config.trialDays)
        seedBusiness(id, r.businessName, type, r.ownerName, r.ownerPin)
        log.info("Negocio nuevo {} para la cuenta {}", id, acc.id)
        return registerDevice(id, r.deviceName, r.platform, acc.mustChange)
    }

    fun registerDevice(businessId: Long, name: String, platform: String, mustChange: Boolean): DeviceCredentials {
        val ent = entitlements(businessId)
        if (control.activeDeviceCount(businessId) >= ent.maxDevices)
            throw ApiError(HttpStatusCode.Forbidden, "Tu plan permite ${ent.maxDevices} ${if (ent.maxDevices == 1) "equipo" else "equipos"}. Quita uno en Más → Equipos o cambia de plan.")
        val id = "eq_" + Secure.token(9)
        val secret = Secure.token(32)
        control.addDevice(id, businessId, name.trim().ifBlank { "Equipo" }, platform, Secure.sha256(secret))
        return DeviceCredentials(id, secret, businessId, control.business(businessId)!!.name, mustChange)
    }

    /** Correo y contraseña del dueño, con bloqueo por intentos. */
    fun checkOwner(email: String, password: String): Account {
        val (acc, hash) = control.accountByEmail(email) ?: run {
            Secure.checkPassword(DUMMY_HASH, password) // mismo tiempo de respuesta aunque no exista
            throw ApiError(HttpStatusCode.Unauthorized, "Correo o contraseña incorrectos")
        }
        control.lockedUntil(acc.id)?.let { throw ApiError(HttpStatusCode.Locked, "Demasiados intentos. Vuelve a intentar en 15 minutos o recupera la contraseña.") }
        if (!Secure.checkPassword(hash, password)) { control.loginFailed(acc.id); throw ApiError(HttpStatusCode.Unauthorized, "Correo o contraseña incorrectos") }
        control.loginOk(acc.id)
        return acc
    }

    fun businessFor(acc: Account, requested: Long?): Business {
        val list = control.businessesOf(acc.id)
        return (requested?.let { id -> list.firstOrNull { it.id == id } } ?: list.firstOrNull())
            ?: throw ApiError(HttpStatusCode.NotFound, "Esta cuenta no tiene negocios")
    }

    companion object {
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")
        private val DUMMY_HASH = Secure.hashPassword("no-existe-123")
        const val DEVICE_TTL = 60 * 60_000L
        const val USER_TTL = 8 * 60 * 60_000L
        const val OWNER_TTL = 30 * 60_000L
        const val MAX_BUSINESSES = 10
    }
}

/** Límite simple de pedidos por minuto (inicio de sesión, recuperar contraseña). */
class RateLimiter(private val perMinute: Int) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()
    fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val q = hits.getOrPut(key) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
            if (q.size >= perMinute) return false
            q.addLast(now)
            return true
        }
    }
}

private fun ApplicationCall.ip(): String = request.header("X-Forwarded-For")?.substringBefore(',')?.trim() ?: request.origin.remoteHost

private fun ApplicationCall.bearer(): String? = request.header("Authorization")?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() }

/** Equipo autorizado que hace el pedido. */
private data class DeviceCtx(val claims: Claims, val device: Device)

private fun VitrinaServer.device(call: ApplicationCall): DeviceCtx {
    val c = call.bearer()?.let { Secure.verify(config.tokenSecret, it) }?.takeIf { it.kind == "eq" }
        ?: throw ApiError(HttpStatusCode.Unauthorized, "La sesión del equipo venció: vuelve a conectarte")
    val d = control.device(c.device)?.takeIf { !it.revoked && it.businessId == c.business }
        ?: throw ApiError(HttpStatusCode.Unauthorized, "Este equipo ya no tiene acceso")
    return DeviceCtx(c, d)
}

/** Usuario que entró con su PIN en ese equipo (null si el negocio no tiene usuarios). */
private fun VitrinaServer.user(call: ApplicationCall, ctx: DeviceCtx, repo: Repository): AppUser? {
    val token = call.request.header("X-Usuario")
    val c = token?.let { Secure.verify(config.tokenSecret, it) }?.takeIf { it.kind == "us" && it.device == ctx.device.id && it.business == ctx.claims.business }
    if (c != null) return repo.db.appUserQueries.selectById(c.user).executeAsOneOrNull()?.takeIf { it.active == 1L }
        ?: throw ApiError(HttpStatusCode.Unauthorized, "Tu usuario ya no está activo")
    return null
}

/** Quien puede administrar el negocio: el dueño con su correo o un usuario con permiso de Usuarios. */
private fun VitrinaServer.manager(call: ApplicationCall): Long {
    val c = call.bearer()?.let { Secure.verify(config.tokenSecret, it) } ?: throw ApiError(HttpStatusCode.Unauthorized, "Entra de nuevo")
    if (c.kind == "dueño") return c.business
    val ctx = device(call)
    val repo = tenants.repo(ctx.claims.business)
    val users = repo.db.appUserQueries.selectAll().executeAsList().filter { it.active == 1L }
    val u = user(call, ctx, repo)
    if (users.isEmpty() || (u != null && (Rules.can(u, Permission.USERS) || Rules.can(u, Permission.NETWORK)))) return ctx.claims.business
    throw ApiError(HttpStatusCode.Forbidden, "Solo el administrador maneja los equipos")
}

private fun VitrinaServer.requireAdmin(call: ApplicationCall) {
    val t = call.bearer()
    if (config.adminToken.isBlank() || t == null || !Secure.same(t, config.adminToken)) throw ApiError(HttpStatusCode.Unauthorized, "No autorizado")
}

private val IMAGE_NAME = Regex("[A-Za-z0-9_.-]{1,80}")

fun Application.vitrinaApi(s: VitrinaServer) {
    val log = LoggerFactory.getLogger("api")
    val loginLimit = RateLimiter(10)
    val mailLimit = RateLimiter(3)

    install(ContentNegotiation) { json(Json(Op.json) { explicitNulls = false }) }
    install(CallLogging) { level = Level.INFO; filter { !it.request.path().startsWith("/salud") } }
    install(StatusPages) {
        exception<ApiError> { call, e -> call.respond(e.status, ErrorBody(error = e.message ?: "Error")) }
        exception<RuleException> { call, e -> call.respond(HttpStatusCode.UnprocessableEntity, ErrorBody(error = e.message ?: "No se pudo")) }
        exception<IllegalStateException> { call, e -> call.respond(HttpStatusCode.UnprocessableEntity, ErrorBody(error = e.message ?: "No se pudo")) }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorBody(error = e.message ?: "Pedido no válido")) }
        exception<Throwable> { call, e ->
            log.error("Error en {}", call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody(error = "Error en el servidor. Inténtalo de nuevo."))
        }
    }

    routing {
        get("/salud") { call.respond(mapOf("ok" to "true", "version" to AppInfo.VERSION)) }

        route("/v1") {
            // ---------- Cuenta ----------
            post("/cuentas") {
                if (!loginLimit.allow("alta:" + call.ip())) throw ApiError(HttpStatusCode.TooManyRequests, "Demasiados intentos. Espera un minuto.")
                val creds = s.signup(call.receive())
                call.respond(HttpStatusCode.Created, creds ?: Message(message = "Cuenta creada"))
            }
            post("/sesion/equipo") {
                // El dueño autoriza este equipo con su correo y contraseña.
                val r = call.receive<LoginRequest>()
                if (!loginLimit.allow("login:" + call.ip())) throw ApiError(HttpStatusCode.TooManyRequests, "Demasiados intentos. Espera un minuto.")
                val acc = s.checkOwner(r.email, r.password)
                val biz = s.businessFor(acc, r.businessId)
                call.respond(s.registerDevice(biz.id, r.deviceName, r.platform, acc.mustChange))
            }
            post("/sesion/negocios") {
                // Los negocios de la cuenta, para que el equipo elija con cuál trabajar.
                val r = call.receive<AccountRequest>()
                if (!loginLimit.allow("login:" + call.ip())) throw ApiError(HttpStatusCode.TooManyRequests, "Demasiados intentos. Espera un minuto.")
                call.respond(s.businessChoices(s.checkOwner(r.email, r.password)))
            }
            post("/negocios") {
                // Otro negocio con la misma cuenta.
                if (!loginLimit.allow("alta:" + call.ip())) throw ApiError(HttpStatusCode.TooManyRequests, "Demasiados intentos. Espera un minuto.")
                call.respond(HttpStatusCode.Created, s.newBusiness(call.receive()))
            }
            post("/sesion/salir") {
                // "Cerrar sesión de la cuenta": el equipo se da de baja y deja de contar en el plan.
                val ctx = s.device(call)
                s.control.revokeDevice(ctx.claims.business, ctx.device.id)
                s.tenants.repo(ctx.claims.business).applyLocal(Op.LogEvent("Cerró la sesión de la cuenta en el equipo", ctx.device.name), "Dueño", ctx.device.name)
                call.respond(Message(message = "Sesión cerrada"))
            }
            post("/sesion/ficha") {
                // El equipo pide su ficha de 1 hora con su llave.
                val r = call.receive<TokenRequest>()
                val (d, hash) = s.control.deviceSecretHash(r.deviceId) ?: throw ApiError(HttpStatusCode.Unauthorized, "Este equipo no está autorizado")
                if (d.revoked || !Secure.same(Secure.sha256(r.deviceSecret), hash)) throw ApiError(HttpStatusCode.Unauthorized, "Este equipo ya no tiene acceso")
                s.control.touchDevice(d.id)
                val exp = System.currentTimeMillis() + VitrinaServer.DEVICE_TTL
                val repo = s.tenants.repo(d.businessId)
                call.respond(TokenResponse(Secure.sign(s.config.tokenSecret, Claims("eq", d.businessId, d.id, exp = exp)), exp, s.epoch(repo), s.entitlements(d.businessId), d.name))
            }
            post("/sesion/usuario") {
                // Un empleado entra con su PIN en un equipo autorizado.
                val ctx = s.device(call)
                val r = call.receive<UserLoginRequest>()
                val repo = s.tenants.repo(ctx.claims.business)
                val u = repo.db.appUserQueries.selectById(r.userId).executeAsOneOrNull()?.takeIf { it.active == 1L }
                    ?: throw ApiError(HttpStatusCode.NotFound, "Ese usuario no existe o está desactivado")
                when (val res = repo.pinAttempt(u, r.pin, true)) {
                    is Repository.PinResult.Ok -> {
                        val exp = System.currentTimeMillis() + VitrinaServer.USER_TTL
                        call.respond(UserTokenResponse(Secure.sign(s.config.tokenSecret, Claims("us", ctx.claims.business, ctx.device.id, user = u.id, exp = exp)), exp, u.name, u.role))
                    }
                    is Repository.PinResult.Wrong -> throw ApiError(HttpStatusCode.Unauthorized, "PIN incorrecto. Te quedan ${res.attemptsLeft} intentos.")
                    is Repository.PinResult.Locked -> throw ApiError(HttpStatusCode.Locked, "Demasiados intentos con este usuario. Espera unos minutos.")
                }
            }
            post("/sesion/dueno") {
                val r = call.receive<OwnerLoginRequest>()
                if (!loginLimit.allow("login:" + call.ip())) throw ApiError(HttpStatusCode.TooManyRequests, "Demasiados intentos. Espera un minuto.")
                val acc = s.checkOwner(r.email, r.password)
                val biz = s.businessFor(acc, r.businessId)
                val exp = System.currentTimeMillis() + VitrinaServer.OWNER_TTL
                call.respond(OwnerTokenResponse(Secure.sign(s.config.tokenSecret, Claims("dueño", biz.id, account = acc.id, exp = exp)), exp, biz.id, acc.mustChange))
            }

            // ---------- Contraseña ----------
            post("/contrasena/olvide") {
                val r = call.receive<ForgotRequest>()
                if (!mailLimit.allow("correo:" + call.ip()) || !mailLimit.allow("correo:" + r.email.trim().lowercase()))
                    throw ApiError(HttpStatusCode.TooManyRequests, "Ya pediste un código. Espera un minuto.")
                s.control.accountByEmail(r.email)?.first?.let { acc ->
                    val code = Secure.digits(6)
                    s.control.addResetCode(acc.id, Secure.sha256("${acc.id}:$code"), 30)
                    s.mailer.send(acc.email, "Tu código para Vitrina POS: $code",
                        "Hola ${acc.name}:\n\nTu código para cambiar la contraseña de Vitrina POS es $code. Vence en 30 minutos.\n\nSi no lo pediste, ignora este correo: tu contraseña no cambia.")
                }
                // La misma respuesta exista o no la cuenta.
                call.respond(Message(message = "Si el correo tiene una cuenta, te llegó un código de 6 números."))
            }
            post("/contrasena/restablecer") {
                val r = call.receive<ResetRequest>()
                if (!loginLimit.allow("codigo:" + call.ip())) throw ApiError(HttpStatusCode.TooManyRequests, "Demasiados intentos. Espera un minuto.")
                Secure.passwordProblem(r.newPassword)?.let { throw ApiError(HttpStatusCode.BadRequest, it) }
                val acc = s.control.accountByEmail(r.email)?.first
                if (acc == null || !s.control.useResetCode(acc.id, Secure.sha256("${acc.id}:${r.code.trim()}")))
                    throw ApiError(HttpStatusCode.BadRequest, "El código no es correcto o ya venció")
                s.control.setPassword(acc.id, Secure.hashPassword(r.newPassword), false)
                call.respond(Message(message = "Contraseña cambiada"))
            }
            post("/contrasena/cambiar") {
                val r = call.receive<ChangePasswordRequest>()
                if (!loginLimit.allow("login:" + call.ip())) throw ApiError(HttpStatusCode.TooManyRequests, "Demasiados intentos. Espera un minuto.")
                val acc = s.checkOwner(r.email, r.oldPassword)
                Secure.passwordProblem(r.newPassword)?.let { throw ApiError(HttpStatusCode.BadRequest, it) }
                s.control.setPassword(acc.id, Secure.hashPassword(r.newPassword), false)
                call.respond(Message(message = "Contraseña cambiada"))
            }

            // ---------- Plan ----------
            get("/plan") { val ctx = s.device(call); call.respond(s.entitlements(ctx.claims.business)) }

            // ---------- Operaciones y cambios ----------
            post("/operaciones") {
                val ctx = s.device(call)
                val r = call.receive<OpRequest>()
                val biz = ctx.claims.business
                val ent = s.entitlements(biz)
                val state = PlanState.valueOf(ent.state)
                if ((state == PlanState.READ_ONLY || state == PlanState.SUSPENDED) && !Rules.allowedReadOnly(r.op))
                    throw ApiError(HttpStatusCode.PaymentRequired,
                        if (state == PlanState.SUSPENDED) "Este negocio está suspendido. Comunícate con Vitrina POS." else "El plan venció: puedes consultar y respaldar, pero no registrar. Renueva para seguir vendiendo.")
                val repo = s.tenants.repo(biz)
                var user = s.user(call, ctx, repo)
                val active = repo.db.appUserQueries.selectAll().executeAsList().filter { it.active == 1L }
                if (user == null && active.isNotEmpty()) {
                    // Ventas hechas sin conexión: el equipo autorizado las envía después con el usuario que vendió.
                    val op = r.op
                    user = if (op is Op.RecordSale && op.offline && op.userId != null) active.firstOrNull { it.id == op.userId } else null
                    if (user == null) throw ApiError(HttpStatusCode.Unauthorized, "Entra con tu PIN para continuar")
                }
                Rules.moduleFor(r.op)?.let { m -> if (m !in ent.modules) throw ApiError(HttpStatusCode.Forbidden, "Tu plan no incluye el módulo de $m") }
                val op = r.op
                if (op is Op.SaveUser && op.id == null && op.active && ent.maxUsers > 0 && active.size >= ent.maxUsers)
                    throw ApiError(HttpStatusCode.Forbidden, "Tu plan permite ${ent.maxUsers} usuarios activos")
                Rules.deny(op, user)?.let { throw ApiError(HttpStatusCode.Forbidden, it) }
                val previous = r.opId.takeIf { it.isNotBlank() }?.let { repo.appliedResult(it) }
                if (previous != null) {
                    call.respond(ApiResponse(result = Json.parseToJsonElement(previous), last = repo.maxSeq())); return@post
                }
                val result = repo.applyLocal(Rules.stamp(op, user, ctx.device.id), user?.name ?: "Dueño", ctx.device.name)
                if (r.opId.isNotBlank()) repo.saveAppliedResult(r.opId, result.toString())
                call.respond(ApiResponse(result = result, last = repo.maxSeq()))
            }
            get("/cambios") {
                val ctx = s.device(call)
                val repo = s.tenants.repo(ctx.claims.business)
                val since = call.request.queryParameters["desde"]?.toLongOrNull() ?: 0
                if (call.request.queryParameters["esperar"] == "1" && repo.maxSeq() <= since) {
                    withTimeoutOrNull(25_000) { repo.changeSignal.first { it > since } }
                }
                val limit = 400L
                val rows = repo.changesSince(since, limit)
                val list = rows.map { Change(it.tbl, it.rowKey, repo.readRow(it.tbl, it.rowKey)) }
                call.respond(ApiResponse(epoch = s.epoch(repo), last = rows.maxOfOrNull { it.seq ?: since } ?: since, more = rows.size.toLong() == limit, changes = list, result = JsonNull))
            }

            // ---------- Fotos ----------
            get("/fotos/{nombre}") {
                val ctx = s.device(call)
                val name = call.parameters["nombre"].orEmpty()
                if (!IMAGE_NAME.matches(name)) throw ApiError(HttpStatusCode.BadRequest, "Nombre no válido")
                val f = File(s.tenants.repo(ctx.claims.business).imagesDir, name)
                if (!f.exists()) throw ApiError(HttpStatusCode.NotFound, "No existe la foto")
                call.respondFile(f)
            }
            put("/fotos/{nombre}") {
                val ctx = s.device(call)
                val name = call.parameters["nombre"].orEmpty()
                if (!IMAGE_NAME.matches(name)) throw ApiError(HttpStatusCode.BadRequest, "Nombre no válido")
                val size = call.request.header("Content-Length")?.toLongOrNull() ?: throw ApiError(HttpStatusCode.LengthRequired, "Falta el tamaño de la foto")
                if (size > 8L * 1024 * 1024) throw ApiError(HttpStatusCode.PayloadTooLarge, "La foto pesa demasiado (máximo 8 MB)")
                val bytes = call.receiveChannel().toByteArray()
                if (bytes.size > 8 * 1024 * 1024) throw ApiError(HttpStatusCode.PayloadTooLarge, "La foto pesa demasiado (máximo 8 MB)")
                File(s.tenants.repo(ctx.claims.business).imagesDir, name).writeBytes(bytes)
                call.respond(Message(message = "Foto guardada"))
            }

            // ---------- Pasar los datos de un equipo a la nube ----------
            post("/migrar") {
                // Solo el dueño con su correo, y solo sobre un negocio recién creado (no pisa datos de la nube).
                val c = call.bearer()?.let { Secure.verify(s.config.tokenSecret, it) }?.takeIf { it.kind == "dueño" }
                    ?: throw ApiError(HttpStatusCode.Unauthorized, "Entra con tu correo y contraseña")
                if (!s.tenants.isFresh(c.business)) throw ApiError(HttpStatusCode.Conflict, "Este negocio ya tiene datos en la nube: no se pueden reemplazar")
                val size = call.request.header("Content-Length")?.toLongOrNull() ?: throw ApiError(HttpStatusCode.LengthRequired, "Falta el tamaño del archivo")
                if (size > 512L * 1024 * 1024) throw ApiError(HttpStatusCode.PayloadTooLarge, "La base es demasiado grande (máximo 512 MB)")
                s.tenants.replaceWith(c.business, call.receiveChannel().toByteArray())
                s.tenants.repo(c.business).applyLocal(Op.LogEvent("Pasó los datos de un equipo a la nube", ""), "Dueño", "Nube")
                call.respond(Message(message = "Datos subidos"))
            }

            // ---------- Equipos ----------
            get("/equipos") { call.respond(s.control.devices(s.manager(call))) }
            post("/equipos/{id}/quitar") {
                val biz = s.manager(call)
                if (s.control.revokeDevice(biz, call.parameters["id"].orEmpty()) == 0) throw ApiError(HttpStatusCode.NotFound, "Ese equipo no existe")
                s.tenants.repo(biz).applyLocal(Op.LogEvent("Quitó el acceso a un equipo", call.parameters["id"].orEmpty()), "Dueño", "Nube")
                call.respond(Message(message = "El equipo ya no tiene acceso"))
            }
            post("/equipos/{id}/nombre") {
                val biz = s.manager(call)
                s.control.renameDevice(biz, call.parameters["id"].orEmpty(), call.receive<RenameRequest>().name)
                call.respond(Message(message = "Nombre cambiado"))
            }
        }

        // ================= Panel administrativo (dueño de Vitrina POS) =================
        route("/admin/v1") {
            get("/negocios") {
                s.requireAdmin(call)
                val out = s.control.allBusinesses().map { b ->
                    val e = s.entitlements(b.id)
                    val sub = s.control.subscription(b.id)
                    AdminBusiness(b.id, b.name, b.type, b.accountId, s.control.account(b.accountId)?.email ?: "", e.plan, e.state,
                        sub?.trialUntil, sub?.paidUntil, s.control.activeDeviceCount(b.id), s.control.extraModules(b.id), b.suspended)
                }
                call.respond(out)
            }
            post("/negocios/{id}/plan") {
                s.requireAdmin(call)
                val id = call.parameters["id"]!!.toLong(); val r = call.receive<PlanChange>()
                if (s.control.plan(r.plan) == null) throw ApiError(HttpStatusCode.BadRequest, "Ese plan no existe")
                s.control.setPlan(id, r.plan); s.control.adminLog("Cambió el plan", "negocio $id → ${r.plan}")
                call.respond(s.entitlements(id))
            }
            post("/negocios/{id}/pagado") {
                s.requireAdmin(call)
                val id = call.parameters["id"]!!.toLong(); val r = call.receive<PaidUntil>()
                s.control.setPaidUntil(id, r.until); s.control.adminLog("Registró un pago", "negocio $id hasta ${r.until}")
                call.respond(s.entitlements(id))
            }
            post("/negocios/{id}/prueba") {
                s.requireAdmin(call)
                val id = call.parameters["id"]!!.toLong(); val r = call.receive<TrialDays>()
                s.control.extendTrial(id, r.days.coerceIn(1, 365)); s.control.adminLog("Extendió la prueba", "negocio $id · ${r.days} días")
                call.respond(s.entitlements(id))
            }
            post("/negocios/{id}/suspender") {
                s.requireAdmin(call)
                val id = call.parameters["id"]!!.toLong(); val r = call.receive<Suspend>()
                s.control.setSuspended(id, r.suspended); s.control.adminLog(if (r.suspended) "Suspendió" else "Reactivó", "negocio $id")
                call.respond(s.entitlements(id))
            }
            post("/negocios/{id}/modulos") {
                s.requireAdmin(call)
                val id = call.parameters["id"]!!.toLong(); val r = call.receive<ModuleChange>()
                if (r.module !in Modules.all) throw ApiError(HttpStatusCode.BadRequest, "Ese módulo no existe")
                if (r.add) s.control.addExtraModule(id, r.module, r.monthly) else s.control.removeExtraModule(id, r.module)
                s.control.adminLog(if (r.add) "Agregó un módulo" else "Quitó un módulo", "negocio $id · ${r.module}")
                call.respond(s.entitlements(id))
            }
            get("/negocios/{id}/equipos") { s.requireAdmin(call); call.respond(s.control.devices(call.parameters["id"]!!.toLong())) }
            post("/negocios/{id}/equipos/{eq}/quitar") {
                s.requireAdmin(call)
                val id = call.parameters["id"]!!.toLong()
                s.control.revokeDevice(id, call.parameters["eq"]!!); s.control.adminLog("Quitó un equipo", "negocio $id · ${call.parameters["eq"]}")
                call.respond(Message(message = "El equipo ya no tiene acceso"))
            }
            get("/negocios/{id}/respaldo") {
                s.requireAdmin(call)
                val id = call.parameters["id"]!!.toLong()
                val f = s.tenants.backup(id)
                s.control.adminLog("Descargó un respaldo", "negocio $id")
                call.response.headers.append("Content-Disposition", "attachment; filename=\"negocio-$id-${f.nameWithoutExtension}.db\"")
                call.respondBytes(f.readBytes(), ContentType.Application.OctetStream)
            }
            post("/cuentas/{id}/restablecer") {
                s.requireAdmin(call)
                val id = call.parameters["id"]!!.toLong()
                val acc = s.control.account(id) ?: throw ApiError(HttpStatusCode.NotFound, "Esa cuenta no existe")
                val temp = "Vp" + Secure.digits(4) + Secure.token(6).filter { it.isLetterOrDigit() }.take(6) + "x"
                s.control.setPassword(id, Secure.hashPassword(temp), mustChange = true)
                s.control.adminLog("Restableció una contraseña", acc.email)
                call.respond(TempPassword(acc.email, temp))
            }
            get("/planes") { s.requireAdmin(call); call.respond(s.control.plans()) }
            post("/planes/{id}/precios") {
                s.requireAdmin(call)
                val id = call.parameters["id"]!!; val r = call.receive<PlanPrices>()
                if (s.control.setPlanPrices(id, r.monthly, r.yearly) == 0) throw ApiError(HttpStatusCode.NotFound, "Ese plan no existe")
                s.control.adminLog("Cambió precios", "$id: ${r.monthly}/${r.yearly}")
                call.respond(s.control.plans())
            }
            get("/actividad") {
                s.requireAdmin(call)
                call.respond(s.control.adminActivity().map { AdminLogEntry(it.first, it.second, it.third) })
            }
        }
    }
}
