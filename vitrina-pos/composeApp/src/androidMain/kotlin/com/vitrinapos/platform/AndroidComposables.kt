package com.vitrinapos.platform

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

@Composable
actual fun rememberImagePicker(onResult: (File?) -> Unit): ImagePicker {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending?.let(::File)
        pending = null
        if (ok && f != null && f.length() > 0) callback(f) else { f?.delete(); callback(null) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) { callback(null); return@rememberLauncherForActivityResult }
        scope.launch {
            val tmp = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(File(context.cacheDir, "fotos").apply { mkdirs() }, "gal_${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                    f
                }.getOrNull()
            }
            callback(tmp)
        }
    }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val launchCamera = remember(camera) {
        {
            val f = File(File(context.cacheDir, "fotos").apply { mkdirs() }, "cam_${System.currentTimeMillis()}.jpg")
            pending = f.path
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
            runCatching { camera.launch(uri) }.onFailure {
                Toast.makeText(context, "No se encontró una app de cámara", Toast.LENGTH_LONG).show()
            }
            Unit
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) launchCamera() else Toast.makeText(context, "Se necesita el permiso de cámara para tomar la foto", Toast.LENGTH_LONG).show()
    }
    return remember(camera, gallery, launchCamera, cameraPermission) {
        object : ImagePicker {
            override val canTakePhoto = hasCamera
            override fun takePhoto() {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            }

            override fun pickFromGallery() {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
    }
}

@Composable
actual fun rememberBarcodeScanner(onResult: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)
    return remember {
        {
            GmsBarcodeScanning.getClient(context).startScan()
                .addOnSuccessListener { barcode -> barcode.rawValue?.takeIf { it.isNotBlank() }?.let { callback(it.trim()) } }
                .addOnFailureListener {
                    Toast.makeText(context, "No se pudo abrir el escáner. Revisa que Google Play esté actualizado.", Toast.LENGTH_LONG).show()
                }
        }
    }
}

@Composable
actual fun rememberPrinterPermission(): (onGranted: () -> Unit) -> Unit {
    val context = LocalContext.current
    val holder = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = holder[0]
        holder[0] = null
        if (granted) next?.invoke()
        else Toast.makeText(context, "Se necesita el permiso de Bluetooth para usar la impresora", Toast.LENGTH_LONG).show()
    }
    return remember(launcher) {
        { onGranted ->
            if (Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            ) {
                onGranted()
            } else {
                holder[0] = onGranted
                launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }
}

@Composable
actual fun rememberFileExchange(): FileExchange {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pendingWrite = remember { arrayOfNulls<(OutputStream) -> Unit>(1) }
    val pendingRead = remember { arrayOfNulls<(InputStream) -> Unit>(1) }
    val pendingDone = remember { arrayOfNulls<(Boolean) -> Unit>(1) }

    fun finish(ok: Boolean) {
        val done = pendingDone[0]
        pendingDone[0] = null; pendingWrite[0] = null; pendingRead[0] = null
        done?.invoke(ok)
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val write = pendingWrite[0]
        if (uri == null || write == null) { finish(false); return@rememberLauncherForActivityResult }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use(write) }.isSuccess
            }
            finish(ok)
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val read = pendingRead[0]
        if (uri == null || read == null) { finish(false); return@rememberLauncherForActivityResult }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use(read) }.isSuccess
            }
            finish(ok)
        }
    }
    return remember(create, open) {
        object : FileExchange {
            override fun saveAs(suggestedName: String, mime: String, write: (OutputStream) -> Unit, onDone: (Boolean) -> Unit) {
                pendingWrite[0] = write; pendingDone[0] = onDone
                create.launch(suggestedName)
            }

            override fun open(mimes: Array<String>, read: (InputStream) -> Unit, onDone: (Boolean) -> Unit) {
                pendingRead[0] = read; pendingDone[0] = onDone
                open.launch(mimes)
            }
        }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
