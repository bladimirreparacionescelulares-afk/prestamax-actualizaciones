package com.vitrinapos.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.vitrinapos.db.AppUser
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
import com.vitrinapos.net.Crypto
import com.vitrinapos.net.RowCodec
import com.vitrinapos.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToLong

/** Una línea del carrito o de una cotización. */
@Serializable
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
    val service: Boolean = false,
) {
    val amount: Long get() = LineInput(qty, price, taxable).amount

    companion object {
        fun of(p: Product, qty: Double = 1.0) = CartLine(
            productId = p.id, name = p.name, code = p.code, qty = qty, price = p.price, cost = p.cost,
            taxable = p.taxable == 1L, imagePath = p.imagePath, trackStock = p.trackStock == 1L, service = p.isService == 1L,
        )
    }
}

@Serializable
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
    /** Servicio (mano de obra, instalación…): no lleva existencias y en el e-CF va como servicio. */
    val isService: Boolean = false,
)

@Serializable
data class EntryLine(val productId: Long, val qty: Double, val newCost: Long?)

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

/** Número visible de una venta. Las ventas sin conexión llevan número provisional: P1, P2… */
fun saleNumberText(n: Long): String = if (n < 0) "P${-n}" else n.toString().padStart(4, '0')
val Sale.label: String get() = if (number < 0) "Venta pendiente P${-number}" else "Venta #$number"

/** Cómo trabaja este equipo en la red. */
object NetRole {
    const val SOLO = "solo"
    const val SERVER = "server"
    const val CLIENT = "client"
}

/** Lo implementa el cliente de red: envía operaciones e imágenes al equipo principal. */
interface RemoteGateway {
    /** Lanza IOException si no hay conexión con el principal. */
    suspend fun execute(op: Op, opId: String): JsonElement
    suspend fun uploadImage(name: String, file: File)
}

class OfflineException(message: String) : IOException(message)

/** Una regla del negocio impide la operación (caja cerrada, sin stock…). El mensaje se muestra tal cual. */
class RuleException(message: String) : IllegalStateException(message)

/** Tipos de movimiento de efectivo en la caja. */
object CashMoveType {
    const val IN = "ENTRADA"
    const val OUT = "SALIDA"
    const val EXPENSE = "GASTO"
    const val REFUND = "DEVOLUCION"
    fun label(t: String) = when (t) { IN -> "Entrada"; OUT -> "Retiro"; EXPENSE -> "Gasto"; REFUND -> "Devolución"; else -> t }
    fun sign(t: String) = if (t == IN) 1 else -1
}

class Repository(val platform: Platform) {
    private val scope = CoroutineScope(SupervisorJob() + platform.io)
    val driver: SqlDriver = platform.createDriver()
    val db = VitrinaDb(driver)

    init {
        RowCodec.installTriggers(driver)
    }
    val imagesDir: File = File(platform.dataDir, "images").apply { mkdirs() }
    val docsDir: File = File(platform.dataDir, "documentos").apply { mkdirs() }
    private val io get() = platform.io

    /** Todas las escrituras pasan por aquí, una a la vez. */
    val writeLock = Any()

    // ---------- Datos propios de este equipo ----------

    val meta: StateFlow<Map<String, String>> = db.syncQueries.metaAll().asFlow().mapToList(io)
        .map { rows -> rows.associate { it.key to it.content } }
        .stateIn(scope, SharingStarted.Eagerly, db.syncQueries.metaAll().executeAsList().associate { it.key to it.content })

    fun metaGet(key: String): String? = db.syncQueries.metaAll().executeAsList().firstOrNull { it.key == key }?.content

    fun metaPut(key: String, value: String) = db.syncQueries.metaPut(key, value)

    val deviceId: String = metaGet("deviceId") ?: UUID.randomUUID().toString().also { metaPut("deviceId", it) }

    val role: String get() = meta.value["role"] ?: metaGet("role") ?: NetRole.SOLO

    init {
        // Cajas abiertas antes de que existiera el trabajo en red pertenecen a este equipo.
        if (role != NetRole.CLIENT) db.cashSessionQueries.claimOrphans(deviceId)
    }

    /** Se asigna cuando este equipo está conectado a un principal. */
    @Volatile var remote: RemoteGateway? = null

    /** Facturación electrónica de este equipo. */
    val fiscal: Fiscal by lazy { Fiscal(this) }

    /** Usuario que está usando la app (null si no hay usuarios configurados). */
    val currentUser = MutableStateFlow<AppUser?>(null)
    private val userName: String get() = currentUser.value?.name ?: ""

    /** Sube cada vez que cambian los datos; el principal lo usa para avisar a los demás. */
    val changeSignal = MutableStateFlow(db.syncQueries.maxSeq().executeAsOne())

    // ---------- Datos compartidos ----------

    val settings: StateFlow<AppSettings> = combine(db.settingQueries.selectAll().asFlow().mapToList(io), meta) { rows, m ->
        AppSettings.fromMap(rows.associate { it.key to it.content } + localSettings(m)).also { RepairStatus.configure(it.repairStatuses) }
    }.stateIn(
        scope, SharingStarted.Eagerly,
        AppSettings.fromMap(db.settingQueries.selectAll().executeAsList().associate { it.key to it.content } + localSettings(meta.value))
            .also { RepairStatus.configure(it.repairStatuses) },
    )

    /** Lee la configuración directamente de la base (sin esperar al flujo), para hacer cumplir reglas. */
    private fun currentSettings(): AppSettings =
        AppSettings.fromMap(db.settingQueries.selectAll().executeAsList().associate { it.key to it.content } + localSettings(meta.value))

    private fun localSettings(m: Map<String, String>) =
        m.filterKeys { it.startsWith("s.") }.mapKeys { it.key.removePrefix("s.") }

    val products: StateFlow<List<Product>> = db.productQueries.selectActive().asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, db.productQueries.selectActive().executeAsList())

    val categories: StateFlow<List<Category>> = db.categoryQueries.selectAll().asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, db.categoryQueries.selectAll().executeAsList())

    val clients: StateFlow<List<Client>> = db.clientQueries.selectAll().asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, db.clientQueries.selectAll().executeAsList())

    val suppliers: StateFlow<List<Supplier>> = db.supplierQueries.selectAll().asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, db.supplierQueries.selectAll().executeAsList())

    val users: StateFlow<List<AppUser>> = db.appUserQueries.selectAll().asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, db.appUserQueries.selectAll().executeAsList())

    val quotes: StateFlow<List<Quote>> = db.quoteQueries.selectAll().asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val recentSales: StateFlow<List<Sale>> = db.saleQueries.selectRecent(200).asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val openCash: StateFlow<CashSession?> = db.cashSessionQueries.selectOpen(deviceId).asFlow().mapToOneOrNull(io)
        .stateIn(scope, SharingStarted.Eagerly, null)

    val pendingSales: StateFlow<Long> = db.syncQueries.outboxCount().asFlow().mapToOne(io)
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** Empieza a enviar a la DGII en segundo plano (y, en una caja, a pedir números para trabajar sin conexión). */
    fun startFiscal() = fiscal.startWorker(scope)

    /** Comprobantes firmados que todavía no se envían a la DGII. */
    fun unsentFiscalDocs(): Long = db.fiscalQueries.pendingDocs().executeAsList().count { it.status == EcfStatus.SIGNED }.toLong()

    fun close() {
        fiscal.stopWorker()
        scope.cancel()
        driver.close()
    }

    // ================= Ejecutar operaciones =================

    private suspend fun run(op: Op): JsonElement {
        val r = remote
        return if (r == null) withContext(io) { applyLocal(op) } else r.execute(op, UUID.randomUUID().toString())
    }

    private fun JsonElement.asId(): Long = jsonPrimitive.long

    /** Aplica una operación en la base de datos de este equipo (solo o principal). */
    fun applyLocal(op: Op): JsonElement {
        val result = synchronized(writeLock) {
            val now = Time.now()
            db.transactionWithResult<JsonElement> {
                when (op) {
                    is Op.SaveSettings -> { op.values.forEach { (k, v) -> db.settingQueries.upsert(k, v) }; JsonNull }
                    is Op.SaveCategory -> JsonPrimitive(
                        if (op.id == null) {
                            db.categoryQueries.insert(op.name.trim(), op.color); db.categoryQueries.lastId().executeAsOne()
                        } else { db.categoryQueries.update(op.name.trim(), op.color, op.id); op.id },
                    )
                    is Op.DeleteCategory -> { db.categoryQueries.clearFromProducts(op.id); db.categoryQueries.delete(op.id); JsonNull }
                    is Op.SaveProduct -> {
                        // Todo producto lleva código: si no lo escribieron, se le asigna uno para su etiqueta.
                        val d = if (op.draft.code.isBlank()) op.draft.copy(code = newCode()) else op.draft
                        if (d.price <= 0) throw RuleException("El precio de venta debe ser mayor que cero")
                        if (d.cost < 0) throw RuleException("El costo no puede ser negativo")
                        db.productQueries.selectByCode(d.code.trim()).executeAsList()
                            .firstOrNull { it.active == 1L && it.id != d.id }
                            ?.let { throw RuleException("El código ${d.code.trim()} ya lo tiene \"${it.name}\"") }
                        if (d.id == null) {
                            db.productQueries.insert(
                                d.name.trim(), d.code.trim(), d.categoryId, d.supplierId, d.cost, d.price, d.initialStock, d.minStock,
                                d.unit.ifBlank { "und" }, if (d.taxable) 1 else 0, if (d.trackStock) 1 else 0, d.imagePath, d.description.trim(), now, now,
                            )
                            val id = db.productQueries.lastId().executeAsOne()
                            db.productQueries.setService(if (d.isService) 1 else 0, id)
                            if (d.initialStock != 0.0) db.stockMoveQueries.insert(id, MoveType.INITIAL, d.initialStock, "", now, op.user)
                            JsonPrimitive(id)
                        } else {
                            db.productQueries.update(
                                d.name.trim(), d.code.trim(), d.categoryId, d.supplierId, d.cost, d.price, d.minStock,
                                d.unit.ifBlank { "und" }, if (d.taxable) 1 else 0, if (d.trackStock) 1 else 0, d.imagePath, d.description.trim(), now, d.id,
                            )
                            db.productQueries.setService(if (d.isService) 1 else 0, d.id)
                            JsonPrimitive(d.id)
                        }
                    }
                    is Op.DeleteProduct -> { db.productQueries.deactivate(now, op.id); JsonNull }
                    is Op.EnsureCodes -> {
                        val out = LinkedHashMap<String, JsonElement>()
                        for (id in op.ids) {
                            val p = db.productQueries.selectById(id).executeAsOneOrNull() ?: continue
                            val code = p.code.ifBlank {
                                newCode().also {
                                    db.productQueries.update(
                                        p.name, it, p.categoryId, p.supplierId, p.cost, p.price, p.minStock, p.unit, p.taxable, p.trackStock,
                                        p.imagePath, p.description, now, p.id,
                                    )
                                }
                            }
                            out[id.toString()] = JsonPrimitive(code)
                        }
                        JsonObject(out)
                    }
                    is Op.AdjustStock -> {
                        if (op.newStock < 0) throw RuleException("El stock no puede quedar en negativo")
                        if (op.note.isBlank()) throw RuleException("Escribe el motivo del ajuste")
                        val p = db.productQueries.selectById(op.productId).executeAsOneOrNull()
                        if (p != null && op.newStock != p.stock) {
                            db.productQueries.setStock(op.newStock, now, op.productId)
                            db.stockMoveQueries.insert(op.productId, MoveType.ADJUST, op.newStock - p.stock, op.note, now, op.user)
                        }
                        JsonNull
                    }
                    is Op.RegisterEntry -> {
                        val text = listOfNotNull(op.supplierName?.let { "Proveedor: $it" }, op.note.takeIf { it.isNotBlank() }).joinToString(" · ")
                        if (op.lines.any { it.qty < 0 }) throw RuleException("Las cantidades de una entrada deben ser positivas. Para sacar mercancía usa un ajuste con su motivo.")
                        for (l in op.lines) {
                            if (l.qty == 0.0) continue
                            db.productQueries.addStock(l.qty, now, l.productId)
                            if (l.newCost != null) db.productQueries.setCost(l.newCost, now, l.productId)
                            db.stockMoveQueries.insert(l.productId, MoveType.ENTRY, l.qty, text, now, op.user)
                        }
                        JsonNull
                    }
                    is Op.SaveClient -> JsonPrimitive(
                        if (op.id == null) {
                            db.clientQueries.insert(op.name.trim(), op.phone.trim(), op.email.trim(), op.docId.trim(), op.address.trim(), op.notes.trim(), now)
                            db.clientQueries.lastId().executeAsOne()
                        } else {
                            db.clientQueries.update(op.name.trim(), op.phone.trim(), op.email.trim(), op.docId.trim(), op.address.trim(), op.notes.trim(), op.id); op.id
                        },
                    )
                    is Op.DeleteClient -> { db.clientQueries.delete(op.id); JsonNull }
                    is Op.SaveSupplier -> JsonPrimitive(
                        if (op.id == null) {
                            db.supplierQueries.insert(op.name.trim(), op.contact.trim(), op.phone.trim(), op.email.trim(), op.docId.trim(), op.address.trim(), op.notes.trim(), now)
                            db.supplierQueries.lastId().executeAsOne()
                        } else {
                            db.supplierQueries.update(op.name.trim(), op.contact.trim(), op.phone.trim(), op.email.trim(), op.docId.trim(), op.address.trim(), op.notes.trim(), op.id); op.id
                        },
                    )
                    is Op.DeleteSupplier -> { db.supplierQueries.clearFromProducts(op.id); db.supplierQueries.delete(op.id); JsonNull }
                    is Op.RecordSale -> JsonPrimitive(insertSale(op, number = db.saleQueries.nextNumber().executeAsOne()))
                    is Op.VoidSale -> {
                        val sale = db.saleQueries.selectById(op.saleId).executeAsOneOrNull()
                        if (sale != null && sale.status != SaleStatus.VOID) {
                            // Una venta con e-CF solo se anula con una nota de crédito E34 firmada.
                            val docs = db.fiscalQueries.docBySale(op.saleId).executeAsList()
                            val original = docs.firstOrNull { it.type != 34L }
                            if (original != null && docs.none { it.type == 34L }) {
                                val note = fiscal.issueCreditNote(sale, db.saleQueries.itemsBySale(op.saleId).executeAsList(), original, op.reason, now)
                                fiscal.store(note, op.saleId, deviceId, now)
                            }
                            // Si la venta era de otro turno y se pagó en efectivo, el dinero sale de la caja de hoy.
                            val session = op.deviceId.takeIf { it.isNotBlank() }?.let { db.cashSessionQueries.selectOpen(it).executeAsOneOrNull() }
                            if (session != null && sale.paymentMethod == PaymentMethods.CASH && sale.createdAt < session.openedAt && sale.total > 0)
                                db.cashSessionQueries.insertMove(session.id, CashMoveType.REFUND, sale.total, "Anulación ${sale.number.let { "venta #$it" }}", op.user, now)
                            db.saleQueries.setStatus(SaleStatus.VOID, op.saleId)
                            for (item in db.saleQueries.itemsBySale(op.saleId).executeAsList()) {
                                val pid = item.productId ?: continue
                                val p = db.productQueries.selectById(pid).executeAsOneOrNull() ?: continue
                                if (p.trackStock == 1L) {
                                    db.productQueries.addStock(item.qty, now, pid)
                                    db.stockMoveQueries.insert(pid, MoveType.VOID, item.qty, "Anulación venta #${sale.number}", now, op.user)
                                }
                            }
                        }
                        JsonNull
                    }
                    is Op.SaveQuote -> {
                        val totals = totalsFor(op.lines, op.discount)
                        val quoteId = if (op.id == null) {
                            val number = db.quoteQueries.nextNumber().executeAsOne()
                            db.quoteQueries.insert(
                                number, op.clientId, op.clientName, totals.gross, totals.discount, totals.tax, totals.total,
                                if (totals.taxIncluded) 1 else 0, op.validDays.toLong(), op.notes, op.status, now, now, op.user,
                            )
                            db.quoteQueries.lastId().executeAsOne()
                        } else {
                            db.quoteQueries.update(
                                op.clientId, op.clientName, totals.gross, totals.discount, totals.tax, totals.total,
                                if (totals.taxIncluded) 1 else 0, op.validDays.toLong(), op.notes, op.status, now, op.id,
                            )
                            db.quoteQueries.deleteItems(op.id)
                            op.id
                        }
                        for (l in op.lines) {
                            db.quoteQueries.insertItem(quoteId, l.productId, l.name, l.code, l.qty, l.price, if (l.taxable) 1 else 0, l.imagePath)
                        }
                        JsonPrimitive(quoteId)
                    }
                    is Op.SetQuoteStatus -> {
                        db.quoteQueries.selectById(op.id).executeAsOneOrNull()?.let { db.quoteQueries.setStatus(op.status, it.saleId, now, op.id) }
                        JsonNull
                    }
                    is Op.DeleteQuote -> { db.quoteQueries.deleteItems(op.id); db.quoteQueries.delete(op.id); JsonNull }
                    is Op.OpenCash -> {
                        if (op.amount < 0) throw RuleException("El efectivo inicial no puede ser negativo")
                        if (db.cashSessionQueries.selectOpen(op.deviceId).executeAsOneOrNull() == null)
                            db.cashSessionQueries.open(now, op.amount, op.deviceId, op.user)
                        JsonNull
                    }
                    is Op.CashMove -> {
                        if (op.amount <= 0) throw RuleException("El monto debe ser mayor que cero")
                        if (op.note.isBlank()) throw RuleException("Escribe el motivo")
                        val session = db.cashSessionQueries.selectOpen(op.deviceId).executeAsOneOrNull()
                            ?: throw RuleException("La caja de este equipo está cerrada")
                        if (op.kind != CashMoveType.IN && op.amount > expectedCashSync(session))
                            throw RuleException("No hay tanto efectivo en la caja")
                        db.cashSessionQueries.insertMove(session.id, op.kind, op.amount, op.note.trim(), op.user, now)
                        JsonNull
                    }
                    is Op.CloseCash -> {
                        val s = db.cashSessionQueries.selectById(op.sessionId).executeAsOneOrNull()
                        if (s != null && s.closedAt == null) db.cashSessionQueries.close(now, expectedCashSync(s), op.counted, op.notes, s.id)
                        JsonNull
                    }
                    is Op.SaveRepair -> JsonPrimitive(saveRepairLocal(op, now))
                    is Op.SetRepairStatus -> {
                        val r = db.repairQueries.selectById(op.id).executeAsOneOrNull()
                        if (r != null && r.status != op.status) {
                            db.repairQueries.setStatus(op.status, now, op.id)
                            db.repairQueries.insertEvent(op.id, op.status, op.note.trim(), op.user, now)
                        } else if (r != null && op.note.isNotBlank()) {
                            db.repairQueries.insertEvent(op.id, op.status, op.note.trim(), op.user, now)
                        }
                        JsonNull
                    }
                    is Op.DeliverRepair -> JsonPrimitive(deliverRepairLocal(op, now))
                    is Op.ReserveFiscalBlock -> {
                        val id = fiscal.reserveLocal(op.ecfType, op.deviceId, op.size.coerceIn(10, 1000), now)
                            ?: throw FiscalException("No quedan números e-NCF E${op.ecfType} autorizados en el equipo principal.")
                        JsonPrimitive(id)
                    }
                    is Op.SaveFiscalRange -> {
                        require(op.fromNo in 1..op.toNo && op.toNo <= 9_999_999_999L) { "El rango no es válido" }
                        val overlap = db.fiscalQueries.ranges().executeAsList().any { it.type == op.ecfType.toLong() && op.fromNo <= it.toNo && op.toNo >= it.fromNo }
                        if (overlap) throw FiscalException("Ese rango se cruza con otro ya registrado")
                        db.fiscalQueries.insertSequence(op.ecfType.toLong(), op.fromNo, op.toNo, op.expiresAt, "", null, now)
                        JsonPrimitive(db.fiscalQueries.lastSequenceId().executeAsOne())
                    }
                    is Op.DeleteFiscalRange -> {
                        if (db.fiscalQueries.blocksOfRange(op.id).executeAsList().isNotEmpty()) throw FiscalException("Ese rango ya se empezó a usar y no se puede borrar")
                        db.fiscalQueries.deleteSequence(op.id)
                        JsonNull
                    }
                    is Op.SetUserPermissions -> { db.appUserQueries.setPermissions(op.permissions, op.id); JsonNull }
                    is Op.SaveUser -> JsonPrimitive(
                        if (op.id == null) {
                            db.appUserQueries.insert(op.name.trim(), op.pinHash ?: "", op.role, if (op.active) 1 else 0, now)
                            db.appUserQueries.lastId().executeAsOne()
                        } else {
                            db.appUserQueries.update(op.name.trim(), op.role, if (op.active) 1 else 0, op.id)
                            if (op.pinHash != null) db.appUserQueries.setPin(op.pinHash, op.id)
                            op.id
                        },
                    )
                }
            }
        }
        changeSignal.value = db.syncQueries.maxSeq().executeAsOne()
        return result
    }

    private fun newCode(): String = com.vitrinapos.labels.Codes.newInternalEan { db.productQueries.selectByCode(it).executeAsOneOrNull() != null }

    /** Guarda la orden (dentro de una transacción). Devuelve su id. */
    private fun saveRepairLocal(op: Op.SaveRepair, now: Long): Long {
        val d = op.draft
        // Cliente nuevo: se registra para que aparezca en Clientes y en su historial.
        val clientId = d.clientId ?: d.clientName.trim().takeIf { it.isNotEmpty() }?.let {
            db.clientQueries.insert(it, d.clientPhone.trim(), d.clientEmail.trim(), "", "", "", now)
            db.clientQueries.lastId().executeAsOne()
        }
        if (d.id != null) {
            db.repairQueries.update(
                clientId, d.clientName.trim(), d.clientPhone.trim(), d.clientEmail.trim(), d.deviceType, d.brand.trim(), d.model.trim(), d.serial.trim(),
                d.color.trim(), d.unlockType, d.unlockCode, d.problem.trim(), d.conditionText, d.accessories, d.photos, d.estimate, d.diagnosisFee,
                d.estimatedAt, d.technician.trim(), d.notes.trim(), d.warrantyDays.toLong(), now, d.id,
            )
            return d.id
        }
        val number = db.repairQueries.nextNumber().executeAsOne()
        db.repairQueries.insert(
            number, clientId, d.clientName.trim(), d.clientPhone.trim(), d.clientEmail.trim(), d.deviceType, d.brand.trim(), d.model.trim(),
            d.serial.trim(), d.color.trim(), d.unlockType, d.unlockCode, d.problem.trim(), d.conditionText, d.accessories, d.photos,
            d.estimate, d.deposit, d.diagnosisFee, d.estimatedAt, d.technician.trim(), RepairStatus.RECEIVED, d.notes.trim(), op.userName,
            d.warrantyDays.toLong(), now, now,
        )
        val id = db.repairQueries.lastId().executeAsOne()
        db.repairQueries.insertEvent(id, RepairStatus.RECEIVED, if (d.deposit > 0) "Abono ${Money.format(d.deposit, settings.value.currencySymbol)}" else "", op.userName, now)
        if (d.deposit > 0) {
            // El abono es un cobro: entra en Caja, en Ventas y en Reportes.
            // El abono se factura aparte (con su ITBIS incluido cuando los precios lo incluyen).
            val taxable = settings.value.pricesIncludeTax && settings.value.taxRate > 0
            val line = CartLine(null, "Abono reparación ${repairCode(number)}", repairCode(number), 1.0, d.deposit, 0, taxable, null, false)
            insertSale(
                Op.RecordSale(listOf(line), clientId, d.clientName.trim(), 0, op.depositMethod, d.deposit, null, op.deviceId, op.userId, op.userName, now),
                number = db.saleQueries.nextNumber().executeAsOne(),
            )
        }
        return id
    }

    /**
     * Reglas que el principal revisa en toda venta (cobro, abono, entrega del taller):
     * cantidades y precios válidos, caja abierta en el equipo que vende y stock suficiente.
     * Las ventas hechas sin conexión se aceptan siempre para no trabar la cola; la caja ya lo revisó.
     */
    private fun checkSaleRules(op: Op.RecordSale) {
        if (op.lines.isEmpty()) throw RuleException("La venta no tiene artículos")
        op.lines.forEach { l ->
            if (l.qty <= 0) throw RuleException("${l.name}: la cantidad debe ser mayor que cero")
            if (l.price < 0) throw RuleException("${l.name}: el precio no puede ser negativo")
        }
        if (op.discount < 0) throw RuleException("El descuento no puede ser negativo")
        if (op.offline) return
        if (currentSettings().requireCash && db.cashSessionQueries.selectOpen(op.deviceId).executeAsOneOrNull() == null)
            throw RuleException("La caja de este equipo está cerrada. Ábrela para poder cobrar.")
        if (!op.allowNoStock) {
            op.lines.filter { it.productId != null && it.trackStock }.groupBy { it.productId!! }.forEach { (pid, ls) ->
                val p = db.productQueries.selectById(pid).executeAsOneOrNull() ?: return@forEach
                val want = ls.sumOf { it.qty }
                if (p.trackStock == 1L && want > p.stock + 1e-9)
                    throw RuleException(if (p.stock <= 0) "${p.name}: no hay existencias" else "${p.name}: solo quedan ${p.stock.fmtQty()}")
            }
        }
    }

    /** Entrega (dentro de una transacción): crea la venta del saldo y cierra la orden. Devuelve el id de la venta. */
    private fun deliverRepairLocal(op: Op.DeliverRepair, now: Long): Long {
        val r = db.repairQueries.selectById(op.id).executeAsOneOrNull() ?: throw IllegalStateException("La orden no existe")
        if (RepairStatus.isClosed(r.status)) throw IllegalStateException("La orden ${r.code} ya fue entregada")
        val code = r.code
        val charged = totalsFor(op.lines, 0).total
        if (r.deposit > charged) throw IllegalStateException("El abono es mayor que el total a cobrar")
        // El abono ya se facturó al recibir: se descuenta del servicio (y lo que sobre, como descuento),
        // sin líneas negativas, para que la factura de entrega sea válida como e-CF.
        var left = r.deposit
        val lines = op.lines.map { l ->
            if (left > 0 && l.productId == null && l.qty == 1.0 && l.price > 0) {
                val cut = minOf(left, l.price)
                left -= cut
                l.copy(name = "${l.name} · menos abono $code", price = l.price - cut)
            } else l
        }.filter { it.price > 0 || it.productId != null }
        val saleId = insertSale(
            Op.RecordSale(lines, r.clientId, r.clientName, left, op.paymentMethod, op.received, null, op.deviceId, op.userId, op.userName, now),
            number = db.saleQueries.nextNumber().executeAsOne(),
        )
        val warranty = if (op.repaired) r.warrantyDays else 0
        db.repairQueries.deliver(RepairStatus.DELIVERED, now, charged, saleId, warranty, now, op.id)
        val note = listOf(
            if (op.repaired) "Reparado" else "Sin reparar",
            "Total ${Money.format(charged, settings.value.currencySymbol)}",
            if (warranty > 0) "garantía $warranty días" else "",
            op.note.trim(),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        db.repairQueries.insertEvent(op.id, RepairStatus.DELIVERED, note, op.userName, now)
        return saleId
    }

    /** Inserta la venta (dentro de una transacción). Devuelve su id. */
    private fun insertSale(op: Op.RecordSale, number: Long): Long {
        checkSaleRules(op)
        val totals = totalsFor(op.lines, op.discount)
        val cost = op.lines.sumOf { (it.qty * it.cost).roundToLong() }
        val cash = op.paymentMethod == PaymentMethods.CASH
        val change = if (cash) (op.received - totals.total).coerceAtLeast(0) else 0
        val paid = if (cash) op.received else totals.total
        db.saleQueries.insert(
            number, op.clientId, op.clientName, totals.gross, totals.discount, totals.tax, totals.total, cost,
            if (totals.taxIncluded) 1 else 0, op.paymentMethod, paid, change, op.quoteId, op.createdAt,
            op.deviceId, op.userId, op.userName,
        )
        val saleId = db.saleQueries.lastId().executeAsOne()
        for (l in op.lines) {
            db.saleQueries.insertItem(saleId, l.productId, l.name, l.code, l.qty, l.price, l.cost, if (l.taxable) 1 else 0)
            if (l.productId != null && l.trackStock) {
                db.productQueries.addStock(-l.qty, op.createdAt, l.productId)
                db.stockMoveQueries.insert(l.productId, MoveType.SALE, -l.qty, "Venta #$number", op.createdAt, op.userName)
            }
        }
        if (op.quoteId != null) db.quoteQueries.setStatus(QuoteStatus.SOLD, saleId, op.createdAt, op.quoteId)
        // Facturación electrónica: el e-CF ya viene firmado (caja sin conexión) o se firma aquí.
        if (op.ecf != null) fiscal.store(op.ecf, saleId, op.deviceId, op.createdAt)
        else if (fiscal.active && totals.total > 0) fiscal.store(fiscal.issueForSale(op, totals.total, op.createdAt), saleId, deviceId, op.createdAt)
        return saleId
    }

    /**
     * Venta hecha sin conexión con el principal: se guarda aquí con número provisional (P1, P2…)
     * y queda en la cola para enviarse cuando vuelva la red.
     */
    private fun recordSaleOffline(original: Op.RecordSale, opId: String): Long {
        // Sin conexión, la caja revisa la caja abierta con su copia local.
        if (currentSettings().requireCash && db.cashSessionQueries.selectOpen(original.deviceId).executeAsOneOrNull() == null)
            throw RuleException("La caja de este equipo está cerrada. Ábrela para poder cobrar.")
        var op = original.copy(offline = true)
        val pid: Long
        synchronized(writeLock) {
            val pending = db.syncQueries.outboxCount().executeAsOne()
            pid = -(System.currentTimeMillis() * 10 + (0..9).random())
            val number = -(pending + 1)
            val totals = totalsFor(op.lines, op.discount)
            val cash = op.paymentMethod == PaymentMethods.CASH
            db.transaction {
                // Sin conexión la caja firma con su propio bloque de números y su copia del certificado.
                val ecf = if (fiscal.active && op.ecf == null && totals.total > 0) fiscal.issueForSale(op, totals.total, op.createdAt) else op.ecf
                if (ecf != null) {
                    op = op.copy(ecf = ecf)
                    RowCodec.upsert(driver, "ecfDoc", JsonObject(mapOf(
                        "id" to JsonPrimitive(pid), "saleId" to JsonPrimitive(pid), "type" to JsonPrimitive(ecf.type), "encf" to JsonPrimitive(ecf.encf),
                        "status" to JsonPrimitive(EcfStatus.SIGNED), "xml" to JsonPrimitive(ecf.xml), "summaryXml" to JsonPrimitive(""),
                        "securityCode" to JsonPrimitive(ecf.securityCode), "signedAt" to JsonPrimitive(ecf.signedAt), "total" to JsonPrimitive(ecf.total),
                        "buyerRnc" to JsonPrimitive(ecf.buyerRnc), "buyerName" to JsonPrimitive(ecf.buyerName), "refEncf" to JsonPrimitive(""),
                        "trackId" to JsonPrimitive(""), "message" to JsonPrimitive("Firmado sin conexión"), "attempts" to JsonPrimitive(0), "sentAt" to JsonNull,
                        "deviceId" to JsonPrimitive(deviceId), "env" to JsonPrimitive(settings.value.fiscalEnv),
                        "createdAt" to JsonPrimitive(op.createdAt), "updatedAt" to JsonPrimitive(op.createdAt),
                    )))
                }
                RowCodec.upsert(driver, "sale", JsonObject(mapOf(
                    "id" to JsonPrimitive(pid), "number" to JsonPrimitive(number), "clientId" to (op.clientId?.let { JsonPrimitive(it) } ?: JsonNull),
                    "clientName" to JsonPrimitive(op.clientName), "gross" to JsonPrimitive(totals.gross), "discount" to JsonPrimitive(totals.discount),
                    "tax" to JsonPrimitive(totals.tax), "total" to JsonPrimitive(totals.total),
                    "costTotal" to JsonPrimitive(op.lines.sumOf { (it.qty * it.cost).roundToLong() }),
                    "taxIncluded" to JsonPrimitive(if (totals.taxIncluded) 1 else 0), "paymentMethod" to JsonPrimitive(op.paymentMethod),
                    "received" to JsonPrimitive(if (cash) op.received else totals.total),
                    "changeDue" to JsonPrimitive(if (cash) (op.received - totals.total).coerceAtLeast(0) else 0),
                    "status" to JsonPrimitive(SaleStatus.OK), "quoteId" to (op.quoteId?.let { JsonPrimitive(it) } ?: JsonNull),
                    "createdAt" to JsonPrimitive(op.createdAt), "deviceId" to JsonPrimitive(op.deviceId),
                    "userId" to (op.userId?.let { JsonPrimitive(it) } ?: JsonNull), "userName" to JsonPrimitive(op.userName),
                )))
                op.lines.forEachIndexed { i, l ->
                    RowCodec.upsert(driver, "saleItem", JsonObject(mapOf(
                        "id" to JsonPrimitive(pid * 1000 - i), "saleId" to JsonPrimitive(pid), "productId" to (l.productId?.let { JsonPrimitive(it) } ?: JsonNull),
                        "name" to JsonPrimitive(l.name), "code" to JsonPrimitive(l.code), "qty" to JsonPrimitive(l.qty), "price" to JsonPrimitive(l.price),
                        "cost" to JsonPrimitive(l.cost), "taxable" to JsonPrimitive(if (l.taxable) 1 else 0),
                    )))
                    if (l.productId != null && l.trackStock) {
                        db.productQueries.addStock(-l.qty, op.createdAt, l.productId)
                        RowCodec.upsert(driver, "stockMove", JsonObject(mapOf(
                            "id" to JsonPrimitive(pid * 1000 - 500 - i), "productId" to JsonPrimitive(l.productId), "type" to JsonPrimitive(MoveType.SALE),
                            "qty" to JsonPrimitive(-l.qty), "note" to JsonPrimitive("Venta pendiente P${-number}"), "createdAt" to JsonPrimitive(op.createdAt),
                            "userName" to JsonPrimitive(op.userName),
                        )))
                    }
                }
                db.syncQueries.outboxAdd(opId, Op.json.encodeToString(Op.serializer(), op), pid, Time.now())
            }
            db.syncQueries.clearChangeLog()
        }
        driver.notifyListeners("sale", "saleItem", "product", "stockMove", "outbox", "ecfDoc")
        return pid
    }

    /** Copia en este equipo los cambios que llegan del principal. */
    fun applyRemoteChanges(changes: List<Triple<String, String, JsonObject?>>) {
        if (changes.isEmpty()) return
        synchronized(writeLock) {
            db.transaction {
                for ((table, key, row) in changes) {
                    if (table == "ecfDoc" && row != null) {
                        // Una copia provisional (firmada sin conexión) se reemplaza por la del principal.
                        val encf = (row["encf"] as? JsonPrimitive)?.content
                        if (encf != null) driver.execute(null, "DELETE FROM ecfDoc WHERE encf = ? AND id != ?", 2) { bindString(0, encf); bindLong(1, key.toLong()) }
                    }
                    if (row == null) RowCodec.delete(driver, table, key) else RowCodec.upsert(driver, table, row)
                }
            }
            db.syncQueries.clearChangeLog()
        }
        driver.notifyListeners(*changes.map { it.first }.distinct().toTypedArray())
    }

    /** Borra los datos compartidos de este equipo (al conectarse a un principal se reemplazan por los suyos). */
    fun clearSharedData() {
        synchronized(writeLock) {
            db.transaction {
                RowCodec.clearAll(driver)
                db.syncQueries.clearChangeLog()
            }
        }
        driver.notifyListeners(*RowCodec.tables.keys.toTypedArray())
    }

    /** Ventas pendientes, en orden, para enviarlas al principal. */
    fun outbox() = db.syncQueries.outboxAll().executeAsList()

    /** La venta pendiente ya llegó al principal: se quita la copia provisional. */
    fun outboxDone(opId: String, provisionalSaleId: Long?) {
        synchronized(writeLock) {
            db.transaction {
                db.syncQueries.outboxRemove(opId)
                if (provisionalSaleId != null) {
                    RowCodec.delete(driver, "sale", provisionalSaleId.toString())
                    driver.execute(null, "DELETE FROM saleItem WHERE saleId = ?", 1) { bindLong(0, provisionalSaleId) }
                    db.fiscalQueries.deleteDocsOfSale(provisionalSaleId)
                }
                if (db.syncQueries.outboxCount().executeAsOne() == 0L) db.stockMoveQueries.deleteProvisional()
            }
            db.syncQueries.clearChangeLog()
        }
        driver.notifyListeners("sale", "saleItem", "stockMove", "outbox")
    }

    // ---------- Para el principal ----------

    fun maxSeq(): Long = db.syncQueries.maxSeq().executeAsOne()

    fun changesSince(since: Long, limit: Long) = db.syncQueries.changesSince(since, limit).executeAsList()

    fun readRow(table: String, key: String): JsonObject? = RowCodec.read(driver, table, key)

    fun appliedResult(opId: String): String? = db.syncQueries.appliedGet(opId).executeAsOneOrNull()

    fun saveAppliedResult(opId: String, result: String) = db.syncQueries.appliedPut(opId, result, Time.now())

    // ================= Configuración =================

    suspend fun saveSettings(s: AppSettings) {
        val all = s.toMap()
        val local = all.filterKeys { it in AppSettings.LOCAL_KEYS }
        withContext(io) { db.transaction { local.forEach { (k, v) -> db.syncQueries.metaPut("s.$k", v) } } }
        val shared = all.filterKeys { it !in AppSettings.LOCAL_KEYS }
        val current = settings.value.toMap()
        val changed = shared.filter { (k, v) -> current[k] != v }
        if (changed.isNotEmpty()) run(Op.SaveSettings(changed))
    }

    suspend fun updateSettings(change: (AppSettings) -> AppSettings) = saveSettings(change(settings.value))

    // ================= Imágenes =================

    /** Archivo de una imagen guardada (se guarda solo el nombre para que los respaldos sirvan en cualquier equipo). */
    fun imageFile(name: String?): File? {
        if (name.isNullOrBlank()) return null
        val f = File(imagesDir, File(name).name)
        return if (f.exists()) f else null
    }

    /** Comprime la foto elegida, la guarda y, si este equipo está conectado, la envía al principal. */
    suspend fun importImage(temp: File, prefix: String = "p"): String? {
        val name = "${prefix}_${Time.now()}_${(1000..9999).random()}.jpg"
        val dest = File(imagesDir, name)
        val ok = withContext(io) { platform.saveCompressedJpeg(temp, dest) }
        if (!ok) return null
        remote?.uploadImage(name, dest)
        return name
    }

    fun deleteImage(name: String?) {
        if (remote != null) return // las imágenes compartidas las administra el principal
        imageFile(name)?.delete()
    }

    // ================= Categorías, productos e inventario =================

    suspend fun saveCategory(id: Long?, name: String, color: Long): Long = run(Op.SaveCategory(id, name, color)).asId()

    suspend fun deleteCategory(id: Long) { run(Op.DeleteCategory(id)) }

    suspend fun productById(id: Long): Product? = withContext(io) { db.productQueries.selectById(id).executeAsOneOrNull() }

    suspend fun productByCode(code: String): Product? = withContext(io) { db.productQueries.selectByCode(code.trim()).executeAsOneOrNull() }

    suspend fun saveProduct(d: ProductDraft): Long = run(Op.SaveProduct(d, userName)).asId()

    suspend fun deleteProduct(id: Long) { run(Op.DeleteProduct(id)) }

    /** Asegura que los productos tengan código (para su QR). Devuelve id → código. */
    suspend fun ensureCodes(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val r = run(Op.EnsureCodes(ids))
        return (r as? JsonObject)?.mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v.jsonPrimitive.content } }?.toMap() ?: emptyMap()
    }

    suspend fun adjustStock(productId: Long, newStock: Double, note: String) {
        require(note.isNotBlank()) { "Escribe el motivo del ajuste" }
        run(Op.AdjustStock(productId, newStock, note.trim(), userName))
    }

    suspend fun registerEntry(lines: List<EntryLine>, supplierName: String?, note: String) { run(Op.RegisterEntry(lines, supplierName, note, userName)) }

    suspend fun movesByProduct(productId: Long): List<StockMove> = withContext(io) { db.stockMoveQueries.selectByProduct(productId).executeAsList() }

    suspend fun recentMoves(limit: Long = 100) = withContext(io) { db.stockMoveQueries.selectRecent(limit).executeAsList() }

    // ================= Clientes y proveedores =================

    suspend fun saveClient(id: Long?, name: String, phone: String, email: String, docId: String, address: String, notes: String): Long =
        run(Op.SaveClient(id, name, phone, email, docId, address, notes)).asId()

    suspend fun deleteClient(id: Long) { run(Op.DeleteClient(id)) }

    suspend fun clientById(id: Long): Client? = withContext(io) { db.clientQueries.selectById(id).executeAsOneOrNull() }

    suspend fun salesByClient(id: Long): List<Sale> = withContext(io) { db.saleQueries.selectByClient(id).executeAsList() }

    suspend fun saveSupplier(id: Long?, name: String, contact: String, phone: String, email: String, docId: String, address: String, notes: String): Long =
        run(Op.SaveSupplier(id, name, contact, phone, email, docId, address, notes)).asId()

    suspend fun deleteSupplier(id: Long) { run(Op.DeleteSupplier(id)) }

    // ================= Ventas =================

    fun totalsFor(lines: List<CartLine>, discount: Long, s: AppSettings = settings.value): Totals =
        Totals.compute(lines.map { LineInput(it.qty, it.price, it.taxable) }, discount, s.taxRate, s.pricesIncludeTax)

    /**
     * Registra la venta, descuenta el inventario y devuelve el id de la venta.
     * Si este equipo está conectado y se perdió la red, la venta queda guardada para enviarse después.
     */
    suspend fun recordSale(
        lines: List<CartLine>,
        client: Client?,
        discount: Long,
        paymentMethod: String,
        received: Long,
        quoteId: Long?,
        fiscal: FiscalChoice? = null,
        allowNoStock: Boolean = false,
    ): Long {
        val user = currentUser.value
        val op = Op.RecordSale(
            lines, client?.id, client?.name ?: "Consumidor final", discount, paymentMethod, received, quoteId,
            deviceId, user?.id, user?.name ?: "", Time.now(), fiscal = fiscal, allowNoStock = allowNoStock,
        )
        val r = remote ?: return withContext(io) { applyLocal(op) }.asId()
        val opId = UUID.randomUUID().toString()
        return try {
            r.execute(op, opId).asId()
        } catch (e: IOException) {
            if (e is com.vitrinapos.net.HttpStatusException) throw e
            withContext(io) { recordSaleOffline(op, opId) }
        }
    }

    suspend fun voidSale(saleId: Long, reason: String = "") {
        if (saleId < 0) throw OfflineException("Esta venta todavía no llega al equipo principal. Podrás anularla cuando se envíe.")
        run(Op.VoidSale(saleId, userName, reason, deviceId))
    }

    // ================= Facturación electrónica =================

    val ecfDocs: StateFlow<List<com.vitrinapos.db.EcfDoc>> = db.fiscalQueries.recentDocs(500).asFlow().mapToList(io).stateIn(scope, SharingStarted.Eagerly, emptyList())
    val fiscalRanges: StateFlow<List<com.vitrinapos.db.FiscalSequence>> = db.fiscalQueries.ranges().asFlow().mapToList(io).stateIn(scope, SharingStarted.Eagerly, emptyList())
    val fiscalBlocks: StateFlow<List<com.vitrinapos.db.FiscalSequence>> = db.fiscalQueries.allBlocks().asFlow().mapToList(io).stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun ecfForSale(saleId: Long): List<com.vitrinapos.db.EcfDoc> = withContext(io) { db.fiscalQueries.docBySale(saleId).executeAsList() }

    suspend fun saveFiscalRange(type: Int, fromNo: Long, toNo: Long, expiresAt: Long?) { run(Op.SaveFiscalRange(type, fromNo, toNo, expiresAt)) }

    suspend fun deleteFiscalRange(id: Long) { run(Op.DeleteFiscalRange(id)) }

    /** Guarda los permisos de un usuario ("" vuelve a los de su rol). */
    suspend fun setUserPermissions(id: Long, permissions: Set<Permission>?) {
        run(Op.SetUserPermissions(id, permissions?.let { Permissions.encode(it).ifEmpty { "NONE" } } ?: ""))
    }

    /** Una caja pide números al principal para poder facturar sin conexión. */
    suspend fun reserveFiscalBlock(type: Int) { run(Op.ReserveFiscalBlock(type, deviceId, settings.value.fiscalBlockSize)) }

    suspend fun saleById(id: Long): Sale? = withContext(io) { db.saleQueries.selectById(id).executeAsOneOrNull() }

    suspend fun saleItems(id: Long): List<SaleItem> = withContext(io) { db.saleQueries.itemsBySale(id).executeAsList() }

    suspend fun salesBetween(from: Long, to: Long): List<Sale> = withContext(io) { db.saleQueries.selectBetween(from, to).executeAsList() }

    suspend fun itemsBetween(from: Long, to: Long): List<SaleItem> = withContext(io) { db.saleQueries.itemsBetween(from, to).executeAsList() }

    // ================= Cotizaciones =================

    suspend fun saveQuote(
        id: Long?, client: Client?, clientName: String, lines: List<CartLine>, discount: Long, validDays: Int, notes: String, status: String,
    ): Long = run(Op.SaveQuote(id, client?.id, clientName, lines, discount, validDays, notes, status, userName)).asId()

    suspend fun setQuoteStatus(id: Long, status: String) { run(Op.SetQuoteStatus(id, status)) }

    suspend fun deleteQuote(id: Long) { run(Op.DeleteQuote(id)) }

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

    // ================= Caja (una por equipo) =================

    suspend fun openCashSession(amount: Long) { run(Op.OpenCash(amount, deviceId, userName)) }

    private fun expectedCashSync(session: CashSession): Long {
        val sales = db.saleQueries.selectBetween(session.openedAt, session.closedAt ?: Long.MAX_VALUE).executeAsList()
        val moves = db.cashSessionQueries.movesBySession(session.id).executeAsList()
        return session.openingAmount + sales.filter {
            it.status == SaleStatus.OK && it.paymentMethod == PaymentMethods.CASH &&
                (session.deviceId.isBlank() || it.deviceId == session.deviceId)
        }.sumOf { it.total } + moves.sumOf { CashMoveType.sign(it.type) * it.amount }
    }

    /** Resumen de una caja: ventas por forma de pago y movimientos. */
    data class CashSummary(
        val opening: Long, val cashSales: Long, val cardSales: Long, val transferSales: Long,
        val ins: Long, val outs: Long, val refunds: Long, val expected: Long, val moves: List<com.vitrinapos.db.CashMove>,
    )

    suspend fun cashSummary(session: CashSession): CashSummary = withContext(io) {
        val sales = db.saleQueries.selectBetween(session.openedAt, session.closedAt ?: Long.MAX_VALUE).executeAsList()
            .filter { it.status == SaleStatus.OK && (session.deviceId.isBlank() || it.deviceId == session.deviceId) }
        val moves = db.cashSessionQueries.movesBySession(session.id).executeAsList()
        fun by(m: String) = sales.filter { it.paymentMethod == m }.sumOf { it.total }
        CashSummary(
            session.openingAmount, by(PaymentMethods.CASH), by(PaymentMethods.CARD), by(PaymentMethods.TRANSFER),
            moves.filter { it.type == CashMoveType.IN }.sumOf { it.amount },
            moves.filter { it.type == CashMoveType.OUT || it.type == CashMoveType.EXPENSE }.sumOf { it.amount },
            moves.filter { it.type == CashMoveType.REFUND }.sumOf { it.amount },
            expectedCashSync(session), moves,
        )
    }

    suspend fun cashMove(kind: String, amount: Long, note: String) { run(Op.CashMove(deviceId, kind, amount, note, userName)) }

    /** Efectivo esperado en la caja de este equipo: monto inicial + ventas en efectivo desde la apertura. */
    suspend fun expectedCash(session: CashSession): Long = withContext(io) { expectedCashSync(session) }

    suspend fun closeCashSession(session: CashSession, counted: Long, notes: String) { run(Op.CloseCash(session.id, counted, notes)) }

    suspend fun cashHistory(): List<CashSession> = withContext(io) { db.cashSessionQueries.selectRecent(deviceId).executeAsList() }

    // ================= Taller =================

    val repairs: StateFlow<List<com.vitrinapos.db.RepairOrder>> = db.repairQueries.selectAll().asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, db.repairQueries.selectAll().executeAsList())

    suspend fun saveRepair(d: RepairDraft, depositMethod: String = PaymentMethods.CASH): Long {
        val u = currentUser.value
        return run(Op.SaveRepair(d, depositMethod, deviceId, u?.id, u?.name ?: "")).asId()
    }

    /** Entrega el equipo y cobra. Devuelve el id de la venta. */
    suspend fun deliverRepair(id: Long, lines: List<CartLine>, repaired: Boolean, paymentMethod: String, received: Long, note: String = ""): Long =
        run(Op.DeliverRepair(id, lines, repaired, paymentMethod, received, note, deviceId, currentUser.value?.id, userName)).asId()

    suspend fun repairsWithStatus(status: String): Long = withContext(io) { db.repairQueries.countByStatus(status).executeAsOne() }

    suspend fun setRepairStatus(id: Long, status: String, note: String = "") { run(Op.SetRepairStatus(id, status, note, userName)) }

    suspend fun repairById(id: Long) = withContext(io) { db.repairQueries.selectById(id).executeAsOneOrNull() }

    suspend fun repairByNumber(n: Long) = withContext(io) { db.repairQueries.selectByNumber(n).executeAsOneOrNull() }

    suspend fun repairEvents(id: Long) = withContext(io) { db.repairQueries.eventsByRepair(id).executeAsList() }

    // ================= Usuarios =================

    suspend fun saveUser(id: Long?, name: String, pin: String?, role: String, active: Boolean): Long =
        run(Op.SaveUser(id, name, pin?.takeIf { it.isNotBlank() }?.let { Crypto.pinHash(it) }, role, active)).asId()

    fun checkPin(user: AppUser, pin: String): Boolean = Crypto.verifyPin(user.pinHash, pin)

    /** Resultado de un intento de PIN. */
    sealed interface PinResult {
        data object Ok : PinResult
        data class Wrong(val attemptsLeft: Int) : PinResult
        data class Locked(val until: Long) : PinResult
    }

    private fun failKey(key: String) = "pin.fail.$key"

    /** Bloqueo tras fallar: cada 5 intentos, 1, 5 y luego 15 minutos (se guarda en este equipo). */
    fun pinLockedUntil(key: String): Long =
        metaGet(failKey(key))?.split(':')?.getOrNull(1)?.toLongOrNull()?.takeIf { it > Time.now() } ?: 0

    private fun registerPinFailure(key: String): PinResult {
        val count = (metaGet(failKey(key))?.split(':')?.getOrNull(0)?.toIntOrNull() ?: 0) + 1
        val lockMinutes = if (count % 5 == 0) when (count / 5) { 1 -> 1; 2 -> 5; else -> 15 } else 0
        val until = if (lockMinutes > 0) Time.now() + lockMinutes * 60_000L else 0
        metaPut(failKey(key), "$count:$until")
        return if (until > 0) PinResult.Locked(until) else PinResult.Wrong(5 - count % 5)
    }

    /**
     * Comprueba el PIN de un usuario. [final] = la persona terminó de escribir: si no coincide, cuenta como intento fallido.
     * Al entrar con una huella de la versión anterior, se cambia por la nueva con sal.
     */
    fun pinAttempt(user: AppUser, pin: String, final: Boolean): PinResult {
        val key = "u" + user.id
        pinLockedUntil(key).takeIf { it > 0 }?.let { return PinResult.Locked(it) }
        if (checkPin(user, pin)) {
            metaPut(failKey(key), "0:0")
            if (Crypto.isLegacyPin(user.pinHash)) scope.launch {
                runCatching { run(Op.SaveUser(user.id, user.name, Crypto.pinHash(pin), user.role, user.active == 1L)) }
            }
            return PinResult.Ok
        }
        return if (final) registerPinFailure(key) else PinResult.Wrong(5)
    }

    /** Autorización de un administrador (descuentos grandes, precios bajo costo, anulaciones viejas). Devuelve su nombre. */
    fun authorizeAdmin(pin: String): Pair<PinResult, String?> {
        pinLockedUntil("admin").takeIf { it > 0 }?.let { return PinResult.Locked(it) to null }
        val admin = users.value.firstOrNull { it.active == 1L && it.role == Roles.ADMIN && checkPin(it, pin) }
        if (admin != null) { metaPut(failKey("admin"), "0:0"); return PinResult.Ok to admin.name }
        return registerPinFailure("admin") to null
    }

    // ================= Respaldo =================

    /** Pasa los cambios pendientes al archivo principal antes de copiarlo. */
    fun checkpoint() {
        runCatching {
            driver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { QueryResult.Value(Unit) }, 0)
        }
    }
}
