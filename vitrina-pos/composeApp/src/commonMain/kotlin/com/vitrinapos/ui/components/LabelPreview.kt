package com.vitrinapos.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vitrinapos.labels.LabelContent
import com.vitrinapos.labels.LabelLayout
import com.vitrinapos.labels.LabelOptions
import com.vitrinapos.labels.LabelPainter
import com.vitrinapos.platform.PdfCanvas

/**
 * Dibuja etiquetas con Compose. Sirve para la vista previa y, más adelante,
 * para convertir la etiqueta en imagen y mandarla a la etiquetadora.
 */
class ComposeLabelPainter(
    private val scope: DrawScope,
    private val measurer: TextMeasurer,
    private val pxPerMm: Float,
    private val ox: Float,
    private val oy: Float,
    private val ink: Color = Color.Black,
) : LabelPainter {
    private fun style(sizeMm: Float, bold: Boolean) = with(scope) {
        TextStyle(fontSize = (sizeMm * pxPerMm).toSp(), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = ink)
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float) {
        scope.drawRect(ink, Offset(ox + x * pxPerMm, oy + y * pxPerMm), Size(w * pxPerMm + 0.4f, h * pxPerMm + 0.4f))
    }

    override fun text(x: Float, y: Float, text: String, sizeMm: Float, bold: Boolean, align: PdfCanvas.TextAlign) {
        if (text.isEmpty()) return
        val r = measurer.measure(text, style(sizeMm, bold), softWrap = false, maxLines = 1)
        val w = r.size.width.toFloat()
        val left = ox + x * pxPerMm - when (align) {
            PdfCanvas.TextAlign.Left -> 0f
            PdfCanvas.TextAlign.Right -> w
            PdfCanvas.TextAlign.Center -> w / 2
        }
        scope.drawText(r, topLeft = Offset(left, oy + y * pxPerMm - r.firstBaseline))
    }

    override fun textWidth(text: String, sizeMm: Float, bold: Boolean): Float =
        measurer.measure(text, style(sizeMm, bold), softWrap = false, maxLines = 1).size.width / pxPerMm
}

/** Vista previa de una etiqueta sobre papel blanco, ajustada al espacio disponible. */
@Composable
fun LabelPreview(options: LabelOptions, content: LabelContent, modifier: Modifier = Modifier, maxHeightDp: Float = 200f) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = options.widthMm / options.heightMm
        val maxW = maxWidth.value
        var wDp = maxW
        var hDp = wDp / ratio
        if (hDp > maxHeightDp) { hDp = maxHeightDp; wDp = hDp * ratio }
        Canvas(
            Modifier.size(wDp.dp, hDp.dp).shadow(6.dp, RoundedCornerShape(6.dp)),
        ) {
            drawRoundRect(Color.White, cornerRadius = CornerRadius(6.dp.toPx()))
            val k = size.width / options.widthMm
            LabelLayout.draw(ComposeLabelPainter(this, measurer, k, 0f, 0f), options, content)
        }
    }
}
