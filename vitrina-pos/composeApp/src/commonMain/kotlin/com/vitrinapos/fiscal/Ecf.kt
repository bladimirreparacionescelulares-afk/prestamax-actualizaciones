package com.vitrinapos.fiscal

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/** Tipos de comprobante fiscal electrónico que usa un comercio. */
enum class EcfType(val code: Int, val title: String, val short: String) {
    CREDITO_FISCAL(31, "Factura de Crédito Fiscal Electrónica", "Crédito fiscal"),
    CONSUMO(32, "Factura de Consumo Electrónica", "Consumo"),
    NOTA_DEBITO(33, "Nota de Débito Electrónica", "Nota de débito"),
    NOTA_CREDITO(34, "Nota de Crédito Electrónica", "Nota de crédito"),
    GUBERNAMENTAL(45, "Comprobante Gubernamental Electrónico", "Gubernamental");

    val prefix: String get() = "E$code"

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: CONSUMO
        /** Tipos que se eligen al cobrar. */
        val forSale = listOf(CONSUMO, CREDITO_FISCAL, GUBERNAMENTAL)
    }
}

/** Ambientes de la DGII. */
enum class DgiiEnv(val path: String, val title: String) {
    TEST("testecf", "Pruebas"), CERT("certecf", "Certificación"), PROD("ecf", "Producción");

    companion object { fun of(s: String) = entries.firstOrNull { it.name == s } ?: TEST }
}

/** Formas de pago del e-CF (tabla de la DGII). */
object EcfPay {
    const val CASH = 1
    const val TRANSFER = 2   // cheque, transferencia o depósito
    const val CARD = 3       // tarjeta de débito o crédito
    const val CREDIT = 4
    fun fromMethod(method: String) = when (method) { "Efectivo" -> CASH; "Tarjeta" -> CARD; "Transferencia" -> TRANSFER; else -> 8 }
}

/** Monto ITBIS de cada línea. */
object TaxIndicator {
    const val ITBIS1 = 1   // 18 %
    const val ITBIS2 = 2   // 16 %
    const val ITBIS3 = 3   // 0 %
    const val EXEMPT = 4
}

data class EcfEmitter(
    val rnc: String,
    val name: String,
    val tradeName: String = "",
    val address: String,
    val phone: String = "",
    val email: String = "",
)

data class EcfBuyer(val rnc: String = "", val name: String = "", val foreignId: String = "")

data class EcfLine(
    val name: String,
    val code: String = "",
    val qty: Double,
    /** Precio unitario en centavos, con o sin ITBIS según [EcfDraft.pricesIncludeTax]. */
    val price: Long,
    val taxable: Boolean,
    val service: Boolean = false,
    /** Descuento de la línea en centavos. */
    val discount: Long = 0,
) {
    val gross: Long get() = (qty * price).roundToLong()
    val amount: Long get() = gross - discount
}

data class EcfPayment(val form: Int, val amount: Long)

data class EcfReference(val encf: String, val date: Long, val code: Int = 1, val reason: String = "")

/** Todo lo necesario para armar un e-CF. Montos en centavos. */
data class EcfDraft(
    val type: EcfType,
    val encf: String,
    val emitter: EcfEmitter,
    val buyer: EcfBuyer = EcfBuyer(),
    val lines: List<EcfLine>,
    val pricesIncludeTax: Boolean,
    val taxRate: Double = 18.0,
    val issuedAt: Long,
    val sequenceExpires: Long? = null,
    val payments: List<EcfPayment> = emptyList(),
    val creditTerm: Boolean = false,
    val reference: EcfReference? = null,
)

/** Totales del e-CF calculados como pide el formato (montos gravados sin ITBIS). */
data class EcfTotals(
    val taxedIncl: Long,
    val taxedBase: Long,
    val itbis: Long,
    val exempt: Long,
    val total: Long,
)

/** Documento ya firmado, listo para guardar, imprimir y enviar. */
@Serializable
data class EcfIssued(
    val type: Int,
    val encf: String,
    val xml: String,
    val securityCode: String,
    val signedAt: Long,
    val total: Long,
    val buyerRnc: String = "",
    val buyerName: String = "",
    val reference: String = "",
)

object Ecf {
    /** Las fechas fiscales se escriben en hora de República Dominicana, sin importar la zona del equipo. */
    val zone: ZoneId = ZoneId.of("America/Santo_Domingo")
    private val dayFmt = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    private val stampFmt = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")
    fun day(ms: Long): String = dayFmt.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun stamp(ms: Long): String = stampFmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** Factura de consumo menor a este monto: a la DGII se le manda el resumen (RFCE). */
    const val RFCE_LIMIT = 25_000_000L // RD$250,000.00 en centavos

    fun money(cents: Long): String {
        val neg = cents < 0
        val a = abs(cents)
        return (if (neg) "-" else "") + "${a / 100}.${(a % 100).toString().padStart(2, '0')}"
    }

    fun qty(q: Double): String {
        val c = (q * 100).roundToLong()
        return money(c)
    }

    fun encf(type: EcfType, n: Long) = type.prefix + n.toString().padStart(10, '0')

    /** Indicador de facturación de una línea según la tasa del negocio. */
    fun indicator(line: EcfLine, rate: Double): Int = when {
        !line.taxable -> TaxIndicator.EXEMPT
        rate == 18.0 -> TaxIndicator.ITBIS1
        rate == 16.0 -> TaxIndicator.ITBIS2
        rate == 0.0 -> TaxIndicator.ITBIS3
        else -> TaxIndicator.ITBIS1
    }

    fun totals(d: EcfDraft): EcfTotals {
        val r = d.taxRate / 100.0
        val taxed = d.lines.filter { it.taxable }.sumOf { it.amount }
        val exempt = d.lines.filter { !it.taxable }.sumOf { it.amount }
        return if (d.pricesIncludeTax) {
            val base = (taxed / (1 + r)).roundToLong()
            EcfTotals(taxed, base, taxed - base, exempt, taxed + exempt)
        } else {
            val itbis = (taxed * r).roundToLong()
            EcfTotals(taxed, taxed, itbis, exempt, taxed + itbis + exempt)
        }
    }

    /** Reparte un descuento global entre las líneas, en proporción a su monto (el resto en la última). */
    fun spreadDiscount(lines: List<EcfLine>, discount: Long): List<EcfLine> {
        if (discount <= 0 || lines.isEmpty()) return lines
        val gross = lines.sumOf { it.gross }
        if (gross <= 0) return lines
        var left = discount.coerceAtMost(gross)
        return lines.mapIndexed { i, l ->
            val part = if (i == lines.lastIndex) left else (discount.toDouble() * l.gross / gross).roundToLong().coerceAtMost(left)
            left -= part
            l.copy(discount = l.discount + part)
        }
    }

    /** Arma el XML del e-CF (sin firmar), en el orden exacto del XSD de la DGII. */
    fun xml(d: EcfDraft, signedAt: Long): String {
        require(d.lines.isNotEmpty()) { "El comprobante no tiene líneas" }
        val t = totals(d)
        val x = Xml()
        val code = d.type.code
        val rate = d.taxRate
        val anyTaxed = d.lines.any { it.taxable }
        x.open("ECF")
        x.open("Encabezado")
        x.leaf("Version", "1.0")
        x.open("IdDoc")
        x.leaf("TipoeCF", code.toString())
        x.leaf("eNCF", d.encf)
        if (d.type == EcfType.NOTA_CREDITO) {
            val ref = requireNotNull(d.reference) { "La nota de crédito necesita el comprobante que modifica" }
            x.leaf("IndicadorNotaCredito", if (d.issuedAt - ref.date > 30L * 86_400_000L) "1" else "0")
        } else if (code != 32) {
            // E31, E33, E45: la fecha de vencimiento de la secuencia es obligatoria.
            x.leaf("FechaVencimientoSecuencia", day(requireNotNull(d.sequenceExpires) { "Falta el vencimiento de la secuencia" }))
        }
        if (anyTaxed) x.leaf("IndicadorMontoGravado", if (d.pricesIncludeTax) "1" else "0")
        x.leaf("TipoIngresos", "01")
        x.leaf("TipoPago", if (d.creditTerm) "2" else "1")
        if (d.type != EcfType.NOTA_CREDITO && d.payments.isNotEmpty()) {
            x.open("TablaFormasPago")
            d.payments.filter { it.amount > 0 }.take(7).forEach { p ->
                x.open("FormaDePago"); x.leaf("FormaPago", p.form.toString()); x.leaf("MontoPago", money(p.amount)); x.close()
            }
            x.close()
        }
        x.close() // IdDoc
        x.open("Emisor")
        x.leaf("RNCEmisor", d.emitter.rnc)
        x.leaf("RazonSocialEmisor", d.emitter.name.take(150))
        if (d.emitter.tradeName.isNotBlank()) x.leaf("NombreComercial", d.emitter.tradeName.take(150))
        x.leaf("DireccionEmisor", d.emitter.address.take(100))
        val phone = phoneFormat(d.emitter.phone)
        if (phone != null) { x.open("TablaTelefonoEmisor"); x.leaf("TelefonoEmisor", phone); x.close() }
        if (emailOk(d.emitter.email)) x.leaf("CorreoEmisor", d.emitter.email.trim())
        x.leaf("FechaEmision", day(d.issuedAt))
        x.close()
        val hasBuyer = d.buyer.rnc.isNotBlank() || d.buyer.foreignId.isNotBlank() || d.buyer.name.isNotBlank()
        if (code == 31 || code == 45) require(d.buyer.rnc.isNotBlank() && d.buyer.name.isNotBlank()) { "Este comprobante necesita el RNC y el nombre del cliente" }
        if (code == 32 && t.total >= RFCE_LIMIT) require(d.buyer.rnc.isNotBlank() || d.buyer.foreignId.isNotBlank()) { "Una factura de consumo de RD$250,000 o más necesita la identificación del cliente" }
        if (hasBuyer || d.type != EcfType.NOTA_CREDITO) {
            x.open("Comprador")
            if (d.buyer.rnc.isNotBlank()) x.leaf("RNCComprador", d.buyer.rnc)
            else if (d.buyer.foreignId.isNotBlank() && code == 32) x.leaf("IdentificadorExtranjero", d.buyer.foreignId.take(20))
            if (d.buyer.name.isNotBlank()) x.leaf("RazonSocialComprador", d.buyer.name.take(150))
            x.close()
        }
        x.open("Totales")
        if (anyTaxed) {
            x.leaf("MontoGravadoTotal", money(t.taxedBase))
            when (indicatorForRate(rate)) {
                TaxIndicator.ITBIS1 -> x.leaf("MontoGravadoI1", money(t.taxedBase))
                TaxIndicator.ITBIS2 -> x.leaf("MontoGravadoI2", money(t.taxedBase))
                else -> x.leaf("MontoGravadoI3", money(t.taxedBase))
            }
        }
        if (t.exempt > 0) x.leaf("MontoExento", money(t.exempt))
        if (anyTaxed) {
            when (indicatorForRate(rate)) {
                TaxIndicator.ITBIS1 -> x.leaf("ITBIS1", "18")
                TaxIndicator.ITBIS2 -> x.leaf("ITBIS2", "16")
                else -> x.leaf("ITBIS3", "0")
            }
            x.leaf("TotalITBIS", money(t.itbis))
            when (indicatorForRate(rate)) {
                TaxIndicator.ITBIS1 -> x.leaf("TotalITBIS1", money(t.itbis))
                TaxIndicator.ITBIS2 -> x.leaf("TotalITBIS2", money(t.itbis))
                else -> x.leaf("TotalITBIS3", money(t.itbis))
            }
        }
        x.leaf("MontoTotal", money(t.total))
        x.close() // Totales
        x.close() // Encabezado
        x.open("DetallesItems")
        d.lines.forEachIndexed { i, l ->
            x.open("Item")
            x.leaf("NumeroLinea", (i + 1).toString())
            if (l.code.isNotBlank()) {
                x.open("TablaCodigosItem"); x.open("CodigosItem")
                x.leaf("TipoCodigo", "Interna"); x.leaf("CodigoItem", l.code.take(35))
                x.close(); x.close()
            }
            x.leaf("IndicadorFacturacion", indicator(l, rate).toString())
            x.leaf("NombreItem", clean(l.name).take(80).ifBlank { "Artículo" })
            x.leaf("IndicadorBienoServicio", if (l.service) "2" else "1")
            x.leaf("CantidadItem", qty(l.qty))
            x.leaf("PrecioUnitarioItem", money(l.price))
            if (l.discount > 0) {
                x.leaf("DescuentoMonto", money(l.discount))
                x.open("TablaSubDescuento"); x.open("SubDescuento")
                x.leaf("TipoSubDescuento", "$"); x.leaf("MontoSubDescuento", money(l.discount))
                x.close(); x.close()
            }
            x.leaf("MontoItem", money(l.amount))
            x.close()
        }
        x.close() // DetallesItems
        d.reference?.let { ref ->
            x.open("InformacionReferencia")
            x.leaf("NCFModificado", ref.encf)
            x.leaf("FechaNCFModificado", day(ref.date))
            x.leaf("CodigoModificacion", ref.code.toString())
            if (ref.reason.isNotBlank() && d.type == EcfType.NOTA_CREDITO) x.leaf("RazonModificacion", clean(ref.reason).take(90))
            x.close()
        }
        x.leaf("FechaHoraFirma", stamp(signedAt))
        x.close() // ECF
        return x.toString()
    }

    /** Resumen de Factura de Consumo (RFCE) para consumos menores de RD$250,000. */
    fun rfce(d: EcfDraft, securityCode: String): String {
        val t = totals(d)
        val x = Xml()
        val anyTaxed = d.lines.any { it.taxable }
        x.open("RFCE")
        x.open("Encabezado")
        x.leaf("Version", "1.0")
        x.open("IdDoc")
        x.leaf("TipoeCF", "32"); x.leaf("eNCF", d.encf); x.leaf("TipoIngresos", "01"); x.leaf("TipoPago", if (d.creditTerm) "2" else "1")
        if (d.payments.isNotEmpty()) {
            x.open("TablaFormasPago")
            d.payments.filter { it.amount > 0 }.take(7).forEach { p ->
                x.open("FormaDePago"); x.leaf("FormaPago", p.form.toString()); x.leaf("MontoPago", money(p.amount)); x.close()
            }
            x.close()
        }
        x.close()
        x.open("Emisor")
        x.leaf("RNCEmisor", d.emitter.rnc); x.leaf("RazonSocialEmisor", d.emitter.name.take(150)); x.leaf("FechaEmision", day(d.issuedAt))
        x.close()
        // El XSD del resumen exige la sección del comprador aunque vaya vacía.
        x.open("Comprador")
        if (d.buyer.rnc.isNotBlank()) x.leaf("RNCComprador", d.buyer.rnc)
        if (d.buyer.name.isNotBlank()) x.leaf("RazonSocialComprador", d.buyer.name.take(150))
        x.close()
        x.open("Totales")
        if (anyTaxed) {
            x.leaf("MontoGravadoTotal", money(t.taxedBase))
            x.leaf(if (indicatorForRate(d.taxRate) == TaxIndicator.ITBIS1) "MontoGravadoI1" else if (indicatorForRate(d.taxRate) == TaxIndicator.ITBIS2) "MontoGravadoI2" else "MontoGravadoI3", money(t.taxedBase))
        }
        if (t.exempt > 0) x.leaf("MontoExento", money(t.exempt))
        if (anyTaxed) {
            x.leaf("TotalITBIS", money(t.itbis))
            x.leaf(if (indicatorForRate(d.taxRate) == TaxIndicator.ITBIS1) "TotalITBIS1" else if (indicatorForRate(d.taxRate) == TaxIndicator.ITBIS2) "TotalITBIS2" else "TotalITBIS3", money(t.itbis))
        }
        x.leaf("MontoTotal", money(t.total))
        x.close()
        x.leaf("CodigoSeguridadeCF", securityCode)
        x.close()
        x.close()
        return x.toString()
    }

    /** Anulación de números e-NCF que nunca se usaron (ANECF). */
    fun anecf(rnc: String, ranges: List<Triple<EcfType, String, String>>, at: Long): String {
        val x = Xml()
        val count = ranges.sumOf { (_, a, b) -> b.takeLast(10).toLong() - a.takeLast(10).toLong() + 1 }
        x.open("ANECF")
        x.open("Encabezado")
        x.leaf("Version", "1.0"); x.leaf("RncEmisor", rnc); x.leaf("CantidadeNCFAnulados", count.toString()); x.leaf("FechaHoraAnulacioneNCF", stamp(at))
        x.close()
        x.open("DetalleAnulacion")
        ranges.groupBy { it.first }.entries.forEachIndexed { i, (type, rs) ->
            x.open("Anulacion")
            x.leaf("NoLinea", (i + 1).toString())
            x.leaf("TipoeCF", type.code.toString())
            x.open("TablaRangoSecuenciasAnuladaseNCF")
            rs.forEach { (_, a, b) -> x.open("Secuencias"); x.leaf("SecuenciaeNCFDesde", a); x.leaf("SecuenciaeNCFHasta", b); x.close() }
            x.close()
            x.leaf("CantidadeNCFAnulados", rs.sumOf { (_, a, b) -> b.takeLast(10).toLong() - a.takeLast(10).toLong() + 1 }.toString())
            x.close()
        }
        x.close()
        x.close()
        return x.toString()
    }

    /** Enlace del QR de la representación impresa. */
    fun qrUrl(env: DgiiEnv, rncEmisor: String, issued: EcfIssued, issuedAt: Long): String {
        val total = money(issued.total)
        return if (issued.type == 32 && issued.total < RFCE_LIMIT) {
            "https://fc.dgii.gov.do/${env.path}/ConsultaTimbreFC?RncEmisor=$rncEmisor&ENCF=${issued.encf}&MontoTotal=$total&CodigoSeguridad=${enc(issued.securityCode)}"
        } else {
            "https://ecf.dgii.gov.do/${env.path}/ConsultaTimbre?RncEmisor=$rncEmisor&RncComprador=${issued.buyerRnc}&ENCF=${issued.encf}" +
                "&FechaEmision=${day(issuedAt)}&MontoTotal=$total&FechaFirma=${enc(stamp(issued.signedAt))}&CodigoSeguridad=${enc(issued.securityCode)}"
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun indicatorForRate(rate: Double) = when (rate) { 16.0 -> TaxIndicator.ITBIS2; 0.0 -> TaxIndicator.ITBIS3; else -> TaxIndicator.ITBIS1 }

    fun phoneFormat(p: String): String? {
        val d = p.filter(Char::isDigit).let { if (it.length == 11 && it.startsWith("1")) it.drop(1) else it }
        return if (d.length == 10) "${d.substring(0, 3)}-${d.substring(3, 6)}-${d.substring(6)}" else null
    }

    private val emailRe = Regex("""\w+([-+.]\w+)*@\w+([-.]\w+)*\.\w+([-.]\w+)*""")
    fun emailOk(e: String) = e.isNotBlank() && e.length <= 80 && emailRe.matches(e.trim())

    /** Quita caracteres de control que la DGII no admite. */
    fun clean(s: String) = s.replace(Regex("[\\p{Cntrl}]"), " ").trim()

    /** Nombre del archivo que pide la DGII: RNC del emisor + e-NCF. */
    fun fileName(rnc: String, encf: String) = "$rnc$encf.xml"
}

/** Escritor de XML mínimo (sin espacios ni sangría, que es lo que se firma). */
private class Xml {
    private val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
    private val stack = ArrayDeque<String>()
    fun open(name: String) { sb.append('<').append(name).append('>'); stack.addLast(name) }
    fun close() { sb.append("</").append(stack.removeLast()).append('>') }
    fun leaf(name: String, value: String) {
        sb.append('<').append(name).append('>')
        for (c in value) when (c) { '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); else -> sb.append(c) }
        sb.append("</").append(name).append('>')
    }
    override fun toString() = sb.toString()
}
