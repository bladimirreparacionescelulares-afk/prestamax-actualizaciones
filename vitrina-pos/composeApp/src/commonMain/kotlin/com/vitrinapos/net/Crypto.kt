package com.vitrinapos.net

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Cifrado de lo que viaja por la red del local (AES-GCM de 256 bits). */
object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun randomDigits(n: Int): String = (1..n).map { random.nextInt(10) }.joinToString("")

    fun randomToken(n: Int): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        return (1..n).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    /** Llave derivada del código de emparejamiento. */
    fun keyFromSecret(secret: String): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), "vitrina-pos-emparejar".toByteArray(), 20_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    /** Lanza una excepción si la llave no es la correcta o los datos fueron alterados. */
    fun open(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size > 28) { "Datos incompletos" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Huella anterior del PIN (versiones hasta la 1.7). Solo se usa para reconocerla y cambiarla por la nueva. */
    fun legacyPinHash(pin: String): String = sha256Hex("vitrina-pin:$pin")

    private const val PIN_ITERATIONS = 20_000

    /** Huella del PIN con sal y PBKDF2: "p2$iteraciones$sal$huella". Nunca se guarda el PIN tal cual. */
    fun pinHash(pin: String): String {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return "p2\$" + PIN_ITERATIONS + "\$" + hex(salt) + "\$" + hex(pbkdf2(pin, salt, PIN_ITERATIONS))
    }

    fun verifyPin(stored: String, pin: String): Boolean {
        if (!stored.startsWith("p2\$")) return stored == legacyPinHash(pin)
        val parts = stored.split('$')
        if (parts.size != 4) return false
        val expected = unhex(parts[3])
        val got = pbkdf2(pin, unhex(parts[2]), parts[1].toIntOrNull() ?: return false)
        return MessageDigest.isEqual(expected, got)
    }

    fun isLegacyPin(stored: String) = !stored.startsWith("p2\$")

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray =
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).encoded

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
