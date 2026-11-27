package com.vitrinapos.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Envía correos. Con RESEND_API_KEY usa Resend; sin ella, solo los anota en el registro (para pruebas). */
interface Mailer {
    fun send(to: String, subject: String, text: String): Boolean
}

class LogMailer : Mailer {
    private val log = LoggerFactory.getLogger("correo")
    val sent = mutableListOf<Triple<String, String, String>>()
    override fun send(to: String, subject: String, text: String): Boolean {
        sent += Triple(to, subject, text)
        log.info("Correo (sin enviar) a {}: {}", to, subject)
        return true
    }
}

class ResendMailer(private val apiKey: String, private val from: String) : Mailer {
    private val log = LoggerFactory.getLogger("correo")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun send(to: String, subject: String, text: String): Boolean {
        val body = JsonObject(mapOf(
            "from" to JsonPrimitive(from), "to" to JsonArray(listOf(JsonPrimitive(to))),
            "subject" to JsonPrimitive(subject), "text" to JsonPrimitive(text),
        )).toString()
        val req = HttpRequest.newBuilder(URI("https://api.resend.com/emails"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return runCatching { http.send(req, HttpResponse.BodyHandlers.ofString()) }
            .map { r -> (r.statusCode() in 200..299).also { ok -> if (!ok) log.warn("Resend respondió {}: {}", r.statusCode(), r.body().take(300)) } }
            .getOrElse { log.warn("No se pudo enviar el correo", it); false }
    }
}
