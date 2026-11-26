package com.vitrinapos.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.vitrinapos.db.VitrinaDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AndroidPlatform(private val context: Context) : Platform {
    override val isDesktop = false
    override val platformName = "Android"
    override val defaultDeviceName: String = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
        .filter { !it.isNullOrBlank() }.joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { "Celular" }

    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    override fun setNetworkActive(active: Boolean) {
        runCatching {
            if (active && multicastLock == null) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                multicastLock = wifi.createMulticastLock("vitrina").apply { setReferenceCounted(false); acquire() }
            } else if (!active) {
                multicastLock?.release(); multicastLock = null
            }
        }
    }

    override val io: CoroutineDispatcher = Dispatchers.IO
    override val dataDir: File = context.filesDir
    override val databaseFile: File get() = context.getDatabasePath(DB_NAME)

    override fun createDriver(): SqlDriver = AndroidSqliteDriver(VitrinaDb.Schema, context, DB_NAME)

    override fun decodeImage(file: File, maxSide: Int): ImageBitmap? = runCatching {
        decodeSampled(file, maxSide)?.asImageBitmap()
    }.getOrNull()

    private fun decodeSampled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    override fun saveCompressedJpeg(src: File, dest: File, maxSide: Int, quality: Int): Boolean = runCatching {
        var bmp = decodeSampled(src, maxSide) ?: return false
        // Corregir la orientación de las fotos de la cámara.
        val rotation = runCatching {
            when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val longest = max(bmp.width, bmp.height)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        if (rotation != 0f || scale != 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out != bmp) bmp.recycle()
            bmp = out
        }
        dest.parentFile?.mkdirs()
        dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        bmp.recycle()
        true
    }.getOrDefault(false)

    // ---------- Impresora Bluetooth ----------

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission")
    override suspend fun listPrinters(): List<PrinterDevice> {
        val a = adapter ?: error("Este teléfono no tiene Bluetooth")
        if (!a.isEnabled) error("Enciende el Bluetooth del teléfono")
        return a.bondedDevices.orEmpty()
            .sortedByDescending { isLikelyPrinter(it) }
            .map { PrinterDevice(it.address, it.name ?: it.address, "Bluetooth · ${it.address}") }
    }

    @SuppressLint("MissingPermission")
    private fun isLikelyPrinter(d: BluetoothDevice): Boolean =
        d.bluetoothClass?.majorDeviceClass == 0x0600 || (d.name ?: "").contains(Regex("(?i)print|pos|pt-|xp|mtp|rpp|thermal"))

    @SuppressLint("MissingPermission")
    override suspend fun printRaw(printer: PrinterDevice, bytes: ByteArray) {
        val a = adapter ?: error("Este teléfono no tiene Bluetooth")
        if (!a.isEnabled) error("Enciende el Bluetooth")
        val device = a.getRemoteDevice(printer.id)
        runCatching { a.cancelDiscovery() }
        var socket: BluetoothSocket? = null
        try {
            socket = runCatching {
                device.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
            }.getOrElse {
                // Algunas impresoras económicas solo responden en el canal 1.
                @Suppress("DiscouragedPrivateApi")
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
            }
            val out = socket.outputStream
            bytes.toList().chunked(512).forEach { chunk ->
                out.write(chunk.toByteArray())
                out.flush()
                delay(20)
            }
            delay(600) // dejar que la impresora reciba todo antes de cerrar
        } catch (e: Exception) {
            error("No se pudo conectar con ${printer.name}. ¿Está encendida y cerca?")
        } finally {
            runCatching { socket?.close() }
        }
    }

    // ---------- PDF ----------

    override fun writePdf(file: File, widthPt: Float, heightPt: Float, draw: (PdfCanvas) -> Unit) {
        file.parentFile?.mkdirs()
        val doc = PdfDocument()
        val canvas = AndroidPdfCanvas(doc, widthPt, heightPt)
        try {
            draw(canvas)
            canvas.finish()
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    // ---------- Compartir ----------

    private fun uriFor(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private val tone by lazy { runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull() }

    override fun scanFeedback(ok: Boolean) {
        runCatching { tone?.startTone(if (ok) android.media.ToneGenerator.TONE_PROP_BEEP else android.media.ToneGenerator.TONE_PROP_NACK, 120) }
        runCatching {
            val v = context.getSystemService(android.os.Vibrator::class.java)
            v?.vibrate(android.os.VibrationEffect.createOneShot(if (ok) 40 else 160, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun shareFile(file: File, mime: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uriFor(file))
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun shareText(text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override val extraKeyStoreProvider: java.security.Provider by lazy { org.bouncycastle.jce.provider.BouncyCastleProvider() }

    private fun vaultKey(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("vitrina-vault", null) as? javax.crypto.SecretKey)?.let { return it }
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                "vitrina-vault", android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    override fun protect(data: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, vaultKey())
        return c.iv + c.doFinal(data)
    }

    override fun unprotect(data: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, vaultKey(), javax.crypto.spec.GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    override fun openUrl(url: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    override val updateExtension = "apk"

    override val updateDir: File get() = File(context.cacheDir, "actualizaciones")

    override fun installUpdate(file: File): String? {
        // Android pide permitir, una sola vez, que Vitrina POS instale aplicaciones.
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return "Activa \"Permitir de esta fuente\" para Vitrina POS, vuelve y toca Actualizar otra vez."
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(file), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(view); null }.getOrElse { "No se pudo abrir el instalador: ${it.message}" }
    }

    override fun openFile(file: File) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(file), if (file.extension == "pdf") "application/pdf" else "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(view) }
    }

    companion object {
        const val DB_NAME = "vitrina.db"
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/** PDF con el PdfDocument de Android (A4 = 595 x 842 puntos). */
private class AndroidPdfCanvas(private val doc: PdfDocument, override val pageWidth: Float, override val pageHeight: Float) : PdfCanvas {
    private var pageNumber = 0
    private var page: PdfDocument.Page? = null
    private val canvas: Canvas get() = page!!.canvas
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val images = HashMap<String, Bitmap?>()

    init { newPage() }

    override fun newPage() {
        page?.let { doc.finishPage(it) }
        pageNumber++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth.roundToInt(), pageHeight.roundToInt(), pageNumber).create())
    }

    fun finish() {
        page?.let { doc.finishPage(it) }
        page = null
        images.values.forEach { it?.recycle() }
    }

    private fun font(size: Float, bold: Boolean) = textPaint.apply {
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    override fun text(x: Float, y: Float, text: String, size: Float, bold: Boolean, color: Long, align: PdfCanvas.TextAlign) {
        val p = font(size, bold)
        p.color = color.toInt()
        val w = p.measureText(text)
        val px = when (align) { PdfCanvas.TextAlign.Left -> x; PdfCanvas.TextAlign.Right -> x - w; PdfCanvas.TextAlign.Center -> x - w / 2 }
        canvas.drawText(text, px, y, p)
    }

    override fun textWidth(text: String, size: Float, bold: Boolean): Float = font(size, bold).measureText(text)

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Long, radius: Float) {
        fill.color = color.toInt()
        if (radius > 0) canvas.drawRoundRect(RectF(x, y, x + w, y + h), radius, radius, fill)
        else canvas.drawRect(x, y, x + w, y + h, fill)
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Long, width: Float) {
        stroke.color = color.toInt()
        stroke.strokeWidth = width
        canvas.drawLine(x1, y1, x2, y2, stroke)
    }

    override fun image(file: File, x: Float, y: Float, w: Float, h: Float) {
        val bmp = images.getOrPut(file.path) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 300) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        } ?: return
        val ratio = min(w / bmp.width, h / bmp.height)
        val dw = bmp.width * ratio
        val dh = bmp.height * ratio
        val left = x + (w - dw) / 2
        val top = y + (h - dh) / 2
        canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    @Suppress("unused")
    private fun Float.px() = roundToInt()
}
