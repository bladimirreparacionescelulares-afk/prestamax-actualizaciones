package com.vitrinapos

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.data.AppGraph
import com.vitrinapos.platform.PlatformBackHandler
import com.vitrinapos.platform.rememberPrinterPermission
import com.vitrinapos.ui.components.Avatar
import com.vitrinapos.ui.screens.*
import com.vitrinapos.ui.theme.AppTheme
import com.vitrinapos.ui.theme.VitrinaTheme

private data class NavItem(val screen: Screen, val label: String, val icon: ImageVector)

private val bottomItems = listOf(
    NavItem(Screen.Home, "Inicio", Icons.Rounded.Home),
    NavItem(Screen.Catalog, "Catálogo", Icons.Rounded.GridView),
    NavItem(Screen.Pos, "Vender", Icons.Rounded.ShoppingCart),
    NavItem(Screen.Taller, "Taller", Icons.Rounded.Build),
    NavItem(Screen.More, "Más", Icons.Rounded.MoreHoriz),
)

private val sideItems = listOf(
    NavItem(Screen.Home, "Inicio", Icons.Rounded.Home),
    NavItem(Screen.Pos, "Vender", Icons.Rounded.ShoppingCart),
    NavItem(Screen.Catalog, "Catálogo", Icons.Rounded.GridView),
    NavItem(Screen.Taller, "Taller", Icons.Rounded.Build),
    NavItem(Screen.Clients, "Clientes", Icons.Rounded.People),
    NavItem(Screen.Quotes, "Cotizaciones", Icons.Rounded.Description),
    NavItem(Screen.Suppliers, "Proveedores", Icons.Rounded.LocalShipping),
    NavItem(Screen.SalesHistory, "Ventas", Icons.AutoMirrored.Rounded.ReceiptLong),
    NavItem(Screen.Reports, "Reportes", Icons.Rounded.BarChart),
    NavItem(Screen.Labels, "Etiquetas", Icons.Rounded.QrCode2),
    NavItem(Screen.Cash, "Caja", Icons.Rounded.PointOfSale),
    NavItem(Screen.Network, "Trabajo en red", Icons.Rounded.Lan),
    NavItem(Screen.Settings, "Configuración", Icons.Rounded.Settings),
)

/** Pantallas que ocupan todo el alto (sin barra de pestañas). */
private fun Screen.isTab() = this is Screen.Home || this is Screen.Catalog || this is Screen.Pos || this is Screen.Taller || this is Screen.More

@Composable
fun App(graph: AppGraph, onReady: (AppState) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val app = remember(graph) { AppState(graph, scope).also(onReady) }
    app.requestPrinterPermission = rememberPrinterPermission()
    VitrinaTheme {
        CompositionLocalProvider(LocalAppState provides app) {
            val settings = app.settings
            val users by app.repo.users.collectAsState()
            val current = app.user
            var unlocked by remember { mutableStateOf(false) }
            var connecting by remember { mutableStateOf(false) }
            val activeUsers = users.filter { it.active == 1L }
            // Si cambian los datos del usuario (rol, activo) se usan los nuevos; si lo desactivan, sale.
            LaunchedEffect(users) {
                val u = app.repo.currentUser.value ?: return@LaunchedEffect
                app.repo.currentUser.value = activeUsers.firstOrNull { it.id == u.id }
            }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when {
                    connecting -> NetworkScreen(onClose = { connecting = false })
                    !settings.onboarded -> WelcomeScreen(onConnect = { connecting = true })
                    activeUsers.isNotEmpty() && current == null -> LoginScreen(activeUsers)
                    activeUsers.isEmpty() && settings.pin.isNotBlank() && !unlocked -> LockScreen(settings.pin) { unlocked = true }
                    else -> MainShell(app)
                }
            }
        }
    }
}

@Composable
private fun MainShell(app: AppState) {
    PlatformBackHandler(enabled = app.stack.isNotEmpty() || app.tab != Screen.Home) { app.back() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(app.snackbar) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!wide && app.current.isTab()) BottomBar(app)
            },
        ) { pad ->
            // En el celular, la barra de pestañas ya deja espacio para la barra del sistema.
            val sides = if (!wide && app.current.isTab()) WindowInsetsSides.Top + WindowInsetsSides.Horizontal
            else WindowInsetsSides.Top + WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
            Row(Modifier.fillMaxSize().padding(pad).windowInsetsPadding(WindowInsets.safeDrawing.only(sides))) {
                app.labelPrompt?.let { LabelPromptDialog(it) }
                app.unitPicker?.let { com.vitrinapos.ui.screens.UnitPickerDialog(it) }
                app.variantPicker?.let { com.vitrinapos.ui.screens.VariantPickerDialog(it) }
                if (wide) SideBar(app)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedContent(
                        targetState = app.current,
                        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                        label = "screen",
                    ) { screen -> ScreenContent(screen, wide) }
                }
            }
        }
    }
}

@Composable
fun ScreenContent(screen: Screen, wide: Boolean) {
    val app = LocalAppState.current
    if (!app.allows(screen)) { NoAccessScreen(); return }
    when (screen) {
        Screen.Home -> HomeScreen()
        Screen.Catalog -> CatalogScreen()
        Screen.Pos -> PosScreen(wide)
        Screen.Clients -> ClientsScreen()
        Screen.More -> MoreScreen()
        Screen.Taller -> TallerScreen()
        is Screen.RepairReceive -> RepairReceiveScreen(screen.id, screen.warrantyOf)
        is Screen.RepairDeliver -> RepairDeliverScreen(screen.id)
        Screen.RepairStatuses -> RepairStatusesScreen()
        Screen.Fiscal -> FiscalScreen()
        Screen.FiscalEmitter -> FiscalEmitterScreen()
        Screen.FiscalCert -> FiscalCertScreen()
        Screen.FiscalDgii -> FiscalDgiiScreen()
        Screen.FiscalSequences -> FiscalSequencesScreen()
        Screen.FiscalDocs -> FiscalDocsScreen()
        is Screen.UserDetail -> UserDetailScreen(screen.id)
        is Screen.RepairDetail -> RepairDetailScreen(screen.id)
        is Screen.ProductForm -> ProductFormScreen(screen.id, screen.presetCode, screen.copyOf)
        Screen.Checkout -> CheckoutScreen()
        is Screen.SaleDetail -> SaleDetailScreen(screen.id)
        Screen.SalesHistory -> SalesHistoryScreen()
        is Screen.ClientForm -> ClientFormScreen(screen.id)
        is Screen.ClientDetail -> ClientDetailScreen(screen.id)
        Screen.Suppliers -> SuppliersScreen()
        is Screen.SupplierForm -> SupplierFormScreen(screen.id)
        Screen.Quotes -> QuotesScreen()
        is Screen.QuoteEditor -> QuoteEditorScreen(screen.id, screen.clientId)
        Screen.Settings -> SettingsScreen()
        Screen.Reports -> ReportsScreen()
        Screen.Categories -> CategoriesScreen()
        is Screen.StockEntry -> com.vitrinapos.ui.screens.PurchaseFormScreen(screen.supplierId)
        Screen.Inventory -> com.vitrinapos.ui.screens.InventoryScreen()
        Screen.Purchases -> com.vitrinapos.ui.screens.PurchasesScreen()
        is Screen.PurchaseDetail -> com.vitrinapos.ui.screens.PurchaseDetailScreen(screen.id)
        Screen.StockCount -> com.vitrinapos.ui.screens.StockCountScreen()
        is Screen.StockCountDetail -> com.vitrinapos.ui.screens.StockCountDetailScreen(screen.id)
        is Screen.Kardex -> com.vitrinapos.ui.screens.KardexScreen(screen.productId)
        Screen.ImeiSearch -> com.vitrinapos.ui.screens.ImeiSearchScreen()
        is Screen.ProductUnits -> com.vitrinapos.ui.screens.ProductUnitsScreen(screen.productId)
        is Screen.SaleReturn -> com.vitrinapos.ui.screens.SaleReturnScreen(screen.saleId)
        Screen.Cash -> CashScreen()
        Screen.StockMoves -> StockMovesScreen()
        Screen.Network -> NetworkScreen()
        Screen.Users -> UsersScreen()
        Screen.Labels -> LabelsScreen()
        Screen.LabelDesigner -> LabelDesignerScreen()
        Screen.LabelPrinter -> LabelPrinterScreen()
        is Screen.Scanner -> ScannerScreen(screen.mode)
    }
}

@Composable
private fun BottomBar(app: AppState) {
    val ex = AppTheme.extra
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(70.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bottomItems.filter { app.allows(it.screen) }.forEach { item ->
                val selected = app.tab == item.screen && app.stack.isEmpty()
                val isSell = item.screen == Screen.Pos
                Column(
                    Modifier.weight(1f).fillMaxHeight().then(if (isSell) Modifier else Modifier.clip(RoundedCornerShape(14.dp))).clickable { app.selectTab(item.screen) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (isSell) {
                        val bg = if (selected) ex.cta else MaterialTheme.colorScheme.primary
                        // El contador va fuera del botón para que no lo corte su borde redondeado.
                        Box(Modifier.size(52.dp, 46.dp)) {
                            Box(
                                Modifier.align(Alignment.BottomCenter).size(44.dp).shadow(8.dp, RoundedCornerShape(16.dp), ambientColor = bg, spotColor = bg)
                                    .clip(RoundedCornerShape(16.dp)).background(bg),
                                contentAlignment = Alignment.Center,
                            ) { Icon(item.icon, item.label, tint = Color.White, modifier = Modifier.size(26.dp)) }
                            val count = app.cart.size
                            if (count > 0) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).size(18.dp).clip(RoundedCornerShape(9.dp))
                                        .background(if (selected) MaterialTheme.colorScheme.primary else ex.cta),
                                    contentAlignment = Alignment.Center,
                                ) { Text(count.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(item.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (selected) ex.cta else ex.muted)
                    } else {
                        val color = if (selected) MaterialTheme.colorScheme.primary else ex.muted
                        Box(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) { Icon(item.icon, item.label, tint = color, modifier = Modifier.size(23.dp)) }
                        Spacer(Modifier.height(2.dp))
                        Text(item.label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = color)
                    }
                }
            }
        }
    }
}

@Composable
private fun SideBar(app: AppState) {
    val ex = AppTheme.extra
    val s = app.settings
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.width(220.dp).fillMaxHeight()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.padding(8.dp, 8.dp, 8.dp, 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(s.businessName, 38.dp, square = true)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Vitrina POS", style = MaterialTheme.typography.titleMedium)
                    Text(s.businessName, style = MaterialTheme.typography.bodySmall, color = ex.muted, maxLines = 1)
                }
            }
            sideItems.filter { app.allows(it.screen) }.forEach { item ->
                val selected = app.tab == item.screen
                val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else ex.muted
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { app.selectTab(item.screen) }.padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(item.icon, null, tint = fg, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(item.label, color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    if (item.screen == Screen.Pos && app.cart.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))
                        Text(app.cart.size.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(ex.cta).padding(horizontal = 7.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
}

/** Pantalla para cuando el usuario no tiene permiso. */
@Composable
private fun NoAccessScreen() {
    val app = LocalAppState.current
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.Lock, null, tint = AppTheme.extra.muted, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("No tienes acceso a esta sección", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("Pídele al administrador que te dé el permiso en Más → Usuarios.", color = AppTheme.extra.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.OutlinedButton(onClick = { if (app.stack.isNotEmpty()) app.back() else app.selectTab(Screen.Home) }) { Text("Volver") }
    }
}
