package com.vitrinapos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import com.vitrinapos.ui.components.AppCard
import com.vitrinapos.ui.components.Avatar
import com.vitrinapos.ui.components.Pill
import com.vitrinapos.ui.components.ScreenHeader
import com.vitrinapos.ui.components.SectionTitle
import com.vitrinapos.ui.theme.AppTheme
import com.vitrinapos.ui.theme.VitrinaTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Maquetas (solo imágenes) de la propuesta 2.1/2.2: sesión con cuenta, PIN, cerrar sesión y sucursales.
 * ./gradlew :composeApp:desktopTest --tests com.vitrinapos.Propuesta21 -Dscreenshots=/carpeta
 */
class Propuesta21 {
    @OptIn(ExperimentalComposeUiApi::class)
    private fun shot(dir: File, name: String, h: Int = 900, w: Int = 412, content: @Composable () -> Unit) {
        ImageComposeScene(w * 2, h * 2, Density(2f)) {
            VitrinaTheme(dark = false) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
        }.use { sc ->
            var t = 0L
            repeat(10) { sc.render(t); t += 50_000_000L }
            File(dir, "$name.png").writeBytes(sc.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test
    fun maquetas() {
        val dir = System.getProperty("screenshots")?.let(::File) ?: return
        dir.mkdirs()
        shot(dir, "01-iniciar-sesion") { LoginMock() }
        shot(dir, "02-elegir-negocio") { ChooseBusinessMock() }
        shot(dir, "03-elegir-sucursal") { ChooseBranchMock() }
        shot(dir, "04-quien-usa") { WhoMock() }
        shot(dir, "05-pin") { PinMock() }
        shot(dir, "06-inicio") { HomeMock() }
        shot(dir, "07-mas") { MoreMock() }
        shot(dir, "08-cambiar-usuario") { WhoMock(switching = true) }
        shot(dir, "09-cerrar-cuenta") { Dimmed({ MoreMock() }) { CloseAccountDialog(pending = 0) } }
        shot(dir, "10-cerrar-con-pendientes") { Dimmed({ MoreMock() }) { CloseAccountDialog(pending = 2) } }
        shot(dir, "11-sesion-cerrada") { LoginMock(after = true) }
        shot(dir, "12-descargando") { DownloadingMock() }
        shot(dir, "13-sucursales", 1000) { BranchesMock() }
        shot(dir, "14-sucursal-detalle", 1100) { BranchDetailMock() }
        shot(dir, "15-nueva-sucursal") { NewBranchMock() }
        shot(dir, "16-transferir", 1000) { TransferMock() }
        shot(dir, "17-recibir") { ReceiveMock() }
        shot(dir, "18-reportes") { ReportsMock() }
        shot(dir, "19-usuario-sucursales", 1100) { UserMock() }
        shot(dir, "20-nombre-invalido") { Dimmed({ MoreMock() }) { BadNameDialog() } }

        // ---- Computadora (Windows y Mac): mismas opciones, otra distribución ----
        val pc = 1280; val ph = 760
        shot(dir, "pc-01-iniciar-sesion", ph, pc) { Splash { LoginMock() } }
        shot(dir, "pc-02-elegir-negocio", ph, pc) { Splash { ChooseBusinessMock() } }
        shot(dir, "pc-03-elegir-sucursal", ph, pc) { Splash { ChooseBranchMock() } }
        shot(dir, "pc-04-quien-usa", ph, pc) { Splash { WhoMock() } }
        shot(dir, "pc-05-pin", ph, pc) { Splash { PinMock() } }
        shot(dir, "pc-06-inicio", ph, pc) { Desk("Inicio") { HomeMock() } }
        shot(dir, "pc-07-menu-usuario", ph, pc) { Desk("Inicio", userMenu = true) { HomeMock() } }
        shot(dir, "pc-08-cerrar-cuenta", ph, pc) { Dimmed({ Desk("Inicio") { HomeMock() } }, narrow = true) { CloseAccountDialog(pending = 0) } }
        shot(dir, "pc-09-cerrar-pendientes", ph, pc) { Dimmed({ Desk("Inicio") { HomeMock() } }, narrow = true) { CloseAccountDialog(pending = 2) } }
        shot(dir, "pc-10-sesion-cerrada", ph, pc) { Splash { LoginMock(after = true) } }
        shot(dir, "pc-11-sucursales", ph, pc) { Desk("Más") { Row { Box(Modifier.weight(1f)) { BranchesMock() }; Box(Modifier.weight(1.2f)) { BranchDetailMock() } } } }
        shot(dir, "pc-12-transferir", ph, pc) { Desk("Más") { Row { Box(Modifier.weight(1f)) { TransferMock() }; Box(Modifier.weight(1f)) { ReceiveMock() } } } }
        shot(dir, "pc-13-reportes", ph, pc) { Desk("Reportes") { ReportsMock() } }
        shot(dir, "pc-14-usuario", ph, pc) { Desk("Más") { Row { Box(Modifier.weight(1f)) { UserMock() }; Box(Modifier.weight(1f)) { NewBranchMock() } } } }
    }
}

// ---------------- piezas ----------------

@Composable
private fun Logo(icon: ImageVector = Icons.Rounded.Storefront) {
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun Field(label: String, value: String = "", password: Boolean = false, trailing: ImageVector? = null, error: String? = null) {
    OutlinedTextField(
        value = if (password && value.isNotEmpty()) "•".repeat(value.length) else value, onValueChange = {},
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        trailingIcon = trailing?.let { { Icon(it, null) } }, isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
}

@Composable
private fun Big(text: String, color: Color = MaterialTheme.colorScheme.primary, icon: ImageVector? = null) {
    Button(onClick = {}, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = color)) {
        icon?.let { Icon(it, null); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 16.sp)
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

@Composable
private fun Muted(t: String, size: Int = 14) = Text(t, color = AppTheme.extra.muted, fontSize = size.sp)

@Composable
private fun Choice(title: String, sub: String, icon: ImageVector, selected: Boolean = false, badge: String? = null) {
    AppCard(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Muted(sub, 13)
            }
            badge?.let { Pill(it, MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary) }
            if (badge == null) Icon(Icons.Rounded.ChevronRight, null, tint = AppTheme.extra.muted)
        }
    }
}

@Composable
private fun Dimmed(back: @Composable () -> Unit, narrow: Boolean = false, dialog: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        back()
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(22.dp).then(if (narrow) Modifier.width(460.dp) else Modifier.fillMaxWidth())) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { dialog() }
            }
        }
    }
}

@Composable
private fun AccountBar() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Cloud, null, tint = AppTheme.extra.ok, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Repuestos Sánchez · Sucursal Principal", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Muted("dueno@…", 12)
    }
}

// ---------------- 1. Sesión ----------------

@Composable
private fun LoginMock(after: Boolean = false) = Page {
    Spacer(Modifier.height(20.dp))
    Logo()
    Text("Iniciar sesión", style = MaterialTheme.typography.headlineSmall)
    Muted(if (after) "Cerraste la sesión de la cuenta en este equipo. Tus datos siguen guardados en la nube." else "Entra con el correo y la contraseña de tu negocio.")
    if (after) Text(
        "✓ Sesión cerrada. Para volver, entra de nuevo: los datos se descargan solos.",
        color = AppTheme.extra.ok, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppTheme.extra.okSoft).padding(12.dp),
    )
    Field("Correo", if (after) "dueno@repuestossanchez.com" else "")
    Field("Contraseña", "", password = true, trailing = Icons.Rounded.Visibility)
    Big("Entrar")
    TextButton(onClick = {}, Modifier.fillMaxWidth()) { Text("Olvidé mi contraseña") }
    HorizontalDivider(color = AppTheme.extra.line)
    OutlinedButton(onClick = {}, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("¿Negocio nuevo? Crear cuenta gratis") }
    Muted("Prueba gratis de 14 días. Funciona en celular, Windows y Mac.", 12)
}

@Composable
private fun ChooseBusinessMock() = Page {
    Spacer(Modifier.height(20.dp))
    Logo(Icons.Rounded.Business)
    Text("¿Con qué negocio vas a trabajar?", style = MaterialTheme.typography.headlineSmall)
    Muted("Tu correo administra varios negocios. Elige uno; luego puedes cambiar cerrando la sesión de la cuenta.")
    Choice("Repuestos Sánchez", "3 sucursales · Plan Pro", Icons.Rounded.Storefront)
    Choice("Colmado La Esquina", "1 sucursal · Plan Esencial", Icons.Rounded.ShoppingBasket)
    Spacer(Modifier.weight(1f))
    TextButton(onClick = {}, Modifier.fillMaxWidth()) { Text("Crear otro negocio con esta cuenta") }
}

@Composable
private fun ChooseBranchMock() = Page {
    Spacer(Modifier.height(20.dp))
    Logo(Icons.Rounded.Place)
    Text("¿En qué sucursal está este equipo?", style = MaterialTheme.typography.headlineSmall)
    Muted("Repuestos Sánchez. Las ventas y el inventario de este equipo serán de esa sucursal. Se pregunta una sola vez.")
    Choice("Principal", "Av. Duarte #45 · 2 equipos", Icons.Rounded.Store, selected = true)
    Choice("Sucursal Norte", "Santiago · 1 equipo", Icons.Rounded.Store)
    Choice("Sucursal Este", "La Romana · 0 equipos", Icons.Rounded.Store)
    Field("Nombre de este equipo", "Caja 2")
    Big("Continuar")
}

@Composable
private fun WhoMock(switching: Boolean = false) = Page {
    AccountBar()
    Spacer(Modifier.height(6.dp))
    Text(if (switching) "Cambiar de usuario" else "¿Quién va a usar la app?", style = MaterialTheme.typography.headlineSmall)
    Muted("Cada persona entra con su PIN. Solo salen los usuarios de esta sucursal.")
    listOf("Bladimir" to "Administrador", "María" to "Cajera", "Luis" to "Técnico").forEach { (n, r) ->
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(n, 46.dp); Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) { Text(n, fontWeight = FontWeight.SemiBold, fontSize = 17.sp); Muted(r, 13) }
                Icon(Icons.Rounded.Lock, null, tint = AppTheme.extra.muted)
            }
        }
    }
    Spacer(Modifier.weight(1f))
    TextButton(onClick = {}, Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp))
        Text("Cerrar sesión de la cuenta", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun PinMock() = Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Avatar("María", 64.dp)
    Spacer(Modifier.height(10.dp))
    Text("María", style = MaterialTheme.typography.titleLarge)
    Muted("Escribe tu PIN")
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(4) { i -> Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < 2) MaterialTheme.colorScheme.primary else AppTheme.extra.line)) }
    }
    Spacer(Modifier.height(8.dp))
    Muted("El PIN nunca se muestra: solo los puntos.", 12)
    Spacer(Modifier.height(18.dp))
    listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("✓", "0", "<")).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            row.forEach { k ->
                Box(Modifier.size(76.dp).clip(CircleShape).background(if (k == "✓") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                    when (k) {
                        "<" -> Icon(Icons.AutoMirrored.Rounded.Backspace, null)
                        "✓" -> Icon(Icons.Rounded.Check, null, tint = Color.White)
                        else -> Text(k, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    TextButton(onClick = {}) { Text("Cambiar de usuario") }
}

@Composable
private fun HomeMock() = Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Muted("Hola, María", 13)
            Text("Repuestos Sánchez", style = MaterialTheme.typography.titleLarge)
        }
        Pill("Principal", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        Spacer(Modifier.width(8.dp))
        Pill("● Nube", AppTheme.extra.ok, AppTheme.extra.okSoft)
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.primary).padding(18.dp)) {
            Column {
                Text("Ventas de hoy · Sucursal Principal", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Text("RD$ 18,450.00", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("23 ventas · caja abierta por María", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard(Modifier.weight(1f)) { Icon(Icons.Rounded.ShoppingCart, null, tint = MaterialTheme.colorScheme.primary); Text("Vender", fontWeight = FontWeight.SemiBold) }
            AppCard(Modifier.weight(1f)) { Icon(Icons.Rounded.Inventory2, null, tint = MaterialTheme.colorScheme.primary); Text("Inventario", fontWeight = FontWeight.SemiBold) }
        }
        Muted("Arriba siempre se ve en qué sucursal estás y si hay conexión con la nube.", 12)
    }
}

@Composable
private fun MoreMock() = Column(Modifier.fillMaxSize()) {
    ScreenHeader("Más")
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar("María", 44.dp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text("María", fontWeight = FontWeight.SemiBold); Muted("Cajera · Sucursal Principal", 12) }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = {}, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Rounded.SwitchAccount, null); Spacer(Modifier.width(8.dp)); Text("Cambiar de usuario")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Icons.Rounded.Store to "Sucursales", Icons.Rounded.Badge to "Usuarios", Icons.Rounded.Inventory2 to "Inventario").forEach { (i, t) ->
                AppCard(Modifier.weight(1f)) { Icon(i, null, tint = MaterialTheme.colorScheme.primary); Text(t, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Icons.Rounded.BarChart to "Reportes", Icons.Rounded.PointOfSale to "Caja", Icons.Rounded.Settings to "Ajustes").forEach { (i, t) ->
                AppCard(Modifier.weight(1f)) { Icon(i, null, tint = MaterialTheme.colorScheme.primary); Text(t, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
            }
        }
        SectionTitle("Cuenta")
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cloud, null, tint = AppTheme.extra.ok); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text("dueno@repuestossanchez.com", fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Muted("Repuestos Sánchez · Plan Pro", 12) }
            }
        }
        OutlinedButton(onClick = {}, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
            Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp))
            Text("Cerrar sesión de la cuenta", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CloseAccountDialog(pending: Int) {
    Text(if (pending > 0) "Primero hay que enviar 2 ventas" else "¿Cerrar la sesión de la cuenta?", style = MaterialTheme.typography.titleLarge)
    if (pending > 0) {
        Text(
            "Este equipo tiene 2 ventas hechas sin internet que todavía no llegan a la nube. Conéctate a internet para enviarlas; si cierras ahora se perderían.",
            color = AppTheme.extra.warn, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(AppTheme.extra.warnSoft).padding(12.dp),
        )
        Big("Enviar ahora", icon = Icons.Rounded.CloudUpload)
        TextButton(onClick = {}, Modifier.fillMaxWidth()) { Text("Cancelar") }
    } else {
        Text("• Tus datos NO se borran: siguen guardados en la nube.", fontSize = 14.sp)
        Text("• Se quita la copia de este equipo, para que nadie más los vea aquí.", fontSize = 14.sp)
        Text("• Al volver a entrar con tu correo, todo se descarga de nuevo solo.", fontSize = 14.sp)
        Text("• Así también puedes entrar a otro negocio en este equipo.", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = {}) { Text("Cancelar") }
            TextButton(onClick = {}) { Text("Cerrar sesión", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun DownloadingMock() = Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Logo(Icons.Rounded.CloudDownload)
    Spacer(Modifier.height(16.dp))
    Text("Recibiendo los datos de la nube…", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    LinearProgressIndicator(progress = { 0.62f }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)))
    Spacer(Modifier.height(12.dp))
    Muted("Productos 1,240 de 1,240 · Ventas 3,180 de 5,100 · Fotos…", 13)
    Spacer(Modifier.height(8.dp))
    Muted("Solo pasa la primera vez que entras en este equipo.", 12)
}

// ---------------- 2. Sucursales ----------------

@Composable
private fun BranchesMock() = Column(Modifier.fillMaxSize()) {
    ScreenHeader("Sucursales", subtitle = "Repuestos Sánchez · Plan Pro", onBack = {})
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            Triple("Principal", "Av. Duarte #45, Santo Domingo", "Hoy RD$ 18,450 · 2 equipos · 3 usuarios"),
            Triple("Sucursal Norte", "Calle del Sol #12, Santiago", "Hoy RD$ 9,870 · 1 equipo · 2 usuarios"),
            Triple("Sucursal Este", "La Romana", "Sin equipos todavía"),
        ).forEachIndexed { i, (n, a, s) ->
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Store, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n, fontWeight = FontWeight.Bold, fontSize = 16.sp); Muted(a, 12); Muted(s, 12)
                    }
                    if (i == 0) Pill("Este equipo", AppTheme.extra.ok, AppTheme.extra.okSoft)
                }
            }
        }
        Big("Nueva sucursal", icon = Icons.Rounded.AddBusiness)
        Muted("Productos, precios y clientes son los mismos en todas. Cada sucursal tiene su propio inventario, caja y ventas.", 13)
    }
}

@Composable
private fun BranchDetailMock() = Column(Modifier.fillMaxSize()) {
    ScreenHeader("Sucursal Norte", subtitle = "Calle del Sol #12, Santiago", onBack = {}, actions = { TextButton(onClick = {}) { Text("Editar") } })
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("RD$ 9,870" to "Ventas hoy", "Abierta" to "Caja", "412" to "Productos con stock").forEach { (v, l) ->
                AppCard(Modifier.weight(1f)) { Text(v, fontWeight = FontWeight.Bold, fontSize = 16.sp); Muted(l, 12) }
            }
        }
        SectionTitle("Equipos")
        AppCard { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text("Caja Norte", fontWeight = FontWeight.SemiBold); Muted("Android · en línea", 12) } } }
        SectionTitle("Usuarios", action = "Asignar")
        listOf("Ana" to "Cajera", "Pedro" to "Vendedor").forEach { (n, r) ->
            AppCard { Row(verticalAlignment = Alignment.CenterVertically) { Avatar(n, 36.dp); Spacer(Modifier.width(10.dp)); Text(n, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Muted(r, 13) } }
        }
        SectionTitle("Inventario bajo en esta sucursal", action = "Transferir")
        listOf("Cargador USB-C 20W" to "2 und", "Mica iPhone 13" to "0 und").forEach { (p, q) ->
            AppCard { Row { Text(p, modifier = Modifier.weight(1f)); Pill(q, AppTheme.extra.warn, AppTheme.extra.warnSoft) } }
        }
        SectionTitle("Datos del ticket")
        AppCard { Text("Repuestos Sánchez · Sucursal Norte", fontWeight = FontWeight.SemiBold); Muted("Tel. 809-555-0199 · Calle del Sol #12", 12) }
    }
}

@Composable
private fun NewBranchMock() = Column(Modifier.fillMaxSize()) {
    ScreenHeader("Nueva sucursal", onBack = {})
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field("Nombre *", "Sucursal Este")
        Field("Dirección", "Av. Libertad #8, La Romana")
        Field("Teléfono (sale en el ticket)", "809-555-0123")
        AppCard {
            Text("Inventario inicial", fontWeight = FontWeight.SemiBold)
            Muted("Empieza en cero. Luego le envías mercancía con una transferencia o registras una compra en esa sucursal.", 13)
        }
        AppCard {
            Text("Tu plan", fontWeight = FontWeight.SemiBold)
            Muted("Plan Pro: sucursales sin límite. (Esencial: 1 · Negocio: 2)", 13)
        }
        Big("Crear sucursal")
    }
}

@Composable
private fun TransferMock() = Column(Modifier.fillMaxSize()) {
    ScreenHeader("Transferir inventario", onBack = {})
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppCard(Modifier.weight(1f)) { Muted("Desde", 12); Text("Principal", fontWeight = FontWeight.Bold) }
            Icon(Icons.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
            AppCard(Modifier.weight(1f), color = MaterialTheme.colorScheme.primaryContainer) { Muted("Hacia", 12); Text("Sucursal Norte", fontWeight = FontWeight.Bold) }
        }
        SectionTitle("Productos", action = "+ Agregar")
        listOf(Triple("Cargador USB-C 20W", "Hay 38 en Principal", "10"), Triple("Mica iPhone 13", "Hay 25 en Principal", "6"), Triple("Samsung A15 128GB", "IMEI 3567…8812, 3567…9021", "2")).forEach { (p, s, q) ->
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(p, fontWeight = FontWeight.SemiBold); Muted(s, 12) }
                    Box(Modifier.border(1.dp, AppTheme.extra.line, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) { Text(q, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Field("Nota (opcional)", "Pedido del lunes")
        Big("Enviar transferencia", icon = Icons.Rounded.LocalShipping)
        Muted("Sale del inventario de Principal ahora. Entra al de Sucursal Norte cuando allá toquen \"Recibir\".", 13)
    }
}

@Composable
private fun ReceiveMock() = Column(Modifier.fillMaxSize()) {
    ScreenHeader("Transferencias", subtitle = "Sucursal Norte", onBack = {})
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppCard(color = AppTheme.extra.warnSoft) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LocalShipping, null, tint = AppTheme.extra.warn); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text("En camino desde Principal", fontWeight = FontWeight.Bold); Muted("18 unidades · envió Bladimir hoy 10:24 a. m.", 12) }
            }
            Spacer(Modifier.height(10.dp))
            Muted("Cargador USB-C 20W × 10 · Mica iPhone 13 × 6 · Samsung A15 × 2", 13)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}, Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Recibir todo") }
                OutlinedButton(onClick = {}, Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Llegó diferente") }
            }
        }
        SectionTitle("Anteriores")
        listOf("Recibida · 12 und · ayer" to AppTheme.extra.ok, "Enviada a Este · 5 und · 24 nov" to AppTheme.extra.muted).forEach { (t, c) ->
            AppCard { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.SwapHoriz, null, tint = c); Spacer(Modifier.width(10.dp)); Text(t) } }
        }
        Muted("Si llega menos de lo enviado, \"Llegó diferente\" registra la diferencia y avisa al administrador.", 13)
    }
}

@Composable
private fun ReportsMock() = Column(Modifier.fillMaxSize()) {
    ScreenHeader("Reportes", onBack = {})
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Todas" to true, "Principal" to false, "Norte" to false, "Este" to false).forEach { (t, sel) ->
                Surface(shape = RoundedCornerShape(999.dp), color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer) {
                    Text(t, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
        AppCard { Muted("Ventas del mes · todas las sucursales", 13); Text("RD$ 642,300.00", fontSize = 26.sp, fontWeight = FontWeight.Bold); Muted("Ganancia RD$ 158,900 · 1,870 ventas", 13) }
        listOf(Triple("Principal", 0.58f, "RD$ 372,500"), Triple("Sucursal Norte", 0.32f, "RD$ 205,600"), Triple("Sucursal Este", 0.10f, "RD$ 64,200")).forEach { (n, f, v) ->
            AppCard {
                Row { Text(n, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(v, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Muted("Solo el administrador ve \"Todas\". Los demás ven su sucursal.", 12)
    }
}

@Composable
private fun UserMock() = Column(Modifier.fillMaxSize()) {
    ScreenHeader("Ana", subtitle = "Cajera", onBack = {})
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field("Nombre", "Ana")
        Field("PIN nuevo (opcional)", "", password = true)
        SectionTitle("Sucursales donde trabaja")
        listOf("Principal" to false, "Sucursal Norte" to true, "Sucursal Este" to false).forEach { (n, on) ->
            AppCard { Row(verticalAlignment = Alignment.CenterVertically) { Text(n, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Switch(checked = on, onCheckedChange = {}) } }
        }
        SectionTitle("Acceso con su propio correo (opcional)")
        AppCard {
            Field("Correo de Ana", "ana.cajera@gmail.com")
            Spacer(Modifier.height(8.dp))
            Big("Enviar invitación", icon = Icons.Rounded.Mail)
            Spacer(Modifier.height(6.dp))
            Muted("Le llega un correo para crear su contraseña. Podrá entrar en su propio celular; en la caja sigue usando su PIN. Puedes quitarle el acceso cuando quieras.", 12)
        }
    }
}

@Composable
private fun BadNameDialog() {
    Text("Nuevo usuario", style = MaterialTheme.typography.titleLarge)
    Field("Nombre", "255206", error = "Escribe el nombre de la persona, no números")
    Field("PIN (4 a 6 números)", "2552", password = true)
    Muted("El PIN es privado: nunca se muestra en la app ni en los tickets.", 12)
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = {}) { Text("Cancelar") }
        TextButton(onClick = {}, enabled = false) { Text("Guardar") }
    }
}


// ---------------- computadora ----------------

/** Pantallas de entrada en la computadora: marca a la izquierda, formulario al centro. */
@Composable
private fun Splash(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.primary).padding(48.dp), contentAlignment = Alignment.CenterStart) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Vitrina POS", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("Vende, controla tu inventario y tus sucursales desde cualquier equipo.", color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp)
                Text("Celular · Windows · Mac", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.width(440.dp).fillMaxHeight()) { content() }
        }
    }
}

/** La app en la computadora: barra lateral con la sucursal arriba y el usuario al pie. */
@Composable
private fun Desk(selected: String, userMenu: Boolean = false, content: @Composable () -> Unit) {
    val ex = AppTheme.extra
    Row(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.width(240.dp).fillMaxHeight()) {
            Column(Modifier.fillMaxHeight().padding(12.dp)) {
                Row(Modifier.padding(8.dp, 8.dp, 8.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar("Repuestos Sánchez", 38.dp, square = true); Spacer(Modifier.width(10.dp))
                    Column { Text("Repuestos Sánchez", fontWeight = FontWeight.Bold, fontSize = 14.sp); Muted("● Nube · en línea", 11) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Store, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text("Sucursal Principal", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.weight(1f)) {
                    listOf(
                        Icons.Rounded.Home to "Inicio", Icons.Rounded.ShoppingCart to "Vender", Icons.Rounded.GridView to "Catálogo",
                        Icons.Rounded.Inventory2 to "Inventario", Icons.Rounded.People to "Clientes", Icons.Rounded.BarChart to "Reportes",
                        Icons.Rounded.PointOfSale to "Caja", Icons.Rounded.Settings to "Configuración", Icons.Rounded.MoreHoriz to "Más",
                    ).forEach { (i, t) ->
                        val sel = t == selected
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(i, null, tint = if (sel) MaterialTheme.colorScheme.primary else ex.muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                            Text(t, color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else ex.muted, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
                if (userMenu) Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Muted("dueno@repuestossanchez.com", 11)
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.SwitchAccount, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text("Cambiar de usuario", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text("Cerrar sesión de la cuenta", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar("María", 34.dp); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text("María", fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Muted("Cajera", 11) }
                    Icon(if (userMenu) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, null, tint = ex.muted)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}
