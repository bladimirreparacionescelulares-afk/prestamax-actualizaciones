package com.vitrinapos.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Servidor HTTP mínimo para la red del local. Solo entiende POST con cuerpo binario,
 * que es todo lo que necesita la app. Cada conexión usa su propio hilo.
 */
class MiniHttpServer(
    private val port: Int,
    private val handler: (path: String, headers: Map<String, String>, body: ByteArray, remote: String) -> Pair<Int, ByteArray>,
) {
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    @Volatile private var running = false

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        running = true
        val p = Executors.newCachedThreadPool { r -> Thread(r, "vitrina-http").apply { isDaemon = true } }
        pool = p
        Thread({
            while (running) {
                val socket = try { s.accept() } catch (e: IOException) { break }
                p.execute { serve(socket) }
            }
        }, "vitrina-http-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        pool?.shutdownNow()
        server = null
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 60_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length < 0 || length > MAX_BODY) { respond(s, 413, "Demasiado grande".toByteArray()); return }
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) return
                    read += n
                }
                val (code, out) = try {
                    handler(parts[1], headers, body, s.inetAddress.hostAddress ?: "")
                } catch (e: Exception) {
                    500 to (e.message ?: "Error").toByteArray()
                }
                respond(s, code, out)
            } catch (_: IOException) {
            }
        }
    }

    private fun respond(s: Socket, code: Int, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1.name())
            if (c == '\n'.code) return buf.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            buf.write(c)
            if (buf.size() > 8192) throw IOException("Línea muy larga")
        }
    }

    companion object {
        const val MAX_BODY = 25 * 1024 * 1024
    }
}

class HttpStatusException(val code: Int, message: String) : IOException(message)

object MiniHttpClient {
    fun post(host: String, port: Int, path: String, body: ByteArray, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000): ByteArray {
        val conn = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 4_000
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw HttpStatusException(code, String(bytes))
            return bytes
        } finally {
            conn.disconnect()
        }
    }
}

object NetInfo {
    /** Direcciones IPv4 de este equipo en la red local (Wi-Fi o cable). */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
            .map { it.hostAddress ?: "" }
            .filter { it.isNotBlank() }
            .distinct()
    }.getOrDefault(emptyList())

    fun isReachable(host: String): Boolean = runCatching { InetAddress.getByName(host).isReachable(1500) }.getOrDefault(false)
}
