package com.vitrinapos.data

import kotlin.math.abs
import kotlin.math.roundToLong

/** Todo el dinero se guarda en centavos (Long) para evitar errores de redondeo. */
object Money {
    fun format(cents: Long, symbol: String = "$"): String {
        val negative = cents < 0
        val value = abs(cents)
        val whole = value / 100
        val frac = (value % 100).toInt()
        val wholeText = groupThousands(whole)
        val text = "$symbol$wholeText.${frac.toString().padStart(2, '0')}"
        return if (negative) "-$text" else text
    }

    /** Sin símbolo, útil para tickets y columnas. */
    fun plain(cents: Long): String = format(cents, "")

    /** Acepta "1234", "1,234.5", "1234.50" o "1234,50". Devuelve null si no es un número válido. */
    fun parse(input: String): Long? {
        var t = input.trim().replace(" ", "").replace("$", "")
        if (t.isEmpty()) return null
        val lastDot = t.lastIndexOf('.')
        val lastComma = t.lastIndexOf(',')
        t = when {
            lastDot >= 0 && lastComma >= 0 ->
                if (lastDot > lastComma) t.replace(",", "") else t.replace(".", "").replace(',', '.')
            lastComma >= 0 -> {
                val decimals = t.length - lastComma - 1
                if (t.count { it == ',' } == 1 && decimals in 1..2) t.replace(',', '.') else t.replace(",", "")
            }
            else -> t
        }
        val d = t.toDoubleOrNull() ?: return null
        if (d.isNaN() || d.isInfinite()) return null
        return (d * 100).roundToLong()
    }

    /** Texto editable a partir de centavos: 125050 -> "1250.50", 120000 -> "1200". */
    fun toInput(cents: Long): String {
        if (cents == 0L) return ""
        val whole = cents / 100
        val frac = abs(cents % 100)
        return if (frac == 0L) whole.toString() else "$whole.${frac.toString().padStart(2, '0')}"
    }

    private fun groupThousands(n: Long): String {
        val s = n.toString()
        val sb = StringBuilder()
        s.forEachIndexed { i, c ->
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return sb.toString()
    }
}

fun Double.fmtQty(): String {
    if (this == this.roundToLong().toDouble()) return this.roundToLong().toString()
    val s = ((this * 1000).roundToLong() / 1000.0).toString()
    return s.trimEnd('0').trimEnd('.')
}

fun parseQty(input: String): Double? = input.trim().replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }

fun percentText(rate: Double): String = rate.fmtQty() + " %"

/** Una línea vendible: venta, cotización o carrito. */
data class LineInput(val qty: Double, val price: Long, val taxable: Boolean) {
    val amount: Long get() = (qty * price).roundToLong()
}

/**
 * Totales de un documento.
 * - gross: suma de las líneas tal como están los precios.
 * - discount: monto descontado del total.
 * - tax: impuesto (incluido en el precio o sumado aparte según [taxIncluded]).
 * - total: lo que paga el cliente.
 */
data class Totals(
    val gross: Long,
    val discount: Long,
    val tax: Long,
    val total: Long,
    val taxIncluded: Boolean,
) {
    companion object {
        val Empty = Totals(0, 0, 0, 0, true)

        fun compute(lines: List<LineInput>, discount: Long, taxRatePercent: Double, taxIncluded: Boolean): Totals {
            val gross = lines.sumOf { it.amount }
            val d = discount.coerceIn(0, gross)
            val factor = if (gross == 0L) 0.0 else (gross - d).toDouble() / gross
            val r = taxRatePercent / 100.0
            var tax = 0.0
            for (l in lines) {
                if (!l.taxable || r <= 0) continue
                val adj = l.amount * factor
                tax += if (taxIncluded) adj - adj / (1 + r) else adj * r
            }
            val taxCents = tax.roundToLong()
            val total = if (taxIncluded) gross - d else gross - d + taxCents
            return Totals(gross, d, taxCents, total, taxIncluded)
        }
    }
}
