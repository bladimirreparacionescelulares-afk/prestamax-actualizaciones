package com.vitrinapos

import com.vitrinapos.data.Permission

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.vitrinapos.data.AppGraph
import com.vitrinapos.data.AppSettings
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.Repository
import com.vitrinapos.data.fmtQty
import com.vitrinapos.db.Client
import com.vitrinapos.db.Product
import com.vitrinapos.platform.Platform
import com.vitrinapos.platform.PrinterDevice
import com.vitrinapos.print.Ticket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    // Pestañas principales
    data object Home : Screen
    data object Catalog : Screen
    data object Pos : Screen
    data object Clients : Screen
    data object More : Screen
    data object Taller : Screen
    data class RepairReceive(val id: Long? = null, val warrantyOf: Long? = null) : Screen
    data class RepairDeliver(val id: Long) : Screen
    data object RepairStatuses : Screen
    data object Fiscal : Screen
    data object FiscalEmitter : Screen
    data object FiscalCert : Screen
    data object FiscalDgii : Screen
    data object FiscalSequences : Screen
    data object FiscalDocs : Screen
    data class UserDetail(val id: Long) : Screen
    data class RepairDetail(val id: Long) : Screen

    // Pantallas secundarias
    data class ProductForm(val id: Long? = null, val presetCode: String? = null, val key: Long = 0, val copyOf: Long? = null) : Screen
    data object Inventory : Screen
    data object Purchases : Screen
    data class PurchaseDetail(val id: Long) : Screen
    data object StockCount : Screen
    data class StockCountDetail(val id: Long) : Screen
    data class Kardex(val productId: Long) : Screen
    data object ImeiSearch : Screen
    data class ProductUnits(val productId: Long) : Screen
    data class SaleReturn(val saleId: Long) : Screen
    data object Checkout : Screen
    data class SaleDetail(val id: Long) : Screen
    data object SalesHistory : Screen
    data class ClientForm(val id: Long? = null) : Screen
    data class ClientDetail(val id: Long) : Screen
    data object Suppliers : Screen
    data class SupplierForm(val id: Long? = null) : Screen
    data object Quotes : Screen
    data class QuoteEditor(val id: Long? = null, val clientId: Long? = null) : Screen
    data object Settings : Screen
    data object Reports : Screen
    data object Categories : Screen
    data class StockEntry(val supplierId: Long? = null) : Screen
    data object Cash : Screen
    data object StockMoves : Screen
    data object Network : Screen
    data object Users : Screen
    data object Labels : Screen
    data object LabelDesigner : Screen
    data object LabelPrinter : Screen
    data class Scanner(val mode: String) : Screen
}

val LocalAppState = staticCompositionLocalOf<AppState> { error("AppState no disponible") }

class AppState(val graph: AppGraph, val scope: CoroutineScope) {
    val platform: Platform get() = graph.platform
    val repo: Repository get() = graph.repo

    val settings: AppSettings
        @Composable get() = repo.settings.collectAsState().value

    val settingsNow: AppSettings get() = repo.settings.value

    // ---------- Usuario actual y permisos ----------

    val user: com.vitrinapos.db.AppUser?
        @Composable get() = repo.currentUser.collectAsState().value

    /** Sin usuarios configurados todo está permitido; con usuarios, según el rol de quien entró. */
    fun can(p: com.vitrinapos.data.Permission): Boolean {
        if (repo.users.value.none { it.active == 1L }) return true
        val cur = repo.currentUser.value ?: return false
        // Se usa la copia más reciente del usuario: si el administrador le cambia los permisos, aplican al momento.
        val u = repo.users.value.firstOrNull { it.id == cur.id } ?: cur
        if (u.active == 0L) return false
        return com.vitrinapos.data.can(u.role, p, u.permissions)
    }

    /** Si el usuario que entró puede abrir esta pantalla. */
    fun allows(screen: Screen): Boolean {
        val needed = requiredPermission(screen)
        return needed.isEmpty() || needed.any { can(it) }
    }

    fun logout() {
        clearCart()
        stack.clear()
        tab = Screen.Home
        repo.currentUser.value = null
    }

    // ---------- Navegación ----------

    var tab by mutableStateOf<Screen>(Screen.Home)
        private set
    val stack = mutableStateListOf<Screen>()
    val current: Screen get() = stack.lastOrNull() ?: tab

    fun open(s: Screen) { stack.add(s) }

    fun back(): Boolean {
        if (stack.isNotEmpty()) { stack.removeAt(stack.lastIndex); return true }
        if (tab != Screen.Home) { tab = Screen.Home; return true }
        return false
    }

    fun selectTab(t: Screen) { stack.clear(); tab = t }

    /** Filtro inicial del catálogo cuando se llega desde otra pantalla (por ejemplo "low" = stock bajo). */
    var catalogPreset by mutableStateOf<String?>(null)

    /** Reemplaza la pantalla actual (por ejemplo, de cobro a detalle de venta). */
    fun replace(s: Screen) {
        if (stack.isNotEmpty()) stack[stack.lastIndex] = s else stack.add(s)
    }

    // ---------- Carrito ----------

    val cart = mutableStateListOf<CartLine>()
    var cartClient by mutableStateOf<Client?>(null)
    var cartDiscount by mutableStateOf(0L)
    var cartQuoteId by mutableStateOf<Long?>(null)

    val cartCount: Double get() = cart.sumOf { it.qty }

    fun qtyInCart(productId: Long): Double = cart.filter { it.productId == productId }.sumOf { it.qty }

    /** Quien vende puede pasar del stock (permiso "Vender sin existencias"). */
    val canSellNoStock: Boolean get() {
        // Nunca por defecto: el administrador (o la tienda sin usuarios) solo si Configuración lo permite;
        // los demás, con el permiso que les dé el administrador.
        val noUsers = repo.users.value.none { it.active == 1L }
        val admin = repo.currentUser.value?.role == com.vitrinapos.data.Roles.ADMIN
        if (noUsers || admin) return repo.settings.value.adminNoStock && can(com.vitrinapos.data.Permission.SELL_NO_STOCK)
        return can(com.vitrinapos.data.Permission.SELL_NO_STOCK)
    }

    /** Cantidad máxima que se puede llevar de un producto (null = sin límite). */
    fun stockLimit(productId: Long?): Double? {
        if (productId == null || canSellNoStock) return null
        val p = repo.products.value.firstOrNull { it.id == productId } ?: return null
        return if (p.trackStock == 1L) p.stock.coerceAtLeast(0.0) else null
    }

    private fun stockMessage(name: String, limit: Double) =
        if (limit <= 0) "$name: no hay existencias" else "$name: solo hay ${limit.fmtQty()}"

    /** Producto con IMEI al que hay que elegirle la unidad (abre el selector). */
    var unitPicker by mutableStateOf<Long?>(null)

    /** Grupo de variantes que se está eligiendo (abre el selector). */
    var variantPicker by mutableStateOf<String?>(null)

    /** Agrega una unidad con su IMEI. Devuelve false si no se pudo. */
    fun addUnit(p: Product, serial: String): Boolean {
        val sn = serial.trim().uppercase()
        if (cart.any { sn in it.serials }) { toast("El IMEI $sn ya está en la venta"); return false }
        val u = repo.availableUnits.value.firstOrNull { it.serial == sn }
        if (u == null || u.productId != p.id) { toast("El IMEI $sn no está en existencia"); return false }
        val i = cart.indexOfFirst { it.productId == p.id && it.price == p.price }
        if (i >= 0) cart[i] = cart[i].copy(qty = cart[i].qty + 1, serials = cart[i].serials + sn)
        else cart.add(CartLine.of(p, 1.0, listOf(sn)))
        return true
    }

    /** Agrega al carrito sin pasar del stock. Devuelve false si no se pudo. */
    fun addToCart(p: Product, qty: Double = 1.0): Boolean {
        // Con IMEI se elige la unidad exacta.
        if (p.serialized == 1L) { unitPicker = p.id; return false }
        val limit = stockLimit(p.id)
        if (limit != null && qtyInCart(p.id) + qty > limit + 1e-9) { toast(stockMessage(p.name, limit)); return false }
        val i = cart.indexOfFirst { it.productId == p.id && it.price == p.price }
        val newQty = (if (i >= 0) cart[i].qty else 0.0) + qty
        if (i >= 0) cart[i] = cart[i].copy(qty = newQty) else cart.add(CartLine.of(p, qty))
        return true
    }

    fun setLineQty(index: Int, qty: Double) {
        if (index !in cart.indices) return
        if (qty <= 0) { cart.removeAt(index); return }
        val line = cart[index]
        if (line.serials.isNotEmpty()) {
            // Con IMEI: subir abre el selector; bajar quita los últimos IMEI elegidos.
            if (qty > line.qty) { unitPicker = line.productId; return }
            val keep = qty.toInt().coerceAtLeast(1)
            cart[index] = line.copy(qty = keep.toDouble(), serials = line.serials.take(keep))
            return
        }
        val limit = stockLimit(line.productId)
        val others = cart.filterIndexed { i, l -> i != index && l.productId == line.productId && line.productId != null }.sumOf { it.qty }
        if (limit != null && others + qty > limit + 1e-9) {
            toast(stockMessage(line.name, limit))
            val allowed = (limit - others).coerceAtLeast(0.0)
            if (allowed <= 0) cart.removeAt(index) else cart[index] = line.copy(qty = allowed)
            return
        }
        cart[index] = line.copy(qty = qty)
    }

    /** Hace falta el PIN de un administrador (hay usuarios y quien entró no es administrador). */
    val needsAdminAuth: Boolean get() {
        if (repo.users.value.none { it.active == 1L }) return false
        return repo.currentUser.value?.role != com.vitrinapos.data.Roles.ADMIN
    }

    /** Quita una unidad del producto en el carrito (y la línea si llega a cero). */
    fun removeOne(productId: Long) {
        val i = cart.indexOfLast { it.productId == productId }
        if (i < 0) return
        val l = cart[i]
        if (l.qty > 1) cart[i] = l.copy(qty = l.qty - 1, serials = l.serials.dropLast(1)) else cart.removeAt(i)
    }

    fun clearCart() {
        cart.clear(); cartClient = null; cartDiscount = 0; cartQuoteId = null
    }

    // ---------- Etiquetas ----------

    /** Productos y copias a imprimir en el diseñador de etiquetas. */
    data class LabelItem(val productId: Long, val copies: Int)
    val labelJob = mutableStateListOf<LabelItem>()

    /** Producto recién creado: se pregunta si imprimir sus etiquetas. */
    var labelPrompt by mutableStateOf<Long?>(null)

    fun openLabelsFor(items: List<LabelItem>) {
        labelJob.clear()
        labelJob.addAll(items.filter { it.copies > 0 })
        open(Screen.LabelDesigner)
    }

    // ---------- Mensajes ----------

    val snackbar = SnackbarHostState()

    fun toast(msg: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    // ---------- Impresión ----------

    /** Lo asigna la interfaz: en Android pide permiso de Bluetooth antes de imprimir. */
    var requestPrinterPermission: (onGranted: () -> Unit) -> Unit = { it() }

    fun print(ticket: Ticket, settings: AppSettings = settingsNow, onNoPrinter: (() -> Unit)? = null) {
        val s = settings
        if (s.printerId.isBlank()) {
            toast("Elige una impresora en Configuración")
            onNoPrinter?.invoke()
            return
        }
        requestPrinterPermission {
            scope.launch {
                val result = runCatching {
                    sendToPrinter(PrinterDevice(s.printerId, s.printerName), ticket.escPos(s.ticketAscii, s.openDrawer))
                }
                toast(result.fold({ "Ticket enviado a ${s.printerName.ifBlank { "la impresora" }}" }, { "No se pudo imprimir: ${it.message ?: "revisa la impresora"}" }))
            }
        }
    }

    /** Envía bytes a una impresora: de red (IP), Bluetooth o instalada en Windows. */
    suspend fun sendToPrinter(device: PrinterDevice, bytes: ByteArray) = withContext(platform.io) {
        val net = com.vitrinapos.labels.NetworkPrinter.parse(device.id)
        if (net != null) com.vitrinapos.labels.NetworkPrinter.send(net.first, net.second, bytes)
        else platform.printRaw(device, bytes)
    }

    /**
     * Imprime etiquetas en la etiquetadora de este equipo. Cada producto se dibuja una vez
     * y la impresora repite las copias. [onProgress] recibe (hechas, total).
     */
    suspend fun printLabels(
        options: com.vitrinapos.labels.LabelOptions,
        labels: List<Pair<com.vitrinapos.labels.LabelContent, Int>>,
        measurer: androidx.compose.ui.text.TextMeasurer,
        settings: AppSettings = settingsNow,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        require(settings.labelPrinterId.isNotBlank()) { "Elige la etiquetadora en Configuración → Etiquetadora" }
        val cfg = settings.labelPrinter
        val device = PrinterDevice(settings.labelPrinterId, settings.labelPrinterName)
        // De a pocas etiquetas por envío para que la impresora no se quede sin memoria.
        labels.chunked(6).forEachIndexed { i, chunk ->
            val maps = chunk.map { (c, n) -> com.vitrinapos.labels.LabelRaster.render(options, c, cfg, measurer) to n }
            sendToPrinter(device, com.vitrinapos.labels.LabelEncoder.encode(cfg, options, maps))
            onProgress(minOf((i + 1) * 6, labels.size), labels.size)
        }
    }
}

/** Permisos que abren cada pantalla (basta con uno). Vacío = cualquiera puede entrar. */
fun requiredPermission(screen: Screen): List<com.vitrinapos.data.Permission> {
    return when (screen) {
        Screen.Pos, Screen.Checkout, is Screen.Scanner -> listOf(Permission.SELL)
        is Screen.ProductForm, Screen.Categories -> listOf(Permission.CATALOG_EDIT)
        Screen.StockMoves, Screen.StockCount, is Screen.StockCountDetail, is Screen.Kardex, is Screen.ProductUnits -> listOf(Permission.ADJUST_STOCK)
        is Screen.StockEntry, Screen.Purchases, is Screen.PurchaseDetail -> listOf(Permission.PURCHASES)
        Screen.Inventory -> listOf(Permission.ADJUST_STOCK, Permission.PURCHASES)
        Screen.ImeiSearch -> listOf(Permission.ADJUST_STOCK, Permission.SALES_HISTORY)
        is Screen.SaleReturn -> listOf(Permission.RETURNS)
        Screen.Clients, is Screen.ClientForm, is Screen.ClientDetail -> listOf(Permission.CLIENTS)
        Screen.Suppliers, is Screen.SupplierForm -> listOf(Permission.SUPPLIERS)
        Screen.Quotes, is Screen.QuoteEditor -> listOf(Permission.QUOTES)
        Screen.SalesHistory -> listOf(Permission.SALES_HISTORY)
        is Screen.SaleDetail -> listOf(Permission.SALES_HISTORY, Permission.SELL)
        Screen.Reports -> listOf(Permission.REPORTS)
        Screen.Cash -> listOf(Permission.CASH)
        Screen.Taller, is Screen.RepairReceive, is Screen.RepairDetail -> listOf(Permission.WORKSHOP, Permission.WORKSHOP_DELIVER)
        is Screen.RepairDeliver -> listOf(Permission.WORKSHOP_DELIVER)
        Screen.RepairStatuses -> listOf(Permission.SETTINGS)
        Screen.Labels, Screen.LabelDesigner, Screen.LabelPrinter -> listOf(Permission.LABELS)
        Screen.Network -> listOf(Permission.NETWORK)
        Screen.Users, is Screen.UserDetail -> listOf(Permission.USERS)
        Screen.Fiscal, Screen.FiscalEmitter, Screen.FiscalCert, Screen.FiscalDgii, Screen.FiscalSequences -> listOf(Permission.FISCAL)
        Screen.FiscalDocs -> listOf(Permission.FISCAL, Permission.SALES_HISTORY)
        else -> emptyList()
    }
}
