package com.vitrinapos.pdf

import com.vitrinapos.data.AppSettings
import com.vitrinapos.data.Money
import com.vitrinapos.data.Time
import com.vitrinapos.data.fmtQty
import com.vitrinapos.db.Client
import com.vitrinapos.db.Quote
import com.vitrinapos.db.QuoteItem
import com.vitrinapos.platform.PdfCanvas
import com.vitrinapos.platform.PdfCanvas.TextAlign
import java.io.File
import kotlin.math.roundToLong

/** Dibuja la cotización en A4. Mismo diseño en Android y Windows. */
object QuotePdf {
    private const val INK = 0xFF1B2421
    private const val SUB = 0xFF6A7570
    private const val PRIMARY = 0xFF0E7C66
    private const val PRIMARY_DARK = 0xFF0A5E4E
    private const val CTA = 0xFFF26B3A
    private const val SOFT = 0xFFF1F6F4
    private const val LINE = 0xFFE3E9E6

    fun quoteCode(number: Long) = "COT-" + number.toString().padStart(4, '0')

    fun draw(
        c: PdfCanvas,
        s: AppSettings,
        quote: Quote,
        items: List<QuoteItem>,
        client: Client?,
        logo: File?,
        image: (String?) -> File?,
        showImages: Boolean = true,
    ) {
        val m = 40f
        val w = c.pageWidth
        val right = w - m
        var y = m

        fun header() {
            y = m
            // Logo o iniciales
            if (logo != null) {
                c.image(logo, m, y, 48f, 48f)
            } else {
                c.rect(m, y, 48f, 48f, PRIMARY, 10f)
                val initials = s.businessName.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "VP" }
                c.text(m + 24f, y + 31f, initials, 17f, true, 0xFFFFFFFF, TextAlign.Center)
            }
            val bx = m + 60f
            c.text(bx, y + 14f, s.businessName.ifBlank { "Mi Tienda" }, 14f, true)
            var by = y + 27f
            listOf(s.businessTagline, s.address, listOfNotNull(
                s.phone.takeIf { it.isNotBlank() }?.let { "Tel. $it" },
                s.taxId.takeIf { it.isNotBlank() }?.let { "RNC $it" },
            ).joinToString(" · ")).filter { it.isNotBlank() }.forEach {
                c.text(bx, by, it, 8.5f, color = SUB); by += 11f
            }
            c.text(right, y + 16f, "COTIZACIÓN", 20f, true, PRIMARY_DARK, TextAlign.Right)
            c.text(right, y + 31f, "N.º ${quoteCode(quote.number)}", 9.5f, true, INK, TextAlign.Right)
            c.text(right, y + 43f, Time.longDate(quote.createdAt), 9f, color = SUB, align = TextAlign.Right)
            y += 64f
            // Banda de color
            val half = (right - m) / 2
            c.rect(m, y, half, 3.5f, PRIMARY)
            c.rect(m + half, y, half, 3.5f, CTA)
            y += 16f
        }

        header()

        // Datos del cliente y validez
        val boxH = 52f
        val gap = 10f
        val bw1 = (right - m - gap * 2) * 0.44f
        val bw2 = (right - m - gap * 2 - bw1) / 2
        val validUntil = quote.createdAt + quote.validDays * 86_400_000L
        val boxes = listOf(
            Triple("PREPARADA PARA", quote.clientName.ifBlank { "Cliente" }, listOfNotNull(client?.phone?.takeIf { it.isNotBlank() }, client?.docId?.takeIf { it.isNotBlank() }?.let { "Doc. $it" }).joinToString(" · ")),
            Triple("VÁLIDA HASTA", Time.date(validUntil), "${quote.validDays} días"),
            Triple("ATENDIDO POR", s.sellerName.ifBlank { s.businessName }, Time.time(quote.createdAt)),
        )
        var bx = m
        boxes.forEachIndexed { i, (label, main, sub) ->
            val bwi = if (i == 0) bw1 else bw2
            c.rect(bx, y, bwi, boxH, SOFT, 8f)
            c.text(bx + 10f, y + 15f, label, 6.8f, true, SUB)
            c.text(bx + 10f, y + 30f, ellipsize(c, main, 10f, true, bwi - 20f), 10f, true)
            if (sub.isNotBlank()) c.text(bx + 10f, y + 43f, ellipsize(c, sub, 8.5f, false, bwi - 20f), 8.5f, color = SUB)
            bx += bwi + gap
        }
        y += boxH + 22f

        // Tabla
        val colCode = m + 250f
        val colQty = right - 170f
        val colPrice = right - 85f
        fun tableHeader() {
            c.text(m, y, "PRODUCTO", 7f, true, SUB)
            c.text(colCode, y, "CÓDIGO", 7f, true, SUB)
            c.text(colQty, y, "CANT.", 7f, true, SUB, TextAlign.Right)
            c.text(colPrice, y, "PRECIO", 7f, true, SUB, TextAlign.Right)
            c.text(right, y, "IMPORTE", 7f, true, SUB, TextAlign.Right)
            y += 6f
            c.line(m, y, right, y, INK, 1.2f)
            y += 4f
        }
        tableHeader()
        val rowH = if (showImages) 34f else 22f
        for (item in items) {
            if (y + rowH > c.pageHeight - 200f) {
                c.newPage(); header(); tableHeader()
            }
            val mid = y + rowH / 2
            var tx = m
            if (showImages) {
                val img = image(item.imagePath)
                if (img != null) c.image(img, m, y + 4f, 26f, 26f) else c.rect(m, y + 4f, 26f, 26f, SOFT, 5f)
                tx = m + 34f
            }
            c.text(tx, mid + 3.5f, ellipsize(c, item.name, 9.5f, false, colCode - tx - 10f), 9.5f)
            c.text(colCode, mid + 3.5f, item.code.ifBlank { "—" }, 8.5f, color = SUB)
            c.text(colQty, mid + 3.5f, item.qty.fmtQty(), 9.5f, align = TextAlign.Right)
            c.text(colPrice, mid + 3.5f, Money.plain(item.price), 9.5f, align = TextAlign.Right)
            c.text(right, mid + 3.5f, Money.plain((item.qty * item.price).roundToLong()), 9.5f, align = TextAlign.Right)
            y += rowH
            c.line(m, y, right, y, LINE, 0.7f)
        }
        y += 18f

        // Condiciones y totales
        if (y > c.pageHeight - 190f) { c.newPage(); header() }
        val sumX = right - 200f
        val termsTop = y
        c.text(m, y + 8f, "Condiciones", 9.5f, true)
        var ty = y + 22f
        val terms = (quote.notes.ifBlank { s.quoteTerms }).trim()
        wrapPdf(c, terms, 8.5f, sumX - m - 24f).forEach { c.text(m, ty, it, 8.5f, color = SUB); ty += 11.5f }

        var sy = termsTop + 8f
        fun sumRow(label: String, value: String) {
            c.text(sumX, sy, label, 9.5f, color = SUB)
            c.text(right, sy, value, 9.5f, align = TextAlign.Right)
            sy += 16f
        }
        val sym = s.currencySymbol
        sumRow("Subtotal", Money.format(quote.gross, sym))
        if (quote.discount > 0) sumRow("Descuento", "-" + Money.format(quote.discount, sym))
        if (quote.tax > 0) sumRow("${s.taxName} ${s.taxRate.fmtQty()} %" + if (quote.taxIncluded == 1L) " (incluido)" else "", Money.format(quote.tax, sym))
        sy += 2f
        c.rect(sumX - 10f, sy - 4f, right - sumX + 10f, 30f, PRIMARY_DARK, 7f)
        c.text(sumX, sy + 15f, "Total", 12f, true, 0xFFFFFFFF)
        c.text(right - 10f, sy + 15f, Money.format(quote.total, sym), 13f, true, 0xFFFFFFFF, TextAlign.Right)

        // Pie y firma
        val fy = c.pageHeight - m - 10f
        c.text(m, fy - 12f, "Gracias por preferirnos.", 8.5f, color = SUB)
        c.text(m, fy, "Documento generado con Vitrina POS.", 8.5f, color = SUB)
        c.line(right - 180f, fy - 12f, right, fy - 12f, 0xFF9AA6A1, 0.8f)
        c.text(right - 90f, fy, "Firma y sello", 8.5f, color = SUB, align = TextAlign.Center)
    }

    private fun ellipsize(c: PdfCanvas, text: String, size: Float, bold: Boolean, maxW: Float): String {
        if (c.textWidth(text, size, bold) <= maxW) return text
        var t = text
        while (t.isNotEmpty() && c.textWidth("$t…", size, bold) > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun wrapPdf(c: PdfCanvas, text: String, size: Float, maxW: Float): List<String> {
        val out = mutableListOf<String>()
        text.split('\n').forEach { para ->
            var line = ""
            para.split(' ').forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (c.textWidth(candidate, size) <= maxW || line.isEmpty()) line = candidate
                else { out += line; line = word }
            }
            out += line
        }
        return out
    }
}
