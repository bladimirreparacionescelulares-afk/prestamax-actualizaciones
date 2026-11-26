package com.vitrinapos.ui.screens

import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitrinapos.AppState
import com.vitrinapos.LocalAppState
import com.vitrinapos.Screen
import com.vitrinapos.data.Money
import com.vitrinapos.data.Time
import com.vitrinapos.db.Product
import com.vitrinapos.platform.ContinuousScanner
import com.vitrinapos.platform.scannerUsesCamera
import com.vitrinapos.ui.components.ProductImage
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.theme.AppTheme

object ScanMode {
    const val SALE = "sale"
    const val LABELS = "labels"
}

/** Escáner continuo: cada código leído se agrega a la venta o a las etiquetas, sin cerrar la cámara. */
@Composable
fun ScannerScreen(mode: String) {
    val app = LocalAppState.current
    val s = app.settings
    val products by app.repo.products.collectAsState()
    val session = remember { mutableStateMapOf<Long, Int>() }
    val scope = com.vitrinapos.ui.components.rememberSafeScope()
    var last by remember { mutableStateOf<Product?>(null) }
    var unknown by remember { mutableStateOf<String?>(null) }
    var lastCode by remember { mutableStateOf("") }
    var lastAt by remember { mutableLongStateOf(0L) }

    fun onCode(code: String) {
        val now = Time.now()
        // La cámara ve el mismo código muchas veces por segundo: se cuenta una vez cada 1,5 s.
        if (code == lastCode && now - lastAt < 1_500) return
        lastCode = code
        lastAt = now
        val p = products.firstOrNull { it.code.equals(code, ignoreCase = true) }
        if (p == null && com.vitrinapos.data.parseRepairCode(code) != null) {
            app.platform.scanFeedback(true)
            scope.launch { if (app.openRepairByCode(code)) app.stack.remove(Screen.Scanner(mode)) }
            return
        }
        if (p == null) {
            unknown = code
            last = null
            app.platform.scanFeedback(false)
            return
        }
        unknown = null
        last = p
        session[p.id] = (session[p.id] ?: 0) + 1
        when (mode) {
            ScanMode.SALE -> app.addToCart(p)
            ScanMode.LABELS -> {
                val i = app.labelJob.indexOfFirst { it.productId == p.id }
                if (i >= 0) app.labelJob[i] = app.labelJob[i].copy(copies = app.labelJob[i].copies + 1)
                else app.labelJob.add(AppState.LabelItem(p.id, 1))
            }
        }
        app.platform.scanFeedback(true)
    }

    val units = session.values.sum()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            if (mode == ScanMode.SALE) "Escanear para vender" else "Escanear para etiquetas",
            subtitle = if (units == 0) "Apunta al código QR o de barras" else "${session.size} productos · $units unidades",
            onBack = { app.back() },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ContinuousScanner(::onCode, Modifier.fillMaxSize())
            if (scannerUsesCamera) {
                // Recuadro de lectura
                Canvas(Modifier.fillMaxSize()) {
                    val side = minOf(size.width, size.height) * 0.62f
                    val l = (size.width - side) / 2
                    val t = (size.height - side) / 2
                    val len = side * 0.16f
                    val w = 5.dp.toPx()
                    val c = Color.White
                    listOf(
                        Offset(l, t) to Offset(l + len, t), Offset(l, t) to Offset(l, t + len),
                        Offset(l + side, t) to Offset(l + side - len, t), Offset(l + side, t) to Offset(l + side, t + len),
                        Offset(l, t + side) to Offset(l + len, t + side), Offset(l, t + side) to Offset(l, t + side - len),
                        Offset(l + side, t + side) to Offset(l + side - len, t + side), Offset(l + side, t + side) to Offset(l + side, t + side - len),
                    ).forEach { (a, b) -> drawLine(c, a, b, w, StrokeCap.Round) }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val p = last
                val u = unknown
                when {
                    u != null -> Row(
                        Modifier.fillMaxWidth().background(AppTheme.extra.dangerSoft, RoundedCornerShape(14.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = AppTheme.extra.danger)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Código no encontrado", fontWeight = FontWeight.Bold, color = AppTheme.extra.danger)
                            Text(u, fontSize = 13.sp, color = AppTheme.extra.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (app.can(com.vitrinapos.data.Permission.CATALOG_EDIT)) OutlinedButton(onClick = { app.open(Screen.ProductForm(presetCode = u)) }, shape = RoundedCornerShape(12.dp)) { Text("Crear") }
                    }
                    p != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        ProductImage(p.imagePath, p.name, Modifier.size(56.dp), textSize = 16)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.code} · ${session[p.id] ?: 0} escaneadas", fontSize = 13.sp, color = AppTheme.extra.muted)
                        }
                        Text(Money.format(p.price, s.currencySymbol), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                    else -> Text(
                        if (scannerUsesCamera) "Cada código que pase frente a la cámara se agrega solo. Para sumar otra unidad del mismo producto, retíralo y vuelve a pasarlo."
                        else "Cada código que leas con el lector se agrega solo.",
                        color = AppTheme.extra.muted, fontSize = 13.sp,
                    )
                }
                val total = if (mode == ScanMode.SALE) app.repo.totalsFor(app.cart, app.cartDiscount, s).total else 0L
                Button(
                    onClick = {
                        app.back()
                        if (mode == ScanMode.SALE && app.cart.isNotEmpty()) app.open(Screen.Checkout)
                        if (mode == ScanMode.LABELS && app.labelJob.isNotEmpty()) app.open(Screen.LabelDesigner)
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.extra.cta, contentColor = AppTheme.extra.onCta),
                ) {
                    Text(
                        when {
                            mode == ScanMode.SALE && app.cart.isNotEmpty() -> "Listo · Cobrar ${Money.format(total, s.currencySymbol)}"
                            mode == ScanMode.LABELS && app.labelJob.isNotEmpty() -> "Listo · Diseñar ${app.labelJob.sumOf { it.copies }} etiquetas"
                            else -> "Listo"
                        },
                        fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    )
                }
            }
        }
    }
}
