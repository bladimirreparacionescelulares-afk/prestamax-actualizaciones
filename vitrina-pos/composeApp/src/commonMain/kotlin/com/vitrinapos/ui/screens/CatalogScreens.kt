package com.vitrinapos.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.LocalAppState
import com.vitrinapos.Screen
import com.vitrinapos.data.EntryLine
import com.vitrinapos.data.Money
import com.vitrinapos.data.MoveType
import com.vitrinapos.data.ProductDraft
import com.vitrinapos.data.Repository
import com.vitrinapos.data.Time
import com.vitrinapos.data.fmtQty
import com.vitrinapos.db.Category
import com.vitrinapos.db.Product
import com.vitrinapos.db.StockMove
import com.vitrinapos.platform.rememberBarcodeScanner
import com.vitrinapos.platform.rememberImagePicker
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.ConfirmDialog
import com.vitrinapos.ui.components.Divider
import com.vitrinapos.ui.components.DropdownField
import com.vitrinapos.ui.components.EmptyState
import com.vitrinapos.ui.components.MoneyInput
import com.vitrinapos.ui.components.Pill
import com.vitrinapos.ui.components.ProductImage
import com.vitrinapos.ui.components.ProductPickerDialog
import com.vitrinapos.ui.components.QtyInput
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SearchBox
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.components.SquareIconButton
import com.vitrinapos.ui.components.StockPill
import com.vitrinapos.ui.components.TextInput
import com.vitrinapos.ui.components.matches
import com.vitrinapos.ui.theme.AppTheme
import com.vitrinapos.ui.theme.CategoryPalette
import kotlinx.coroutines.launch

// ================= Catálogo =================

@Composable
fun CatalogScreen() {
    val app = LocalAppState.current
    val s = app.settings
    val products by app.repo.products.collectAsState()
    val categories by app.repo.categories.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(app.catalogPreset ?: "all") }
    var category by remember { mutableStateOf<Long?>(null) }
    var grid by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.catalogPreset = null }

    val low = products.count { it.trackStock == 1L && it.stock > 0 && it.stock <= it.minStock }
    val out = products.count { it.trackStock == 1L && it.stock <= 0 }
    val noPhoto = products.count { it.imagePath.isNullOrBlank() }
    val value = products.filter { it.trackStock == 1L && it.stock > 0 }.sumOf { (it.stock * it.cost).toLong() }
    val list = products.filter { p ->
        p.matches(query) && (category == null || p.categoryId == category) && when (filter) {
            "low" -> p.trackStock == 1L && p.stock <= p.minStock
            "out" -> p.trackStock == 1L && p.stock <= 0
            "nophoto" -> p.imagePath.isNullOrBlank()
            else -> true
        }
    }
    val catNames = categories.associate { it.id to it.name }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Catálogo", subtitle = "${products.size} productos") {
                SquareIconButton(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, "Cambiar vista") { grid = !grid }
                SquareIconButton(Icons.Rounded.QrCode2, "Etiquetas") { app.open(Screen.Labels) }
                SquareIconButton(Icons.Rounded.MoveToInbox, "Entrada de mercancía") { app.open(Screen.StockEntry()) }
            }
            SearchBox(query, { query = it }, "Buscar por nombre o código", Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { CategoryChip("Todos", filter == "all" && category == null) { filter = "all"; category = null } }
                item { CategoryChip("Stock bajo · $low", filter == "low") { filter = if (filter == "low") "all" else "low" } }
                item { CategoryChip("Agotados · $out", filter == "out") { filter = if (filter == "out") "all" else "out" } }
                item { CategoryChip("Sin foto · $noPhoto", filter == "nophoto") { filter = if (filter == "nophoto") "all" else "nophoto" } }
                items(categories, key = { "c${it.id}" }) { c -> CategoryChip(c.name, category == c.id) { category = if (category == c.id) null else c.id } }
                item {
                    Surface(onClick = { app.open(Screen.Categories) }, shape = RoundedCornerShape(12.dp), color = Color.Transparent,
                        border = BorderStroke(1.dp, AppTheme.extra.line)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Category, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Categorías", fontSize = 14.sp)
                        }
                    }
                }
            }
            when {
                products.isEmpty() -> EmptyState(Icons.Rounded.Inventory2, "Tu catálogo está vacío", "Agrega productos con foto, nombre y precio.")
                list.isEmpty() -> EmptyState(Icons.Rounded.Inventory2, "Sin resultados", "Prueba con otra búsqueda o quita los filtros.")
                grid -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(list, key = { it.id }) { p -> ProductTile(p, 0.0, onAdd = { app.open(Screen.ProductForm(p.id)) }, onEdit = { app.open(Screen.ProductForm(p.id)) }) }
                }
                else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { p -> ProductRow(p, catNames[p.categoryId]) { app.open(Screen.ProductForm(p.id)) } }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { app.open(Screen.ProductForm()) },
            containerColor = AppTheme.extra.cta,
            contentColor = AppTheme.extra.onCta,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text("Producto", fontWeight = FontWeight.Bold) },
        )
    }
}

@Composable
private fun ProductRow(p: Product, category: String?, onClick: () -> Unit) {
    val app = LocalAppState.current
    AppCard(onClick = onClick, padding = PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductImage(p.imagePath, p.name, Modifier.size(64.dp), textSize = 18)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(p.code.ifBlank { null }, category).joinToString(" · ").ifBlank { "Sin código" },
                    fontSize = 12.sp, color = AppTheme.extra.muted, maxLines = 1)
                Text(Money.format(p.price, app.settings.currencySymbol), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            }
            StockPill(p)
        }
    }
}

// ================= Formulario de producto =================

private val units = listOf("und", "kg", "lb", "m", "caja", "par", "paquete", "servicio")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProductFormScreen(id: Long?, presetCode: String?) {
    val app = LocalAppState.current
    val s = app.settings
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    val categories by app.repo.categories.collectAsState()
    val suppliers by app.repo.suppliers.collectAsState()
    val products by app.repo.products.collectAsState()

    var loaded by remember { mutableStateOf(id == null) }
    var original by remember { mutableStateOf<Product?>(null) }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(presetCode ?: "") }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var supplierId by remember { mutableStateOf<Long?>(null) }
    var cost by remember { mutableStateOf<Long?>(null) }
    var price by remember { mutableStateOf<Long?>(null) }
    var stock by remember { mutableStateOf<Double?>(null) }
    var minStock by remember { mutableStateOf<Double?>(null) }
    var unit by remember { mutableStateOf("und") }
    var taxable by remember { mutableStateOf(true) }
    var trackStock by remember { mutableStateOf(true) }
    var imagePath by remember { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf("") }
    var showErrors by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var adjust by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf(false) }
    var moves by remember { mutableStateOf<List<StockMove>>(emptyList()) }
    val createdImages = remember { mutableStateListOf<String>() }

    LaunchedEffect(id) {
        if (id != null) {
            app.repo.productById(id)?.let { p ->
                original = p
                name = p.name; code = p.code; categoryId = p.categoryId; supplierId = p.supplierId
                cost = p.cost; price = p.price; stock = p.stock; minStock = p.minStock; unit = p.unit
                taxable = p.taxable == 1L; trackStock = p.trackStock == 1L; imagePath = p.imagePath; description = p.description
            }
            moves = app.repo.movesByProduct(id)
            loaded = true
        }
    }
    // Si se sale sin guardar, borra las fotos nuevas que no se usaron.
    DisposableEffect(Unit) {
        onDispose {
            val keep = if (saved) imagePath else original?.imagePath
            createdImages.filter { it != keep }.forEach { app.repo.deleteImage(it) }
        }
    }

    val picker = rememberImagePicker { temp ->
        if (temp != null) scope.launch {
            val stored = app.repo.importImage(temp)
            temp.delete()
            if (stored != null) { createdImages += stored; imagePath = stored } else app.toast("No se pudo leer la imagen")
        }
    }
    val scanner = rememberBarcodeScanner { code = it }

    val duplicate = code.isNotBlank() && products.any { it.code.equals(code.trim(), true) && it.id != id }
    val nameError = if (showErrors && name.isBlank()) "Escribe el nombre" else null
    val priceError = if (showErrors && price == null) "Escribe el precio" else null
    val codeError = if (duplicate) "Ya existe otro producto con este código" else null

    fun save(another: Boolean) {
        showErrors = true
        if (name.isBlank() || price == null || duplicate) return
        scope.launch {
            val newId = app.repo.saveProduct(
                ProductDraft(
                    id = id, name = name, code = code, categoryId = categoryId, supplierId = supplierId,
                    cost = cost ?: 0, price = price ?: 0, initialStock = if (id == null) stock ?: 0.0 else 0.0,
                    minStock = minStock ?: 0.0, unit = unit, taxable = taxable, trackStock = trackStock,
                    imagePath = imagePath, description = description,
                ),
            )
            if (original?.imagePath != null && original?.imagePath != imagePath) app.repo.deleteImage(original?.imagePath)
            saved = true
            app.toast(if (id == null) "Producto guardado" else "Cambios guardados")
            if (another) app.replace(Screen.ProductForm(key = newId)) else app.back()
            if (id == null && !another && app.settingsNow.labelAskOnSave) app.labelPrompt = newId
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(if (id == null) "Nuevo producto" else "Editar producto", onBack = { app.back() }) {
            if (id != null) IconButton(onClick = { app.openLabelsFor(listOf(com.vitrinapos.AppState.LabelItem(id, original?.suggestedCopies() ?: 1))) }) {
                Icon(Icons.Rounded.QrCode2, "Etiquetas", tint = MaterialTheme.colorScheme.primary)
            }
            if (id != null && app.can(com.vitrinapos.data.Permission.DELETE_RECORDS)) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Eliminar", tint = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { save(false) }) { Text("Guardar", fontWeight = FontWeight.Bold) }
        }
        if (!loaded) return@Column
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)
                .widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Foto
            if (imagePath != null) {
                Box(Modifier.fillMaxWidth().height(220.dp)) {
                    ProductImage(imagePath, name.ifBlank { "Producto" }, Modifier.fillMaxSize(), RoundedCornerShape(20.dp), maxSide = 1080)
                    Row(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (picker.canTakePhoto) PhotoAction(Icons.Rounded.CameraAlt, "Repetir") { picker.takePhoto() }
                        PhotoAction(Icons.Rounded.PhotoLibrary, "Galería") { picker.pickFromGallery() }
                        PhotoAction(Icons.Rounded.Delete, "Quitar") { imagePath = null }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                        .clickable { if (picker.canTakePhoto) picker.takePhoto() else picker.pickFromGallery() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(if (picker.canTakePhoto) Icons.Rounded.CameraAlt else Icons.Rounded.PhotoLibrary, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(if (picker.canTakePhoto) "Tomar foto del producto" else "Elegir foto del producto", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (picker.canTakePhoto) {
                        TextButton(onClick = { picker.pickFromGallery() }) { Text("o elegir de la galería") }
                    }
                }
            }

            TextInput(name, { name = it }, "Nombre del producto *", error = nameError)
            TextInput(
                code, { code = it }, "Código de barras o referencia", error = codeError, placeholder = "Vacío: se asigna uno para su etiqueta",
                trailing = {
                    Row {
                        if (scanner != null) IconButton(onClick = scanner) { Icon(Icons.Rounded.QrCodeScanner, "Escanear", tint = MaterialTheme.colorScheme.primary) }
                        IconButton(onClick = { code = com.vitrinapos.labels.Codes.newInternalEan { c -> products.any { it.code == c } } }) { Icon(Icons.Rounded.AutoAwesome, "Generar código", tint = MaterialTheme.colorScheme.primary) }
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MoneyInput(cost, { cost = it }, "Costo", Modifier.weight(1f))
                MoneyInput(price, { price = it }, "Precio de venta *", Modifier.weight(1f), error = priceError)
            }
            val c = cost; val p = price
            if (c != null && p != null && p > 0) {
                val profit = p - c
                val pct = if (p > 0) profit * 100.0 / p else 0.0
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (profit >= 0) AppTheme.extra.okSoft else AppTheme.extra.dangerSoft).padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text("Ganancia por unidad", Modifier.weight(1f), color = if (profit >= 0) AppTheme.extra.ok else AppTheme.extra.danger, fontWeight = FontWeight.SemiBold)
                    Text("${Money.format(profit, s.currencySymbol)} · ${pct.toInt()} %", color = if (profit >= 0) AppTheme.extra.ok else AppTheme.extra.danger, fontWeight = FontWeight.Bold)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DropdownField(
                    "Categoría", categories.firstOrNull { it.id == categoryId }, categories, { it.name }, { categoryId = it?.id },
                    Modifier.weight(1f), noneLabel = "Sin categoría",
                    footer = { close -> TextButton(onClick = { close(); newCategory = true }) { Text("+ Nueva categoría") } },
                )
                DropdownField("Proveedor", suppliers.firstOrNull { it.id == supplierId }, suppliers, { it.name }, { supplierId = it?.id }, Modifier.weight(1f))
            }

            SectionTitle("Inventario")
            AppCard(onClick = { trackStock = !trackStock }, padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Controlar existencias", fontWeight = FontWeight.SemiBold)
                        Text("Desactívalo para servicios o productos sin conteo", fontSize = 12.sp, color = AppTheme.extra.muted)
                    }
                    Switch(trackStock, { trackStock = it })
                }
            }
            if (trackStock) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (id == null) {
                        QtyInput(stock, { stock = it }, "Stock inicial", Modifier.weight(1f))
                    } else {
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                                .clickable(enabled = app.can(com.vitrinapos.data.Permission.ADJUST_STOCK)) { adjust = true }.padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text("Stock actual", fontSize = 12.sp, color = AppTheme.extra.muted)
                            Text("${(original?.stock ?: 0.0).fmtQty()} $unit · Ajustar", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    QtyInput(minStock, { minStock = it }, "Avisar cuando queden", Modifier.weight(1f))
                }
            }
            DropdownField("Unidad", unit, units, { it }, { unit = it ?: "und" }, noneLabel = null)
            AppCard(onClick = { taxable = !taxable }, padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Cobra ${s.taxName}", fontWeight = FontWeight.SemiBold)
                        Text(if (s.pricesIncludeTax) "El precio ya incluye ${s.taxRate.fmtQty()} %" else "Se suma ${s.taxRate.fmtQty()} % al cobrar", fontSize = 12.sp, color = AppTheme.extra.muted)
                    }
                    Switch(taxable, { taxable = it })
                }
            }
            TextInput(description, { description = it }, "Descripción (opcional)", singleLine = false, minLines = 2)

            if (id != null && moves.isNotEmpty()) {
                SectionTitle("Últimos movimientos")
                AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    moves.take(8).forEachIndexed { i, m ->
                        if (i > 0) Divider()
                        MoveRow(m.type, m.qty, m.note, m.createdAt, null)
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 720.dp).align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (id == null) {
                    OutlinedButton(onClick = { save(true) }, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Guardar y otro") }
                }
                Button(onClick = { save(false) }, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(6.dp)); Text("Guardar producto", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (confirmDelete && id != null) {
        ConfirmDialog("Eliminar producto", "\"$name\" dejará de aparecer en el catálogo. Las ventas anteriores se conservan.", "Eliminar",
            onConfirm = { scope.launch { app.repo.deleteProduct(id); saved = true; app.toast("Producto eliminado"); app.back() } },
            onDismiss = { confirmDelete = false }, destructive = true)
    }
    if (adjust && id != null) {
        AdjustStockDialog(original?.stock ?: 0.0, onDismiss = { adjust = false }) { qty, note ->
            adjust = false
            scope.launch {
                app.repo.adjustStock(id, qty, note)
                original = app.repo.productById(id)
                moves = app.repo.movesByProduct(id)
                app.toast("Stock actualizado")
            }
        }
    }
    if (newCategory) {
        CategoryDialog(null, onDismiss = { newCategory = false }) { n, color ->
            newCategory = false
            scope.launch { categoryId = app.repo.saveCategory(null, n, color) }
        }
    }
}

@Composable
private fun PhotoAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = Color(0xCC13201C), contentColor = Color.White) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}


@Composable
private fun AdjustStockDialog(current: Double, onDismiss: () -> Unit, onSave: (Double, String) -> Unit) {
    var qty by remember { mutableStateOf<Double?>(current) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustar stock") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Stock actual: ${current.fmtQty()}. Escribe la cantidad real que tienes.", color = AppTheme.extra.muted)
                QtyInput(qty, { qty = it }, "Cantidad real")
                TextInput(note, { note = it }, "Motivo (conteo, daño, regalo…)")
            }
        },
        confirmButton = { TextButton(onClick = { qty?.let { onSave(it, note) } }, enabled = qty != null) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun MoveRow(type: String, qty: Double, note: String, at: Long, productName: String?) {
    val positive = qty > 0
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(productName ?: MoveType.label(type), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(if (productName != null) MoveType.label(type) else null, "${Time.relativeDay(at)} ${Time.time(at)}", note.ifBlank { null }).joinToString(" · ")
            Text(sub, fontSize = 12.sp, color = AppTheme.extra.muted, maxLines = 2)
        }
        Text((if (positive) "+" else "") + qty.fmtQty(), fontWeight = FontWeight.Bold, color = if (positive) AppTheme.extra.ok else AppTheme.extra.danger)
    }
}

// ================= Categorías =================

@Composable
fun CategoriesScreen() {
    val app = LocalAppState.current
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    val categories by app.repo.categories.collectAsState()
    val products by app.repo.products.collectAsState()
    var editing by remember { mutableStateOf<Category?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Categorías", subtitle = "${categories.size} categorías", onBack = { app.back() }) {
            SquareIconButton(Icons.Rounded.Add, "Nueva categoría", primary = true) { creating = true }
        }
        if (categories.isEmpty()) {
            EmptyState(Icons.Rounded.Category, "Sin categorías", "Agrupa tus productos para encontrarlos más rápido al vender.") {
                Button(onClick = { creating = true }) { Text("Nueva categoría") }
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories, key = { it.id }) { c ->
                AppCard(onClick = { editing = c }, padding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(c.color)))
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${products.count { it.categoryId == c.id }} productos", color = AppTheme.extra.muted, fontSize = 13.sp)
                        IconButton(onClick = { deleting = c }) { Icon(Icons.Rounded.Delete, "Eliminar", tint = AppTheme.extra.muted) }
                    }
                }
            }
        }
    }
    if (creating || editing != null) {
        CategoryDialog(editing, onDismiss = { creating = false; editing = null }) { n, color ->
            val id = editing?.id
            creating = false; editing = null
            scope.launch { app.repo.saveCategory(id, n, color) }
        }
    }
    deleting?.let { c ->
        ConfirmDialog("Eliminar categoría", "Los productos de \"${c.name}\" quedarán sin categoría.", "Eliminar",
            onConfirm = { scope.launch { app.repo.deleteCategory(c.id) } }, onDismiss = { deleting = null }, destructive = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryDialog(existing: Category?, onDismiss: () -> Unit, onSave: (String, Long) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var color by remember { mutableStateOf(existing?.color ?: CategoryPalette.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nueva categoría" else "Editar categoría") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextInput(name, { name = it }, "Nombre")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CategoryPalette.forEach { c ->
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Color(c)).clickable { color = c },
                            contentAlignment = Alignment.Center,
                        ) { if (c == color) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, color) }, enabled = name.isNotBlank()) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ================= Entrada de mercancía =================

private data class EntryDraft(val product: Product, val qty: Double?, val cost: Long?)

@Composable
fun StockEntryScreen(supplierId: Long?) {
    val app = LocalAppState.current
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    val suppliers by app.repo.suppliers.collectAsState()
    val products by app.repo.products.collectAsState()
    var supplier by remember { mutableStateOf(suppliers.firstOrNull { it.id == supplierId }) }
    val lines = remember { mutableStateListOf<EntryDraft>() }
    var note by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    fun add(p: Product) {
        if (lines.none { it.product.id == p.id }) lines += EntryDraft(p, 1.0, p.cost)
    }
    val scanner = rememberBarcodeScanner { code ->
        products.firstOrNull { it.code.equals(code, true) }?.let { add(it) } ?: app.toast("No hay producto con el código $code")
    }
    val pickable = products.filter { it.trackStock == 1L }.let { list -> supplier?.let { s -> list.sortedByDescending { it.supplierId == s.id } } ?: list }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Entrada de mercancía", subtitle = "Suma existencias y actualiza el costo", onBack = { app.back() })
        LazyColumn(
            Modifier.weight(1f).widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { DropdownField("Proveedor", supplier, suppliers, { it.name }, { supplier = it }, noneLabel = "Sin proveedor") }
            itemsIndexed(lines, key = { _, l -> l.product.id }) { i, l ->
                AppCard(padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProductImage(l.product.imagePath, l.product.name, Modifier.size(48.dp), textSize = 14)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(l.product.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Stock actual: ${l.product.stock.fmtQty()}", fontSize = 12.sp, color = AppTheme.extra.muted)
                        }
                        IconButton(onClick = { lines.removeAt(i) }) { Icon(Icons.Rounded.Delete, "Quitar", tint = AppTheme.extra.muted) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QtyInput(l.qty, { lines[i] = l.copy(qty = it) }, "Cantidad que entra", Modifier.weight(1f))
                        MoneyInput(l.cost, { lines[i] = l.copy(cost = it) }, "Costo unitario", Modifier.weight(1f))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Agregar producto")
                    }
                    if (scanner != null) OutlinedButton(onClick = scanner, modifier = Modifier.height(50.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.QrCodeScanner, "Escanear")
                    }
                }
            }
            item { TextInput(note, { note = it }, "Nota (factura, observaciones)") }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Button(
                onClick = {
                    scope.launch {
                        app.repo.registerEntry(
                            lines.mapNotNull { l -> l.qty?.takeIf { it != 0.0 }?.let { EntryLine(l.product.id, it, l.cost?.takeIf { c -> c != l.product.cost }) } },
                            supplier?.name, note,
                        )
                        app.toast("Entrada registrada")
                        app.back()
                    }
                },
                enabled = lines.any { (it.qty ?: 0.0) != 0.0 },
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Registrar entrada", fontWeight = FontWeight.Bold) }
        }
    }
    if (picking) ProductPickerDialog(pickable, onPick = { add(it); picking = false }, onDismiss = { picking = false })
}

// ================= Movimientos =================

@Composable
fun StockMovesScreen() {
    val app = LocalAppState.current
    val products by app.repo.products.collectAsState()
    val moves by produceState(emptyList<com.vitrinapos.db.SelectRecent>(), products) { value = app.repo.recentMoves(200) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Movimientos de inventario", subtitle = "Ventas, entradas y ajustes", onBack = { app.back() }) {
            SquareIconButton(Icons.Rounded.MoveToInbox, "Entrada de mercancía", primary = true) { app.open(Screen.StockEntry()) }
        }
        if (moves.isEmpty()) EmptyState(Icons.Rounded.History, "Sin movimientos", "Aquí verás cada venta, entrada y ajuste de inventario.")
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            items(moves, key = { it.id }) { m ->
                MoveRow(m.type, m.qty, m.note, m.createdAt, m.productName)
                Divider()
            }
        }
    }
}

@Composable
fun SmallPill(text: String) = Pill(text, AppTheme.extra.muted, MaterialTheme.colorScheme.surfaceContainer)
