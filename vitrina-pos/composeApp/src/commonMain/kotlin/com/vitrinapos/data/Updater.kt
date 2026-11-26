package com.vitrinapos.data

import com.vitrinapos.AppInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Una versión publicada en GitHub (Releases), con el archivo para este sistema. */
data class AppUpdate(
    val version: String,
    val notes: String,
    /** Marcada como importante: el aviso sale en cada inicio aunque lo hayan cerrado. */
    val important: Boolean,
    val fileName: String,
    val url: String,
    val size: Long,
    /** SHA-256 que publica GitHub para el archivo (vacío si no lo trae). */
    val sha256: String,
)

/**
 * Busca versiones nuevas en las entregas ("Releases") del repositorio. Las de Vitrina llevan la etiqueta
 * vitrina-v1.2.3; el archivo se elige por su extensión (.apk en Android, .msi en Windows, .dmg en Mac).
 */
object Updater {
    const val REPO = "bladimirreparacionescelulares-afk/prestamax-actualizaciones"
    const val TAG_PREFIX = "vitrina-v"
    private val json = Json { ignoreUnknownKeys = true }

    /** Compara versiones "1.10.2" > "1.9.0" (por partes numéricas). */
    fun isNewer(candidate: String, current: String = AppInfo.VERSION): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Elige la versión más nueva que tenga archivo para este sistema ([extension]: "apk", "msi" o "dmg"). */
    fun pick(releasesJson: String, extension: String, current: String = AppInfo.VERSION): AppUpdate? {
        val list = runCatching { json.parseToJsonElement(releasesJson).jsonArray }.getOrNull() ?: return null
        return list.mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            if (r["draft"]?.jsonPrimitive?.booleanOrNull == true || r["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val tag = r["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!tag.startsWith(TAG_PREFIX)) return@mapNotNull null
            val version = tag.removePrefix(TAG_PREFIX)
            val asset = (r["assets"] as? JsonArray)?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".$extension", ignoreCase = true) == true } ?: return@mapNotNull null
            val body = r["body"]?.jsonPrimitive?.content.orEmpty()
            AppUpdate(
                version = version,
                notes = body.replace("[importante]", "", ignoreCase = true).trim(),
                important = body.contains("[importante]", ignoreCase = true),
                fileName = asset["name"]!!.jsonPrimitive.content,
                url = asset["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0,
                sha256 = asset["digest"]?.jsonPrimitive?.content?.removePrefix("sha256:")?.lowercase().orEmpty(),
            )
        }.filter { isNewer(it.version, current) }.maxWithOrNull { x, y -> if (isNewer(x.version, y.version)) 1 else if (x.version == y.version) 0 else -1 }
    }

    /** Consulta GitHub. Devuelve null si no hay nada nuevo; lanza IOException si no hay conexión. */
    fun check(extension: String): AppUpdate? {
        val c = URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "VitrinaPOS/${AppInfo.VERSION}")
        try {
            if (c.responseCode != 200) throw IOException("GitHub respondió ${c.responseCode}")
            return pick(c.inputStream.bufferedReader().readText(), extension)
        } finally { c.disconnect() }
    }

    /** Descarga el instalador y comprueba que llegó completo y sin alterar. */
    fun download(u: AppUpdate, dir: File, progress: (Float) -> Unit): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() } // descargas viejas
        val dest = File(dir, File(u.fileName).name)
        val c = URL(u.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "VitrinaPOS/${AppInfo.VERSION}")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            if (c.responseCode != 200) throw IOException("La descarga respondió ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: u.size
            var done = 0L
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (u.size > 0 && done != u.size) throw IOException("La descarga quedó incompleta")
        } finally { c.disconnect() }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (u.sha256.isNotBlank() && hash != u.sha256) { dest.delete(); throw IOException("El archivo descargado no coincide con el publicado. Inténtalo otra vez.") }
        return dest
    }
}
