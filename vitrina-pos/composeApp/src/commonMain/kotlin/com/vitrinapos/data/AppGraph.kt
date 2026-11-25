package com.vitrinapos.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vitrinapos.platform.Platform
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Mantiene el repositorio activo. Al restaurar un respaldo se cierra y se abre uno nuevo. */
class AppGraph(val platform: Platform) {
    var repo by mutableStateOf(Repository(platform))
        private set

    /** Escribe un .zip con la base de datos y las fotos. */
    fun exportBackup(out: OutputStream) {
        val r = repo
        r.checkpoint()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            platform.databaseFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            r.imagesDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                zip.putNextEntry(ZipEntry("images/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("info.txt"))
            zip.write("Respaldo de Vitrina POS\nFecha: ${Time.dateTime(Time.now())}\n".toByteArray())
            zip.closeEntry()
        }
    }

    /**
     * Restaura un respaldo. Primero lo extrae a una carpeta temporal y comprueba que tenga la base de datos;
     * solo entonces reemplaza los datos actuales.
     */
    fun importBackup(input: InputStream) {
        val tmp = File(platform.dataDir, "restore_tmp").apply { deleteRecursively(); mkdirs() }
        var hasDb = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val target = when {
                    e.name == DB_ENTRY -> File(tmp, DB_ENTRY).also { hasDb = true }
                    e.name.startsWith("images/") -> {
                        val n = File(e.name).name
                        if (n.isBlank() || n.startsWith(".")) null else File(tmp, "images/$n")
                    }
                    else -> null
                } ?: continue
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
        require(hasDb) { "El archivo no es un respaldo de Vitrina POS." }
        val header = File(tmp, DB_ENTRY).inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        require(String(header, Charsets.US_ASCII).startsWith("SQLite format 3")) { "El respaldo está dañado." }

        val old = repo
        old.close()
        val dbFile = platform.databaseFile
        listOf("-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        File(tmp, DB_ENTRY).copyTo(dbFile, overwrite = true)
        val images = File(platform.dataDir, "images")
        images.deleteRecursively()
        images.mkdirs()
        File(tmp, "images").listFiles()?.forEach { it.copyTo(File(images, it.name), overwrite = true) }
        tmp.deleteRecursively()
        repo = Repository(platform)
    }

    companion object {
        const val DB_ENTRY = "vitrina.db"
    }
}
