package com.vitrinapos.print

import com.vitrinapos.data.AppSettings
import com.vitrinapos.data.Money
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.Time
import com.vitrinapos.data.fmtQty
import com.vitrinapos.db.Sale
import com.vitrinapos.db.SaleItem
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.Normalizer

/**
 * Un ticket como lista de líneas con formato. Sirve para imprimir (ESC/POS)
 * y para mostrar la vista previa en pantalla con el mismo contenido.
 */
class Ticket(val width: Int) {
    enum class Style { Normal, Bold, Big }
    enum class Align { Left, Center, Right }
    data class Line(val text: String, val style: Style = Style.Normal, val align: Align = Align.Left)

    val lines = mutableListOf<Line>()

    fun center(text: String, style: Style = Style.Normal) {
        val w = if (style == Style.Big) width / 2 else width
        wrap(text, w).forEach { lines += Line(it, style, Align.Center) }
    }

    fun left(text: String, style: Style = Style.Normal) {
        wrap(text, width).forEach { lines += Line(it, style, Align.Left) }
    }

    /** Línea ya formateada (columnas): se respeta tal cual, recortada al ancho. */
    fun raw(text: String, style: Style = Style.Normal) {
        lines += Line(text.take(if (style == Style.Big) width / 2 else width), style)
    }

    fun separator(c: Char = '-') { lines += Line(c.toString().repeat(width)) }

    fun blank() { lines += Line("") }

    /** Texto a la izquierda y a la derecha en la misma línea. */
    fun pair(left: String, right: String, style: Style = Style.Normal) {
        // Si no cabe en letra grande, se imprime en negrita de tamaño normal.
        if (style == Style.Big && left.length + right.length + 1 > width / 2) return pair(left, right, Style.Bold)
        val w = if (style == Style.Big) width / 2 else width
        val space = w - right.length - 1
        val l = if (left.length > space) left.take(space.coerceAtLeast(0)) else left
        lines += Line(l + " ".repeat((w - l.length - right.length).coerceAtLeast(1)) + right, style)
    }

    /** Vista previa como texto plano. */
    fun preview(): String = lines.joinToString("\n") { aligned(it) }

    /** Texto de la línea ya alineado dentro de su ancho (la mitad en letra grande). */
    fun aligned(line: Line): String {
        val w = if (line.style == Style.Big) width / 2 else width
        return when (line.align) {
            Align.Left -> line.text
            Align.Center -> " ".repeat(((w - line.text.length) / 2).coerceAtLeast(0)) + line.text
            Align.Right -> line.text.padStart(w)
        }
    }

    /** Bytes ESC/POS para impresoras térmicas compatibles con Epson. */
    fun escPos(ascii: Boolean, openDrawer: Boolean, cut: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        val cp850 = if (ascii) null else runCatching { Charset.forName("IBM850") }.getOrNull()
        fun cmd(vararg b: Int) = b.forEach { out.write(it) }
        cmd(0x1B, 0x40) // inicializar
        if (cp850 != null) cmd(0x1B, 0x74, 2) // tabla de caracteres PC850 (acentos y ñ)
        for (line in lines) {
            cmd(0x1B, 0x61, when (line.align) { Align.Left -> 0; Align.Center -> 1; Align.Right -> 2 })
            when (line.style) {
                Style.Normal -> { cmd(0x1B, 0x45, 0); cmd(0x1D, 0x21, 0x00) }
                Style.Bold -> { cmd(0x1B, 0x45, 1); cmd(0x1D, 0x21, 0x00) }
                Style.Big -> { cmd(0x1B, 0x45, 1); cmd(0x1D, 0x21, 0x11) }
            }
            val text = if (cp850 != null) line.text else stripAccents(line.text)
            out.write(text.toByteArray(cp850 ?: Charsets.US_ASCII))
            out.write(0x0A)
        }
        cmd(0x1B, 0x45, 0); cmd(0x1D, 0x21, 0x00); cmd(0x1B, 0x61, 0)
        cmd(0x1B, 0x64, 4) // avanzar papel
        if (cut) cmd(0x1D, 0x56, 0x42, 0x00) // corte parcial
        if (openDrawer) cmd(0x1B, 0x70, 0x00, 0x19, 0xFA)
        return out.toByteArray()
    }

    companion object {
        fun stripAccents(s: String): String {
            val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            return n.map { c ->
                when (c) {
                    '¡' -> '!'; '¿' -> '?'; '·' -> '-'; '–', '—' -> '-'; '“', '”' -> '"'; '‘', '’' -> '\''
                    else -> if (c.code < 128) c else '?'
                }
            }.joinToString("")
        }

        fun wrap(text: String, width: Int): List<String> {
            if (text.isEmpty()) return listOf("")
            val out = mutableListOf<String>()
            text.split('\n').forEach { para ->
                var line = ""
                para.split(' ').forEach { word ->
                    var w = word
                    while (w.length > width) {
                        if (line.isNotEmpty()) { out += line; line = "" }
                        out += w.take(width); w = w.drop(width)
                    }
                    line = when {
                        line.isEmpty() -> w
                        line.length + 1 + w.length <= width -> "$line $w"
                        else -> { out += line; w }
                    }
                }
                out += line
            }
            return out
        }

        fun forSale(s: AppSettings, sale: Sale, items: List<SaleItem>, reprint: Boolean = false): Ticket {
            val t = Ticket(s.ticketChars)
            val wide = s.ticketChars >= 48
            header(t, s)
            t.separator()
            t.pair(if (sale.number < 0) "Venta P${-sale.number}" else "Venta #${sale.number.toString().padStart(4, '0')}", Time.dateTime(sale.createdAt))
            t.left("Cliente: ${sale.clientName}")
            val seller = sale.userName.ifBlank { s.sellerName }
            if (seller.isNotBlank()) t.left("Atendió: $seller")
            if (sale.number < 0) t.center("Venta sin conexión: el número final se asigna al sincronizar")
            if (reprint) t.center("*** COPIA ***", Ticket.Style.Bold)
            if (sale.status != "OK") t.center("*** VENTA ANULADA ***", Ticket.Style.Bold)
            t.separator()
            if (wide) {
                t.raw(columns("Producto", "Cant.", "Precio", "Importe", t.width), Ticket.Style.Bold)
                items.forEach { it ->
                    val amount = Math.round(it.qty * it.price)
                    val name = it.name
                    val firstWidth = t.width - 28
                    if (name.length <= firstWidth) {
                        t.raw(columns(name, it.qty.fmtQty(), Money.plain(it.price), Money.plain(amount), t.width))
                    } else {
                        t.left(name)
                        t.raw(columns("", it.qty.fmtQty(), Money.plain(it.price), Money.plain(amount), t.width))
                    }
                }
            } else {
                items.forEach {
                    val amount = Math.round(it.qty * it.price)
                    t.left(it.name)
                    t.pair("  ${it.qty.fmtQty()} x ${Money.plain(it.price)}", Money.plain(amount))
                }
            }
            t.separator()
            totals(t, s, sale.gross, sale.discount, sale.tax, sale.total, sale.taxIncluded == 1L)
            t.separator()
            if (sale.paymentMethod == PaymentMethods.CASH) {
                t.pair("Efectivo", Money.plain(sale.received))
                t.pair("Cambio", Money.plain(sale.changeDue))
            } else {
                t.pair("Pago", sale.paymentMethod)
            }
            footer(t, s)
            return t
        }

        fun test(s: AppSettings): Ticket {
            val t = Ticket(s.ticketChars)
            header(t, s)
            t.separator()
            t.center("TICKET DE PRUEBA", Style.Bold)
            t.left("Papel: ${s.paperWidthMm} mm (${s.ticketChars} caracteres)")
            t.left("Acentos: á é í ó ú ñ Ñ ¿? ¡!")
            t.separator('=')
            t.pair("Total", Money.format(123450, s.currencySymbol), Style.Big)
            footer(t, s)
            return t
        }

        private fun header(t: Ticket, s: AppSettings) {
            t.center(s.businessName.ifBlank { "Mi Tienda" }, Style.Big)
            if (s.businessTagline.isNotBlank()) t.center(s.businessTagline)
            if (s.address.isNotBlank()) t.center(s.address)
            val contact = listOfNotNull(s.phone.takeIf { it.isNotBlank() }?.let { "Tel. $it" }, s.taxId.takeIf { it.isNotBlank() }?.let { "RNC $it" })
            if (contact.isNotEmpty()) t.center(contact.joinToString(" · "))
        }

        private fun totals(t: Ticket, s: AppSettings, gross: Long, discount: Long, tax: Long, total: Long, included: Boolean) {
            t.pair("Subtotal", Money.plain(gross))
            if (discount > 0) t.pair("Descuento", "-" + Money.plain(discount))
            if (tax > 0) {
                val label = "${s.taxName} ${s.taxRate.fmtQty()}%" + if (included) " incl." else ""
                t.pair(label, Money.plain(tax))
            }
            t.pair("TOTAL", Money.format(total, s.currencySymbol), Style.Big)
        }

        private fun footer(t: Ticket, s: AppSettings) {
            t.blank()
            if (s.ticketFooter.isNotBlank()) t.center(s.ticketFooter)
        }

        private fun columns(name: String, qty: String, price: String, amount: String, width: Int): String {
            val nameW = width - 28
            return name.take(nameW).padEnd(nameW) + qty.padStart(5) + price.padStart(11) + amount.padStart(12)
        }
    }
}
