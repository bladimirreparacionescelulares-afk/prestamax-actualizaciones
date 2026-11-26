package com.vitrinapos

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
    data class RepairReceive(val id: Long? = null) : Screen
    data class RepairDetail(val id: Long) : Screen

    // Pantallas secundarias
    data class ProductForm(val id: Long? = null, val presetCode: String? = null, val key: Long = 0) : Screen
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
        val u = repo.currentUser.value ?: return false
        return com.vitrinapos.data.can(u.role, p)
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

    fun addToCart(p: Product, qty: Double = 1.0) {
        val i = cart.indexOfFirst { it.productId == p.id && it.price == p.price }
        val newQty = (if (i >= 0) cart[i].qty else 0.0) + qty
        if (i >= 0) cart[i] = cart[i].copy(qty = newQty) else cart.add(CartLine.of(p, qty))
        if (p.trackStock == 1L && newQty > p.stock) {
            toast(if (p.stock <= 0) "${p.name}: sin stock" else "${p.name}: solo quedan ${p.stock.fmtQty()}")
        }
    }

    fun setLineQty(index: Int, qty: Double) {
        if (index !in cart.indices) return
        if (qty <= 0) cart.removeAt(index) else cart[index] = cart[index].copy(qty = qty)
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
