package com.vitrinapos

import com.vitrinapos.data.AppGraph
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.NetRole
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.ProductDraft
import com.vitrinapos.data.Roles
import com.vitrinapos.net.SignupRequest
import com.vitrinapos.platform.DesktopPlatform
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.vitrinapos.ui.screens.CloudAccountScreen
import com.vitrinapos.ui.screens.CloudMode
import com.vitrinapos.ui.theme.VitrinaTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La app completa contra un servidor de la nube que esté corriendo (solo se ejecuta con -DcloudUrl=...):
 * una tienda que trabajaba sola pasa sus datos a la nube, otra PC entra con la cuenta, venden y una sale.
 */
class CloudAppTest {
    private fun graph(name: String): AppGraph {
        System.setProperty("vitrina.dataDir", Files.createTempDirectory("vitrina-$name").toString())
        return AppGraph(DesktopPlatform()).also { it.setDeviceName(name) }
    }

    private val shots = System.getProperty("screenshots")?.let(::File)?.also { it.mkdirs() }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun scene(name: String, h: Int = 900, w: Int = 412, content: @Composable () -> Unit) {
        val dir = shots ?: return
        ImageComposeScene(w * 2, h * 2, Density(2f)) { content() }.use { sc ->
            var t = 0L
            repeat(40) { sc.render(t); t += 50_000_000L; Thread.sleep(40) }
            File(dir, "$name.png").writeBytes(sc.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    private fun shoot(graph: AppGraph, name: String, h: Int = 900, w: Int = 412, setup: (AppState) -> Unit) = scene(name, h, w) { App(graph, onReady = setup) }

    private fun shootScreen(graph: AppGraph, name: String, h: Int = 900, content: @Composable () -> Unit) = scene(name, h) {
        val scope = rememberCoroutineScope()
        val app = remember { AppState(graph, scope) }
        VitrinaTheme { CompositionLocalProvider(LocalAppState provides app) { androidx.compose.material3.Surface { content() } } }
    }

    @Test
    fun pasarDatosEntrarYSalir(): Unit = runBlocking {
        val url = System.getProperty("cloudUrl") ?: return@runBlocking
        System.setProperty("vitrina.cloudUrl", url)
        val email = "prueba${System.currentTimeMillis()}@tienda.com"

        // Tienda que trabajaba sola, con un administrador y productos.
        val a = graph("Caja 1")
        val adminId = a.repo.saveUser(null, "Bladimir", "2468", Roles.ADMIN, true)
        a.repo.currentUser.value = a.repo.db.appUserQueries.selectById(adminId).executeAsOne()
        val pid = a.repo.saveProduct(ProductDraft(null, "Cargador 20W", "CRG-20", null, null, 40000, 95000, 6.0, 1.0, "und", true, true, null, ""))
        a.repo.saveSettings(a.repo.settings.value.copy(businessName = "Tienda Prueba", onboarded = true))
        val steps = mutableListOf<String>()
        a.migrateToCloud(SignupRequest(email, "Clave2026", "Bladimir", "Tienda Prueba", "PHONES", "2468"), url) { steps += it }
        assertEquals(NetRole.CLOUD, a.repo.metaGet("role"))
        assertTrue(a.cloud != null && steps.isNotEmpty(), "$steps")
        assertEquals(6.0, a.repo.db.productQueries.selectById(pid).executeAsOne().stock)
        val admin = a.repo.db.appUserQueries.selectAll().executeAsList().single()
        assertEquals("Bladimir", admin.name)

        // Entra con su PIN y vende: pasa por la nube.
        a.repo.currentUser.value = admin
        assertTrue(a.cloud!!.loginUser(admin.id, "2468"))
        a.repo.openCashSession(100000)
        val p = a.repo.db.productQueries.selectById(pid).executeAsOne()
        a.repo.recordSale(listOf(CartLine.of(p, 1.0)), null, 0, PaymentMethods.CASH, 100000, null)
        assertEquals(5.0, a.repo.db.productQueries.selectById(pid).executeAsOne().stock)

        Thread.sleep(1500)
        shoot(a, "80-nube-panel", 1500) { it.repo.currentUser.value = admin; it.selectTab(Screen.More); it.open(Screen.Network) }
        shoot(a, "81-nube-inicio") { it.repo.currentUser.value = admin; it.selectTab(Screen.Home) }

        // Otra PC entra con la cuenta y recibe todo.
        val b = graph("Caja 2")
        b.cloudLogin(email, "Clave2026", url = url)
        assertEquals(NetRole.CLOUD, b.repo.metaGet("role"))
        assertEquals(5.0, b.repo.db.productQueries.selectById(pid).executeAsOne().stock)
        assertEquals(1, b.repo.db.saleQueries.selectRecent(10).executeAsList().size)

        // La sesión del usuario se abre sola con el PIN al entrar (como en la pantalla de PIN).
        b.repo.currentUser.value = b.repo.db.appUserQueries.selectAll().executeAsList().single()
        b.cloud!!.userEntered(admin.id, "2468")
        b.repo.openCashSession(50000)
        val pb = b.repo.db.productQueries.selectById(pid).executeAsOne()
        b.repo.recordSale(listOf(CartLine.of(pb, 1.0)), null, 0, PaymentMethods.CASH, 100000, null)
        assertEquals(4.0, b.repo.db.productQueries.selectById(pid).executeAsOne().stock)
        // Con la sesión del administrador se ven los equipos del negocio.
        assertEquals(setOf("Caja 1", "Caja 2"), b.cloud!!.devices().map { it.name }.toSet())

        // La PC 2 sale de la nube y se queda con su copia.
        shoot(b, "82-nube-pin") { it.repo.currentUser.value = null }
        shoot(b, "87-mas-cuenta", 1500) { it.repo.currentUser.value = it.repo.db.appUserQueries.selectAll().executeAsList().first(); it.selectTab(Screen.More) }
        shoot(b, "88-pc-inicio", 760, 1280) { it.repo.currentUser.value = it.repo.db.appUserQueries.selectAll().executeAsList().first(); it.selectTab(Screen.Home) }
        shoot(b, "89-pc-quien-usa", 760, 1280) { it.repo.currentUser.value = null }
        shoot(b, "90-cerrar-cuenta") { it.repo.currentUser.value = it.repo.db.appUserQueries.selectAll().executeAsList().first(); it.selectTab(Screen.More); it.askCloseAccount = true }

        // "Cerrar sesión de la cuenta": se quita la copia de este equipo, los datos siguen en la nube.
        b.signOutAccount()
        assertTrue(b.cloud == null && b.signedOut)
        assertEquals(0, b.repo.db.productQueries.selectActive().executeAsList().size)
        assertEquals(email, b.lastAccountEmail)
        assertTrue(!b.repo.settings.value.onboarded, "vuelve a la pantalla de Iniciar sesión")
        shoot(b, "91-sesion-cerrada") { }
        shoot(b, "92-pc-sesion-cerrada", 760, 1280) { }
        // Al volver a entrar, todo se descarga de nuevo.
        b.cloudLogin(email, "Clave2026", url = url)
        assertEquals(4.0, b.repo.db.productQueries.selectById(pid).executeAsOne().stock)
        assertEquals(2, b.repo.db.saleQueries.selectRecent(10).executeAsList().size)
        assertTrue(!b.signedOut)
        b.leaveCloud()
        assertEquals(NetRole.SOLO, b.repo.metaGet("role"))
        assertTrue(b.cloud == null)
        assertEquals(4.0, b.repo.db.productQueries.selectById(pid).executeAsOne().stock)
        // Al abrir la app otra vez sigue sola (no intenta conectarse).
        b.repo.close()
        a.leaveCloud(); a.repo.close()
        // Pantallas de cuenta en un equipo nuevo.
        val fresh = graph("Nuevo")
        shoot(fresh, "83-asistente-nube") { }
        shootScreen(fresh, "84-crear-cuenta", 1250) { CloudAccountScreen(CloudMode.SIGNUP) {} }
        shootScreen(fresh, "85-entrar") { CloudAccountScreen(CloudMode.LOGIN) {} }
        shootScreen(fresh, "86-pasar-datos", 1100) { CloudAccountScreen(CloudMode.MIGRATE) {} }
        fresh.repo.close()
        println("RESULTADO nube ok: pasos=$steps")
    }
}
