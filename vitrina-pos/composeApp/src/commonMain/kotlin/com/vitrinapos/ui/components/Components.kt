package com.vitrinapos.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vitrinapos.LocalAppState
import com.vitrinapos.data.Money
import com.vitrinapos.data.fmtQty
import com.vitrinapos.data.parseQty
import com.vitrinapos.db.Client
import com.vitrinapos.db.Product
import com.vitrinapos.ui.theme.AppTheme
import com.vitrinapos.ui.theme.CategoryPalette
import kotlinx.coroutines.withContext
import java.io.File

// ---------- Imágenes ----------

/** Caché pequeña de imágenes decodificadas para que el catálogo se desplace suave. */
object ImageCache {
    private val map = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 120
    }

    fun get(key: String): ImageBitmap? = synchronized(map) { map[key] }
    fun put(key: String, bmp: ImageBitmap) = synchronized(map) { map[key] = bmp }
    fun clear() = synchronized(map) { map.clear() }
}

@Composable
fun rememberImage(file: File?, maxSide: Int = 480): ImageBitmap? {
    val app = LocalAppState.current
    val key = file?.let { "${it.path}:${it.lastModified()}:$maxSide" }
    return produceState(key?.let { ImageCache.get(it) }, key) {
        if (file == null || key == null) { value = null; return@produceState }
        ImageCache.get(key)?.let { value = it; return@produceState }
        value = withContext(app.platform.io) {
            app.platform.decodeImage(file, maxSide)?.also { ImageCache.put(key, it) }
        }
    }.value
}

fun colorFor(text: String): Color = Color(CategoryPalette[(text.hashCode() and 0x7fffffff) % CategoryPalette.size])

fun initials(text: String): String =
    text.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }

/** Foto del producto, o un fondo de color con las iniciales si no tiene foto. */
@Composable
fun ProductImage(
    imageName: String?,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    maxSide: Int = 480,
    textSize: Int = 20,
) {
    val app = LocalAppState.current
    val bmp = rememberImage(app.repo.imageFile(imageName), maxSide)
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val c = colorFor(title)
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.copy(alpha = 0.18f), c.copy(alpha = 0.38f)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(initials(title), color = c, fontWeight = FontWeight.Bold, fontSize = textSize.sp)
            }
        }
    }
}

// ---------- Estructura de pantalla ----------

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 6.dp else 18.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver") }
            Spacer(Modifier.width(2.dp))
        }
        Column(Modifier.weight(1f)) {
            if (subtitle != null && onBack == null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = AppTheme.extra.muted)
            }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null && onBack != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted, maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun SquareIconButton(icon: ImageVector, description: String, primary: Boolean = false, onClick: () -> Unit) {
    val ex = AppTheme.extra
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = if (primary) null else BorderStroke(1.dp, ex.line),
        modifier = Modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(21.dp)) }
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(14.dp),
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val border = BorderStroke(1.dp, AppTheme.extra.line)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(padding), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(padding), content = content)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
fun Pill(text: String, color: Color, background: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(background).padding(horizontal = 8.dp, vertical = 3.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

@Composable
fun StockPill(p: Product, modifier: Modifier = Modifier) {
    if (p.trackStock != 1L) return
    val ex = AppTheme.extra
    val unit = if (p.unit == "und") "und" else p.unit
    when {
        p.stock <= 0 -> Pill("Agotado", ex.danger, ex.dangerSoft, modifier)
        p.stock <= p.minStock -> Pill("${p.stock.fmtQty()} $unit", ex.warn, ex.warnSoft, modifier)
        else -> Pill("${p.stock.fmtQty()} $unit", ex.ok, ex.okSoft, modifier)
    }
}

@Composable
fun Avatar(name: String, size: Dp = 40.dp, square: Boolean = false) {
    val c = colorFor(name)
    Box(
        Modifier.size(size).clip(if (square) RoundedCornerShape(12.dp) else CircleShape).background(c.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), color = c, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp)) }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = AppTheme.extra.muted, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(4.dp)); action() }
    }
}

@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onSubmit: (() -> Unit)? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = true,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = if (value.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Borrar") } }) else null,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit?.invoke() }, onDone = { onSubmit?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = AppTheme.extra.line,
        ),
    )
}

// ---------- Campos de formulario ----------

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedBorderColor = AppTheme.extra.line,
)

@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    error: String? = null,
    prefix: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    placeholder: String? = null,
    visual: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        visualTransformation = visual,
        label = { Text(label) },
        modifier = modifier,
        singleLine = singleLine,
        minLines = minLines,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        prefix = prefix?.let { { Text(it) } },
        trailingIcon = trailing,
        placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(14.dp),
        colors = fieldColors(),
    )
}

/** Campo de dinero: guarda el texto mientras se escribe y entrega centavos. */
@Composable
fun MoneyInput(
    cents: Long?,
    onChange: (Long?) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    error: String? = null,
) {
    val app = LocalAppState.current
    var text by remember { mutableStateOf(cents?.let { Money.toInput(it) } ?: "") }
    LaunchedEffect(cents) {
        val parsed = Money.parse(text)
        if (parsed != cents) text = cents?.let { Money.toInput(it) } ?: ""
    }
    TextInput(
        value = text,
        onChange = { t ->
            val clean = t.filter { it.isDigit() || it == '.' || it == ',' }
            text = clean
            onChange(Money.parse(clean))
        },
        label = label,
        modifier = modifier,
        keyboard = KeyboardType.Decimal,
        error = error,
        prefix = app.settings.currencySymbol,
    )
}

@Composable
fun QtyInput(value: Double?, onChange: (Double?) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    var text by remember { mutableStateOf(value?.fmtQty() ?: "") }
    LaunchedEffect(value) {
        if (parseQty(text) != value) text = value?.fmtQty() ?: ""
    }
    TextInput(
        value = text,
        onChange = { t ->
            val clean = t.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
            text = clean
            onChange(parseQty(clean))
        },
        label = label,
        modifier = modifier,
        keyboard = KeyboardType.Decimal,
    )
}

/** Selector desplegable con apariencia de campo de texto. */
@Composable
fun <T> DropdownField(
    label: String,
    selected: T?,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    noneLabel: String? = "Ninguno",
    footer: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = selected?.let(optionLabel) ?: (noneLabel ?: ""),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Rounded.ExpandMore, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
        )
        Box(Modifier.matchParentSize().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (noneLabel != null) DropdownMenuItem(text = { Text(noneLabel) }, onClick = { open = false; onSelect(null) })
            options.forEach { o -> DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { open = false; onSelect(o) }) }
            footer?.invoke { open = false }
        }
    }
}

@Composable
fun QtyStepper(qty: Double, onChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onChange(qty - 1) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Remove, "Menos", Modifier.size(18.dp)) }
        Text(qty.fmtQty(), fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 26.dp), textAlign = TextAlign.Center)
        IconButton(onClick = { onChange(qty + 1) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Add, "Más", Modifier.size(18.dp)) }
    }
}

@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier, strong: Boolean = false, valueColor: Color = Color.Unspecified) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (strong) MaterialTheme.colorScheme.onSurface else AppTheme.extra.muted, modifier = Modifier.weight(1f),
            style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
        Text(value, style = if (strong) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = valueColor)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Hoja a pantalla completa con encabezado, para elegir cliente o producto. */
@Composable
fun PickerDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp).fillMaxHeight(0.88f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cerrar") }
                }
                content()
            }
        }
    }
}

@Composable
fun ClientPickerDialog(clients: List<Client>, onPick: (Client?) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    PickerDialog("Elegir cliente", onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchBox(q, { q = it }, "Buscar por nombre, teléfono o documento", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppCard(Modifier.weight(1f), onClick = { onPick(null) }, padding = PaddingValues(12.dp)) {
                    Text("Consumidor final", fontWeight = FontWeight.SemiBold)
                }
                AppCard(Modifier.weight(1f), onClick = onNew, padding = PaddingValues(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("+ Nuevo cliente", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        val list = clients.filter { c ->
            q.isBlank() || listOf(c.name, c.phone, c.docId).any { it.contains(q.trim(), ignoreCase = true) }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(c) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c.name)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold)
                        val sub = listOf(c.phone, c.docId).filter { it.isNotBlank() }.joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted)
                    }
                }
            }
        }
    }
}

@Composable
fun ProductPickerDialog(products: List<Product>, onPick: (Product) -> Unit, onDismiss: () -> Unit, title: String = "Agregar producto") {
    var q by remember { mutableStateOf("") }
    val app = LocalAppState.current
    PickerDialog(title, onDismiss) {
        SearchBox(q, { q = it }, "Buscar por nombre o código", Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        val list = products.filter { it.matches(q) }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(list, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(p) }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProductImage(p.imagePath, p.name, Modifier.size(52.dp), textSize = 15)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(p.code.ifBlank { "Sin código" }, style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Money.format(p.price, app.settings.currencySymbol), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        StockPill(p)
                    }
                }
            }
        }
    }
}

fun Product.matches(q: String): Boolean {
    val t = q.trim()
    if (t.isEmpty()) return true
    return name.contains(t, ignoreCase = true) || code.contains(t, ignoreCase = true) || description.contains(t, ignoreCase = true)
}

@Composable
fun Divider() = HorizontalDivider(color = AppTheme.extra.line)

@Composable
fun BoxScope.CountBadge(count: String, modifier: Modifier = Modifier) {
    Box(
        modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(AppTheme.extra.cta),
        contentAlignment = Alignment.Center,
    ) { Text(count, color = AppTheme.extra.onCta, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold)) }
}

@Composable
fun Modifier.outlined(shape: Shape = RoundedCornerShape(16.dp)) = this.border(1.dp, AppTheme.extra.line, shape)

/**
 * Corrutinas de pantalla: si una acción falla (por ejemplo, sin conexión con el equipo principal)
 * se muestra el motivo en vez de cerrar la app.
 */
@Composable
fun rememberSafeScope(): kotlinx.coroutines.CoroutineScope {
    val app = LocalAppState.current
    val handler = remember(app) {
        kotlinx.coroutines.CoroutineExceptionHandler { _, e -> app.toast(e.message ?: "Ocurrió un error") }
    }
    return androidx.compose.runtime.rememberCoroutineScope { handler }
}
