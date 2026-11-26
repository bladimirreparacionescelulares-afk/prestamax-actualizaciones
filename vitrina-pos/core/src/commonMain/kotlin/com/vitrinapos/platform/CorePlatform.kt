package com.vitrinapos.platform

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

/** Lo que el núcleo necesita de cada sistema (Android, Windows, Mac o el servidor). */
interface CorePlatform {
    /** "Android", "Windows", "Mac OS X" o "Servidor". */
    val platformName: String
    /** Nombre sugerido para este equipo en la red. */
    val defaultDeviceName: String

    /** Mantiene activa la recepción de mensajes de la red (necesario en Android para que lo encuentren). */
    fun setNetworkActive(active: Boolean)

    val io: CoroutineDispatcher
    /** Carpeta privada (base de datos, fotos, PDF). */
    val dataDir: File
    val databaseFile: File

    fun createDriver(): SqlDriver

    /** Reduce y comprime una foto a JPEG. Devuelve false si no se pudo leer. */
    fun saveCompressedJpeg(src: File, dest: File, maxSide: Int = 1080, quality: Int = 82): Boolean

    /** Proveedor extra para abrir certificados .p12 (Android antiguo); null si no hace falta. */
    val extraKeyStoreProvider: java.security.Provider? get() = null

    /** Cifra datos con una llave guardada en este equipo (certificado de facturación, etc.). */
    fun protect(data: ByteArray): ByteArray
    fun unprotect(data: ByteArray): ByteArray
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

