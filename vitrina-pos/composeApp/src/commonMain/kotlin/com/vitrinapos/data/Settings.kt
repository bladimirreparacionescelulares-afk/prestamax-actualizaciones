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
) {
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
    )

    companion object {
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
            )
        }
    }
}
