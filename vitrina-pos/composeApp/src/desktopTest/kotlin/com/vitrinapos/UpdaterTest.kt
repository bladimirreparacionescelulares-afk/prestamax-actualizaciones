package com.vitrinapos

import com.sun.net.httpserver.HttpServer
import com.vitrinapos.data.AppUpdate
import com.vitrinapos.data.Updater
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdaterTest {
    private val releases = """
    [
      {"tag_name":"v4.11","draft":false,"prerelease":false,"body":"Prestamax","assets":[{"name":"prestamax.apk","browser_download_url":"https://x/p.apk","size":10}]},
      {"tag_name":"vitrina-v1.12.0","draft":true,"prerelease":false,"body":"","assets":[{"name":"VitrinaPOS-1.12.0.apk","browser_download_url":"https://x/b.apk","size":10}]},
      {"tag_name":"vitrina-v1.11.0","draft":false,"prerelease":false,"body":"[importante] Arreglo del cierre de caja",
       "assets":[
         {"name":"VitrinaPOS-1.11.0.apk","browser_download_url":"https://x/a.apk","size":5000,"digest":"sha256:ABCDEF"},
         {"name":"Vitrina POS-1.11.0.msi","browser_download_url":"https://x/a.msi","size":9000}
       ]},
      {"tag_name":"vitrina-v1.10.5","draft":false,"prerelease":false,"body":"Menor","assets":[{"name":"VitrinaPOS-1.10.5.apk","browser_download_url":"https://x/c.apk","size":10}]}
    ]
    """.trimIndent()

    @Test
    fun comparaVersiones() {
        assertTrue(Updater.isNewer("1.10.2", "1.9.9"))
        assertTrue(Updater.isNewer("2.0", "1.10.1"))
        assertFalse(Updater.isNewer("1.10.1", "1.10.1"))
        assertFalse(Updater.isNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun eligeLaVersionCorrecta() {
        val apk = Updater.pick(releases, "apk", current = "1.10.1")!!
        assertEquals("1.11.0", apk.version)       // ni el borrador ni Prestamax
        assertTrue(apk.important)
        assertEquals("Arreglo del cierre de caja", apk.notes)
        assertEquals("abcdef", apk.sha256)
        assertEquals("https://x/a.msi", Updater.pick(releases, "msi", current = "1.10.1")!!.url)
        assertNull(Updater.pick(releases, "dmg", current = "1.10.1"))   // todavía sin versión para Mac
        assertNull(Updater.pick(releases, "apk", current = "1.11.0"))   // ya está al día
        assertNull(Updater.pick("no es json", "apk"))
    }

    @Test
    fun descargaYComprueba() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f") { ex -> ex.sendResponseHeaders(200, data.size.toLong()); ex.responseBody.use { it.write(data) } }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/f"
            val dir = Files.createTempDirectory("upd").toFile()
            var last = 0f
            val f = Updater.download(AppUpdate("9.0", "", false, "VitrinaPOS.apk", url, data.size.toLong(), sha), dir) { last = it }
            assertEquals(data.size.toLong(), f.length())
            assertEquals(1f, last)
            // Si el archivo no coincide con lo publicado, se borra y avisa.
            val bad = runCatching { Updater.download(AppUpdate("9.0", "", false, "VitrinaPOS.apk", url, data.size.toLong(), "00ff"), dir) {} }
            assertTrue(bad.exceptionOrNull()!!.message!!.contains("no coincide"))
            assertFalse(java.io.File(dir, "VitrinaPOS.apk").exists())
        } finally { server.stop(0) }
    }
}
