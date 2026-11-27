package com.vitrinapos.server

import com.vitrinapos.data.CartLine
import com.vitrinapos.data.PaymentMethods
import com.vitrinapos.data.ProductDraft
import com.vitrinapos.data.Repository
import com.vitrinapos.net.CloudClient
import com.vitrinapos.net.DeviceCredentials
import com.vitrinapos.net.HttpStatusException
import com.vitrinapos.net.LoginRequest
import com.vitrinapos.net.OwnerLoginRequest
import com.vitrinapos.net.SignupRequest
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** La app (núcleo + CloudClient) contra el servidor de verdad, por HTTP, con dos equipos. */
class CloudEndToEndTest {
    private val baseUrl = System.getenv("TEST_DATABASE_URL")!!
    private lateinit var dbName: String
    private lateinit var server: VitrinaServer
    private lateinit var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var url: String
    private val clients = mutableListOf<Pair<Repository, CloudClient?>>()

    @BeforeTest
    fun setUp() {
        dbName = "vt_" + Secure.digits(10)
        DriverManager.getConnection(baseUrl).use { it.createStatement().execute("CREATE DATABASE $dbName") }
        val jdbc = baseUrl.replace(Regex("/[A-Za-z0-9_]+\\?"), "/$dbName?")
        val cfg = Config(jdbc, Files.createTempDirectory("nube").toFile(), Secure.bytes(32), Secure.bytes(32), "admin-x")
        server = VitrinaServer(cfg, ControlStore(jdbc), Tenants(cfg.dataDir, cfg.masterKey), LogMailer())
        val port = ServerSocket(0).use { it.localPort }
        engine = embeddedServer(Netty, port = port, host = "127.0.0.1") { vitrinaApi(server) }.start(wait = false)
        url = "http://127.0.0.1:$port"
    }

    @AfterTest
    fun tearDown() {
        clients.forEach { (r, c) -> c?.stop(); runCatching { r.close() } }
        engine.stop(100, 1000)
        server.tenants.close(); server.control.close()
        DriverManager.getConnection(baseUrl).use { it.createStatement().execute("DROP DATABASE IF EXISTS $dbName") }
    }

    /** Un equipo de la tienda: su propia carpeta y su base local, con el id que le dio la nube. */
    private fun device(creds: DeviceCredentials, base: String = url): Pair<Repository, CloudClient> {
        val dir = Files.createTempDirectory("equipo").toFile()
        Repository(ServerPlatform(dir, Secure.bytes(32))).apply { metaPut("deviceId", creds.deviceId); metaPut("role", "cloud"); close() }
        val repo = Repository(ServerPlatform(dir, Secure.bytes(32)))
        assertEquals(creds.deviceId, repo.deviceId)
        val c = CloudClient(repo, base, creds.deviceId, creds.deviceSecret)
        repo.remote = c
        clients += repo to c
        return repo to c
    }

    private suspend fun waitFor(what: String, check: () -> Boolean) {
        withTimeout(15_000) { while (!check()) delay(100) }.also { assertTrue(check(), what) }
    }

    @Test
    fun dosEquiposVentaSinConexionYMigracion(): Unit = runBlocking {
        val creds = CloudClient.signup(url, SignupRequest("dueno@tienda.com", "Clave2026", "Bladimir", "Mi Tienda", "PHONES", "1234", "Caja 1", "Android"))
        val (a, ca) = device(creds)
        ca.pull()
        assertEquals("pro", ca.plan.value?.plan)
        assertEquals("Mi Tienda", a.settings.value.businessName.ifBlank { a.db.settingQueries.selectAll().executeAsList().first { it.key == "businessName" }.content })
        val admin = a.db.appUserQueries.selectAll().executeAsList().single()
        a.currentUser.value = admin

        // Sin entrar con el PIN en la nube, el servidor no deja operar.
        val noPin = runCatching { a.openCashSession(100000) }.exceptionOrNull()
        assertTrue(noPin is HttpStatusException && noPin.code == 401 && noPin.message!!.contains("PIN"), "$noPin")
        assertTrue(ca.loginUser(admin.id, "1234"))
        assertTrue(!ca.loginUser(admin.id, "0000"))
        assertTrue(ca.loginUser(admin.id, "1234"))

        // Caja 1: abre caja, crea un producto y vende; todo pasa por la nube.
        a.openCashSession(100000)
        val pid = a.saveProduct(ProductDraft(null, "Cargador USB-C", "CRG-1", null, null, 30000, 85000, 10.0, 1.0, "und", true, true, null, ""))
        waitFor("el producto llega a la copia local") { a.db.productQueries.selectById(pid).executeAsOneOrNull() != null }
        val p = a.db.productQueries.selectById(pid).executeAsOne()
        a.recordSale(listOf(CartLine.of(p, 2.0)), null, 0, PaymentMethods.CASH, 200000, null)
        assertEquals(8.0, server.tenants.repo(creds.businessId).db.productQueries.selectById(pid).executeAsOne().stock)

        // Caja 2: el dueño la autoriza con su correo; recibe todo.
        val creds2 = CloudClient.login(url, LoginRequest("dueno@tienda.com", "Clave2026", "Caja 2", "Windows"))
        val (b, cb) = device(creds2)
        cb.start()
        waitFor("la caja 2 recibe la venta y el stock") {
            b.db.saleQueries.selectRecent(10).executeAsList().size == 1 && b.db.productQueries.selectById(pid).executeAsOneOrNull()?.stock == 8.0
        }
        b.currentUser.value = b.db.appUserQueries.selectAll().executeAsList().single()
        assertTrue(cb.loginUser(admin.id, "1234"))
        b.openCashSession(50000)

        // Sin conexión: la caja 2 vende con su copia y lo envía al volver la red.
        val offline = CloudClient(b, "http://127.0.0.1:1", creds2.deviceId, creds2.deviceSecret)
        b.remote = offline
        val pb = b.db.productQueries.selectById(pid).executeAsOne()
        val provisional = b.recordSale(listOf(CartLine.of(pb, 1.0)), null, 0, PaymentMethods.CASH, 100000, null)
        assertTrue(provisional < 0, "venta provisional")
        assertEquals(1L, b.pendingSales.value.let { b.db.syncQueries.outboxCount().executeAsOne() })
        b.remote = cb
        cb.flushOutbox()
        assertEquals(7.0, server.tenants.repo(creds.businessId).db.productQueries.selectById(pid).executeAsOne().stock)
        waitFor("la caja 1 ve la venta sin conexión") { a.db.saleQueries.selectRecent(10).executeAsList().size == 2.also { runBlocking { ca.pull() } } }
        // El servidor anotó en la actividad el usuario y el equipo verificados.
        assertTrue(server.tenants.repo(creds.businessId).activity(0, Long.MAX_VALUE).any { it.action == "Abrió la caja" && it.device == "Caja 2" })

        // El dueño quita la caja 2: deja de tener acceso.
        val ownerTok = CloudClient.ownerLogin(url, OwnerLoginRequest("dueno@tienda.com", "Clave2026")).token
        CloudClient.http(url, "POST", "/v1/equipos/${creds2.deviceId}/quitar", "{}".toByteArray(), "application/json", ownerTok, null, 10_000)
        val denied = runCatching { CloudClient(b, url, creds2.deviceId, creds2.deviceSecret).ensureDeviceToken() }.exceptionOrNull()
        assertTrue(denied is HttpStatusException && denied.code == 401)

        // "Pasar mis datos a la nube": una tienda que trabajaba sola sube su base a una cuenta nueva.
        val localDir = Files.createTempDirectory("tienda-sola").toFile()
        val local = Repository(ServerPlatform(localDir, Secure.bytes(32)))
        local.saveProduct(ProductDraft(null, "Funda A54", "FND-1", null, null, 10000, 30000, 5.0, 1.0, "und", true, true, null, ""))
        local.saveProduct(ProductDraft(null, "Mica 13", "MIC-1", null, null, 5000, 25000, 3.0, 1.0, "und", true, true, null, ""))
        local.saveUser(null, "Ana", "4321", com.vitrinapos.data.Roles.ADMIN, true)
        local.checkpoint()
        val dbFile = File(localDir, "negocio.db")
        val creds3 = CloudClient.signup(url, SignupRequest("tienda2@x.com", "Clave2026", "Ana", "Tienda Dos", "COLMADO", "4321", "Celular", "Android"))
        val owner2 = CloudClient.ownerLogin(url, OwnerLoginRequest("tienda2@x.com", "Clave2026"))
        CloudClient.uploadDatabase(url, owner2.token, dbFile)
        // Una segunda subida ya no se acepta (no pisa datos de la nube).
        val again = runCatching { CloudClient.uploadDatabase(url, owner2.token, dbFile) }.exceptionOrNull()
        assertTrue(again is HttpStatusException && again.code == 409)
        val (d, cd) = device(creds3)
        cd.pull()
        assertEquals(setOf("FND-1", "MIC-1"), d.db.productQueries.selectActive().executeAsList().map { it.code }.toSet())
        assertEquals("Ana", d.db.appUserQueries.selectAll().executeAsList().single().name)
        local.close()
    }
}
