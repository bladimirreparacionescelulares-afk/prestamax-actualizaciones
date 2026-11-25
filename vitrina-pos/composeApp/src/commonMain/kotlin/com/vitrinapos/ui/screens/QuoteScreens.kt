package com.vitrinapos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.AppState
import com.vitrinapos.LocalAppState
import com.vitrinapos.Screen
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.Money
import com.vitrinapos.data.QuoteStatus
import com.vitrinapos.data.Time
import com.vitrinapos.db.Client
import com.vitrinapos.db.Quote
import com.vitrinapos.pdf.QuotePdf
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.ClientPickerDialog
import com.vitrinapos.ui.components.ConfirmDialog
import com.vitrinapos.ui.components.Divider
import com.vitrinapos.ui.components.DropdownField
import com.vitrinapos.ui.components.EmptyState
import com.vitrinapos.ui.components.Pill
import com.vitrinapos.ui.components.ProductImage
import com.vitrinapos.ui.components.ProductPickerDialog
import com.vitrinapos.ui.components.QtyStepper
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun QuoteStatusPill(status: String) {
    val ex = AppTheme.extra
    when (status) {
        QuoteStatus.DRAFT -> Pill("Borrador", ex.muted, MaterialTheme.colorScheme.surfaceContainer)
        QuoteStatus.SENT -> Pill("Enviada", ex.warn, ex.warnSoft)
        QuoteStatus.ACCEPTED -> Pill("Aceptada", ex.ok, ex.okSoft)
        QuoteStatus.SOLD -> Pill("Vendida", MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary)
        else -> Pill(status, ex.muted, MaterialTheme.colorScheme.surfaceContainer)
    }
}

@Composable
fun QuoteRow(q: Quote, onClick: () -> Unit) {
    val app = LocalAppState.current
    AppCard(onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(QuotePdf.quoteCode(q.number), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    QuoteStatusPill(q.status)
                }
                Text("${q.clientName} · ${Time.shortDate(q.createdAt)}", fontSize = 12.sp, color = AppTheme.extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(Money.format(q.total, app.settings.currencySymbol), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun QuotesScreen() {
    val app = LocalAppState.current
    val quotes by app.repo.quotes.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }
    val list = quotes.filter { status == null || it.status == status }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Cotizaciones", subtitle = "${quotes.count { it.status != QuoteStatus.SOLD }} pendientes", onBack = if (app.stack.isNotEmpty()) ({ app.back() }) else null)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { CategoryChip("Todas", status == null) { status = null } }
                items(QuoteStatus.all) { st -> CategoryChip(QuoteStatus.label(st) + " · " + quotes.count { it.status == st }, status == st) { status = if (status == st) null else st } }
            }
            if (quotes.isEmpty()) {
                EmptyState(Icons.Rounded.Description, "Sin cotizaciones", "Crea una cotización con fotos de tus productos y envíala en PDF.")
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { q -> QuoteRow(q) { app.open(Screen.QuoteEditor(q.id)) } }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { app.open(Screen.QuoteEditor()) },
            containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Cotización", fontWeight = FontWeight.Bold) },
        )
    }
}

/** Genera el PDF A4 de la cotización y devuelve el archivo. */
suspend fun AppState.quotePdf(quoteId: Long): File? {
    val repo = repo
    val q = repo.quoteById(quoteId) ?: return null
    val items = repo.quoteItems(quoteId)
    val client = q.clientId?.let { repo.clientById(it) }
    val s = settingsNow
    return withContext(platform.io) {
        val file = File(repo.docsDir, "${QuotePdf.quoteCode(q.number)}.pdf")
        platform.writePdf(file) { c ->
            QuotePdf.draw(c, s, q, items, client, repo.imageFile(s.logoPath), { repo.imageFile(it) })
        }
        file
    }
}

private val validOptions = listOf(7, 15, 30, 60, 90)

@Composable
fun QuoteEditorScreen(id: Long?, presetClientId: Long?) {
    val app = LocalAppState.current
    val s = app.settings
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    val clients by app.repo.clients.collectAsState()
    val products by app.repo.products.collectAsState()

    var quoteId by remember { mutableStateOf(id) }
    var number by remember { mutableStateOf<Long?>(null) }
    var client by remember { mutableStateOf<Client?>(null) }
    var clientName by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<CartLine>() }
    var discount by remember { mutableStateOf(0L) }
    var validDays by remember { mutableStateOf(s.quoteValidDays) }
    var notes by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(QuoteStatus.DRAFT) }
    var createdAt by remember { mutableStateOf(Time.now()) }
    var picking by remember { mutableStateOf(false) }
    var pickClient by remember { mutableStateOf(false) }
    var discountDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var editLine by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(id) {
        if (id != null) {
            val q = app.repo.quoteById(id) ?: return@LaunchedEffect
            number = q.number
            client = q.clientId?.let { app.repo.clientById(it) }
            clientName = q.clientName
            lines.clear(); lines.addAll(app.repo.quoteToCart(id))
            discount = q.discount; validDays = q.validDays.toInt(); notes = q.notes; status = q.status; createdAt = q.createdAt
        } else if (presetClientId != null) {
            client = app.repo.clientById(presetClientId)
            clientName = client?.name ?: ""
        }
    }
    val totals = app.repo.totalsFor(lines, discount, s)
    val sold = status == QuoteStatus.SOLD

    suspend fun persist(newStatus: String = status): Long {
        val saved = app.repo.saveQuote(quoteId, client, clientName.ifBlank { client?.name ?: "Cliente" }, lines.toList(), discount, validDays, notes, newStatus)
        quoteId = saved
        status = newStatus
        if (number == null) number = app.repo.quoteById(saved)?.number
        return saved
    }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { runCatching { block() }.onFailure { app.toast("Error: ${it.message}") }; busy = false }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Cotización",
            subtitle = (number?.let { QuotePdf.quoteCode(it) } ?: "Nueva") + " · " + QuoteStatus.label(status),
            onBack = { app.back() },
        ) {
            if (quoteId != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Eliminar", tint = MaterialTheme.colorScheme.error) }
            if (!sold) TextButton(onClick = { action { persist(); app.toast("Cotización guardada") } }, enabled = lines.isNotEmpty()) { Text("Guardar", fontWeight = FontWeight.Bold) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppCard(onClick = if (sold) null else ({ pickClient = true }), padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Cliente", fontSize = 12.sp, color = AppTheme.extra.muted)
                        Text(client?.name ?: clientName.ifBlank { "Elegir cliente" }, fontWeight = FontWeight.SemiBold)
                    }
                    if (!sold) Text("Cambiar", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            if (client == null && !sold) TextInput(clientName, { clientName = it }, "O escribe el nombre del cliente")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DropdownField("Válida por", validDays, validOptions, { "$it días" }, { validDays = it ?: 15 }, Modifier.weight(1f), noneLabel = null)
                DropdownField("Estado", status, QuoteStatus.all.filter { it != QuoteStatus.SOLD || sold }, { QuoteStatus.label(it) }, { status = it ?: QuoteStatus.DRAFT }, Modifier.weight(1f), noneLabel = null)
            }

            SectionTitle("Productos · ${lines.size}")
            if (lines.isEmpty()) {
                Text("Agrega los productos que vas a cotizar.", color = AppTheme.extra.muted)
            } else {
                AppCard(padding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                    lines.forEachIndexed { i, l ->
                        if (i > 0) Divider()
                        Row(Modifier.fillMaxWidth().clickable(enabled = !sold) { editLine = i }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProductImage(l.imagePath, l.name, Modifier.size(50.dp), textSize = 14)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(l.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                Text("${Money.format(l.price, s.currencySymbol)} · ${Money.format(l.amount, s.currencySymbol)}", fontSize = 12.sp, color = AppTheme.extra.muted)
                            }
                            if (!sold) QtyStepper(l.qty, { q -> if (q <= 0) lines.removeAt(i) else lines[i] = l.copy(qty = q) })
                        }
                    }
                }
            }
            if (!sold) {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Agregar producto")
                }
            }
            AppCard {
                TotalsBlock(totals, s.currencySymbol, s.taxName)
                if (!sold) TextButton(onClick = { discountDialog = true }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (discount > 0) "Cambiar descuento" else "+ Agregar descuento")
                }
            }
            TextInput(notes, { notes = it }, "Condiciones (si lo dejas vacío se usan las de Configuración)", singleLine = false, minLines = 3)
            Text("Creada el ${Time.longDate(createdAt)}", fontSize = 12.sp, color = AppTheme.extra.muted)
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 720.dp).align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            action {
                                val qid = persist(if (status == QuoteStatus.DRAFT) QuoteStatus.SENT else status)
                                val file = app.quotePdf(qid)
                                if (file != null) {
                                    if (app.platform.isDesktop) app.platform.openFile(file)
                                    else app.platform.shareFile(file, "application/pdf", "Cotización ${QuotePdf.quoteCode(number ?: 0)}")
                                }
                            }
                        },
                        enabled = lines.isNotEmpty() && !busy,
                        modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Rounded.PictureAsPdf, null); Spacer(Modifier.width(6.dp))
                        Text(if (app.platform.isDesktop) "Ver PDF A4" else "Enviar PDF A4")
                    }
                    if (app.platform.isDesktop) {
                        OutlinedButton(
                            onClick = { action { val f = app.quotePdf(persist()); if (f != null) app.platform.shareFile(f, "application/pdf", "Cotización") } },
                            enabled = lines.isNotEmpty() && !busy, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(14.dp),
                        ) { Text("Abrir carpeta") }
                    }
                }
                if (!sold) {
                    Button(
                        onClick = {
                            action {
                                val qid = persist(QuoteStatus.ACCEPTED)
                                app.clearCart()
                                app.cart.addAll(app.repo.quoteToCart(qid))
                                app.cartClient = client
                                app.cartDiscount = discount
                                app.cartQuoteId = qid
                                app.replace(Screen.Checkout)
                            }
                        },
                        enabled = lines.isNotEmpty() && !busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta),
                    ) { Icon(Icons.Rounded.ShoppingCart, null); Spacer(Modifier.width(6.dp)); Text("Convertir en venta", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    if (picking) {
        ProductPickerDialog(products, onPick = { p ->
            val i = lines.indexOfFirst { it.productId == p.id }
            if (i >= 0) lines[i] = lines[i].copy(qty = lines[i].qty + 1) else lines += CartLine.of(p)
            picking = false
        }, onDismiss = { picking = false })
    }
    if (pickClient) {
        ClientPickerDialog(clients, onPick = { client = it; clientName = it?.name ?: ""; pickClient = false },
            onNew = { pickClient = false; app.open(Screen.ClientForm()) }, onDismiss = { pickClient = false })
    }
    if (discountDialog) {
        DiscountDialog(totals.gross, discount, s.currencySymbol, onDismiss = { discountDialog = false }) { discount = it; discountDialog = false }
    }
    editLine?.let { i ->
        val l = lines.getOrNull(i)
        if (l == null) editLine = null else LineEditDialog(l, onDismiss = { editLine = null }) { qty, price ->
            editLine = null
            if (qty <= 0) lines.removeAt(i) else lines[i] = l.copy(qty = qty, price = price)
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Eliminar cotización", "Se borra esta cotización. Esta acción no se puede deshacer.", "Eliminar",
            onConfirm = { scope.launch { quoteId?.let { app.repo.deleteQuote(it) }; app.toast("Cotización eliminada"); app.back() } },
            onDismiss = { confirmDelete = false }, destructive = true)
    }
}
