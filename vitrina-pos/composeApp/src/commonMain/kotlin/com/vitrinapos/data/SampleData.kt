package com.vitrinapos.data

import com.vitrinapos.ui.theme.CategoryPalette

/** Productos de ejemplo para probar la app. Se pueden borrar desde el catálogo. */
object SampleData {
    suspend fun load(repo: Repository) {
        val cats = listOf("Cargadores", "Audio", "Fundas", "Micas", "Energía", "Cables")
            .mapIndexed { i, n -> n to repo.saveCategory(null, n, CategoryPalette[i % CategoryPalette.size]) }.toMap()
        val sup1 = repo.saveSupplier(null, "TecnoImport", "Pedro Almonte", "809-555-0177", "", "", "", "")
        val sup2 = repo.saveSupplier(null, "Distribuidora Caribe", "Laura Pérez", "809-555-0133", "", "", "", "")
        data class P(val name: String, val code: String, val cat: String, val cost: Long, val price: Long, val stock: Double, val min: Double, val sup: Long)
        listOf(
            P("Cargador USB-C 20 W", "CRG-020", "Cargadores", 45000, 85000, 2.0, 3.0, sup1),
            P("Cargador de carro doble USB", "CRG-110", "Cargadores", 30000, 60000, 9.0, 3.0, sup1),
            P("Audífonos Bluetooth Pro", "AUD-210", "Audio", 70000, 120000, 8.0, 3.0, sup1),
            P("Bocina portátil mini", "AUD-305", "Audio", 60000, 110000, 5.0, 2.0, sup2),
            P("Funda silicona A54", "FND-054", "Fundas", 12000, 30000, 12.0, 4.0, sup2),
            P("Funda antigolpe iPhone 13", "FND-113", "Fundas", 15000, 35000, 7.0, 4.0, sup2),
            P("Mica cristal iPhone 13", "MIC-113", "Micas", 8000, 25000, 0.0, 5.0, sup2),
            P("Mica cristal A54", "MIC-054", "Micas", 8000, 25000, 20.0, 5.0, sup2),
            P("Power bank 10 000 mAh", "PWB-100", "Energía", 90000, 145000, 3.0, 3.0, sup1),
            P("Cable Lightning 1 m", "CBL-001", "Cables", 12000, 30000, 15.0, 5.0, sup1),
            P("Cable USB-C a USB-C 1 m", "CBL-002", "Cables", 12000, 30000, 18.0, 5.0, sup1),
        ).forEach { p ->
            repo.saveProduct(
                Repository.ProductDraft(
                    id = null, name = p.name, code = p.code, categoryId = cats[p.cat], supplierId = p.sup,
                    cost = p.cost, price = p.price, initialStock = p.stock, minStock = p.min, unit = "und",
                    taxable = true, trackStock = true, imagePath = null, description = "",
                ),
            )
        }
        repo.saveClient(null, "María Rodríguez", "809-555-0142", "", "", "", "")
        repo.saveClient(null, "José Peña", "829-555-0187", "", "", "", "")
        repo.saveClient(null, "Taller Centro SRL", "809-555-0123", "", "131000000", "Av. Principal #10", "")
    }
}
