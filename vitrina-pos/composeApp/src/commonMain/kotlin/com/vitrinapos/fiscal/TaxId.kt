package com.vitrinapos.fiscal

/** Validación de RNC (9 dígitos) y cédula (11 dígitos) con su dígito verificador. */
object TaxId {
    enum class Kind { RNC, CEDULA, OTHER }

    data class Result(val digits: String, val kind: Kind, val valid: Boolean, val message: String) {
        val formatted: String get() = format(digits)
    }

    fun digits(text: String) = text.filter(Char::isDigit)

    fun check(text: String): Result {
        val d = digits(text)
        return when (d.length) {
            9 -> if (rncOk(d)) Result(d, Kind.RNC, true, "RNC válido") else Result(d, Kind.RNC, false, "El RNC no es válido: revisa los números")
            11 -> if (cedulaOk(d)) Result(d, Kind.CEDULA, true, "Cédula válida")
                // Algunas cédulas antiguas no cumplen el dígito verificador y aun así existen: se advierte, no se bloquea.
                else Result(d, Kind.CEDULA, false, "La cédula no pasa la verificación: confírmala con el documento")
            0 -> Result(d, Kind.OTHER, false, "")
            else -> Result(d, Kind.OTHER, false, "Un RNC tiene 9 números y una cédula 11")
        }
    }

    /** RNC: pesos 7,9,8,6,5,4,3,2 sobre los 8 primeros dígitos, módulo 11. */
    fun rncOk(d: String): Boolean {
        if (d.length != 9 || !d.all(Char::isDigit)) return false
        val w = intArrayOf(7, 9, 8, 6, 5, 4, 3, 2)
        val sum = (0 until 8).sumOf { (d[it] - '0') * w[it] }
        val r = sum % 11
        val check = when (r) { 0 -> 2; 1 -> 1; else -> 11 - r }
        return check == d[8] - '0'
    }

    /** Cédula: Luhn con pesos 1,2 alternos sobre los 10 primeros dígitos. */
    fun cedulaOk(d: String): Boolean {
        if (d.length != 11 || !d.all(Char::isDigit)) return false
        var sum = 0
        for (i in 0 until 10) {
            var p = (d[i] - '0') * (if (i % 2 == 0) 1 else 2)
            if (p > 9) p -= 9
            sum += p
        }
        return (10 - sum % 10) % 10 == d[10] - '0'
    }

    fun format(d: String): String = when (d.length) {
        9 -> "${d.substring(0, 1)}-${d.substring(1, 3)}-${d.substring(3, 8)}-${d.substring(8)}"
        11 -> "${d.substring(0, 3)}-${d.substring(3, 10)}-${d.substring(10)}"
        else -> d
    }
}
