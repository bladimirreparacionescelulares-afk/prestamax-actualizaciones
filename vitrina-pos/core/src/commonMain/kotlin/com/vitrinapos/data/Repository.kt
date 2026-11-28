package com.vitrinapos.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.vitrinapos.db.AppUser
import com.vitrinapos.db.Branch
import com.vitrinapos.db.BranchStock
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
import com.vitrinapos.platform.CorePlatform
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
    /** IMEI o números de serie de las unidades vendidas (productos con IMEI). */
    val serials: List<String> = emptyList(),
) {
    val amount: Long get() = LineInput(qty, price, taxable).amount

    companion object {
        fun of(p: Product, qty: Double = 1.0, serials: List<String> = emptyList()) = CartLine(
            productId = p.id, name = p.fullName, code = p.code, qty = qty, price = p.price, cost = p.cost,
            taxable = p.taxable == 1L, imagePath = p.imagePath, trackStock = p.trackStock == 1L, service = p.isService == 1L,
            serials = serials,
        )
    }
}

/** Nombre con la variante: "iPhone 13 · Azul 128 GB". */
val Product.fullName: String get() = if (variantName.isBlank()) name else "$name · $variantName"
val Product.isSerialized: Boolean get() = serialized == 1L

@Serializable
data class PurchaseLine(val productId: Long, val qty: Double, val unitCost: Long, val serials: List<String> = emptyList())

@Serializable
data class CountLine(val productId: Long, val counted: Double, val expected: Double)

@Serializable
data class ReturnLine(val saleItemId: Long, val qty: Double, val restock: Boolean = true, val serials: List<String> = emptyList())

/** Estados de una unidad con IMEI. */
object UnitStatus {
    const val AVAILABLE = "DISPONIBLE"
    const val SOLD = "VENDIDO"
    const val LOST = "MERMA"
    const val RETURNED = "DEVUELTO_PROVEEDOR"
    fun label(s: String) = when (s) { AVAILABLE -> "En existencia"; SOLD -> "Vendido"; LOST -> "Merma"; RETURNED -> "Devuelto al proveedor"; else -> s }
}

/** Motivos de merma. */
object LossReason {
    val all = listOf("Dañado", "Vencido", "Robo o pérdida", "Uso interno", "Regalo o muestra", "Garantía del proveedor")
}

/** Comprobantes fiscales de compra: NCF (B01, B11…) o e-NCF (E31…). Devuelve null si es válido, o el problema. */
fun ncfProblem(ncf: String): String? {
    val t = ncf.trim().uppercase()
    if (t.isEmpty()) return null
    return when {
        Regex("B(01|02|03|04|11|12|13|14|15|16|17)\\d{8}").matches(t) -> null
        Regex("E(31|32|33|34|41|43|44|45|46|47)\\d{10}").matches(t) -> null
        t.startsWith("B") -> "Un NCF tiene 11 caracteres: B01 y 8 números"
        t.startsWith("E") -> "Un e-NCF tiene 13 caracteres: E31 y 10 números"
        else -> "El NCF empieza por B o E (por ejemplo B0100000123)"
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
    /** Cada unidad lleva su IMEI o número de serie. */
    val serialized: Boolean = false,
    /** Productos que son variantes del mismo (color, capacidad…) comparten el grupo. */
    val variantGroup: String = "",
    val variantName: String = "",
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
    const val PURCHASE = "COMPRA"
    const val PURCHASE_VOID = "COMPRA_ANULADA"
    const val LOSS = "MERMA"
    const val COUNT = "CONTEO"
    const val RETURN = "DEVOLUCION"
    fun label(t: String) = when (t) {
        SALE -> "Venta"; ENTRY -> "Entrada"; ADJUST -> "Ajuste"; VOID -> "Anulación"; INITIAL -> "Stock inicial"
        PURCHASE -> "Compra"; PURCHASE_VOID -> "Compra anulada"; LOSS -> "Merma"; COUNT -> "Conteo físico"; RETURN -> "Devolución"
        else -> t
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
    /** Conectado a la nube de Vitrina POS. */
    const val CLOUD = "cloud"
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

/** La sucursal Principal: a ella pasa todo lo que había antes de las sucursales. */
const val MAIN_BRANCH = 1L

/** Problema con el nombre de un usuario (null si está bien). Debe tener letras: así no se confunde con un PIN. */
fun userNameProblem(name: String): String? = when {
    name.isBlank() -> "Escribe el nombre de la persona"
    name.none { it.isLetter() } -> "Escribe el nombre de la persona, no números"
    else -> null
}

/** Tipos de movimiento de efectivo en la caja. */
object CashMoveType {
    const val IN = "ENTRADA"
    const val OUT = "SALIDA"
    const val EXPENSE = "GASTO"
    const val REFUND = "DEVOLUCION"
    fun label(t: String) = when (t) { IN -> "Entrada"; OUT -> "Retiro"; EXPENSE -> "Gasto"; REFUND -> "Devolución"; else -> t }
    fun sign(t: String) = if (t == IN) 1 else -1
}

class Repository(val platform: CorePlatform) {
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

    /** Toda escritura va con el candado: en WAL, dos conexiones escribiendo a la vez dan "database is locked". */
    fun metaPut(key: String, value: String) = synchronized(writeLock) { db.syncQueries.metaPut(key, value) }

    val deviceId: String = metaGet("deviceId") ?: UUID.randomUUID().toString().also { metaPut("deviceId", it) }

    val role: String get() = meta.value["role"] ?: metaGet("role") ?: NetRole.SOLO

    /** Sucursal en la que está este equipo (2.2). Sin elegir, la Principal. */
    val branchId: Long get() = (meta.value["branchId"] ?: metaGet("branchId"))?.toLongOrNull() ?: MAIN_BRANCH

    /** Sucursal de la operación que se está aplicando: la del equipo que la hizo. */
    @Volatile private var opBranch: Long = MAIN_BRANCH

    init {
        // Cajas abiertas antes de que existiera el trabajo en red pertenecen a este equipo.
        if (role != NetRole.CLIENT) db.cashSessionQueries.claimOrphans(deviceId)
        // Toda base que guarda los datos (sola, principal o la de la nube) tiene la sucursal Principal.
        if (role != NetRole.CLIENT && role != NetRole.CLOUD && db.branchQueries.selectById(MAIN_BRANCH).executeAsOneOrNull() == null)
            synchronized(writeLock) {
                driver.execute(null, "INSERT OR IGNORE INTO branch(id, name, address, phone, active, createdAt) VALUES ($MAIN_BRANCH, 'Principal', '', '', 1, 0)", 0)
            }
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

    /** Productos con la existencia de la sucursal de este equipo (en product.stock está el total del negocio). */
    val products: StateFlow<List<Product>> = combine(
        db.productQueries.selectActive().asFlow().mapToList(io), db.branchQueries.stockAll().asFlow().mapToList(io), meta,
    ) { ps, rows, m -> atBranch(ps, rows, branchOf(m)) }
        .stateIn(scope, SharingStarted.Eagerly, atBranch(db.productQueries.selectActive().executeAsList(), db.branchQueries.stockAll().executeAsList(), branchId))

    /** Sucursales del negocio. */
    val branches: StateFlow<List<Branch>> = db.branchQueries.selectAll().asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, db.branchQueries.selectAll().executeAsList())

    private fun branchOf(m: Map<String, String>) = m["branchId"]?.toLongOrNull() ?: MAIN_BRANCH

    /** La existencia de cada producto en la sucursal (sin filas de sucursal, todo es de la Principal). */
    private fun atBranch(ps: List<Product>, rows: List<BranchStock>, branch: Long): List<Product> {
        val withRows = rows.mapTo(HashSet()) { it.productId }
        val here = rows.filter { it.branchId == branch }.associate { it.productId to it.qty }
        return ps.map { p ->
            val q = here[p.id] ?: if (branch == MAIN_BRANCH && p.id !in withRows) p.stock else 0.0
            if (q == p.stock) p else p.copy(stock = q)
        }
    }

    /** Existencia de un producto en cada sucursal (para la ficha del producto). */
    suspend fun stockByBranch(productId: Long): Map<Long, Double> = withContext(io) {
        val rows = db.branchQueries.rowsOfProduct(productId).executeAsList()
        if (rows.isEmpty()) mapOf(MAIN_BRANCH to (db.productQueries.selectById(productId).executeAsOneOrNull()?.stock ?: 0.0))
        else rows.associate { it.branchId to it.qty }
    }

    /** Existencia del producto en una sucursal (dentro de una transacción). */
    fun stockAt(productId: Long, branch: Long = opBranch): Double {
        db.branchQueries.stockOf(productId, branch).executeAsOneOrNull()?.let { return it }
        if (branch != MAIN_BRANCH || db.branchQueries.rowsOfProduct(productId).executeAsList().isNotEmpty()) return 0.0
        return db.productQueries.selectById(productId).executeAsOneOrNull()?.stock ?: 0.0
    }

    /** Crea la fila de la sucursal; si el producto no tenía ninguna, lo que había queda en la Principal. */
    private fun ensureBranchRow(productId: Long, branch: Long) {
        if (db.branchQueries.rowsOfProduct(productId).executeAsList().isEmpty()) {
            val legacy = db.productQueries.selectById(productId).executeAsOneOrNull()?.stock ?: 0.0
            db.branchQueries.ensureRow("$productId:$MAIN_BRANCH", productId, MAIN_BRANCH, legacy)
        }
        db.branchQueries.ensureRow("$productId:$branch", productId, branch, 0.0)
    }

    /** Suma (o resta) existencias en una sucursal y en el total del producto. Todo cambio de stock pasa por aquí. */
    private fun addStockAt(productId: Long, delta: Double, now: Long, branch: Long = opBranch) {
        ensureBranchRow(productId, branch)
        db.branchQueries.addQty(delta, "$productId:$branch")
        db.productQueries.addStock(delta, now, productId)
    }

    private fun setStockAt(productId: Long, qty: Double, now: Long, branch: Long = opBranch) =
        addStockAt(productId, qty - stockAt(productId, branch), now, branch)

    /** Anota la sucursal en una fila recién creada (venta, caja, compra, IMEI…). */
    private fun tagBranch(table: String, id: Long, branch: Long = opBranch) =
        driver.execute(null, "UPDATE $table SET branchId = ? WHERE id = ?", 2) { bindLong(0, branch); bindLong(1, id) }

    private fun lastRowId(): Long = db.branchQueries.lastId().executeAsOne()

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

    /** Nombre de este equipo (para la actividad). */
    private val localDeviceName: String get() = meta.value["deviceName"] ?: metaGet("deviceName") ?: platform.defaultDeviceName

    /**
     * Aplica una operación en la base de datos de este equipo (solo o principal).
     * [actor] y [device]: quién la hizo y desde qué equipo (por defecto, el usuario y el equipo de aquí).
     */
    fun applyLocal(op: Op, actor: String? = null, device: String? = null, branch: Long = branchId): JsonElement {
        val result = synchronized(writeLock) {
            opBranch = branch
            val now = Time.now()
            db.transactionWithResult<JsonElement> {
                // Se describe antes de aplicar, para ver los valores anteriores (precio viejo, nombre de lo que se borra…).
                val audit = runCatching { describe(op) }.getOrNull()
                val r = when (op) {
                    is Op.SaveSettings -> { op.values.forEach { (k, v) -> db.settingQueries.upsert(k, v) }; JsonNull }
                    is Op.SaveCategory -> JsonPrimitive(
                        if (op.id == null) {
                            db.categoryQueries.insert(op.name.trim(), op.color); db.categoryQueries.lastId().executeAsOne()
                        } else { db.categoryQueries.update(op.name.trim(), op.color, op.id); op.id },
                    )
                    is Op.DeleteCategory -> { db.categoryQueries.clearFromProducts(op.id); db.categoryQueries.delete(op.id); JsonNull }
                    is Op.SaveProduct -> {
                        // Todo producto lleva código: si no lo escribieron, se le asigna uno para su etiqueta.
                        var d = if (op.draft.code.isBlank()) op.draft.copy(code = newCode()) else op.draft
                        if (d.serialized) d = d.copy(trackStock = true, isService = false)
                        if (d.price <= 0) throw RuleException("El precio de venta debe ser mayor que cero")
                        if (d.cost < 0) throw RuleException("El costo no puede ser negativo")
                        (db.productQueries.selectByCode(d.code.trim()).executeAsList() + db.productQueries.selectByAltCode(d.code.trim()).executeAsList())
                            .firstOrNull { it.active == 1L && it.id != d.id }
                            ?.let { throw RuleException("El código ${d.code.trim()} ya lo tiene \"${it.name}\"") }
                        if (d.id == null) {
                            db.productQueries.insert(
                                d.name.trim(), d.code.trim(), d.categoryId, d.supplierId, d.cost, d.price, d.initialStock, d.minStock,
                                d.unit.ifBlank { "und" }, if (d.taxable) 1 else 0, if (d.trackStock) 1 else 0, d.imagePath, d.description.trim(), now, now,
                            )
                            val id = db.productQueries.lastId().executeAsOne()
                            db.branchQueries.ensureRow("$id:$opBranch", id, opBranch, d.initialStock)
                            db.productQueries.setService(if (d.isService) 1 else 0, id)
                            saveExtras(d, id)
                            if (d.initialStock != 0.0) logMove(id, MoveType.INITIAL, d.initialStock, "", now, op.user)
                            JsonPrimitive(id)
                        } else {
                            db.productQueries.update(
                                d.name.trim(), d.code.trim(), d.categoryId, d.supplierId, d.cost, d.price, d.minStock,
                                d.unit.ifBlank { "und" }, if (d.taxable) 1 else 0, if (d.trackStock) 1 else 0, d.imagePath, d.description.trim(), now, d.id,
                            )
                            db.productQueries.setService(if (d.isService) 1 else 0, d.id)
                            saveExtras(d, d.id)
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
                        if (p?.serialized == 1L) throw RuleException("Este producto se controla por IMEI: registra los IMEI o usa una merma")
                        val here = stockAt(op.productId)
                        if (p != null && op.newStock != here) {
                            setStockAt(op.productId, op.newStock, now)
                            logMove(op.productId, MoveType.ADJUST, op.newStock - here, op.note, now, op.user)
                        }
                        JsonNull
                    }
                    is Op.RegisterEntry -> {
                        val text = listOfNotNull(op.supplierName?.let { "Proveedor: $it" }, op.note.takeIf { it.isNotBlank() }).joinToString(" · ")
                        if (op.lines.any { it.qty < 0 }) throw RuleException("Las cantidades de una entrada deben ser positivas. Para sacar mercancía usa un ajuste con su motivo.")
                        for (l in op.lines) {
                            if (l.qty == 0.0) continue
                            if (db.productQueries.selectById(l.productId).executeAsOneOrNull()?.serialized == 1L)
                                throw RuleException("Los productos con IMEI entran con una compra")
                            addStockAt(l.productId, l.qty, now)
                            if (l.newCost != null) db.productQueries.setCost(l.newCost, now, l.productId)
                            logMove(l.productId, MoveType.ENTRY, l.qty, text, now, op.user)
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
                            if (db.inventoryQueries.returnsBySale(op.saleId).executeAsList().isNotEmpty())
                                throw RuleException("Esta venta ya tiene devoluciones. Devuelve lo que falta desde Devolución.")
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
                            for (u in db.inventoryQueries.unitsBySale(op.saleId).executeAsList())
                                db.inventoryQueries.setUnitStatus(UnitStatus.AVAILABLE, null, "Anulación venta #${sale.number}", now, u.id)
                            for (item in db.saleQueries.itemsBySale(op.saleId).executeAsList()) {
                                val pid = item.productId ?: continue
                                val p = db.productQueries.selectById(pid).executeAsOneOrNull() ?: continue
                                if (p.trackStock == 1L) {
                                    addStockAt(pid, item.qty, now, sale.branchId)
                                    logMove(pid, MoveType.VOID, item.qty, "Anulación venta #${sale.number}", now, op.user, branch = sale.branchId)
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
                            tagBranch("cashSession", lastRowId())
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
                        if (s != null && s.closedAt == null) {
                            if (op.counted < 0) throw RuleException("El efectivo contado no puede ser negativo")
                            val expected = expectedCashSync(s)
                            val diff = op.counted - expected
                            // Una diferencia grande solo se cierra con un administrador y con nota.
                            if (kotlin.math.abs(diff) > currentSettings().cashDiffLimit) {
                                if (op.authorizedBy.isBlank()) throw RuleException("La diferencia es grande: hace falta un administrador")
                                if (op.notes.isBlank()) throw RuleException("Escribe por qué no cuadra la caja")
                            }
                            val notes = listOf(op.notes.trim(), op.authorizedBy.takeIf { it.isNotBlank() && it != op.user }?.let { "Autorizó: $it" } ?: "")
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            db.cashSessionQueries.close(now, expected, op.counted, notes, s.id)
                        }
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
                    is Op.SavePurchase -> JsonPrimitive(savePurchaseLocal(op, now))
                    is Op.VoidPurchase -> { voidPurchaseLocal(op, now); JsonNull }
                    is Op.StockLoss -> { stockLossLocal(op, now); JsonNull }
                    is Op.ApplyCount -> JsonPrimitive(applyCountLocal(op, now))
                    is Op.ReturnSale -> JsonPrimitive(returnSaleLocal(op, now))
                    is Op.AddUnits -> { addUnitsLocal(op, now); JsonNull }
                    is Op.MergeProducts -> { mergeLocal(op, now); JsonNull }
                    is Op.LogEvent -> JsonNull
                    is Op.SetUserPermissions -> { db.appUserQueries.setPermissions(op.permissions, op.id); JsonNull }
                    is Op.SaveBranch -> JsonPrimitive(saveBranchLocal(op, now))
                    is Op.SetUserBranches -> {
                        val ids = op.branches.split(',').mapNotNull { it.trim().toLongOrNull() }.distinct()
                        driver.execute(null, "UPDATE appUser SET branches = ? WHERE id = ?", 2) { bindString(0, ids.joinToString(",")); bindLong(1, op.id) }
                        JsonNull
                    }
                    is Op.SaveUser -> JsonPrimitive(
                        run {
                            // El nombre es de la persona: sin letras parece un PIN y se mostraría en pantalla.
                            val oldName = op.id?.let { db.appUserQueries.selectById(it).executeAsOneOrNull()?.name }
                            if (op.name.trim() != oldName) userNameProblem(op.name)?.let { throw RuleException(it) }
                        }.let {
                        if (op.id == null) {
                            db.appUserQueries.insert(op.name.trim(), op.pinHash ?: "", op.role, if (op.active) 1 else 0, now)
                            db.appUserQueries.lastId().executeAsOne()
                        } else {
                            db.appUserQueries.update(op.name.trim(), op.role, if (op.active) 1 else 0, op.id)
                            if (op.pinHash != null) db.appUserQueries.setPin(op.pinHash, op.id)
                            op.id
                        }
                        },
                    )
                }
                if (audit != null) db.auditQueries.insert(audit.first, audit.second.take(400), actor ?: userName, device ?: localDeviceName, now)
                r
            }
        }
        changeSignal.value = db.syncQueries.maxSeq().executeAsOne()
        return result
    }

    /** Anota un movimiento en el kardex con el saldo y el costo después del cambio (dentro de una transacción). */
    private fun logMove(productId: Long, type: String, qty: Double, note: String, at: Long, user: String, unitCost: Long? = null, branch: Long = opBranch) {
        val p = db.productQueries.selectById(productId).executeAsOneOrNull()
        // El saldo del kardex es el de la sucursal donde pasó el movimiento.
        db.stockMoveQueries.insert(productId, type, qty, note, at, user, unitCost ?: p?.cost ?: 0, stockAt(productId, branch))
        tagBranch("stockMove", lastRowId(), branch)
    }

    /** Crea o cambia una sucursal (dentro de una transacción). */
    private fun saveBranchLocal(op: Op.SaveBranch, now: Long): Long {
        val name = op.name.trim()
        if (name.isBlank()) throw RuleException("Escribe el nombre de la sucursal")
        val all = db.branchQueries.selectAll().executeAsList()
        if (all.any { it.id != op.id && it.name.equals(name, true) }) throw RuleException("Ya hay una sucursal que se llama $name")
        if (op.id == null) {
            db.branchQueries.insert(name, op.address.trim(), op.phone.trim(), 1, now)
            return lastRowId()
        }
        if (!op.active && op.id == MAIN_BRANCH) throw RuleException("La sucursal Principal no se puede desactivar")
        if (!op.active && db.branchQueries.stockAll().executeAsList().any { it.branchId == op.id && it.qty > 1e-9 })
            throw RuleException("Esa sucursal todavía tiene mercancía. Pásala a otra sucursal antes de desactivarla.")
        db.branchQueries.update(name, op.address.trim(), op.phone.trim(), if (op.active) 1 else 0, op.id)
        return op.id
    }

    /** IMEI y variante del producto. Al crear una variante de otro producto, este entra al grupo. */
    private fun saveExtras(d: ProductDraft, id: Long) {
        db.productQueries.setExtras(if (d.serialized) 1 else 0, d.variantGroup.trim(), d.variantName.trim(), id)
        val owner = d.variantGroup.removePrefix("g").toLongOrNull()
        if (d.variantGroup.startsWith("g") && owner != null && owner != id) db.productQueries.joinGroup(d.variantGroup, owner)
    }

    private fun cleanSerials(list: List<String>): List<String> = list.map { it.trim().uppercase() }.filter { it.isNotEmpty() }

    /** Compra (dentro de una transacción). Devuelve su id. */
    private fun savePurchaseLocal(op: Op.SavePurchase, now: Long): Long {
        val lines = op.lines.filter { it.qty != 0.0 }
        if (lines.isEmpty()) throw RuleException("La compra no tiene productos")
        lines.forEach {
            if (it.qty < 0) throw RuleException("Las cantidades de una compra deben ser positivas")
            if (it.unitCost < 0) throw RuleException("El costo no puede ser negativo")
        }
        if (op.tax < 0) throw RuleException("El ITBIS no puede ser negativo")
        val ncf = op.ncf.trim().uppercase()
        ncfProblem(ncf)?.let { throw RuleException(it) }
        val rnc = op.supplierRnc.filter(Char::isDigit)
        if (ncf.isNotEmpty() && db.inventoryQueries.purchaseByNcf(ncf, rnc).executeAsOneOrNull() != null)
            throw RuleException("Ya registraste una compra con el NCF $ncf de este proveedor")
        val seen = HashSet<String>()
        val products = lines.associate { l ->
            val p = db.productQueries.selectById(l.productId).executeAsOneOrNull() ?: throw RuleException("Un producto de la compra ya no existe")
            if (p.serialized == 1L) {
                val sn = cleanSerials(l.serials)
                if (sn.size.toDouble() != l.qty) throw RuleException("${p.fullName}: escribe o escanea un IMEI por cada unidad (${l.qty.fmtQty()})")
                sn.forEach { if (!seen.add(it) || db.inventoryQueries.availableUnit(it).executeAsOneOrNull() != null) throw RuleException("El IMEI $it ya está en existencia") }
            }
            l.productId to p
        }
        val subtotal = lines.sumOf { (it.qty * it.unitCost).roundToLong() }
        val number = db.inventoryQueries.nextPurchaseNumber().executeAsOne()
        db.inventoryQueries.insertPurchase(
            number, op.supplierId, op.supplierName.trim(), rnc, ncf, op.docDate, subtotal, op.tax, subtotal + op.tax,
            op.paymentMethod, op.note.trim(), op.user, now,
        )
        val id = db.inventoryQueries.lastId().executeAsOne()
        tagBranch("purchase", id)
        val text = listOf("Compra #$number", op.supplierName.trim(), ncf).filter { it.isNotBlank() }.joinToString(" · ")
        for (l in lines) {
            val p = db.productQueries.selectById(l.productId).executeAsOne()
            val sn = if (products.getValue(l.productId).serialized == 1L) cleanSerials(l.serials) else emptyList()
            db.inventoryQueries.insertPurchaseItem(id, p.id, p.fullName, l.qty, l.unitCost, sn.joinToString(","))
            // Costo promedio ponderado: lo que había a su costo más lo que entra a su costo.
            val old = p.stock.coerceAtLeast(0.0)
            val avg = if (old + l.qty > 0) ((old * p.cost + l.qty * l.unitCost) / (old + l.qty)).roundToLong() else l.unitCost
            db.productQueries.setCost(avg, now, p.id)
            if (p.trackStock == 1L) {
                addStockAt(p.id, l.qty, now)
                logMove(p.id, MoveType.PURCHASE, l.qty, text, now, op.user, l.unitCost)
            }
            sn.forEach { db.inventoryQueries.insertUnit(p.id, it, UnitStatus.AVAILABLE, l.unitCost, id, "", now, now); tagBranch("productUnit", lastRowId()) }
        }
        return id
    }

    private fun voidPurchaseLocal(op: Op.VoidPurchase, now: Long) {
        val pu = db.inventoryQueries.purchaseById(op.id).executeAsOneOrNull() ?: throw RuleException("La compra no existe")
        if (pu.status != SaleStatus.OK) return
        if (op.reason.isBlank()) throw RuleException("Escribe el motivo")
        val items = db.inventoryQueries.purchaseItems(op.id).executeAsList()
        // Primero se revisa que todo siga en existencia.
        for (it in items) {
            val p = db.productQueries.selectById(it.productId).executeAsOneOrNull() ?: continue
            val here = stockAt(p.id, pu.branchId)
            if (p.trackStock == 1L && here + 1e-9 < it.qty) throw RuleException("No se puede anular: de ${p.fullName} solo quedan ${here.fmtQty()} en la sucursal de la compra")
            it.serials.split(',').filter { s -> s.isNotBlank() }.forEach { sn ->
                if (db.inventoryQueries.availableUnit(sn).executeAsOneOrNull()?.takeIf { u -> u.branchId == pu.branchId } == null)
                    throw RuleException("No se puede anular: el IMEI $sn ya no está en existencia")
            }
        }
        for (it in items) {
            val p = db.productQueries.selectById(it.productId).executeAsOneOrNull() ?: continue
            val left = p.stock - it.qty
            if (left > 0) db.productQueries.setCost(((p.stock * p.cost - it.qty * it.unitCost) / left).roundToLong().coerceAtLeast(0), now, p.id)
            if (p.trackStock == 1L) {
                addStockAt(p.id, -it.qty, now, pu.branchId)
                logMove(p.id, MoveType.PURCHASE_VOID, -it.qty, "Compra #${pu.number} anulada · ${op.reason.trim()}", now, op.user, it.unitCost, branch = pu.branchId)
            }
            it.serials.split(',').filter { s -> s.isNotBlank() }.forEach { sn ->
                db.inventoryQueries.availableUnit(sn).executeAsOneOrNull()?.let { u ->
                    db.inventoryQueries.setUnitStatus(UnitStatus.RETURNED, null, "Compra #${pu.number} anulada", now, u.id)
                }
            }
        }
        db.inventoryQueries.setPurchaseStatus(SaleStatus.VOID, op.id)
    }

    private fun stockLossLocal(op: Op.StockLoss, now: Long) {
        if (op.qty <= 0) throw RuleException("La cantidad debe ser mayor que cero")
        if (op.reason.isBlank()) throw RuleException("Elige el motivo de la merma")
        val p = db.productQueries.selectById(op.productId).executeAsOneOrNull() ?: throw RuleException("El producto no existe")
        if (p.trackStock != 1L) throw RuleException("Este producto no lleva existencias")
        val here = stockAt(p.id)
        if (op.qty > here + 1e-9) throw RuleException("De ${p.fullName} solo hay ${here.fmtQty()} en esta sucursal")
        val limit = currentSettings().approvalAmount
        if (limit > 0 && op.qty * p.cost > limit && op.authorizedBy.isBlank())
            throw RuleException("Una merma de ${Money.format((op.qty * p.cost).roundToLong(), currentSettings().currencySymbol)} necesita un administrador")
        val text = listOf(op.reason.trim(), op.note.trim()).filter { it.isNotBlank() }.joinToString(" · ")
        if (p.serialized == 1L) {
            val sn = cleanSerials(op.serials)
            if (sn.size.toDouble() != op.qty) throw RuleException("Elige el IMEI de cada unidad")
            sn.forEach { s ->
                val u = db.inventoryQueries.availableUnit(s).executeAsOneOrNull()
                if (u == null || u.productId != p.id || u.branchId != opBranch) throw RuleException("El IMEI $s no está en existencia en esta sucursal")
                db.inventoryQueries.setUnitStatus(UnitStatus.LOST, null, text, now, u.id)
            }
        }
        addStockAt(p.id, -op.qty, now)
        logMove(p.id, MoveType.LOSS, -op.qty, text, now, op.user)
    }

    /** Conteo físico. Devuelve el id del conteo. */
    private fun applyCountLocal(op: Op.ApplyCount, now: Long): Long {
        if (op.lines.isEmpty()) throw RuleException("No contaste ningún producto")
        data class Row(val p: Product, val line: CountLine, val real: Double, val here: Double)
        val rows = op.lines.mapNotNull { l ->
            if (l.counted < 0) throw RuleException("Las cantidades contadas no pueden ser negativas")
            val p = db.productQueries.selectById(l.productId).executeAsOneOrNull() ?: return@mapNotNull null
            if (p.trackStock != 1L || p.serialized == 1L) return@mapNotNull null
            // Lo que se vendió o entró mientras se contaba se descuenta de lo contado.
            val here = stockAt(p.id)
            Row(p, l, (l.counted + (here - l.expected)).coerceAtLeast(0.0), here)
        }
        if (rows.isEmpty()) throw RuleException("Ninguno de los productos contados lleva existencias (los de IMEI se revisan en su ficha)")
        val number = db.inventoryQueries.nextCountNumber().executeAsOne()
        val diffQty = rows.sumOf { it.real - it.here }
        val diffValue = rows.sumOf { ((it.real - it.here) * it.p.cost).roundToLong() }
        db.inventoryQueries.insertCount(number, op.note.trim(), rows.size.toLong(), diffQty, diffValue, op.user, now)
        val id = db.inventoryQueries.lastId().executeAsOne()
        tagBranch("stockCount", id)
        val text = listOf("Conteo #$number", op.note.trim()).filter { it.isNotBlank() }.joinToString(" · ")
        for (r in rows) {
            db.inventoryQueries.insertCountItem(id, r.p.id, r.p.fullName, r.line.expected, r.line.counted, r.p.cost)
            val diff = r.real - r.here
            if (kotlin.math.abs(diff) > 1e-9) {
                setStockAt(r.p.id, r.real, now)
                logMove(r.p.id, MoveType.COUNT, diff, text, now, op.user)
            }
        }
        return id
    }

    /** Devolución de una venta (todo o parte). Devuelve el id de la devolución. */
    private fun returnSaleLocal(op: Op.ReturnSale, now: Long): Long {
        val sale = db.saleQueries.selectById(op.saleId).executeAsOneOrNull() ?: throw RuleException("La venta no existe")
        if (sale.status == SaleStatus.VOID) throw RuleException("Esta venta está anulada")
        if (op.reason.isBlank()) throw RuleException("Escribe el motivo de la devolución")
        val rules = currentSettings()
        if (rules.returnMaxDays > 0 && op.authorizedBy.isBlank() && now - sale.createdAt > rules.returnMaxDays * 86_400_000L)
            throw RuleException("La venta tiene más de ${rules.returnMaxDays} días: hace falta un administrador")
        val lines = op.lines.filter { it.qty > 0 }
        if (lines.isEmpty()) throw RuleException("Elige qué se devuelve")
        val items = db.saleQueries.itemsBySale(op.saleId).executeAsList()
        val prior = db.inventoryQueries.returnItemsBySale(op.saleId).executeAsList()
        val gross = items.sumOf { LineInput(it.qty, it.price, it.taxable == 1L).amount }
        val ratio = if (gross == 0L) 0.0 else sale.total.toDouble() / gross
        data class R(val item: SaleItem, val line: ReturnLine, val serials: List<String>, val amount: Long)
        val rows = lines.map { l ->
            val item = items.firstOrNull { it.id == l.saleItemId } ?: throw RuleException("Ese artículo no es de esta venta")
            val left = item.qty - prior.filter { it.saleItemId == item.id }.sumOf { it.qty }
            if (l.qty > left + 1e-9) throw RuleException("${item.name}: solo quedan ${left.fmtQty()} por devolver")
            val sold = item.serials.split(',').filter { it.isNotBlank() }
            val sn = cleanSerials(l.serials)
            if (sold.isNotEmpty()) {
                val back = prior.filter { it.saleItemId == item.id }.flatMap { it.serials.split(',') }.toSet()
                if (sn.size.toDouble() != l.qty) throw RuleException("${item.name}: elige el IMEI de cada unidad que se devuelve")
                sn.forEach { if (it !in sold || it in back) throw RuleException("El IMEI $it no se puede devolver en esta venta") }
            }
            R(item, l, sn, (l.qty * item.price * ratio).roundToLong())
        }
        val already = db.inventoryQueries.returnsBySale(op.saleId).executeAsList().sumOf { it.total }
        val estimate = rows.sumOf { it.amount }.coerceAtMost(sale.total - already)
        if (rules.approvalAmount > 0 && estimate > rules.approvalAmount && op.authorizedBy.isBlank())
            throw RuleException("Una devolución de ${Money.format(estimate, rules.currencySymbol)} necesita un administrador")
        // Nota de crédito (E34) cuando la venta tiene comprobante electrónico.
        val original = db.fiscalQueries.docBySale(op.saleId).executeAsList().firstOrNull { it.type != 34L }
        var total = rows.sumOf { it.amount }
        if (original != null) {
            val retItems = rows.map { it.item.copy(qty = it.line.qty) }
            val retGross = retItems.sumOf { LineInput(it.qty, it.price, it.taxable == 1L).amount }
            val disc = if (gross == 0L) 0 else (sale.discount * retGross.toDouble() / gross).roundToLong()
            val whole = rows.sumOf { it.line.qty } + prior.sumOf { it.qty } >= items.sumOf { it.qty } - 1e-9
            val note = fiscal.issueCreditNote(sale, retItems, original, op.reason.trim(), now, discount = disc, code = if (whole && already == 0L) 1 else 3)
            fiscal.store(note, op.saleId, deviceId, now)
            total = note.total
        }
        total = total.coerceAtMost(sale.total - already).coerceAtLeast(0)
        if (rules.approvalAmount > 0 && total > rules.approvalAmount && op.authorizedBy.isBlank())
            throw RuleException("Una devolución de ${Money.format(total, rules.currencySymbol)} necesita un administrador")
        val tax = if (sale.total == 0L) 0 else (sale.tax * total.toDouble() / sale.total).roundToLong()
        val cost = rows.sumOf { (it.line.qty * it.item.cost).roundToLong() }
        // Reembolso en efectivo: sale de la caja abierta de este equipo.
        if (op.refundMethod == PaymentMethods.CASH && total > 0) {
            val session = db.cashSessionQueries.selectOpen(op.deviceId).executeAsOneOrNull()
                ?: throw RuleException("Para devolver en efectivo abre la caja de este equipo")
            if (total > expectedCashSync(session)) throw RuleException("No hay suficiente efectivo en la caja para devolver")
            db.cashSessionQueries.insertMove(session.id, CashMoveType.REFUND, total, "Venta #${sale.number} · ${op.reason.trim()}", op.user, now)
        }
        db.inventoryQueries.insertReturn(op.saleId, total, tax, cost, op.refundMethod, op.reason.trim(), op.deviceId, op.user, now)
        val id = db.inventoryQueries.lastId().executeAsOne()
        tagBranch("saleReturn", id)
        for (r in rows) {
            db.inventoryQueries.insertReturnItem(id, r.item.id, r.item.productId, r.item.name, r.line.qty, r.amount, if (r.line.restock) 1 else 0, r.serials.joinToString(","))
            val pid = r.item.productId ?: continue
            val p = db.productQueries.selectById(pid).executeAsOneOrNull() ?: continue
            if (r.line.restock && p.trackStock == 1L) {
                addStockAt(pid, r.line.qty, now)
                logMove(pid, MoveType.RETURN, r.line.qty, "Devolución venta #${sale.number} · ${op.reason.trim()}", now, op.user, r.item.cost)
            }
            r.serials.forEach { sn ->
                db.inventoryQueries.unitBySerial(sn).executeAsList().firstOrNull { it.saleId == sale.id }?.let { u ->
                    db.inventoryQueries.setUnitStatus(
                        if (r.line.restock) UnitStatus.AVAILABLE else UnitStatus.LOST, null,
                        "Devuelto de la venta #${sale.number}" + if (r.line.restock) "" else " (dañado)", now, u.id,
                    )
                    tagBranch("productUnit", u.id)
                }
            }
        }
        return id
    }

    /** Une productos repetidos (por ejemplo, el mismo artículo creado una vez por proveedor). */
    private fun mergeLocal(op: Op.MergeProducts, now: Long) {
        val keep = db.productQueries.selectById(op.keepId).executeAsOneOrNull()?.takeIf { it.active == 1L } ?: throw RuleException("El producto no existe")
        val others = op.mergeIds.distinct().filter { it != keep.id }.mapNotNull { db.productQueries.selectById(it).executeAsOneOrNull()?.takeIf { p -> p.active == 1L } }
        if (others.isEmpty()) throw RuleException("Elige qué productos se unen")
        if (others.any { it.serialized != keep.serialized }) throw RuleException("No se puede unir un producto con IMEI con uno sin IMEI")
        val all = listOf(keep) + others
        val units = all.sumOf { it.stock.coerceAtLeast(0.0) }
        val cost = if (units > 0) (all.sumOf { it.stock.coerceAtLeast(0.0) * it.cost } / units).roundToLong() else keep.cost
        // Existencias por sucursal: las de los que se unen pasan al que queda.
        val byBranch = HashMap<Long, Double>()
        for (x in all) {
            val rows = db.branchQueries.rowsOfProduct(x.id).executeAsList()
            if (rows.isEmpty()) byBranch.merge(MAIN_BRANCH, x.stock, Double::plus) else rows.forEach { byBranch.merge(it.branchId, it.qty, Double::plus) }
        }
        all.forEach { db.branchQueries.deleteOfProduct(it.id) }
        byBranch.forEach { (b, q) -> db.branchQueries.ensureRow("${keep.id}:$b", keep.id, b, q) }
        for (o in others) {
            for (t in listOf("saleItem", "stockMove", "purchaseItem", "productUnit", "quoteItem", "stockCountItem", "saleReturnItem")) {
                driver.execute(null, "UPDATE $t SET productId = ? WHERE productId = ?", 2) { bindLong(0, keep.id); bindLong(1, o.id) }
            }
            db.productQueries.deactivate(now, o.id)
        }
        val codes = (keep.altCodes.split(',') + others.flatMap { it.altCodes.split(',') + it.code })
            .map { it.trim() }.filter { it.isNotBlank() && !it.equals(keep.code, true) }.distinct()
        db.productQueries.setAltCodes(codes.joinToString(","), keep.id)
        db.productQueries.setStock(all.sumOf { it.stock }, now, keep.id)
        db.productQueries.setCost(cost, now, keep.id)
        if (keep.trackStock == 0L && others.any { it.trackStock == 1L })
            driver.execute(null, "UPDATE product SET trackStock = 1 WHERE id = ?", 1) { bindLong(0, keep.id) }
        op.name.trim().takeIf { it.isNotBlank() && it != keep.name }?.let { n ->
            driver.execute(null, "UPDATE product SET name = ? WHERE id = ?", 2) { bindString(0, n); bindLong(1, keep.id) }
        }
        // El kardex queda con el saldo acumulado de todos los movimientos unidos.
        val bal = HashMap<Long, Double>()
        db.stockMoveQueries.kardex(keep.id, 0, Long.MAX_VALUE).executeAsList().forEach { m ->
            val b = bal.merge(m.branchId, m.qty, Double::plus)!!
            driver.execute(null, "UPDATE stockMove SET balance = ? WHERE id = ?", 2) { bindDouble(0, b); bindLong(1, m.id) }
        }
        logMove(keep.id, MoveType.ADJUST, 0.0, "Unido con " + others.joinToString(", ") { it.fullName }, now, "")
    }

    /** Qué se anota en la actividad para cada operación (null = nada). Se llama antes de aplicarla. */
    private fun describe(op: Op): Pair<String, String>? {
        val sym = currentSettings().currencySymbol
        fun m(c: Long) = Money.format(c, sym)
        fun prod(id: Long) = db.productQueries.selectById(id).executeAsOneOrNull()
        fun join(vararg p: String?) = p.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
        return when (op) {
            is Op.SaveProduct -> {
                val d = op.draft
                val old = d.id?.let { prod(it) }
                when {
                    old == null -> "Creó un producto" to join(d.name.trim(), m(d.price))
                    old.price != d.price && old.cost != d.cost -> "Cambió precio y costo" to "${old.fullName}: precio ${m(old.price)} → ${m(d.price)}, costo ${m(old.cost)} → ${m(d.cost)}"
                    old.price != d.price -> "Cambió el precio" to "${old.fullName}: ${m(old.price)} → ${m(d.price)}"
                    old.cost != d.cost -> "Cambió el costo" to "${old.fullName}: ${m(old.cost)} → ${m(d.cost)}"
                    old.name != d.name.trim() -> "Cambió el nombre" to "${old.name} → ${d.name.trim()}"
                    else -> null
                }
            }
            is Op.DeleteProduct -> prod(op.id)?.let { "Borró un producto" to it.fullName }
            is Op.AdjustStock -> prod(op.productId)?.let { "Ajustó existencias" to join("${it.fullName}: ${it.stock.fmtQty()} → ${op.newStock.fmtQty()}", op.note) }
            is Op.VoidSale -> db.saleQueries.selectById(op.saleId).executeAsOneOrNull()?.let { "Anuló la venta #${it.number}" to join(m(it.total), op.reason) }
            is Op.ReturnSale -> db.saleQueries.selectById(op.saleId).executeAsOneOrNull()?.let {
                "Devolución de la venta #${it.number}" to join(op.refundMethod, op.reason, op.authorizedBy.takeIf { a -> a.isNotBlank() && a != op.user }?.let { a -> "autorizó $a" })
            }
            is Op.StockLoss -> prod(op.productId)?.let {
                "Registró una merma" to join("${op.qty.fmtQty()} ${it.fullName}", op.reason, m((op.qty * it.cost).roundToLong()), op.authorizedBy.takeIf { a -> a.isNotBlank() && a != op.user }?.let { a -> "autorizó $a" })
            }
            is Op.ApplyCount -> "Aplicó un conteo físico" to "${op.lines.size} productos"
            is Op.SavePurchase -> "Registró una compra" to join(op.supplierName, op.ncf, "${op.lines.size} productos")
            is Op.VoidPurchase -> db.inventoryQueries.purchaseById(op.id).executeAsOneOrNull()?.let { "Anuló la compra #${it.number}" to join(m(it.total), op.reason) }
            is Op.OpenCash -> "Abrió la caja" to m(op.amount)
            is Op.CloseCash -> db.cashSessionQueries.selectById(op.sessionId).executeAsOneOrNull()?.let {
                val diff = op.counted - expectedCashSync(it)
                "Cerró la caja" to join("contado ${m(op.counted)}", if (diff == 0L) "cuadra" else if (diff > 0) "sobran ${m(diff)}" else "faltan ${m(-diff)}",
                    op.authorizedBy.takeIf { a -> a.isNotBlank() && a != op.user }?.let { a -> "autorizó $a" })
            }
            is Op.CashMove -> "${CashMoveType.label(op.kind)} de caja" to join(m(op.amount), op.note)
            is Op.SetUserPermissions -> db.appUserQueries.selectById(op.id).executeAsOneOrNull()?.let { "Cambió permisos" to it.name }
            is Op.SaveBranch -> if (op.id == null) "Creó una sucursal" to op.name.trim()
                else db.branchQueries.selectById(op.id).executeAsOneOrNull()?.let { old ->
                    when {
                        old.active == 1L && !op.active -> "Desactivó una sucursal" to old.name
                        old.active == 0L && op.active -> "Activó una sucursal" to old.name
                        else -> "Cambió una sucursal" to op.name.trim()
                    }
                }
            is Op.SetUserBranches -> db.appUserQueries.selectById(op.id).executeAsOneOrNull()?.let { u ->
                val names = op.branches.split(',').mapNotNull { it.trim().toLongOrNull() }.mapNotNull { db.branchQueries.selectById(it).executeAsOneOrNull()?.name }
                "Cambió las sucursales de un usuario" to "${u.name}: " + names.ifEmpty { listOf("todas") }.joinToString(", ")
            }
            is Op.SaveUser -> {
                val old = op.id?.let { db.appUserQueries.selectById(it).executeAsOneOrNull() }
                when {
                    old == null -> "Creó un usuario" to join(op.name.trim(), Roles.label(op.role))
                    old.role != op.role -> "Cambió el rol" to "${old.name}: ${Roles.label(old.role)} → ${Roles.label(op.role)}"
                    (old.active == 1L) != op.active -> (if (op.active) "Activó un usuario" else "Desactivó un usuario") to old.name
                    old.name != op.name.trim() -> "Cambió el nombre de un usuario" to "${old.name} → ${op.name.trim()}"
                    op.pinHash != null && !Crypto.isLegacyPin(old.pinHash) -> "Cambió el PIN" to old.name
                    else -> null
                }
            }
            is Op.SaveSettings -> {
                val keys = op.values.keys - setOf("lastBackupAt", "fiscalSteps", "onboarded")
                if (keys.isEmpty()) null else "Cambió la configuración" to keys.joinToString(", ") { settingLabel(it) }
            }
            is Op.DeleteClient -> db.clientQueries.selectById(op.id).executeAsOneOrNull()?.let { "Borró un cliente" to it.name }
            is Op.DeleteSupplier -> db.supplierQueries.selectById(op.id).executeAsOneOrNull()?.let { "Borró un proveedor" to it.name }
            is Op.DeleteCategory -> "Borró una categoría" to (categories.value.firstOrNull { it.id == op.id }?.name ?: "")
            is Op.DeleteQuote -> db.quoteQueries.selectById(op.id).executeAsOneOrNull()?.let { "Borró una cotización" to "#${it.number} · ${it.clientName}" }
            is Op.SaveFiscalRange -> "Registró números e-NCF" to "E${op.ecfType}: ${op.fromNo} a ${op.toNo}"
            is Op.DeleteFiscalRange -> "Borró un rango de e-NCF" to ""
            is Op.AddUnits -> prod(op.productId)?.let { "Registró IMEI" to "${op.serials.size} · ${it.fullName}" }
            is Op.MergeProducts -> "Unió productos" to (listOf(op.keepId) + op.mergeIds).mapNotNull { prod(it)?.fullName }.joinToString(" + ")
            is Op.LogEvent -> op.action to op.detail
            else -> null
        }
    }

    private fun settingLabel(k: String) = when (k) {
        "businessName" -> "nombre del negocio"; "taxRate" -> "ITBIS"; "pricesIncludeTax" -> "precios con ITBIS"; "currencySymbol" -> "moneda"
        "requireCash" -> "exigir caja"; "maxDiscountPct" -> "descuento máximo"; "adminNoStock" -> "vender sin existencias"
        "modTaller" -> "módulo Taller"; "modImei" -> "módulo IMEI"; "modVariants" -> "módulo Variantes"; "modQuotes" -> "módulo Cotizaciones"
        "modLabels" -> "módulo Etiquetas"; "businessType" -> "tipo de negocio"; "lockMinutes" -> "bloqueo por inactividad"
        "blindClose" -> "cierre a ciegas"; "cashDiffLimit" -> "tope de diferencia de caja"; "returnMaxDays" -> "días para devolver"
        "approvalAmount" -> "tope de devolución o merma"; "fiscalEnabled" -> "facturación electrónica"; "fiscalEnv" -> "ambiente DGII"
        else -> k
    }

    private fun addUnitsLocal(op: Op.AddUnits, now: Long) {
        val p = db.productQueries.selectById(op.productId).executeAsOneOrNull() ?: throw RuleException("El producto no existe")
        if (p.serialized != 1L) throw RuleException("Este producto no se controla por IMEI")
        val sn = cleanSerials(op.serials)
        if (sn.isEmpty()) throw RuleException("Escribe o escanea al menos un IMEI")
        if (sn.toSet().size != sn.size) throw RuleException("Hay IMEI repetidos")
        sn.forEach { if (db.inventoryQueries.availableUnit(it).executeAsOneOrNull() != null) throw RuleException("El IMEI $it ya está en existencia") }
        // Primero se le pone IMEI a lo que ya está en existencia; lo que sobre entra como existencia nueva.
        val tagged = db.inventoryQueries.unitsByProduct(p.id).executeAsList().count { it.status == UnitStatus.AVAILABLE && it.branchId == opBranch }
        val untagged = (stockAt(p.id) - tagged).coerceAtLeast(0.0).toInt()
        sn.forEach { db.inventoryQueries.insertUnit(p.id, it, UnitStatus.AVAILABLE, p.cost, null, "Registrado en existencia", now, now); tagBranch("productUnit", lastRowId()) }
        val extra = sn.size - untagged
        if (extra > 0) {
            addStockAt(p.id, extra.toDouble(), now)
            logMove(p.id, MoveType.ENTRY, extra.toDouble(), "Alta por IMEI", now, op.user)
        }
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
        tagBranch("repairOrder", id)
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
        // Productos con IMEI: una unidad disponible por cada una que se vende, sin repetir.
        val seen = HashSet<String>()
        op.lines.filter { it.productId != null }.forEach { l ->
            val p = db.productQueries.selectById(l.productId!!).executeAsOneOrNull() ?: return@forEach
            if (p.serialized != 1L) return@forEach
            if (l.serials.size.toDouble() != l.qty) throw RuleException("${l.name}: elige el IMEI de cada unidad")
            l.serials.forEach { sn ->
                if (!seen.add(sn)) throw RuleException("El IMEI $sn está repetido en la venta")
                val u = db.inventoryQueries.availableUnit(sn).executeAsOneOrNull()
                if (u == null || u.productId != p.id) throw RuleException("El IMEI $sn no está disponible para ${p.fullName}")
                if (u.branchId != opBranch) throw RuleException("El IMEI $sn está en otra sucursal")
            }
        }
        if (currentSettings().requireCash && db.cashSessionQueries.selectOpen(op.deviceId).executeAsOneOrNull() == null)
            throw RuleException("La caja de este equipo está cerrada. Ábrela para poder cobrar.")
        if (!op.allowNoStock) {
            op.lines.filter { it.productId != null && it.trackStock }.groupBy { it.productId!! }.forEach { (pid, ls) ->
                val p = db.productQueries.selectById(pid).executeAsOneOrNull() ?: return@forEach
                val want = ls.sumOf { it.qty }
                val here = stockAt(pid)
                if (p.trackStock == 1L && want > here + 1e-9)
                    throw RuleException(if (here <= 0) "${p.name}: no hay existencias en esta sucursal" else "${p.name}: solo quedan ${here.fmtQty()}")
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
        // Con IMEI, el costo es el de cada unidad (identificación específica).
        val lines = op.lines.map { l ->
            if (l.serials.isEmpty()) return@map l
            val costs = l.serials.mapNotNull { db.inventoryQueries.availableUnit(it).executeAsOneOrNull()?.cost }
            if (costs.size == l.serials.size && costs.isNotEmpty()) l.copy(cost = costs.sum() / costs.size) else l
        }
        val totals = totalsFor(lines, op.discount)
        val cost = lines.sumOf { (it.qty * it.cost).roundToLong() }
        val cash = op.paymentMethod == PaymentMethods.CASH
        val change = if (cash) (op.received - totals.total).coerceAtLeast(0) else 0
        val paid = if (cash) op.received else totals.total
        db.saleQueries.insert(
            number, op.clientId, op.clientName, totals.gross, totals.discount, totals.tax, totals.total, cost,
            if (totals.taxIncluded) 1 else 0, op.paymentMethod, paid, change, op.quoteId, op.createdAt,
            op.deviceId, op.userId, op.userName,
        )
        val saleId = db.saleQueries.lastId().executeAsOne()
        tagBranch("sale", saleId)
        for (l in lines) {
            db.saleQueries.insertItem(saleId, l.productId, l.name, l.code, l.qty, l.price, l.cost, if (l.taxable) 1 else 0, l.serials.joinToString(","))
            if (l.productId != null && l.trackStock) {
                addStockAt(l.productId, -l.qty, op.createdAt)
                logMove(l.productId, MoveType.SALE, -l.qty, "Venta #$number", op.createdAt, op.userName, l.cost)
            }
            for (sn in l.serials) {
                val u = db.inventoryQueries.availableUnit(sn).executeAsOneOrNull() ?: continue
                db.inventoryQueries.setUnitStatus(UnitStatus.SOLD, saleId, "", op.createdAt, u.id)
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
                    "branchId" to JsonPrimitive(branchId),
                )))
                op.lines.forEachIndexed { i, l ->
                    RowCodec.upsert(driver, "saleItem", JsonObject(mapOf(
                        "id" to JsonPrimitive(pid * 1000 - i), "saleId" to JsonPrimitive(pid), "productId" to (l.productId?.let { JsonPrimitive(it) } ?: JsonNull),
                        "name" to JsonPrimitive(l.name), "code" to JsonPrimitive(l.code), "qty" to JsonPrimitive(l.qty), "price" to JsonPrimitive(l.price),
                        "cost" to JsonPrimitive(l.cost), "taxable" to JsonPrimitive(if (l.taxable) 1 else 0),
                        "serials" to JsonPrimitive(l.serials.joinToString(",")),
                    )))
                    if (l.productId != null && l.trackStock) {
                        addStockAt(l.productId, -l.qty, op.createdAt, branchId)
                        RowCodec.upsert(driver, "stockMove", JsonObject(mapOf(
                            "id" to JsonPrimitive(pid * 1000 - 500 - i), "productId" to JsonPrimitive(l.productId), "type" to JsonPrimitive(MoveType.SALE),
                            "qty" to JsonPrimitive(-l.qty), "note" to JsonPrimitive("Venta pendiente P${-number}"), "createdAt" to JsonPrimitive(op.createdAt),
                            "userName" to JsonPrimitive(op.userName), "branchId" to JsonPrimitive(branchId),
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

    fun saveAppliedResult(opId: String, result: String) = synchronized(writeLock) { db.syncQueries.appliedPut(opId, result, Time.now()) }

    // ================= Configuración =================

    suspend fun saveSettings(s: AppSettings) {
        val all = s.toMap()
        val local = all.filterKeys { it in AppSettings.LOCAL_KEYS }
        withContext(io) { synchronized(writeLock) { db.transaction { local.forEach { (k, v) -> db.syncQueries.metaPut("s.$k", v) } } } }
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

    /** El producto con la existencia de la sucursal de este equipo. */
    suspend fun productById(id: Long): Product? = withContext(io) {
        db.productQueries.selectById(id).executeAsOneOrNull()?.let { p -> atBranch(listOf(p), db.branchQueries.rowsOfProduct(id).executeAsList(), branchId).first() }
    }

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

    // ---------- Inventario (1.9) ----------

    /** IMEI en existencia en la sucursal de este equipo. */
    val availableUnits: StateFlow<List<com.vitrinapos.db.ProductUnit>> = combine(db.inventoryQueries.availableUnits().asFlow().mapToList(io), meta) { us, m ->
        val b = branchOf(m); us.filter { it.branchId == b }
    }.stateIn(scope, SharingStarted.Eagerly, db.inventoryQueries.availableUnits().executeAsList().filter { it.branchId == branchId })

    val purchases: StateFlow<List<com.vitrinapos.db.Purchase>> = db.inventoryQueries.purchases(300).asFlow().mapToList(io)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun savePurchase(
        supplier: Supplier?, supplierName: String, supplierRnc: String, ncf: String, docDate: Long,
        lines: List<PurchaseLine>, tax: Long, paymentMethod: String, note: String,
    ): Long = run(Op.SavePurchase(supplier?.id, supplierName, supplierRnc, ncf, docDate, lines, tax, paymentMethod, note, userName)).asId()

    suspend fun voidPurchase(id: Long, reason: String) { run(Op.VoidPurchase(id, reason, userName)) }

    suspend fun purchaseById(id: Long) = withContext(io) { db.inventoryQueries.purchaseById(id).executeAsOneOrNull() }

    suspend fun purchaseItems(id: Long) = withContext(io) { db.inventoryQueries.purchaseItems(id).executeAsList() }

    suspend fun purchasesBetween(from: Long, to: Long) = withContext(io) { db.inventoryQueries.purchasesBetween(from, to).executeAsList() }

    suspend fun stockLoss(productId: Long, qty: Double, reason: String, note: String, serials: List<String> = emptyList(), authorizedBy: String = "") {
        run(Op.StockLoss(productId, qty, reason, note, serials, userName, authorizedBy))
    }

    suspend fun lossesBetween(from: Long, to: Long) = withContext(io) { db.stockMoveQueries.lossesBetween(from, to).executeAsList() }

    suspend fun applyCount(lines: List<CountLine>, note: String): Long = run(Op.ApplyCount(lines, note, userName)).asId()

    suspend fun counts() = withContext(io) { db.inventoryQueries.counts().executeAsList() }

    suspend fun countItems(id: Long) = withContext(io) { db.inventoryQueries.countItems(id).executeAsList() }

    /** Conteo en curso de este equipo (se guarda para no perderlo si se cierra la app). */
    fun countDraft(): Map<Long, Pair<Double, Double>> = metaGet("count.draft")?.split(';')?.mapNotNull { e ->
        val (id, c, x) = e.split(':').takeIf { it.size == 3 } ?: return@mapNotNull null
        id.toLongOrNull()?.let { pid -> pid to ((c.toDoubleOrNull() ?: 0.0) to (x.toDoubleOrNull() ?: 0.0)) }
    }?.toMap() ?: emptyMap()

    fun saveCountDraft(d: Map<Long, Pair<Double, Double>>) = metaPut("count.draft", d.entries.joinToString(";") { "${it.key}:${it.value.first}:${it.value.second}" })

    suspend fun returnSale(saleId: Long, lines: List<ReturnLine>, reason: String, refundMethod: String, authorizedBy: String = ""): Long {
        if (saleId < 0) throw OfflineException("Esta venta todavía no llega al equipo principal.")
        return run(Op.ReturnSale(saleId, lines, reason, refundMethod, deviceId, userName, authorizedBy)).asId()
    }

    suspend fun returnsBySale(saleId: Long) = withContext(io) { db.inventoryQueries.returnsBySale(saleId).executeAsList() }

    suspend fun returnItemsBySale(saleId: Long) = withContext(io) { db.inventoryQueries.returnItemsBySale(saleId).executeAsList() }

    suspend fun returnsBetween(from: Long, to: Long) = withContext(io) { db.inventoryQueries.returnsBetween(from, to).executeAsList() }

    suspend fun addUnits(productId: Long, serials: List<String>) { run(Op.AddUnits(productId, serials, userName)) }

    suspend fun mergeProducts(keepId: Long, mergeIds: List<Long>, name: String) { run(Op.MergeProducts(keepId, mergeIds, name)) }

    /** Anota algo en la actividad (si falla, no detiene lo que se estaba haciendo). */
    suspend fun logEvent(action: String, detail: String) { runCatching { run(Op.LogEvent(action, detail)) } }

    suspend fun activity(from: Long, to: Long) = withContext(io) { db.auditQueries.between(from, to).executeAsList() }

    fun activityFlow(from: Long) = db.auditQueries.between(from, Long.MAX_VALUE).asFlow().mapToList(io)

    /** Producto por su código o por uno de sus códigos alternos (productos unidos). */
    fun productForCode(code: String, list: List<Product> = products.value): Product? {
        val c = code.trim()
        if (c.isEmpty()) return null
        return list.firstOrNull { it.code.equals(c, true) } ?: list.firstOrNull { p -> p.altCodes.split(',').any { it.trim().equals(c, true) } }
    }

    suspend fun unitsOf(productId: Long) = withContext(io) { db.inventoryQueries.unitsByProduct(productId).executeAsList() }

    suspend fun searchUnits(q: String) = withContext(io) { db.inventoryQueries.searchUnits(q.trim().uppercase()).executeAsList() }

    /** Kardex de un producto: saldo anterior y movimientos del periodo. */
    /** Kardex de un producto en una sucursal (por defecto, la de este equipo): saldo inicial y movimientos. */
    suspend fun kardex(productId: Long, from: Long, to: Long, branch: Long = branchId): Pair<Double, List<StockMove>> = withContext(io) {
        val all = db.stockMoveQueries.kardex(productId, 0, to).executeAsList().filter { it.branchId == branch }
        all.filter { it.createdAt < from }.sumOf { it.qty } to all.filter { it.createdAt >= from }
    }

    suspend fun movesByProduct(productId: Long): List<StockMove> = withContext(io) { db.stockMoveQueries.selectByProduct(productId).executeAsList().filter { it.branchId == branchId } }

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
    // ---------- Sucursales (2.2) ----------

    suspend fun saveBranch(id: Long?, name: String, address: String, phone: String, active: Boolean = true): Long =
        run(Op.SaveBranch(id, name, address, phone, active)).asId()

    /** Sucursales donde trabaja un usuario (vacío = todas). */
    suspend fun setUserBranches(userId: Long, branchIds: List<Long>) { run(Op.SetUserBranches(userId, branchIds.distinct().joinToString(","))) }

    /** Sucursales asignadas a un usuario (vacío = todas). */
    fun branchesOf(user: AppUser): List<Long> = user.branches.split(',').mapNotNull { it.trim().toLongOrNull() }

    /** ¿El usuario trabaja en esta sucursal? Los administradores y los que no tienen sucursales asignadas, en todas. */
    fun worksAt(user: AppUser, branch: Long = branchId): Boolean =
        user.role == Roles.ADMIN || branchesOf(user).let { it.isEmpty() || branch in it }

    /** En qué sucursal está este equipo (en la nube lo decide el dueño en Equipos). */
    fun setThisDeviceBranch(id: Long) = metaPut("branchId", id.toString())

    /** Sucursal de este equipo (null si todavía no llegó la lista de sucursales). */
    val thisBranch: Branch? get() = branches.value.firstOrNull { it.id == branchId }

    /** Ventas de un periodo por sucursal: sucursal → (cantidad, total). */
    suspend fun salesByBranch(from: Long, to: Long): Map<Long, Pair<Long, Long>> = withContext(io) {
        db.branchQueries.salesByBranch(from, to).executeAsList().associate { it.branchId to (it.sales to it.total) }
    }

    /** Productos con poca existencia en una sucursal. */
    suspend fun lowStockAt(branch: Long) = withContext(io) { db.branchQueries.lowStockByBranch(branch).executeAsList() }

    /** Productos con existencia y unidades por sucursal. */
    suspend fun stockSummaryByBranch(): Map<Long, Pair<Long, Double>> = withContext(io) {
        db.branchQueries.stockByBranch().executeAsList().associate { it.branchId to (it.products to (it.units ?: 0.0)) }
    }

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

    suspend fun closeCashSession(session: CashSession, counted: Long, notes: String, authorizedBy: String = "") {
        run(Op.CloseCash(session.id, counted, notes, authorizedBy, userName))
    }

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
