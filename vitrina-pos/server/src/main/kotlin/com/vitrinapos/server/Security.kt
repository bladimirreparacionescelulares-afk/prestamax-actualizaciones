package com.vitrinapos.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Secure {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    fun token(n: Int = 32): String = b64.encodeToString(bytes(n))
    fun digits(n: Int) = (1..n).map { random.nextInt(10) }.joinToString("")
    fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Comparación en tiempo constante. */
    fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    // ---------- Contraseñas (PBKDF2-SHA256 con sal) ----------
    private const val ITER = 310_000

    fun hashPassword(pw: String): String {
        val salt = bytes(16)
        return "pbkdf2$$ITER$${b64.encodeToString(salt)}$${b64.encodeToString(pbkdf2(pw, salt, ITER))}"
    }

    fun checkPassword(stored: String, pw: String): Boolean {
        val p = stored.split('$')
        if (p.size != 4 || p[0] != "pbkdf2") return false
        val hash = pbkdf2(pw, b64d.decode(p[2]), p[1].toInt())
        return MessageDigest.isEqual(hash, b64d.decode(p[3]))
    }

    private fun pbkdf2(pw: String, salt: ByteArray, iter: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw.toCharArray(), salt, iter, 256)).encoded

    /** Reglas mínimas de una contraseña de cuenta. Devuelve el problema o null. */
    fun passwordProblem(pw: String): String? = when {
        pw.length < 8 -> "La contraseña lleva al menos 8 caracteres"
        pw.length > 128 -> "La contraseña es demasiado larga"
        pw.all { it.isDigit() } || pw.all { it.isLetter() } -> "Usa letras y números"
        else -> null
    }

    // ---------- Fichas firmadas (sesiones) ----------
    private val json = Json { ignoreUnknownKeys = true }

    fun sign(secret: ByteArray, claims: Claims): String {
        val body = b64.encodeToString(json.encodeToString(Claims.serializer(), claims).toByteArray())
        return body + "." + b64.encodeToString(hmac(secret, body))
    }

    /** Devuelve los datos de la ficha si la firma es válida y no ha vencido. */
    fun verify(secret: ByteArray, token: String, now: Long = System.currentTimeMillis()): Claims? {
        val dot = token.indexOf('.')
        if (dot <= 0) return null
        val body = token.substring(0, dot)
        val sig = runCatching { b64d.decode(token.substring(dot + 1)) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(sig, hmac(secret, body))) return null
        val c = runCatching { json.decodeFromString(Claims.serializer(), String(b64d.decode(body))) }.getOrNull() ?: return null
        return c.takeIf { it.exp > now }
    }

    private fun hmac(secret: ByteArray, s: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(s.toByteArray())
}

/**
 * Datos de una ficha de sesión. [kind]: "eq" = equipo autorizado (1 hora), "us" = usuario que entró con su PIN
 * en ese equipo (8 horas), "dueño" = el dueño con su correo (para administrar equipos).
 */
@Serializable
data class Claims(val kind: String, val business: Long, val device: String = "", val user: Long = 0, val account: Long = 0, val exp: Long)
