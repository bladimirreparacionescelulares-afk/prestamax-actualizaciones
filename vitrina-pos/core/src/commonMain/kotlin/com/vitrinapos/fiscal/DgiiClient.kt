package com.vitrinapos.fiscal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

/** Estados que devuelve la DGII. */
enum class DgiiStatus(val code: Int, val label: String) {
    NOT_FOUND(0, "No encontrado"), ACCEPTED(1, "Aceptado"), REJECTED(2, "Rechazado"),
    IN_PROCESS(3, "En proceso"), CONDITIONAL(4, "Aceptado condicional");

    companion object {
        fun of(code: Int?, text: String?): DgiiStatus = entries.firstOrNull { it.code == code }
            ?: text?.let { t -> entries.firstOrNull { t.contains(it.label, ignoreCase = true) } }
            ?: IN_PROCESS
    }
}

data class DgiiResult(val status: DgiiStatus, val trackId: String = "", val message: String = "", val sequenceUsed: Boolean = true)

class DgiiException(message: String, val http: Int = 0, val retry: Boolean = true) : IOException(message)

/**
 * Servicios web de la DGII (Descripción Técnica Servicios DGII):
 * autenticación con semilla firmada, recepción de e-CF, recepción de resumen de consumo (RFCE),
 * consulta de resultado por TrackId y anulación de rangos.
 */
class DgiiClient(
    private val env: DgiiEnv,
    private val signer: (String) -> String,
    private val http: Http = Http.Default,
) {
    /** Transporte HTTP (reemplazable en las pruebas). */
    interface Http {
        fun get(url: String, headers: Map<String, String>): Pair<Int, String>
        fun postXml(url: String, fileName: String, xml: String, headers: Map<String, String>): Pair<Int, String>

        object Default : Http {
            override fun get(url: String, headers: Map<String, String>): Pair<Int, String> {
                val c = open(url, headers)
                c.requestMethod = "GET"
                return read(c)
            }

            override fun postXml(url: String, fileName: String, xml: String, headers: Map<String, String>): Pair<Int, String> {
                val c = open(url, headers)
                val boundary = "----VitrinaPOS" + System.nanoTime()
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                val body = ("--$boundary\r\nContent-Disposition: form-data; name=\"xml\"; filename=\"$fileName\"\r\nContent-Type: text/xml\r\n\r\n").toByteArray() +
                    xml.toByteArray(Charsets.UTF_8) + "\r\n--$boundary--\r\n".toByteArray()
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
                return read(c)
            }

            private fun open(url: String, headers: Map<String, String>) = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 45_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "VitrinaPOS")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            private fun read(c: HttpURLConnection): Pair<Int, String> = try {
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                code to text
            } finally { c.disconnect() }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var token: String? = null
    private var tokenUntil: Long = 0

    private val ecfBase get() = "https://ecf.dgii.gov.do/${env.path}"
    private val fcBase get() = "https://fc.dgii.gov.do/${env.path}"

    /** Token de sesión: se pide una semilla, se firma con el certificado y se cambia por el token. */
    @Synchronized
    fun token(now: Long = System.currentTimeMillis()): String {
        token?.let { if (now < tokenUntil - 60_000) return it }
        val (c1, seed) = call { http.get("$ecfBase/autenticacion/api/autenticacion/semilla", emptyMap()) }
        if (c1 != 200 || !seed.contains("SemillaModel")) throw DgiiException("La DGII no entregó la semilla (HTTP $c1)", c1)
        val signed = signer(seed)
        val (c2, body) = call { http.postXml("$ecfBase/autenticacion/api/autenticacion/validarsemilla", "semilla.xml", signed, emptyMap()) }
        if (c2 != 200) throw DgiiException(dgiiMessage(body).ifBlank { "La DGII no aceptó la autenticación (HTTP $c2)" }, c2, retry = c2 >= 500)
        val o = json.parseToJsonElement(body) as JsonObject
        val t = o.str("token") ?: throw DgiiException("Respuesta de autenticación sin token")
        token = t
        tokenUntil = o.str("expira")?.let { runCatching { OffsetDateTime.parse(it.trim()).toInstant().toEpochMilli() }.getOrNull() } ?: (now + 50 * 60_000)
        return t
    }

    fun clearToken() { token = null }

    /** Envía un e-CF completo. Devuelve el TrackId para consultar el resultado. */
    fun sendEcf(fileName: String, signedXml: String): DgiiResult {
        val (code, body) = authed { postXml("$ecfBase/recepcion/api/facturaselectronicas", fileName, signedXml, it) }
        if (code !in 200..299) throw DgiiException(dgiiMessage(body).ifBlank { "La DGII rechazó el envío (HTTP $code)" }, code, retry = code >= 500 || code == 429)
        val o = json.parseToJsonElement(body) as JsonObject
        val track = o.str("trackId") ?: throw DgiiException(o.str("mensaje") ?: o.str("error") ?: "Respuesta sin TrackId", code, retry = false)
        return DgiiResult(DgiiStatus.IN_PROCESS, track, o.str("mensaje") ?: "")
    }

    /** Envía el resumen de una factura de consumo menor de RD$250,000. La respuesta trae el estado al momento. */
    fun sendRfce(fileName: String, signedXml: String): DgiiResult {
        val (code, body) = authed { postXml("$fcBase/recepcionfc/api/recepcion/ecf", fileName, signedXml, it) }
        if (code >= 500 || code == 401 || code == 429) throw DgiiException("La DGII no pudo recibir el resumen (HTTP $code)", code)
        val o = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: throw DgiiException(body.take(200).ifBlank { "Respuesta vacía (HTTP $code)" }, code, retry = false)
        val status = DgiiStatus.of(o["codigo"]?.let { (it as? JsonPrimitive)?.intOrNull }, o.str("estado"))
        val used = o.str("secuenciaUtilizada")?.toBooleanStrictOrNull() ?: true
        return DgiiResult(status, "", messages(o), used)
    }

    /** Resultado de un e-CF enviado. */
    fun status(trackId: String): DgiiResult {
        val (code, body) = authed { get("$ecfBase/consultaresultado/api/consultas/estado?trackid=$trackId", it) }
        if (code !in 200..299) throw DgiiException("No se pudo consultar el estado (HTTP $code)", code)
        val o = json.parseToJsonElement(body) as JsonObject
        val status = DgiiStatus.of(o["codigo"]?.let { (it as? JsonPrimitive)?.intOrNull }, o.str("estado"))
        return DgiiResult(status, trackId, messages(o))
    }

    /** Anula números que no se usaron. */
    fun voidRanges(fileName: String, signedXml: String): DgiiResult {
        val (code, body) = authed { postXml("$ecfBase/anulacionrangos/api/operaciones/anularrango", fileName, signedXml, it) }
        if (code !in 200..299) throw DgiiException(dgiiMessage(body).ifBlank { "La DGII no aceptó la anulación (HTTP $code)" }, code, retry = code >= 500)
        return DgiiResult(DgiiStatus.ACCEPTED, "", dgiiMessage(body))
    }

    private fun authed(block: Http.(Map<String, String>) -> Pair<Int, String>): Pair<Int, String> {
        var r = call { http.block(mapOf("Authorization" to "Bearer ${token()}")) }
        if (r.first == 401) { clearToken(); r = call { http.block(mapOf("Authorization" to "Bearer ${token()}")) } }
        return r
    }

    private inline fun call(block: () -> Pair<Int, String>): Pair<Int, String> = try {
        block()
    } catch (e: DgiiException) {
        throw e
    } catch (e: Exception) {
        throw DgiiException("Sin conexión con la DGII: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun messages(o: JsonObject): String = when (val m = o["mensajes"]) {
        is JsonArray -> m.mapNotNull { (it as? JsonObject)?.let { x -> listOfNotNull(x.str("codigo"), x.str("valor")).joinToString(": ") } }.joinToString(" · ")
        else -> o.str("mensaje") ?: ""
    }

    private fun dgiiMessage(body: String): String {
        val t = body.trim()
        if (t.startsWith("\"")) return t.trim('"')
        return runCatching { (json.parseToJsonElement(t) as JsonObject).let { it.str("mensaje") ?: it.str("error") ?: it.str("title") ?: messages(it) } }.getOrNull() ?: t.take(200)
    }

    private fun JsonObject.str(k: String): String? {
        val key = keys.firstOrNull { it.equals(k, ignoreCase = true) } ?: return null
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }

}
