package com.vitrinapos.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
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

actual val scannerUsesCamera: Boolean = false

/** En Windows: un campo que recibe lo que escribe el lector USB (código + Enter). */
@Composable
actual fun ContinuousScanner(onCode: (String) -> Unit, modifier: androidx.compose.ui.Modifier) {
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.compose.foundation.layout.Column(
        modifier.background(androidx.compose.ui.graphics.Color(0xFF101614)).padding(24.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Rounded.QrCodeScanner, null,
            tint = androidx.compose.ui.graphics.Color.White, modifier = androidx.compose.ui.Modifier.size(64.dp),
        )
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
        androidx.compose.material3.Text("Pasa los productos por el lector de códigos", color = androidx.compose.ui.graphics.Color.White)
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { v ->
                if (v.endsWith("\n")) { v.trim().takeIf { it.isNotEmpty() }?.let(onCode); text = "" } else text = v
            },
            singleLine = true,
            placeholder = { androidx.compose.material3.Text("O escribe el código y presiona Enter") },
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { text.trim().takeIf { it.isNotEmpty() }?.let(onCode); text = "" }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            modifier = androidx.compose.ui.Modifier.focusRequester(focus).fillMaxWidth(0.8f),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = androidx.compose.ui.graphics.Color.White, unfocusedContainerColor = androidx.compose.ui.graphics.Color.White,
            ),
        )
    }
}
