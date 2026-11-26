package com.vitrinapos.labels

/** Lenguaje que entiende la etiquetadora. */
enum class LabelLanguage(val title: String, val hint: String) {
    TSPL("TSPL", "Xprinter, HPRT, Gprinter, Munbyn, TSC, iDPRT y la mayoría de etiquetadoras económicas"),
    ZPL("ZPL", "Zebra y compatibles (Honeywell, Godex en modo ZPL)"),
    ESCPOS("ESC/POS", "Impresoras de tickets con rollo de etiquetas"),
}

data class LabelPrinterConfig(
    val language: LabelLanguage = LabelLanguage.TSPL,
    val dpi: Int = 203,
    val gapMm: Float = 2f,
    /** Oscuridad 1 (claro) a 15 (oscuro). */
    val darkness: Int = 8,
    val offsetXmm: Float = 0f,
    val offsetYmm: Float = 0f,
) {
    val dotsPerMm: Float get() = dpi / 25.4f
}

