package com.vitrinapos.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private fun chooseFile(title: String, mode: Int, suggested: String? = null, filter: ((String) -> Boolean)? = null): File? {
    val dialog = FileDialog(null as Frame?, title, mode)
    if (suggested != null) dialog.file = suggested
    if (filter != null) dialog.setFilenameFilter { _, name -> filter(name.lowercase()) }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

@Composable
actual fun rememberImagePicker(onResult: (File?) -> Unit): ImagePicker = remember(onResult) {
    object : ImagePicker {
        override val canTakePhoto = false
        override fun takePhoto() = pickFromGallery()
        override fun pickFromGallery() {
            val f = chooseFile("Elegir foto", FileDialog.LOAD) { n ->
                n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".bmp") || n.endsWith(".gif")
            }
            if (f == null) { onResult(null); return }
            val tmp = File.createTempFile("foto", "." + f.extension.ifBlank { "jpg" })
            f.copyTo(tmp, overwrite = true)
            onResult(tmp)
        }
    }
}

/** En Windows se usa un lector de código de barras USB, que escribe como un teclado. */
@Composable
actual fun rememberBarcodeScanner(onResult: (String) -> Unit): (() -> Unit)? = null

@Composable
actual fun rememberPrinterPermission(): (onGranted: () -> Unit) -> Unit = remember { { it() } }

@Composable
actual fun rememberFileExchange(): FileExchange = remember {
    object : FileExchange {
        override fun saveAs(suggestedName: String, mime: String, write: (OutputStream) -> Unit, onDone: (Boolean) -> Unit) {
            val f = chooseFile("Guardar respaldo", FileDialog.SAVE, suggestedName) ?: return onDone(false)
            val target = if (f.extension.isBlank()) File(f.path + ".zip") else f
            onDone(runCatching { target.outputStream().use(write) }.isSuccess)
        }

        override fun open(mimes: Array<String>, read: (InputStream) -> Unit, onDone: (Boolean) -> Unit) {
            val f = chooseFile("Abrir respaldo", FileDialog.LOAD) { it.endsWith(".zip") } ?: return onDone(false)
            onDone(runCatching { f.inputStream().use(read) }.isSuccess)
        }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // En escritorio se vuelve con el botón de la pantalla o con la tecla Esc (ver main.kt).
}
