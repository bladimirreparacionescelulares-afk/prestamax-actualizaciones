package com.vitrinapos.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import java.io.InputStream
import java.io.OutputStream

data class PrinterDevice(val id: String, val name: String, val detail: String = "")

/** Servicios que cambian entre Android y Windows. */
interface Platform {
    val isDesktop: Boolean
    /** "Android" o "Windows". */
    val platformName: String
    /** Nombre sugerido para este equipo en la red. */
    val defaultDeviceName: String

    /** Mantiene activa la recepción de mensajes de la red (necesario en Android para que lo encuentren). */
    fun setNetworkActive(active: Boolean)

    val io: CoroutineDispatcher
    /** Carpeta privada de la app (base de datos, fotos, PDF). */
    val dataDir: File
    val databaseFile: File

    fun createDriver(): SqlDriver

    /** Decodifica una imagen reduciéndola a [maxSide] píxeles como máximo. */
    fun decodeImage(file: File, maxSide: Int): ImageBitmap?

    /** Guarda [src] como JPEG comprimido en [dest], corrigiendo la orientación. */
    fun saveCompressedJpeg(src: File, dest: File, maxSide: Int = 1080, quality: Int = 82): Boolean

    suspend fun listPrinters(): List<PrinterDevice>
    suspend fun printRaw(printer: PrinterDevice, bytes: ByteArray)

    /** Dibuja un PDF. Por omisión, A4 (595 x 842 puntos). */
    fun writePdf(file: File, widthPt: Float = 595f, heightPt: Float = 842f, draw: (PdfCanvas) -> Unit)

    /** Pitido (y vibración en el celular) al escanear: [ok] = encontrado. */
    fun scanFeedback(ok: Boolean)

    fun shareFile(file: File, mime: String, title: String)
    fun shareText(text: String, title: String)
    fun openFile(file: File)

    /** Abre un enlace (por ejemplo, WhatsApp). Devuelve false si no hay con qué abrirlo. */
    fun openUrl(url: String): Boolean
}

/** Lienzo para dibujar PDF; coordenadas en puntos desde la esquina superior izquierda. */
interface PdfCanvas {
    val pageWidth: Float
    val pageHeight: Float
    fun newPage()
    fun text(x: Float, y: Float, text: String, size: Float, bold: Boolean = false, color: Long = 0xFF1B2421, align: TextAlign = TextAlign.Left)
    fun textWidth(text: String, size: Float, bold: Boolean = false): Float
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Long, radius: Float = 0f)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Long, width: Float = 0.8f)
    fun image(file: File, x: Float, y: Float, w: Float, h: Float)

    enum class TextAlign { Left, Right, Center }
}

interface ImagePicker {
    val canTakePhoto: Boolean
    fun takePhoto()
    fun pickFromGallery()
}

/** Elige una foto con la cámara o la galería. Devuelve un archivo temporal o null si se canceló. */
@Composable
expect fun rememberImagePicker(onResult: (File?) -> Unit): ImagePicker

/** Escáner de códigos de barras con la cámara. Null si el equipo no tiene cámara (Windows). */
@Composable
expect fun rememberBarcodeScanner(onResult: (String) -> Unit): (() -> Unit)?

/** Pide permiso para usar la impresora (Bluetooth en Android) y ejecuta [onGranted]. */
@Composable
expect fun rememberPrinterPermission(): (onGranted: () -> Unit) -> Unit

interface FileExchange {
    fun saveAs(suggestedName: String, mime: String, write: (OutputStream) -> Unit, onDone: (Boolean) -> Unit)
    fun open(mimes: Array<String>, read: (InputStream) -> Unit, onDone: (Boolean) -> Unit)
}

/** Guardar o abrir archivos fuera de la app (respaldos). */
@Composable
expect fun rememberFileExchange(): FileExchange

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * Vista del escáner continuo: en Android, la cámara leyendo códigos sin parar;
 * en Windows, espera el lector USB. [onCode] se llama por cada código leído.
 */
@Composable
expect fun ContinuousScanner(onCode: (String) -> Unit, modifier: androidx.compose.ui.Modifier)

/** true si el escáner usa la cámara (Android); false si espera un lector USB (Windows). */
expect val scannerUsesCamera: Boolean
