package com.vitrinapos

import com.vitrinapos.fiscal.DgiiEnv
import com.vitrinapos.fiscal.Ecf
import com.vitrinapos.fiscal.EcfBuyer
import com.vitrinapos.fiscal.EcfDraft
import com.vitrinapos.fiscal.EcfEmitter
import com.vitrinapos.fiscal.EcfIssued
import com.vitrinapos.fiscal.EcfLine
import com.vitrinapos.fiscal.EcfPay
import com.vitrinapos.fiscal.EcfPayment
import com.vitrinapos.fiscal.EcfReference
import com.vitrinapos.fiscal.EcfType
import com.vitrinapos.fiscal.FiscalCertificate
import com.vitrinapos.fiscal.XmlSigner
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cada tipo de e-CF se arma, se firma y se valida contra el XSD oficial de la DGII. */
class EcfTest {
    companion object {
        val cert: FiscalCertificate by lazy {
            val pw = "clave-de-prueba".toCharArray()
            val p12 = File(Files.createTempDirectory("c").toFile(), "c.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "f", "-keyalg", "RSA", "-keysize", "2048", "-dname",
                "CN=MARIA PEREZ, SERIALNUMBER=00113918205, C=DO", "-validity", "365", "-storetype", "PKCS12",
                "-keystore", p12.path, "-storepass", String(pw), "-keypass", String(pw)).redirectErrorStream(true).start()
            p.inputStream.readBytes(); check(p.waitFor() == 0)
            p12.inputStream().use { FiscalCertificate.load(it, pw) }
        }

        fun validate(xsd: String, xml: String) {
            val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(EcfTest::class.java.getResource("/dgii-xsd/$xsd")!!)
            schema.newValidator().validate(StreamSource(StringReader(xml)))
        }
    }

    private val emitter = EcfEmitter("101010632", "MI TIENDA & ACCESORIOS SRL", "Mi Tienda", "Av. Principal #24, Santo Domingo", "809-555-0100", "ventas@mitienda.do")
    private val now = 1_795_700_000_000L
    private val lines = listOf(
        EcfLine("Cargador USB-C 20 W", "CRG-020", 2.0, 45000, taxable = true),
        EcfLine("Cable USB-C <1 m>", "CBL-001", 1.0, 35050, taxable = true),
        EcfLine("Reparación de pantalla", "", 1.0, 150000, taxable = true, service = true),
        EcfLine("Libro (exento)", "", 3.0, 12333, taxable = false),
    )

    private fun draft(type: EcfType, n: Long = 1, buyer: EcfBuyer = EcfBuyer(), ref: EcfReference? = null, incl: Boolean = true) = EcfDraft(
        type = type, encf = Ecf.encf(type, n), emitter = emitter, buyer = buyer,
        lines = Ecf.spreadDiscount(lines, 5000), pricesIncludeTax = incl, issuedAt = now,
        sequenceExpires = now + 400L * 86_400_000L, payments = listOf(EcfPayment(EcfPay.CASH, 300000), EcfPayment(EcfPay.CARD, 31049)),
        reference = ref,
    )

    private fun signAndCheck(xsd: String, xml: String): XmlSigner.Signed {
        val s = cert.sign(xml)
        validate(xsd, s.xml)
        assertTrue(XmlSigner.verify(s.xml))
        return s
    }

    @Test
    fun todosLosTiposPasanElXsdOficial() {
        val rncBuyer = EcfBuyer("101010632", "FERRETERÍA LA ESQUINA SRL")
        signAndCheck("ecf32.xsd", Ecf.xml(draft(EcfType.CONSUMO), now))
        signAndCheck("ecf32.xsd", Ecf.xml(draft(EcfType.CONSUMO, buyer = EcfBuyer("00113918205", "María Pérez")), now))
        signAndCheck("ecf31.xsd", Ecf.xml(draft(EcfType.CREDITO_FISCAL, buyer = rncBuyer), now))
        signAndCheck("ecf31.xsd", Ecf.xml(draft(EcfType.CREDITO_FISCAL, buyer = rncBuyer, incl = false), now))
        signAndCheck("ecf45.xsd", Ecf.xml(draft(EcfType.GUBERNAMENTAL, buyer = rncBuyer), now))
        val ref = EcfReference(Ecf.encf(EcfType.CONSUMO, 1), now - 40L * 86_400_000L, 1, "Devolución del cliente")
        signAndCheck("ecf34.xsd", Ecf.xml(draft(EcfType.NOTA_CREDITO, ref = ref), now))
        signAndCheck("ecf33.xsd", Ecf.xml(draft(EcfType.NOTA_DEBITO, buyer = rncBuyer, ref = ref.copy(code = 3)), now))
        // Resumen de consumo con el código de seguridad de la factura.
        val ecf = signAndCheck("ecf32.xsd", Ecf.xml(draft(EcfType.CONSUMO, 7), now))
        signAndCheck("rfce32.xsd", Ecf.rfce(draft(EcfType.CONSUMO, 7), ecf.securityCode))
        // Anulación de números sin usar.
        signAndCheck("anecf.xsd", Ecf.anecf(emitter.rnc, listOf(Triple(EcfType.CONSUMO, "E320000000050", "E320000000099")), now))
    }

    @Test
    fun cuadraturaDeMontos() {
        val d = draft(EcfType.CONSUMO)
        val t = Ecf.totals(d)
        // Lo que paga el cliente: líneas menos descuento.
        assertEquals(lines.sumOf { it.gross } - 5000, t.total)
        assertEquals(5000, d.lines.sumOf { it.discount })
        // ITBIS = base * 18 % dentro de la tolerancia (±1 peso por línea).
        assertTrue(abs(t.itbis - t.taxedBase * 0.18) < 1.0)
        assertEquals(t.total, t.taxedBase + t.itbis + t.exempt)
        // Precios sin ITBIS: se suma aparte.
        val e = Ecf.totals(draft(EcfType.CREDITO_FISCAL, incl = false))
        assertEquals(e.taxedIncl + e.itbis + e.exempt, e.total)
        // Montos con el formato de la DGII.
        assertEquals("1234.05", Ecf.money(123405)); assertEquals("0.07", Ecf.money(7))
        assertEquals("E320000000123", Ecf.encf(EcfType.CONSUMO, 123))
        assertEquals("101010632E320000000123.xml", Ecf.fileName("101010632", "E320000000123"))
    }

    @Test
    fun reglasDelComprador() {
        val big = draft(EcfType.CONSUMO).copy(lines = listOf(EcfLine("Laptop", "", 1.0, 30_000_000, true)))
        assertTrue(runCatching { Ecf.xml(big, now) }.exceptionOrNull()!!.message!!.contains("250,000"))
        assertTrue(runCatching { Ecf.xml(draft(EcfType.CREDITO_FISCAL), now) }.isFailure)
    }

    @Test
    fun enlaceDelQr() {
        val small = EcfIssued(32, "E320000000123", "", "Ab+3xY", now, 177000)
        val q1 = Ecf.qrUrl(DgiiEnv.PROD, "101010632", small, now)
        assertTrue(q1.startsWith("https://fc.dgii.gov.do/ecf/ConsultaTimbreFC?RncEmisor=101010632&ENCF=E320000000123&MontoTotal=1770.00&CodigoSeguridad=Ab%2B3xY"), q1)
        val e31 = small.copy(type = 31, encf = "E310000000045", buyerRnc = "101010632")
        val q2 = Ecf.qrUrl(DgiiEnv.TEST, "101010632", e31, now)
        assertTrue(q2.startsWith("https://ecf.dgii.gov.do/testecf/ConsultaTimbre?RncEmisor=101010632&RncComprador=101010632&ENCF=E310000000045"), q2)
        assertTrue("FechaFirma=" in q2 && "%20" in q2)
    }
}
