package com.vitrinapos.server

import com.vitrinapos.net.*
import com.vitrinapos.data.CartLine
import com.vitrinapos.data.Op
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.Permission
import com.vitrinapos.data.Permissions
import com.vitrinapos.data.ProductDraft
import com.vitrinapos.data.PurchaseLine
import com.vitrinapos.data.Roles
import com.vitrinapos.net.ApiResponse
import com.vitrinapos.net.Crypto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerTest {
    private val baseUrl = System.getenv("TEST_DATABASE_URL")!!
    private lateinit var dbName: String
    private lateinit var server: VitrinaServer
    private lateinit var mailer: LogMailer
    private val adminToken = "admin-" + Secure.token(16)

    /** Cada prueba con su propia base de PostgreSQL, vacía. */
    @BeforeTest
    fun setUp() {
        dbName = "vt_" + Secure.digits(10)
        DriverManager.getConnection(baseUrl).use { it.createStatement().execute("CREATE DATABASE $dbName") }
        val url = baseUrl.replace(Regex("/[A-Za-z0-9_]+\\?"), "/$dbName?")
        mailer = LogMailer()
        val cfg = Config(url, Files.createTempDirectory("negocios").toFile(), Secure.bytes(32), Secure.bytes(32), adminToken)
        server = VitrinaServer(cfg, ControlStore(url), Tenants(cfg.dataDir, cfg.masterKey), mailer)
    }

    @AfterTest
    fun tearDown() {
        server.tenants.close(); server.control.close()
        DriverManager.getConnection(baseUrl).use { it.createStatement().execute("DROP DATABASE IF EXISTS $dbName") }
    }

    private val json = Json(Op.json) { explicitNulls = false }

    private fun ApplicationTestBuilder.api(): HttpClient {
        application { vitrinaApi(server) }
        return createClient { install(ContentNegotiation) { json(json) } }
    }

    private suspend inline fun <reified T> HttpClient.postJson(path: String, body: T, token: String? = null, user: String? = null): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json); setBody(body)
            token?.let { header("Authorization", "Bearer $it") }
            user?.let { header("X-Usuario", it) }
        }

    private suspend fun HttpClient.op(op: Op, token: String, user: String?, opId: String = Secure.token(8)) =
        postJson("/v1/operaciones", OpRequest(opId, op), token, user)

    private suspend fun HttpClient.signup(email: String = "dueno@mitienda.com"): DeviceCredentials =
        postJson("/v1/cuentas", SignupRequest(email, "Clave2026", "Bladimir", "Mi Tienda", "PHONES", "1234", "Caja 1", "Android")).body()

    private suspend fun HttpClient.deviceToken(c: DeviceCredentials): TokenResponse = postJson("/v1/sesion/ficha", TokenRequest(c.deviceId, c.deviceSecret)).body()

    private suspend fun HttpClient.userToken(token: String, userId: Long, pin: String): HttpResponse = postJson("/v1/sesion/usuario", UserLoginRequest(userId, pin), token)

    @Test
    fun cuentaEquiposYPin() = testApplication {
        val c = api()
        // Validaciones del alta.
        assertEquals(HttpStatusCode.BadRequest, c.postJson("/v1/cuentas", SignupRequest("x@y.com", "corta", "A", "B", "", "1234")).status)
        assertEquals(HttpStatusCode.BadRequest, c.postJson("/v1/cuentas", SignupRequest("no-es-correo", "Clave2026", "A", "B", "", "1234")).status)
        val creds = c.signup()
        assertEquals(HttpStatusCode.Conflict, c.postJson("/v1/cuentas", SignupRequest("DUENO@mitienda.com", "Clave2026", "Otro", "Otra", "", "1111")).status)

        // El equipo pide su ficha; con una llave equivocada, no.
        assertEquals(HttpStatusCode.Unauthorized, c.postJson("/v1/sesion/ficha", TokenRequest(creds.deviceId, "mala")).status)
        val t = c.deviceToken(creds)
        assertEquals("pro", t.entitlements.plan)
        assertEquals("TRIAL", t.entitlements.state)
        assertTrue("taller" in t.entitlements.modules && "facturacion" in t.entitlements.modules)

        // El negocio nació con su administrador; sin PIN no se opera.
        assertEquals(HttpStatusCode.Unauthorized, c.op(Op.OpenCash(10000, "", ""), t.token, null).status)
        val admin = server.tenants.repo(creds.businessId).db.appUserQueries.selectAll().executeAsList().single()
        assertEquals(Roles.ADMIN, admin.role)
        assertEquals(HttpStatusCode.Unauthorized, c.userToken(t.token, admin.id, "9999").status)
        val ut: UserTokenResponse = c.userToken(t.token, admin.id, "1234").body()
        assertEquals("Bladimir", ut.userName)

        // Con la sesión del usuario: abre la caja y vende; los datos llegan por los cambios.
        assertEquals(HttpStatusCode.OK, c.op(Op.OpenCash(100000, "otro-equipo", "Alguien"), t.token, ut.token).status)
        val prod = c.op(Op.SaveProduct(ProductDraft(null, "Cargador", "", null, null, 30000, 85000, 5.0, 1.0, "und", true, true, null, ""), ""), t.token, ut.token)
            .body<ApiResponse>().result.jsonPrimitive.long
        val p = server.tenants.repo(creds.businessId).db.productQueries.selectById(prod).executeAsOne()
        val sale = Op.RecordSale(listOf(CartLine.of(p, 2.0)), null, "Consumidor final", 0, PaymentMethods.CASH, 200000, null, "x", 999, "Falso", System.currentTimeMillis())
        val opId = "venta-1"
        val r1 = c.op(sale, t.token, ut.token, opId).body<ApiResponse>()
        val r2 = c.op(sale, t.token, ut.token, opId).body<ApiResponse>()
        assertEquals(r1.result, r2.result) // reenviada (sin red): no se duplica
        val repo = server.tenants.repo(creds.businessId)
        assertEquals(1, repo.db.saleQueries.selectRecent(10).executeAsList().size)
        val saved = repo.db.saleQueries.selectRecent(10).executeAsList().single()
        assertEquals("Bladimir", saved.userName)       // el usuario verificado, no el que dijo el equipo
        assertEquals(creds.deviceId, saved.deviceId)
        assertEquals(3.0, repo.db.productQueries.selectById(prod).executeAsOne().stock)
        val ch = c.get("/v1/cambios?desde=0") { header("Authorization", "Bearer ${t.token}") }.body<ApiResponse>()
        assertTrue(ch.changes.any { it.t == "sale" } && ch.changes.any { it.t == "product" })
        assertEquals(t.epoch, ch.epoch)
        // La actividad la firma el servidor con el usuario y el equipo verificados.
        assertTrue(repo.activity(0, Long.MAX_VALUE).any { it.action == "Abrió la caja" && it.userName == "Bladimir" && it.device == "Caja 1" })
    }

    @Test
    fun permisosYModulosDelPlan() = testApplication {
        val c = api()
        val creds = c.signup()
        val t = c.deviceToken(creds)
        val repo = server.tenants.repo(creds.businessId)
        val adminId = repo.db.appUserQueries.selectAll().executeAsList().single().id
        val ut: UserTokenResponse = c.userToken(t.token, adminId, "1234").body()
        // El administrador crea un vendedor.
        val sellerId = c.op(Op.SaveUser(null, "Pedro", Crypto.pinHash("5678"), Roles.SELLER, true), t.token, ut.token).body<ApiResponse>().result.jsonPrimitive.long
        val st: UserTokenResponse = c.userToken(t.token, sellerId, "5678").body()
        // El vendedor no puede anular ni cambiar permisos ni la configuración.
        val void = c.op(Op.VoidSale(1, ""), t.token, st.token)
        assertEquals(HttpStatusCode.Forbidden, void.status)
        assertTrue(void.bodyAsText().contains("Anular ventas"))
        assertEquals(HttpStatusCode.Forbidden, c.op(Op.SetUserPermissions(sellerId, Permissions.encode(Permission.entries.toSet())), t.token, st.token).status)
        assertEquals(HttpStatusCode.Forbidden, c.op(Op.SaveSettings(mapOf("maxDiscountPct" to "100")), t.token, st.token).status)
        // Pero sí puede cambiar su propio PIN.
        assertEquals(HttpStatusCode.OK, c.op(Op.SaveUser(sellerId, "Pedro", Crypto.pinHash("2468"), Roles.SELLER, true), t.token, st.token).status)

        // Plan Esencial: sin inventario ni taller, y un solo equipo.
        c.postJson("/admin/v1/negocios/${creds.businessId}/plan", PlanChange("esencial"), adminToken)
        val buy = c.op(Op.SavePurchase(null, "", "", "", 0, listOf(PurchaseLine(1, 1.0, 100)), 0, "Contado", "", ""), t.token, ut.token)
        assertEquals(HttpStatusCode.Forbidden, buy.status)
        assertTrue(buy.bodyAsText().contains("inventario"))
        val second = c.postJson("/v1/sesion/equipo", LoginRequest("dueno@mitienda.com", "Clave2026", "Caja 2"))
        assertEquals(HttpStatusCode.Forbidden, second.status)
        assertTrue(second.bodyAsText().contains("1 equipo"))
        // Un módulo suelto lo habilita.
        c.postJson("/admin/v1/negocios/${creds.businessId}/modulos", ModuleChange("inventario", true, 50000), adminToken)
        assertTrue("inventario" in c.deviceToken(creds).entitlements.modules)

        // Plan vencido: solo lectura.
        server.control.tx { it.u("UPDATE suscripcion SET prueba_hasta = now() - interval '30 days', pagado_hasta = NULL WHERE negocio_id = ?", creds.businessId) }
        val ro = c.op(Op.OpenCash(0, "", ""), t.token, ut.token)
        assertEquals(HttpStatusCode.PaymentRequired, ro.status)
        assertTrue(ro.bodyAsText().contains("venció"))
        // Al registrar el pago, vuelve a funcionar.
        c.postJson("/admin/v1/negocios/${creds.businessId}/pagado", PaidUntil(System.currentTimeMillis() + 30L * 86_400_000), adminToken)
        assertEquals(HttpStatusCode.OK, c.op(Op.OpenCash(0, "", ""), t.token, ut.token).status)
    }

    @Test
    fun contrasenaPanelYEquipos() = testApplication {
        val c = api()
        val creds = c.signup()
        // Recuperar la contraseña con el código del correo.
        c.postJson("/v1/contrasena/olvide", ForgotRequest("dueno@mitienda.com"))
        val code = Regex("\\d{6}").find(mailer.sent.last().second)!!.value
        assertEquals(HttpStatusCode.BadRequest, c.postJson("/v1/contrasena/restablecer", ResetRequest("dueno@mitienda.com", "000000", "Nueva2026")).status)
        assertEquals(HttpStatusCode.OK, c.postJson("/v1/contrasena/restablecer", ResetRequest("dueno@mitienda.com", code, "Nueva2026")).status)
        assertEquals(HttpStatusCode.BadRequest, c.postJson("/v1/contrasena/restablecer", ResetRequest("dueno@mitienda.com", code, "Otra2026x")).status) // ya se usó
        // Pedir un código para un correo que no existe responde igual (no revela cuentas).
        assertEquals(HttpStatusCode.OK, c.postJson("/v1/contrasena/olvide", ForgotRequest("nadie@x.com")).status)

        // Panel administrativo: sin la ficha, nada.
        assertEquals(HttpStatusCode.Unauthorized, c.get("/admin/v1/negocios").status)
        val list = c.get("/admin/v1/negocios") { header("Authorization", "Bearer $adminToken") }.body<List<AdminBusiness>>()
        assertEquals("dueno@mitienda.com", list.single().email)
        // Contraseña temporal desde el panel: al entrar, pide cambiarla.
        val temp = c.postJson("/admin/v1/cuentas/${list.single().accountId}/restablecer", Message(), adminToken).body<TempPassword>()
        val dev2 = c.postJson("/v1/sesion/equipo", LoginRequest("dueno@mitienda.com", temp.temporaryPassword, "Caja 2")).body<DeviceCredentials>()
        assertTrue(dev2.mustChangePassword)

        // Bloqueo tras 5 intentos fallidos.
        repeat(5) { c.postJson("/v1/sesion/dueno", OwnerLoginRequest("dueno@mitienda.com", "mala-$it")) }
        assertEquals(HttpStatusCode.Locked, c.postJson("/v1/sesion/dueno", OwnerLoginRequest("dueno@mitienda.com", temp.temporaryPassword)).status)

        // El dueño quita un equipo: ya no consigue ficha.
        server.control.loginOk(list.single().accountId)
        val owner = c.postJson("/v1/sesion/dueno", OwnerLoginRequest("dueno@mitienda.com", temp.temporaryPassword)).body<OwnerTokenResponse>()
        val devices = c.get("/v1/equipos") { header("Authorization", "Bearer ${owner.token}") }.body<List<Device>>()
        assertEquals(2, devices.size)
        assertEquals(HttpStatusCode.OK, c.postJson("/v1/equipos/${creds.deviceId}/quitar", Message(), owner.token).status)
        assertEquals(HttpStatusCode.Unauthorized, c.postJson("/v1/sesion/ficha", TokenRequest(creds.deviceId, creds.deviceSecret)).status)

        // Respaldo del negocio: base completa y sana.
        val f = server.tenants.backup(creds.businessId)
        assertTrue(server.tenants.integrityOk(f))
        assertNotNull(server.tenants.latestBackup(creds.businessId))
        assertTrue(server.control.adminActivity().any { it.first == "Restableció una contraseña" })
    }
}
