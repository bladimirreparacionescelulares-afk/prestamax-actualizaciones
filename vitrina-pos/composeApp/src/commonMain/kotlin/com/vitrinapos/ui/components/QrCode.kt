package com.vitrinapos.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodes {
    /** Matriz del QR (sin margen); true = módulo negro. */
    fun matrix(text: String, level: ErrorCorrectionLevel = ErrorCorrectionLevel.M): BitMatrix =
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to level, EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
}

/** Dibuja un código QR nítido, con fondo blanco y margen. */
@Composable
fun QrCodeImage(text: String, modifier: Modifier = Modifier, dark: Color = Color(0xFF111111)) {
    val m = remember(text) { QrCodes.matrix(text) }
    Canvas(modifier) {
        val quiet = 2
        val n = m.width + quiet * 2
        val cell = minOf(size.width, size.height) / n
        drawRect(Color.White, size = size)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m[x, y]) drawRect(dark, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
