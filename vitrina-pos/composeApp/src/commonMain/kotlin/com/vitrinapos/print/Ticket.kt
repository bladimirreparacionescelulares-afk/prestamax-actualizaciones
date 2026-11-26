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
    data class Line(val text: String, val style: Style = Style.Normal, val align: Align = Align.Left, val qr: String? = null)

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

    /** Código QR impreso por la propia impresora (comando ESC/POS). */
    fun qr(data: String) { lines += Line("[ QR $data ]", Style.Normal, Align.Center, qr = data) }

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
            if (line.qr != null) {
                val data = line.qr.toByteArray(Charsets.US_ASCII)
                cmd(0x1D, 0x28, 0x6B, 4, 0, 49, 65, 50, 0) // modelo 2
                cmd(0x1D, 0x28, 0x6B, 3, 0, 49, 67, if (width >= 48) 7 else 6) // tamaño del módulo
                cmd(0x1D, 0x28, 0x6B, 3, 0, 49, 69, 49) // corrección M
                val len = data.size + 3
                cmd(0x1D, 0x28, 0x6B, len and 0xFF, len shr 8, 49, 80, 48)
                out.write(data)
                cmd(0x1D, 0x28, 0x6B, 3, 0, 49, 81, 48) // imprimir
                out.write(0x0A)
                continue
            }
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

        fun forSale(
            s: AppSettings, sale: Sale, items: List<SaleItem>, reprint: Boolean = false, repair: com.vitrinapos.db.RepairOrder? = null,
            ecf: com.vitrinapos.db.EcfDoc? = null,
        ): Ticket {
            val t = Ticket(s.ticketChars)
            header(t, s, fiscal = ecf != null)
            t.separator()
            if (ecf != null) fiscalHead(t, ecf)
            if (repair != null) {
                t.center("COMPROBANTE DE ENTREGA", Style.Bold)
                t.center(com.vitrinapos.data.repairCode(repair.number), Style.Big)
                t.left("Equipo: ${com.vitrinapos.data.DeviceTypes.label(repair.deviceType)} ${listOf(repair.brand, repair.model).filter { it.isNotBlank() }.joinToString(" ")}".trim())
                if (repair.serial.isNotBlank()) t.left("${if (repair.deviceType == com.vitrinapos.data.DeviceTypes.PHONE) "IMEI" else "Serie"}: ${repair.serial}")
                t.left("Recibido: ${Time.date(repair.createdAt)}")
                t.separator()
            }
            t.pair(if (sale.number < 0) "Venta P${-sale.number}" else "Venta #${sale.number.toString().padStart(4, '0')}", Time.dateTime(sale.createdAt))
            t.left("Cliente: ${ecf?.buyerName?.ifBlank { null } ?: sale.clientName}")
            val seller = sale.userName.ifBlank { s.sellerName }
            if (seller.isNotBlank()) t.left("Atendió: $seller")
            if (sale.number < 0) t.center("Venta sin conexión: el número final se asigna al sincronizar")
            if (reprint) t.center("*** COPIA ***", Ticket.Style.Bold)
            if (sale.status != "OK") t.center("*** VENTA ANULADA ***", Ticket.Style.Bold)
            t.separator()
            // Cada artículo en dos renglones: el nombre completo y debajo cantidad x precio con su importe.
            t.pair("DESCRIPCIÓN", "IMPORTE", Style.Bold)
            items.forEach {
                t.left(it.name)
                t.pair("   ${it.qty.fmtQty()} x ${Money.plain(it.price)}", Money.plain(Math.round(it.qty * it.price)))
                it.serials.split(',').filter { sn -> sn.isNotBlank() }.forEach { sn -> t.left("   IMEI: $sn") }
            }
            t.separator()
            val units = items.sumOf { it.qty }
            t.pair("Artículos", units.fmtQty())
            totals(t, s, sale.gross, sale.discount, sale.tax, sale.total, sale.taxIncluded == 1L)
            t.separator('=')
            if (sale.paymentMethod == PaymentMethods.CASH) {
                t.pair("Forma de pago", "Efectivo")
                t.pair("Recibido", Money.plain(sale.received))
                t.pair("Cambio", Money.plain(sale.changeDue), Style.Bold)
            } else {
                t.pair("Forma de pago", sale.paymentMethod)
            }
            if (ecf != null) fiscalFoot(t, s, ecf)
            if (repair != null) {
                t.separator()
                val until = repair.deliveredAt?.takeIf { repair.warrantyDays > 0 }?.let { Time.addDays(Time.startOfDay(it), repair.warrantyDays.toInt()) }
                if (until != null) {
                    t.center("GARANTÍA: ${repair.warrantyDays} DÍAS", Style.Bold)
                    t.center("Válida hasta el ${Time.date(until)}")
                    t.center("Cubre la reparación realizada. No cubre golpes, humedad ni equipos abiertos por terceros.")
                } else {
                    t.center("Equipo entregado sin reparar: sin garantía.")
                }
                t.qr(com.vitrinapos.data.repairCode(repair.number))
                t.blank(); t.blank()
                t.center("______________________________".take(t.width - 4))
                t.center("Recibí conforme")
            }
            footer(t, s)
            return t
        }

        /** Comprobante de recepción de un equipo en el taller. */
        /** Corte de caja al cerrar. */
        fun forCashClose(
            s: AppSettings, opened: Long, closed: Long, sm: com.vitrinapos.data.Repository.CashSummary, counted: Long,
            user: String, notes: String, bills: List<Pair<Long, Int>> = emptyList(),
        ): Ticket {
            val t = Ticket(s.ticketChars)
            header(t, s)
            t.separator()
            t.center("CORTE DE CAJA", Style.Bold)
            t.pair("Apertura", Time.dateTime(opened))
            t.pair("Cierre", Time.dateTime(closed))
            if (user.isNotBlank()) t.left("Cerró: $user")
            t.separator()
            t.pair("Efectivo inicial", Money.plain(sm.opening))
            t.pair("Ventas en efectivo", Money.plain(sm.cashSales))
            if (sm.ins > 0) t.pair("Entradas", Money.plain(sm.ins))
            if (sm.outs > 0) t.pair("Gastos y retiros", "-" + Money.plain(sm.outs))
            if (sm.refunds > 0) t.pair("Devoluciones", "-" + Money.plain(sm.refunds))
            t.pair("Debe haber", Money.plain(sm.expected), Style.Bold)
            t.separator()
            if (bills.isNotEmpty()) {
                bills.filter { it.second > 0 }.forEach { (v, n) -> t.pair("   ${Money.plain(v)} x $n", Money.plain(v * n)) }
            }
            t.pair("Contado", Money.plain(counted), Style.Bold)
            val diff = counted - sm.expected
            t.pair(if (diff == 0L) "Cuadra" else if (diff > 0) "Sobrante" else "Faltante", Money.plain(kotlin.math.abs(diff)), Style.Bold)
            t.separator()
            t.pair("Tarjeta", Money.plain(sm.cardSales))
            t.pair("Transferencia", Money.plain(sm.transferSales))
            if (notes.isNotBlank()) { t.separator(); t.left("Nota: $notes") }
            t.blank(); t.blank()
            t.center("______________________________".take(t.width - 4))
            t.center("Firma")
            return t
        }

        fun forRepair(s: AppSettings, r: com.vitrinapos.db.RepairOrder): Ticket {
            val t = Ticket(s.ticketChars)
            val sym = s.currencySymbol
            header(t, s)
            t.separator()
            t.center("COMPROBANTE DE RECEPCIÓN", Style.Bold)
            t.center(com.vitrinapos.data.repairCode(r.number), Style.Big)
            t.separator()
            t.pair("Fecha", Time.dateTime(r.createdAt))
            t.left("Cliente: ${r.clientName}")
            if (r.clientPhone.isNotBlank()) t.left("Tel.: ${r.clientPhone}")
            t.left("Equipo: ${com.vitrinapos.data.DeviceTypes.label(r.deviceType)} ${listOf(r.brand, r.model).filter { it.isNotBlank() }.joinToString(" ")}".trim())
            if (r.serial.isNotBlank()) t.left("${if (r.deviceType == com.vitrinapos.data.DeviceTypes.PHONE) "IMEI" else "Serie"}: ${r.serial}")
            if (r.color.isNotBlank()) t.left("Color: ${r.color}")
            if (r.problem.isNotBlank()) t.left("Falla: ${r.problem}")
            val cond = com.vitrinapos.data.Condition.parse(r.conditionText)
            if (cond.isNotEmpty()) {
                val ok = cond.filterValues { it == com.vitrinapos.data.Condition.OK }.keys
                val bad = cond.filterValues { it == com.vitrinapos.data.Condition.BAD }.keys
                if (ok.isNotEmpty()) t.left("Funciona: ${ok.joinToString(", ")}")
                if (bad.isNotEmpty()) t.left("Con daño: ${bad.joinToString(", ")}")
            }
            if (r.accessories.isNotBlank()) t.left("Deja: ${r.accessories.replace(",", ", ")}")
            t.separator()
            if (r.estimate > 0) t.pair("Presupuesto", Money.format(r.estimate, sym))
            if (r.diagnosisFee > 0) t.pair("Diagnóstico", Money.format(r.diagnosisFee, sym))
            if (r.deposit > 0) t.pair("Abono", Money.format(r.deposit, sym))
            if (r.estimate > 0 && r.deposit > 0) t.pair("Saldo estimado", Money.format((r.estimate - r.deposit).coerceAtLeast(0), sym), Style.Bold)
            r.estimatedAt?.let { t.pair("Entrega estimada", Time.date(it)) }
            if (r.technician.isNotBlank()) t.pair("Técnico", r.technician)
            if (r.receivedBy.isNotBlank()) t.pair("Recibió", r.receivedBy)
            t.separator()
            t.qr(com.vitrinapos.data.repairCode(r.number))
            if (s.repairTerms.isNotBlank()) t.center(s.repairTerms)
            if (r.diagnosisFee > 0) t.center("Si no aprueba la reparación, se cobra el diagnóstico.")
            t.center("Equipos no retirados en ${s.repairAbandonDays} días se consideran abandonados.")
            t.blank(); t.blank()
            t.center("______________________________".take(t.width - 4))
            t.center("Firma del cliente")
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

        private fun header(t: Ticket, s: AppSettings, fiscal: Boolean = false) {
            t.center(s.businessName.ifBlank { "Mi Tienda" }, Style.Big)
            if (s.businessTagline.isNotBlank()) t.center(s.businessTagline)
            // En un comprobante fiscal van la razón social y el RNC o la cédula del emisor.
            if (fiscal && s.fiscalName.isNotBlank() && s.fiscalName != s.businessName) t.center(s.fiscalName, Style.Bold)
            val address = if (fiscal) s.fiscalAddress.ifBlank { s.address } else s.address
            if (address.isNotBlank()) t.center(address)
            val id = if (fiscal) s.fiscalRnc.ifBlank { s.taxId } else s.taxId
            val idLabel = if (com.vitrinapos.fiscal.TaxId.digits(id).length == 11) "Cédula" else "RNC"
            val contact = listOfNotNull(s.phone.takeIf { it.isNotBlank() }?.let { "Tel. $it" }, id.takeIf { it.isNotBlank() }?.let { "$idLabel $it" })
            if (contact.isNotEmpty()) t.center(contact.joinToString(" · "))
        }

        /** Encabezado del e-CF: tipo de comprobante, e-NCF y comprador. */
        private fun fiscalHead(t: Ticket, d: com.vitrinapos.db.EcfDoc) {
            val type = com.vitrinapos.fiscal.EcfType.of(d.type.toInt())
            t.center(type.title.uppercase(), Style.Bold)
            t.pair("e-NCF", d.encf, Style.Bold)
            xmlValue(d.xml, "FechaVencimientoSecuencia")?.let { t.pair("Vence secuencia", it) }
            if (d.buyerRnc.isNotBlank()) {
                t.left("${if (d.buyerRnc.length == 11) "Cédula" else "RNC"} comprador: ${d.buyerRnc}")
                if (d.buyerName.isNotBlank()) t.left(d.buyerName)
            }
            if (d.refEncf.isNotBlank()) t.left("Modifica: ${d.refEncf}")
            t.separator()
        }

        /** Pie del e-CF: QR de consulta, código de seguridad y fecha de firma (Informe Técnico, 18.2.3). */
        private fun fiscalFoot(t: Ticket, s: AppSettings, d: com.vitrinapos.db.EcfDoc) {
            t.separator()
            val issued = com.vitrinapos.fiscal.EcfIssued(d.type.toInt(), d.encf, "", d.securityCode, d.signedAt, d.total, d.buyerRnc)
            t.qr(com.vitrinapos.fiscal.Ecf.qrUrl(com.vitrinapos.fiscal.DgiiEnv.of(d.env.ifBlank { s.fiscalEnv }), com.vitrinapos.fiscal.TaxId.digits(s.fiscalRnc), issued, d.signedAt))
            t.center("Código de seguridad: ${d.securityCode}")
            t.center("Fecha de firma: ${com.vitrinapos.fiscal.Ecf.stamp(d.signedAt)}")
            if (d.env.isNotBlank() && d.env != "PROD") t.center("*** AMBIENTE DE ${com.vitrinapos.fiscal.DgiiEnv.of(d.env).title.uppercase()}: SIN VALIDEZ FISCAL ***")
        }

        private fun xmlValue(xml: String, tag: String): String? =
            Regex("<$tag>([^<]*)</$tag>").find(xml)?.groupValues?.get(1)

        private fun totals(t: Ticket, s: AppSettings, gross: Long, discount: Long, tax: Long, total: Long, included: Boolean) {
            t.pair("Subtotal", Money.plain(gross))
            if (discount > 0) t.pair("Descuento", "-" + Money.plain(discount))
            if (tax > 0) {
                val label = "${s.taxName} ${s.taxRate.fmtQty()}%" + if (included) " incl." else ""
                t.pair(label, Money.plain(tax))
            }
            t.separator()
            t.pair("TOTAL", Money.format(total, s.currencySymbol), Style.Big)
        }

        private fun footer(t: Ticket, s: AppSettings) {
            t.blank()
            if (s.ticketFooter.isNotBlank()) t.center(s.ticketFooter)
        }
    }
}
