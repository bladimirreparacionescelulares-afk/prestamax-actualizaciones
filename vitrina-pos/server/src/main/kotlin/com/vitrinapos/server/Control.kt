package com.vitrinapos.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Serializable
data class PlanInfo(val id: String, val name: String, val monthly: Long, val yearly: Long, val maxDevices: Int, val maxUsers: Int, val modules: List<String>)

@Serializable
data class Account(val id: Long, val email: String, val name: String, val mustChange: Boolean)

@Serializable
data class Business(val id: Long, val accountId: Long, val name: String, val type: String, val suspended: Boolean)

@Serializable
data class Subscription(val planId: String, val trialUntil: Long?, val paidUntil: Long?, val period: String)

@Serializable
data class Device(val id: String, val businessId: Long, val name: String, val platform: String, val created: Long, val lastUse: Long?, val revoked: Boolean)

/** Base de control (PostgreSQL): cuentas, negocios, planes, suscripciones y equipos. */
class ControlStore(jdbcUrl: String) : AutoCloseable {
    private val ds = HikariDataSource(HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        maximumPoolSize = 8
        poolName = "control"
    })

    init { migrate() }

    override fun close() = ds.close()

    fun <T> tx(block: (Connection) -> T): T = ds.connection.use { c ->
        c.autoCommit = false
        try { block(c).also { c.commit() } } catch (e: Throwable) { c.rollback(); throw e }
    }

    private fun <T> q(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = ds.connection.use { c -> c.q(sql, *args, map = map) }
    private fun u(sql: String, vararg args: Any?): Int = ds.connection.use { c -> c.u(sql, *args) }

    // ---------- Migraciones ----------

    private fun migrate() = tx { c ->
        c.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS esquema (version INT PRIMARY KEY, aplicado TIMESTAMPTZ NOT NULL DEFAULT now())") }
        // Una sola instancia aplica las migraciones a la vez.
        c.createStatement().use { it.execute("LOCK TABLE esquema IN EXCLUSIVE MODE") }
        val done = c.q("SELECT version FROM esquema") { it.getInt(1) }.toSet()
        for ((version, file) in MIGRATIONS) {
            if (version in done) continue
            val sql = javaClass.getResource("/db/$file")!!.readText()
            c.createStatement().use { it.execute(sql) }
            c.u("INSERT INTO esquema(version) VALUES (?)", version)
        }
    }

    // ---------- Planes ----------

    fun plans(): List<PlanInfo> = q("SELECT id, nombre, precio_mensual, precio_anual, max_equipos, max_usuarios FROM plan WHERE activo ORDER BY orden") {
        PlanInfo(it.getString(1), it.getString(2), it.getLong(3), it.getLong(4), it.getInt(5), it.getInt(6), emptyList())
    }.map { p -> p.copy(modules = q("SELECT modulo FROM plan_modulo WHERE plan_id = ? ORDER BY modulo", p.id) { it.getString(1) }) }

    fun plan(id: String): PlanInfo? = plans().firstOrNull { it.id == id }

    fun setPlanPrices(id: String, monthly: Long, yearly: Long) = u("UPDATE plan SET precio_mensual = ?, precio_anual = ? WHERE id = ?", monthly, yearly, id)

    // ---------- Cuentas ----------

    fun accountByEmail(email: String): Pair<Account, String>? =
        q("SELECT id, correo, nombre, debe_cambiar, hash_contrasena FROM cuenta WHERE correo = ?", email.trim().lowercase()) {
            Account(it.getLong(1), it.getString(2), it.getString(3), it.getBoolean(4)) to it.getString(5)
        }.firstOrNull()

    fun account(id: Long): Account? = q("SELECT id, correo, nombre, debe_cambiar FROM cuenta WHERE id = ?", id) {
        Account(it.getLong(1), it.getString(2), it.getString(3), it.getBoolean(4))
    }.firstOrNull()

    /** Bloqueo por intentos fallidos: hasta cuándo (null = no está bloqueada). */
    fun lockedUntil(accountId: Long): Instant? =
        q("SELECT bloqueada_hasta FROM cuenta WHERE id = ?", accountId) { it.getTimestamp(1)?.toInstant() }.firstOrNull()?.takeIf { it.isAfter(Instant.now()) }

    fun loginFailed(accountId: Long) {
        // Cada 5 fallos seguidos: 15 minutos sin poder entrar.
        u("""UPDATE cuenta SET intentos_fallidos = intentos_fallidos + 1,
             bloqueada_hasta = CASE WHEN (intentos_fallidos + 1) % 5 = 0 THEN now() + interval '15 minutes' ELSE bloqueada_hasta END WHERE id = ?""", accountId)
    }

    fun loginOk(accountId: Long) = u("UPDATE cuenta SET intentos_fallidos = 0, bloqueada_hasta = NULL WHERE id = ?", accountId)

    fun setPassword(accountId: Long, hash: String, mustChange: Boolean) =
        u("UPDATE cuenta SET hash_contrasena = ?, debe_cambiar = ?, intentos_fallidos = 0, bloqueada_hasta = NULL WHERE id = ?", hash, mustChange, accountId)

    /** Crea la cuenta, el negocio y la prueba gratis. Devuelve (cuenta, negocio). */
    fun createAccount(email: String, passwordHash: String, name: String, businessName: String, type: String, trialPlan: String, trialDays: Int): Pair<Long, Long> = tx { c ->
        val acc = c.q("INSERT INTO cuenta(correo, hash_contrasena, nombre) VALUES (?, ?, ?) RETURNING id", email.trim().lowercase(), passwordHash, name.trim()) { it.getLong(1) }.single()
        val biz = c.q("INSERT INTO negocio(cuenta_id, nombre, tipo) VALUES (?, ?, ?) RETURNING id", acc, businessName.trim(), type) { it.getLong(1) }.single()
        c.u("INSERT INTO suscripcion(negocio_id, plan_id, prueba_hasta) VALUES (?, ?, now() + make_interval(days => ?))", biz, trialPlan, trialDays)
        acc to biz
    }

    // ---------- Negocios y suscripciones ----------

    fun businessesOf(accountId: Long): List<Business> = q("SELECT id, cuenta_id, nombre, tipo, suspendido FROM negocio WHERE cuenta_id = ? ORDER BY id", accountId, map = ::business)
    fun business(id: Long): Business? = q("SELECT id, cuenta_id, nombre, tipo, suspendido FROM negocio WHERE id = ?", id, map = ::business).firstOrNull()
    fun allBusinesses(): List<Business> = q("SELECT id, cuenta_id, nombre, tipo, suspendido FROM negocio ORDER BY id", map = ::business)
    private fun business(r: ResultSet) = Business(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getBoolean(5))

    fun setSuspended(businessId: Long, suspended: Boolean) = u("UPDATE negocio SET suspendido = ? WHERE id = ?", suspended, businessId)

    fun subscription(businessId: Long): Subscription? =
        q("SELECT plan_id, prueba_hasta, pagado_hasta, periodo FROM suscripcion WHERE negocio_id = ?", businessId) {
            Subscription(it.getString(1), it.getTimestamp(2)?.time, it.getTimestamp(3)?.time, it.getString(4))
        }.firstOrNull()

    fun setPlan(businessId: Long, planId: String) = u("UPDATE suscripcion SET plan_id = ? WHERE negocio_id = ?", planId, businessId)
    fun setPaidUntil(businessId: Long, until: Long) = u("UPDATE suscripcion SET pagado_hasta = ? WHERE negocio_id = ?", Timestamp(until), businessId)
    fun extendTrial(businessId: Long, days: Int) =
        u("UPDATE suscripcion SET prueba_hasta = GREATEST(COALESCE(prueba_hasta, now()), now()) + make_interval(days => ?) WHERE negocio_id = ?", days, businessId)

    fun extraModules(businessId: Long): List<String> =
        q("SELECT modulo FROM modulo_extra WHERE negocio_id = ? AND (hasta IS NULL OR hasta > now()) ORDER BY modulo", businessId) { it.getString(1) }

    fun addExtraModule(businessId: Long, module: String, monthly: Long) =
        u("INSERT INTO modulo_extra(negocio_id, modulo, precio_mensual) VALUES (?, ?, ?) ON CONFLICT (negocio_id, modulo) DO UPDATE SET hasta = NULL, precio_mensual = EXCLUDED.precio_mensual", businessId, module, monthly)

    fun removeExtraModule(businessId: Long, module: String) = u("UPDATE modulo_extra SET hasta = now() WHERE negocio_id = ? AND modulo = ?", businessId, module)

    // ---------- Equipos ----------

    fun addDevice(id: String, businessId: Long, name: String, platform: String, secretHash: String) =
        u("INSERT INTO equipo(id, negocio_id, nombre, plataforma, secreto_hash) VALUES (?, ?, ?, ?, ?)", id, businessId, name.take(60), platform.take(30), secretHash)

    fun deviceSecretHash(id: String): Pair<Device, String>? =
        q("SELECT id, negocio_id, nombre, plataforma, creado, ultimo_uso, revocado, secreto_hash FROM equipo WHERE id = ?", id) { device(it) to it.getString(8) }.firstOrNull()

    fun device(id: String): Device? = deviceSecretHash(id)?.first

    fun devices(businessId: Long): List<Device> =
        q("SELECT id, negocio_id, nombre, plataforma, creado, ultimo_uso, revocado FROM equipo WHERE negocio_id = ? ORDER BY creado", businessId, map = ::device)

    fun activeDeviceCount(businessId: Long): Int = q("SELECT count(*) FROM equipo WHERE negocio_id = ? AND NOT revocado", businessId) { it.getInt(1) }.single()

    fun touchDevice(id: String) = u("UPDATE equipo SET ultimo_uso = now() WHERE id = ?", id)
    fun revokeDevice(businessId: Long, id: String) = u("UPDATE equipo SET revocado = TRUE WHERE id = ? AND negocio_id = ?", id, businessId)
    fun renameDevice(businessId: Long, id: String, name: String) = u("UPDATE equipo SET nombre = ? WHERE id = ? AND negocio_id = ?", name.take(60), id, businessId)

    private fun device(r: ResultSet) = Device(
        r.getString(1), r.getLong(2), r.getString(3), r.getString(4), r.getTimestamp(5).time, r.getTimestamp(6)?.time, r.getBoolean(7),
    )

    // ---------- Recuperar la contraseña ----------

    fun addResetCode(accountId: Long, codeHash: String, minutes: Int) = tx { c ->
        c.u("UPDATE restablecimiento SET usado = TRUE WHERE cuenta_id = ? AND NOT usado", accountId) // solo vale el último
        c.u("INSERT INTO restablecimiento(cuenta_id, codigo_hash, vence) VALUES (?, ?, now() + make_interval(mins => ?))", accountId, codeHash, minutes)
    }

    /** Comprueba el código (5 intentos como máximo). Devuelve true y lo marca usado si es correcto. */
    fun useResetCode(accountId: Long, codeHash: String): Boolean = tx { c ->
        val row = c.q("SELECT id, codigo_hash, intentos FROM restablecimiento WHERE cuenta_id = ? AND NOT usado AND vence > now() ORDER BY id DESC LIMIT 1 FOR UPDATE", accountId) {
            Triple(it.getLong(1), it.getString(2), it.getInt(3))
        }.firstOrNull() ?: return@tx false
        if (row.third >= 5) return@tx false
        if (!Secure.same(row.second, codeHash)) { c.u("UPDATE restablecimiento SET intentos = intentos + 1 WHERE id = ?", row.first); return@tx false }
        c.u("UPDATE restablecimiento SET usado = TRUE WHERE id = ?", row.first)
        true
    }

    // ---------- Panel administrativo ----------

    fun adminLog(action: String, detail: String) = u("INSERT INTO admin_actividad(accion, detalle) VALUES (?, ?)", action, detail.take(500))

    fun adminActivity(limit: Int = 200): List<Triple<String, String, Long>> =
        q("SELECT accion, detalle, creado FROM admin_actividad ORDER BY id DESC LIMIT ?", limit) { Triple(it.getString(1), it.getString(2), it.getTimestamp(3).time) }

    companion object {
        val MIGRATIONS = listOf(1 to "V1__inicial.sql")
    }
}

// ---------- Ayudas de JDBC ----------

private fun bindAll(st: java.sql.PreparedStatement, args: Array<out Any?>) {
    args.forEachIndexed { i, a ->
        when (a) {
            null -> st.setObject(i + 1, null)
            is Timestamp -> st.setTimestamp(i + 1, a)
            else -> st.setObject(i + 1, a)
        }
    }
}

internal fun <T> Connection.q(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        bindAll(st, args)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

internal fun Connection.u(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { st -> bindAll(st, args); st.executeUpdate() }
