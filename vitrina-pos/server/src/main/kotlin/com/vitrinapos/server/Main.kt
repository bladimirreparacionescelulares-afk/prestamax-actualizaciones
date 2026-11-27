package com.vitrinapos.server

import com.vitrinapos.AppInfo
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Arranca el servidor. Todo se configura con variables de entorno (ver server/.env.ejemplo):
 * DATABASE_URL, DATA_DIR, TOKEN_SECRET, MASTER_KEY, ADMIN_TOKEN, PORT, HOST, RESEND_API_KEY, MAIL_FROM.
 */
fun main() {
    val log = LoggerFactory.getLogger("vitrina")
    fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
    fun required(k: String) = env(k) ?: error("Falta la variable de entorno $k")
    fun key(k: String): ByteArray = Base64.getDecoder().decode(required(k)).also { require(it.size >= 32) { "$k debe tener 32 bytes en base64" } }

    val config = Config(
        databaseUrl = required("DATABASE_URL"),
        dataDir = File(env("DATA_DIR") ?: "/datos"),
        tokenSecret = key("TOKEN_SECRET"),
        masterKey = key("MASTER_KEY").copyOf(32),
        adminToken = env("ADMIN_TOKEN") ?: "",
        trialPlan = env("TRIAL_PLAN") ?: "pro",
        trialDays = env("TRIAL_DAYS")?.toIntOrNull() ?: 14,
    )
    val control = ControlStore(config.databaseUrl)
    val tenants = Tenants(config.dataDir, config.masterKey)
    val mailer: Mailer = env("RESEND_API_KEY")?.let { ResendMailer(it, env("MAIL_FROM") ?: "Vitrina POS <no-responder@facturaprord.online>") }
        ?: LogMailer().also { log.warn("Sin RESEND_API_KEY: los correos no se envían (solo se anotan)") }
    val server = VitrinaServer(config, control, tenants, mailer)

    // Respaldo de todos los negocios cada noche a las 3:00 a. m. (hora de República Dominicana).
    val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "respaldos").apply { isDaemon = true } }
    val now = ZonedDateTime.now(Tenants.ZONE)
    var next = now.with(LocalTime.of(3, 0))
    if (!next.isAfter(now)) next = next.plusDays(1)
    scheduler.scheduleAtFixedRate({
        runCatching { tenants.backupAll(control.allBusinesses().map { it.id }) }.onFailure { log.error("Falló el respaldo nocturno", it) }
    }, java.time.Duration.between(now, next).toMinutes(), 24 * 60, TimeUnit.MINUTES)

    val port = env("PORT")?.toIntOrNull() ?: 8080
    log.info("Vitrina POS servidor {} en el puerto {}", AppInfo.VERSION, port)
    Runtime.getRuntime().addShutdownHook(Thread { tenants.close(); control.close() })
    embeddedServer(Netty, port = port, host = env("HOST") ?: "0.0.0.0") { vitrinaApi(server) }.start(wait = true)
}
