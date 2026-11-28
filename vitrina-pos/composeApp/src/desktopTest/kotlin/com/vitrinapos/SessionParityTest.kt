package com.vitrinapos

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vitrinapos.data.AppGraph
import com.vitrinapos.data.NetRole
import com.vitrinapos.data.Repository
import com.vitrinapos.data.Roles
import com.vitrinapos.net.Crypto
import com.vitrinapos.platform.DesktopPlatform
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Celular y computadora tienen las mismas opciones de sesión: "Cambiar de usuario" y "Cerrar sesión de la cuenta",
 * y las dos hacen lo mismo. Si a una de las dos le falta una opción, esta prueba falla y no se publica.
 */
@OptIn(ExperimentalTestApi::class)
class SessionParityTest {

    /** Un equipo que trabaja con la nube (sin conexión en la prueba), con María como administradora. */
    private fun cloudGraph(): AppGraph {
        System.setProperty("vitrina.dataDir", Files.createTempDirectory("paridad").toString())
        val p = DesktopPlatform()
        Repository(p).apply {
            runBlocking {
                saveSettings(settings.value.copy(businessName = "Repuestos Sánchez", onboarded = true))
                saveUser(null, "María", "2468", Roles.ADMIN, true)
            }
            metaPut("role", NetRole.CLOUD)
            metaPut("cloud.device", "eq_prueba")
            metaPut("cloud.secret", Crypto.hex(p.protect("secreto".toByteArray())))
            metaPut("cloud.url", "http://127.0.0.1:1")
            metaPut("cloud.email", "dueno@tienda.com")
            metaPut("cloud.businessName", "Repuestos Sánchez")
            close()
        }
        return AppGraph(p)
    }

    /** La app tiene ciclos que no terminan (bloqueo por inactividad, estado de la nube): el reloj se avanza a mano. */
    private fun ComposeUiTest.settle() { repeat(6) { mainClock.advanceTimeBy(250) } }

    /** Abre la app en una ventana del tamaño de un celular o de una computadora. */
    private fun run(graph: AppGraph, phone: Boolean, setup: (AppState) -> Unit, checks: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = if (phone) 412 else 1280, height = if (phone) 2400 else 800) {
            mainClock.autoAdvance = false
            setContent {
                App(graph) { app ->
                    app.repo.currentUser.value = app.repo.db.appUserQueries.selectAll().executeAsList().first()
                    setup(app)
                }
            }
            settle()
            checks()
        }

    /** En la computadora las opciones están en el menú del usuario, al pie de la barra lateral. */
    private fun ComposeUiTest.openDesktopMenu() {
        onNode(hasClickAction() and hasContentDescription("Opciones de la sesión")).performClick()
        settle()
    }

    @Test
    fun celularTieneCambiarUsuarioYCerrarCuenta() {
        val graph = cloudGraph()
        var state: AppState? = null
        run(graph, phone = true, { state = it; it.selectTab(Screen.More) }) {
        onNodeWithText("Cambiar de usuario").assertExists()
        onNodeWithText("Cerrar sesión de la cuenta").performClick()
        settle()
        onNodeWithText("¿Cerrar la sesión de la cuenta?").assertExists()
        onNodeWithText("Cancelar").performClick()
        settle()
        onNodeWithText("Cambiar de usuario").performClick()
        settle()
        assertTrue(state!!.repo.currentUser.value == null, "Cambiar de usuario cierra la sesión del usuario")
        onNodeWithText("¿Quién va a usar la app?").assertExists()
        onNodeWithText("Cerrar sesión de la cuenta").assertExists()
        }
        graph.repo.close()
    }

    @Test
    fun computadoraTieneCambiarUsuarioYCerrarCuenta() {
        val graph = cloudGraph()
        var state: AppState? = null
        run(graph, phone = false, { state = it; it.selectTab(Screen.Home) }) {
        openDesktopMenu()
        onNodeWithText("dueno@tienda.com").assertExists()
        onNodeWithText("Cambiar de usuario").assertExists()
        onNodeWithText("Cerrar sesión de la cuenta").performClick()
        settle()
        onNodeWithText("¿Cerrar la sesión de la cuenta?").assertExists()
        onNodeWithText("Cancelar").performClick()
        settle()
        openDesktopMenu()
        onNodeWithText("Cambiar de usuario").performClick()
        settle()
        assertTrue(state!!.repo.currentUser.value == null, "Cambiar de usuario cierra la sesión del usuario")
        onNodeWithText("¿Quién va a usar la app?").assertExists()
        onNodeWithText("Cerrar sesión de la cuenta").assertExists()
        // Y también se puede pasar a "Más" desde la barra lateral, como en el celular.
        assertTrue(onAllNodesWithText("María").fetchSemanticsNodes().isNotEmpty())
        }
        graph.repo.close()
    }

    @Test
    fun cerrarCuentaSinConexionConVentasPendientesNoDejaSalir() {
        val graph = cloudGraph()
        // Una venta guardada sin conexión.
        graph.repo.db.syncQueries.outboxAdd("op-1", "{}", null, 0)
        run(graph, phone = true, { it.askCloseAccount = true }) {
        onNode(hasText("Primero hay que enviar", substring = true)).assertExists()
        onNodeWithText("Enviar ahora").assertExists()
        }
        graph.repo.close()
    }
}
