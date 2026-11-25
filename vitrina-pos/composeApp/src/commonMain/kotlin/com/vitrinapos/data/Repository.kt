package com.vitrinapos.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.vitrinapos.db.CashSession
import com.vitrinapos.db.Category
import com.vitrinapos.db.Client
import com.vitrinapos.db.Product
import com.vitrinapos.db.Quote
import com.vitrinapos.db.QuoteItem
import com.vitrinapos.db.Sale
import com.vitrinapos.db.SaleItem
import com.vitrinapos.db.StockMove
import com.vitrinapos.db.Supplier
import com.vitrinapos.db.VitrinaDb
import com.vitrinapos.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File

/** Una línea del carrito o de una cotización. */
data class CartLine(
    val productId: Long?,
    val name: String,
    val code: String,
    val qty: Double,
    val price: Long,
    val cost: Long,
    val taxable: Boolean,
    val imagePath: String?,
    val trackStock: Boolean,
) {
    val amount: Long get() = LineInput(qty, price, taxable).amount

    companion object {
        fun of(p: Product, qty: Double = 1.0) = CartLine(
            productId = p.id, name = p.name, code = p.code, qty = qty, price = p.price, cost = p.cost,
            taxable = p.taxable == 1L, imagePath = p.imagePath, trackStock = p.trackStock == 1L,
        )
    }
}

object PaymentMethods {
    const val CASH = "Efectivo"
    const val CARD = "Tarjeta"
    const val TRANSFER = "Transferencia"
    val all = listOf(CASH, CARD, TRANSFER)
}

object QuoteStatus {
    const val DRAFT = "BORRADOR"
    const val SENT = "ENVIADA"
    const val ACCEPTED = "ACEPTADA"
    const val SOLD = "VENDIDA"
    val all = listOf(DRAFT, SENT, ACCEPTED, SOLD)
    fun label(s: String) = when (s) {
        DRAFT -> "Borrador"; SENT -> "Enviada"; ACCEPTED -> "Aceptada"; SOLD -> "Vendida"; else -> s
    }
}

object SaleStatus {
    const val OK = "OK"
    const val VOID = "ANULADA"
}

object MoveType {
    const val SALE = "VENTA"
    const val ENTRY = "ENTRADA"
    const val ADJUST = "AJUSTE"
    const val VOID = "ANULACION"
    const val INITIAL = "INICIAL"
    fun label(t: String) = when (t) {
        SALE -> "Venta"; ENTRY -> "Entrada"; ADJUST -> "Ajuste"; VOID -> "Anulación"; INITIAL -> "Stock inicial"; else -> t
    }
}

data class EntryLine(val productId: Long, val qty: Double, val newCost: Long?)

class Repository(val platform: Platform) {
    private val scope = CoroutineScope(SupervisorJob() + platform.io)
    private val driver: SqlDriver = platform.createDriver()
    val db = VitrinaDb(driver)
    val imagesDir: File = File(platform.dataDir, "images").apply { mkdirs() }
    val docsDir: File = File(platform.dataDir, "documentos").apply { mkdirs() }

    private val io get() = platform.io

    val settings: StateFlow<AppSettings> = db.settingQueries.selectAll().asFlow().mapToList(io)
        .map { rows -> AppSettings.fromMap(rows.associate { it.key to it.content }) }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings.fromMap(db.settingQueries.selectAll().executeAsList().associate { it.key to it.content }))

    val products: StateFlow<List<Product>> = db.productQueries.selectActive().asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, db.productQueries.selectActive().executeAsList())

    val categories: StateFlow<List<Category>> = db.categoryQueries.selectAll().asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, db.categoryQueries.selectAll().executeAsList())

    val clients: StateFlow<List<Client>> = db.clientQueries.selectAll().asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, db.clientQueries.selectAll().executeAsList())

    val suppliers: StateFlow<List<Supplier>> = db.supplierQueries.selectAll().asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, db.supplierQueries.selectAll().executeAsList())

    val quotes: StateFlow<List<Quote>> = db.quoteQueries.selectAll().asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val recentSales: StateFlow<List<Sale>> = db.saleQueries.selectRecent(200).asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val openCash: StateFlow<CashSession?> = db.cashSessionQueries.selectOpen().asFlow().mapToOneOrNull(io)
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun close() {
        scope.cancel()
        driver.close()
    }

    // ---------- Configuración ----------

    suspend fun saveSettings(s: AppSettings) = withContext(io) {
        db.transaction { s.toMap().forEach { (k, v) -> db.settingQueries.upsert(k, v) } }
    }

    suspend fun updateSettings(change: (AppSettings) -> AppSettings) = saveSettings(change(settings.value))

    // ---------- Imágenes ----------

    /** Devuelve el archivo de una imagen guardada (se guarda solo el nombre para que los respaldos sirvan en cualquier equipo). */
    fun imageFile(name: String?): File? {
        if (name.isNullOrBlank()) return null
        val f = File(imagesDir, File(name).name)
        return if (f.exists()) f else null
    }

    /** Comprime la foto elegida y la guarda en la carpeta de la app. Devuelve el nombre del archivo. */
    suspend fun importImage(temp: File, prefix: String = "p"): String? = withContext(io) {
        val name = "${prefix}_${Time.now()}_${(1000..9999).random()}.jpg"
        val dest = File(imagesDir, name)
        if (platform.saveCompressedJpeg(temp, dest)) name else null
    }

    fun deleteImage(name: String?) {
        imageFile(name)?.delete()
    }

    // ---------- Categorías ----------

    suspend fun saveCategory(id: Long?, name: String, color: Long): Long = withContext(io) {
        db.transactionWithResult {
            if (id == null) {
                db.categoryQueries.insert(name.trim(), color)
                db.categoryQueries.lastId().executeAsOne()
            } else {
                db.categoryQueries.update(name.trim(), color, id); id
            }
        }
    }

    suspend fun deleteCategory(id: Long) = withContext(io) {
        db.transaction {
            db.categoryQueries.clearFromProducts(id)
            db.categoryQueries.delete(id)
        }
    }

    // ---------- Productos ----------

    data class ProductDraft(
        val id: Long?,
        val name: String,
        val code: String,
        val categoryId: Long?,
        val supplierId: Long?,
        val cost: Long,
        val price: Long,
        val initialStock: Double,
        val minStock: Double,
        val unit: String,
        val taxable: Boolean,
        val trackStock: Boolean,
        val imagePath: String?,
        val description: String,
    )

    suspend fun productById(id: Long): Product? = withContext(io) { db.productQueries.selectById(id).executeAsOneOrNull() }

    suspend fun productByCode(code: String): Product? = withContext(io) {
        db.productQueries.selectByCode(code.trim()).executeAsOneOrNull()
    }

    suspend fun saveProduct(d: ProductDraft): Long = withContext(io) {
        val now = Time.now()
        db.transactionWithResult {
            if (d.id == null) {
                db.productQueries.insert(
                    d.name.trim(), d.code.trim(), d.categoryId, d.supplierId, d.cost, d.price, d.initialStock, d.minStock,
                    d.unit.ifBlank { "und" }, if (d.taxable) 1 else 0, if (d.trackStock) 1 else 0, d.imagePath, d.description.trim(), now, now,
                )
                val id = db.productQueries.lastId().executeAsOne()
                if (d.initialStock != 0.0) db.stockMoveQueries.insert(id, MoveType.INITIAL, d.initialStock, "", now)
                id
            } else {
                db.productQueries.update(
                    d.name.trim(), d.code.trim(), d.categoryId, d.supplierId, d.cost, d.price, d.minStock,
                    d.unit.ifBlank { "und" }, if (d.taxable) 1 else 0, if (d.trackStock) 1 else 0, d.imagePath, d.description.trim(), now, d.id,
                )
                d.id
            }
        }
    }

    suspend fun deleteProduct(id: Long) = withContext(io) { db.productQueries.deactivate(Time.now(), id) }

    suspend fun adjustStock(productId: Long, newStock: Double, note: String) = withContext(io) {
        db.transaction {
            val p = db.productQueries.selectById(productId).executeAsOneOrNull() ?: return@transaction
            val diff = newStock - p.stock
            if (diff == 0.0) return@transaction
            val now = Time.now()
            db.productQueries.setStock(newStock, now, productId)
            db.stockMoveQueries.insert(productId, MoveType.ADJUST, diff, note, now)
        }
    }

    suspend fun registerEntry(lines: List<EntryLine>, supplierName: String?, note: String) = withContext(io) {
        val now = Time.now()
        val text = listOfNotNull(supplierName?.let { "Proveedor: $it" }, note.takeIf { it.isNotBlank() }).joinToString(" · ")
        db.transaction {
            for (l in lines) {
                if (l.qty == 0.0) continue
                db.productQueries.addStock(l.qty, now, l.productId)
                if (l.newCost != null) db.productQueries.setCost(l.newCost, now, l.productId)
                db.stockMoveQueries.insert(l.productId, MoveType.ENTRY, l.qty, text, now)
            }
        }
    }

    suspend fun movesByProduct(productId: Long): List<StockMove> = withContext(io) {
        db.stockMoveQueries.selectByProduct(productId).executeAsList()
    }

    suspend fun recentMoves(limit: Long = 100) = withContext(io) { db.stockMoveQueries.selectRecent(limit).executeAsList() }

    // ---------- Clientes y proveedores ----------

    suspend fun saveClient(id: Long?, name: String, phone: String, email: String, docId: String, address: String, notes: String): Long =
        withContext(io) {
            db.transactionWithResult {
                if (id == null) {
                    db.clientQueries.insert(name.trim(), phone.trim(), email.trim(), docId.trim(), address.trim(), notes.trim(), Time.now())
                    db.clientQueries.lastId().executeAsOne()
                } else {
                    db.clientQueries.update(name.trim(), phone.trim(), email.trim(), docId.trim(), address.trim(), notes.trim(), id); id
                }
            }
        }

    suspend fun deleteClient(id: Long) = withContext(io) { db.clientQueries.delete(id) }

    suspend fun clientById(id: Long): Client? = withContext(io) { db.clientQueries.selectById(id).executeAsOneOrNull() }

    suspend fun salesByClient(id: Long): List<Sale> = withContext(io) { db.saleQueries.selectByClient(id).executeAsList() }

    suspend fun saveSupplier(id: Long?, name: String, contact: String, phone: String, email: String, docId: String, address: String, notes: String): Long =
        withContext(io) {
            db.transactionWithResult {
                if (id == null) {
                    db.supplierQueries.insert(name.trim(), contact.trim(), phone.trim(), email.trim(), docId.trim(), address.trim(), notes.trim(), Time.now())
                    db.supplierQueries.lastId().executeAsOne()
                } else {
                    db.supplierQueries.update(name.trim(), contact.trim(), phone.trim(), email.trim(), docId.trim(), address.trim(), notes.trim(), id); id
                }
            }
        }

    suspend fun deleteSupplier(id: Long) = withContext(io) {
        db.transaction {
            db.supplierQueries.clearFromProducts(id)
            db.supplierQueries.delete(id)
        }
    }

    // ---------- Ventas ----------

    fun totalsFor(lines: List<CartLine>, discount: Long, s: AppSettings = settings.value): Totals =
        Totals.compute(lines.map { LineInput(it.qty, it.price, it.taxable) }, discount, s.taxRate, s.pricesIncludeTax)

    /** Registra la venta, descuenta el inventario y devuelve el id de la venta. */
    suspend fun recordSale(
        lines: List<CartLine>,
        client: Client?,
        discount: Long,
        paymentMethod: String,
        received: Long,
        quoteId: Long?,
    ): Long = withContext(io) {
        val s = settings.value
        val totals = totalsFor(lines, discount, s)
        val now = Time.now()
        val cost = lines.sumOf { (it.qty * it.cost).toLong() }
        val change = if (paymentMethod == PaymentMethods.CASH) (received - totals.total).coerceAtLeast(0) else 0
        val paid = if (paymentMethod == PaymentMethods.CASH) received else totals.total
        db.transactionWithResult {
            val number = db.saleQueries.nextNumber().executeAsOne()
            db.saleQueries.insert(
                number, client?.id, client?.name ?: "Consumidor final", totals.gross, totals.discount, totals.tax, totals.total, cost,
                if (totals.taxIncluded) 1 else 0, paymentMethod, paid, change, quoteId, now,
            )
            val saleId = db.saleQueries.lastId().executeAsOne()
            for (l in lines) {
                db.saleQueries.insertItem(saleId, l.productId, l.name, l.code, l.qty, l.price, l.cost, if (l.taxable) 1 else 0)
                if (l.productId != null && l.trackStock) {
                    db.productQueries.addStock(-l.qty, now, l.productId)
                    db.stockMoveQueries.insert(l.productId, MoveType.SALE, -l.qty, "Venta #$number", now)
                }
            }
            if (quoteId != null) db.quoteQueries.setStatus(QuoteStatus.SOLD, saleId, now, quoteId)
            saleId
        }
    }

    suspend fun voidSale(saleId: Long) = withContext(io) {
        db.transaction {
            val sale = db.saleQueries.selectById(saleId).executeAsOneOrNull() ?: return@transaction
            if (sale.status == SaleStatus.VOID) return@transaction
            val now = Time.now()
            db.saleQueries.setStatus(SaleStatus.VOID, saleId)
            for (item in db.saleQueries.itemsBySale(saleId).executeAsList()) {
                val pid = item.productId ?: continue
                val p = db.productQueries.selectById(pid).executeAsOneOrNull() ?: continue
                if (p.trackStock == 1L) {
                    db.productQueries.addStock(item.qty, now, pid)
                    db.stockMoveQueries.insert(pid, MoveType.VOID, item.qty, "Anulación venta #${sale.number}", now)
                }
            }
        }
    }

    suspend fun saleById(id: Long): Sale? = withContext(io) { db.saleQueries.selectById(id).executeAsOneOrNull() }

    suspend fun saleItems(id: Long): List<SaleItem> = withContext(io) { db.saleQueries.itemsBySale(id).executeAsList() }

    suspend fun salesBetween(from: Long, to: Long): List<Sale> = withContext(io) { db.saleQueries.selectBetween(from, to).executeAsList() }

    suspend fun itemsBetween(from: Long, to: Long): List<SaleItem> = withContext(io) { db.saleQueries.itemsBetween(from, to).executeAsList() }

    // ---------- Cotizaciones ----------

    suspend fun saveQuote(
        id: Long?,
        client: Client?,
        clientName: String,
        lines: List<CartLine>,
        discount: Long,
        validDays: Int,
        notes: String,
        status: String,
    ): Long = withContext(io) {
        val totals = totalsFor(lines, discount)
        val now = Time.now()
        db.transactionWithResult {
            val quoteId = if (id == null) {
                val number = db.quoteQueries.nextNumber().executeAsOne()
                db.quoteQueries.insert(
                    number, client?.id, clientName, totals.gross, totals.discount, totals.tax, totals.total,
                    if (totals.taxIncluded) 1 else 0, validDays.toLong(), notes, status, now, now,
                )
                db.quoteQueries.lastId().executeAsOne()
            } else {
                db.quoteQueries.update(
                    client?.id, clientName, totals.gross, totals.discount, totals.tax, totals.total,
                    if (totals.taxIncluded) 1 else 0, validDays.toLong(), notes, status, now, id,
                )
                db.quoteQueries.deleteItems(id)
                id
            }
            for (l in lines) {
                db.quoteQueries.insertItem(quoteId, l.productId, l.name, l.code, l.qty, l.price, if (l.taxable) 1 else 0, l.imagePath)
            }
            quoteId
        }
    }

    suspend fun setQuoteStatus(id: Long, status: String) = withContext(io) {
        val q = db.quoteQueries.selectById(id).executeAsOneOrNull() ?: return@withContext
        db.quoteQueries.setStatus(status, q.saleId, Time.now(), id)
    }

    suspend fun deleteQuote(id: Long) = withContext(io) {
        db.transaction {
            db.quoteQueries.deleteItems(id)
            db.quoteQueries.delete(id)
        }
    }

    suspend fun quoteById(id: Long): Quote? = withContext(io) { db.quoteQueries.selectById(id).executeAsOneOrNull() }

    suspend fun quoteItems(id: Long): List<QuoteItem> = withContext(io) { db.quoteQueries.itemsByQuote(id).executeAsList() }

    /** Convierte las líneas de una cotización en líneas de carrito usando los datos actuales del producto. */
    suspend fun quoteToCart(id: Long): List<CartLine> = withContext(io) {
        db.quoteQueries.itemsByQuote(id).executeAsList().map { qi ->
            val p = qi.productId?.let { db.productQueries.selectById(it).executeAsOneOrNull() }
            CartLine(
                productId = p?.id, name = qi.name, code = qi.code, qty = qi.qty, price = qi.price,
                cost = p?.cost ?: 0, taxable = qi.taxable == 1L, imagePath = p?.imagePath ?: qi.imagePath,
                trackStock = p?.trackStock == 1L,
            )
        }
    }

    // ---------- Caja ----------

    suspend fun openCashSession(amount: Long) = withContext(io) { db.cashSessionQueries.open(Time.now(), amount) }

    /** Efectivo esperado en caja: monto inicial + ventas en efectivo desde la apertura. */
    suspend fun expectedCash(session: CashSession): Long = withContext(io) {
        val sales = db.saleQueries.selectBetween(session.openedAt, Long.MAX_VALUE).executeAsList()
        session.openingAmount + sales.filter { it.status == SaleStatus.OK && it.paymentMethod == PaymentMethods.CASH }.sumOf { it.total }
    }

    suspend fun closeCashSession(session: CashSession, counted: Long, notes: String) = withContext(io) {
        val expected = expectedCash(session)
        db.cashSessionQueries.close(Time.now(), expected, counted, notes, session.id)
    }

    suspend fun cashHistory(): List<CashSession> = withContext(io) { db.cashSessionQueries.selectRecent().executeAsList() }

    // ---------- Respaldo ----------

    /** Pasa los cambios pendientes al archivo principal antes de copiarlo. */
    fun checkpoint() {
        runCatching {
            driver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { QueryResult.Value(Unit) }, 0)
        }
    }
}
