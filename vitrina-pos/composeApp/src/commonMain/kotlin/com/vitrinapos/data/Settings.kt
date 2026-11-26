package com.vitrinapos.data

/** Configuración del negocio. Se guarda como pares clave/valor en la tabla `setting`. */
data class AppSettings(
    val businessName: String = "Mi Tienda",
    val businessTagline: String = "",
    val address: String = "",
    val phone: String = "",
    val taxId: String = "",
    val logoPath: String? = null,
    val currencySymbol: String = "$",
    val taxName: String = "ITBIS",
    val taxRate: Double = 18.0,
    val pricesIncludeTax: Boolean = true,
    val paperWidthMm: Int = 80,
    val printerId: String = "",
    val printerName: String = "",
    val ticketFooter: String = "¡Gracias por su compra!",
    val ticketAscii: Boolean = false,
    val autoPrint: Boolean = true,
    val openDrawer: Boolean = false,
    val quoteValidDays: Int = 15,
    val quoteTerms: String = "Precios sujetos a disponibilidad de inventario. Garantía de 30 días por defecto de fábrica.",
    val sellerName: String = "",
    val pin: String = "",
    val lastBackupAt: Long = 0,
    val onboarded: Boolean = false,
    // Etiquetas (propias de cada equipo, según su etiquetadora)
    val labelTemplate: String = "PRICE_QR",
    val labelW: Float = 50f,
    val labelH: Float = 30f,
    val labelShowName: Boolean = true,
    val labelShowPrice: Boolean = true,
    val labelShowCode: Boolean = true,
    val labelShowStore: Boolean = true,
    val labelOutput: String = "roll",
    val labelSheet: Int = 0,
    val labelAskOnSave: Boolean = true,
    // Etiquetadora de este equipo
    val labelPrinterId: String = "",
    val labelPrinterName: String = "",
    val labelLanguage: String = "TSPL",
    val labelDpi: Int = 203,
    val labelGap: Float = 2f,
    val labelDarkness: Int = 8,
    val labelOffX: Float = 0f,
    val labelOffY: Float = 0f,
) {
    val labelPrinter: com.vitrinapos.labels.LabelPrinterConfig
        get() = com.vitrinapos.labels.LabelPrinterConfig(
            language = runCatching { com.vitrinapos.labels.LabelLanguage.valueOf(labelLanguage) }.getOrDefault(com.vitrinapos.labels.LabelLanguage.TSPL),
            dpi = labelDpi, gapMm = labelGap, darkness = labelDarkness, offsetXmm = labelOffX, offsetYmm = labelOffY,
        )

    val labelOptions: com.vitrinapos.labels.LabelOptions
        get() = com.vitrinapos.labels.LabelOptions(
            template = runCatching { com.vitrinapos.labels.LabelTemplate.valueOf(labelTemplate) }.getOrDefault(com.vitrinapos.labels.LabelTemplate.PRICE_QR),
            widthMm = com.vitrinapos.labels.LabelLimits.clampW(labelW),
            heightMm = com.vitrinapos.labels.LabelLimits.clampH(labelH),
            showName = labelShowName, showPrice = labelShowPrice, showCode = labelShowCode, showStore = labelShowStore,
        )

    fun withLabelOptions(o: com.vitrinapos.labels.LabelOptions) = copy(
        labelTemplate = o.template.name, labelW = o.widthMm, labelH = o.heightMm,
        labelShowName = o.showName, labelShowPrice = o.showPrice, labelShowCode = o.showCode, labelShowStore = o.showStore,
    )

    val ticketChars: Int get() = if (paperWidthMm <= 58) 32 else 48

    fun toMap(): Map<String, String> = mapOf(
        "businessName" to businessName,
        "businessTagline" to businessTagline,
        "address" to address,
        "phone" to phone,
        "taxId" to taxId,
        "logoPath" to (logoPath ?: ""),
        "currencySymbol" to currencySymbol,
        "taxName" to taxName,
        "taxRate" to taxRate.toString(),
        "pricesIncludeTax" to pricesIncludeTax.toString(),
        "paperWidthMm" to paperWidthMm.toString(),
        "printerId" to printerId,
        "printerName" to printerName,
        "ticketFooter" to ticketFooter,
        "ticketAscii" to ticketAscii.toString(),
        "autoPrint" to autoPrint.toString(),
        "openDrawer" to openDrawer.toString(),
        "quoteValidDays" to quoteValidDays.toString(),
        "quoteTerms" to quoteTerms,
        "sellerName" to sellerName,
        "pin" to pin,
        "lastBackupAt" to lastBackupAt.toString(),
        "onboarded" to onboarded.toString(),
        "labelTemplate" to labelTemplate,
        "labelW" to labelW.toString(),
        "labelH" to labelH.toString(),
        "labelShowName" to labelShowName.toString(),
        "labelShowPrice" to labelShowPrice.toString(),
        "labelShowCode" to labelShowCode.toString(),
        "labelShowStore" to labelShowStore.toString(),
        "labelOutput" to labelOutput,
        "labelSheet" to labelSheet.toString(),
        "labelAskOnSave" to labelAskOnSave.toString(),
        "labelPrinterId" to labelPrinterId,
        "labelPrinterName" to labelPrinterName,
        "labelLanguage" to labelLanguage,
        "labelDpi" to labelDpi.toString(),
        "labelGap" to labelGap.toString(),
        "labelDarkness" to labelDarkness.toString(),
        "labelOffX" to labelOffX.toString(),
        "labelOffY" to labelOffY.toString(),
    )

    companion object {
        /** Ajustes propios de cada equipo: no se comparten por la red. */
        val LOCAL_KEYS = setOf(
            "printerId", "printerName", "paperWidthMm", "ticketAscii", "autoPrint", "openDrawer",
            "labelTemplate", "labelW", "labelH", "labelShowName", "labelShowPrice", "labelShowCode", "labelShowStore",
            "labelOutput", "labelSheet", "labelAskOnSave",
            "labelPrinterId", "labelPrinterName", "labelLanguage", "labelDpi", "labelGap", "labelDarkness", "labelOffX", "labelOffY",
        )

        fun fromMap(m: Map<String, String>): AppSettings {
            val d = AppSettings()
            fun s(k: String, def: String) = m[k] ?: def
            fun b(k: String, def: Boolean) = m[k]?.toBooleanStrictOrNull() ?: def
            fun i(k: String, def: Int) = m[k]?.toIntOrNull() ?: def
            return AppSettings(
                businessName = s("businessName", d.businessName),
                businessTagline = s("businessTagline", d.businessTagline),
                address = s("address", d.address),
                phone = s("phone", d.phone),
                taxId = s("taxId", d.taxId),
                logoPath = m["logoPath"]?.takeIf { it.isNotBlank() },
                currencySymbol = s("currencySymbol", d.currencySymbol),
                taxName = s("taxName", d.taxName),
                taxRate = m["taxRate"]?.toDoubleOrNull() ?: d.taxRate,
                pricesIncludeTax = b("pricesIncludeTax", d.pricesIncludeTax),
                paperWidthMm = i("paperWidthMm", d.paperWidthMm),
                printerId = s("printerId", d.printerId),
                printerName = s("printerName", d.printerName),
                ticketFooter = s("ticketFooter", d.ticketFooter),
                ticketAscii = b("ticketAscii", d.ticketAscii),
                autoPrint = b("autoPrint", d.autoPrint),
                openDrawer = b("openDrawer", d.openDrawer),
                quoteValidDays = i("quoteValidDays", d.quoteValidDays),
                quoteTerms = s("quoteTerms", d.quoteTerms),
                sellerName = s("sellerName", d.sellerName),
                pin = s("pin", d.pin),
                lastBackupAt = m["lastBackupAt"]?.toLongOrNull() ?: 0,
                onboarded = b("onboarded", d.onboarded),
                labelTemplate = s("labelTemplate", d.labelTemplate),
                labelW = m["labelW"]?.toFloatOrNull() ?: d.labelW,
                labelH = m["labelH"]?.toFloatOrNull() ?: d.labelH,
                labelShowName = b("labelShowName", d.labelShowName),
                labelShowPrice = b("labelShowPrice", d.labelShowPrice),
                labelShowCode = b("labelShowCode", d.labelShowCode),
                labelShowStore = b("labelShowStore", d.labelShowStore),
                labelOutput = s("labelOutput", d.labelOutput),
                labelSheet = i("labelSheet", d.labelSheet),
                labelAskOnSave = b("labelAskOnSave", d.labelAskOnSave),
                labelPrinterId = s("labelPrinterId", d.labelPrinterId),
                labelPrinterName = s("labelPrinterName", d.labelPrinterName),
                labelLanguage = s("labelLanguage", d.labelLanguage),
                labelDpi = i("labelDpi", d.labelDpi),
                labelGap = m["labelGap"]?.toFloatOrNull() ?: d.labelGap,
                labelDarkness = i("labelDarkness", d.labelDarkness),
                labelOffX = m["labelOffX"]?.toFloatOrNull() ?: d.labelOffX,
                labelOffY = m["labelOffY"]?.toFloatOrNull() ?: d.labelOffY,
            )
        }
    }
}
