package com.vitrinapos

import com.vitrinapos.fiscal.FiscalCertificate
import com.vitrinapos.fiscal.TaxId
import com.vitrinapos.fiscal.XmlSigner
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.xml.crypto.dsig.XMLSignature
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Firma de e-CF: se firma con XmlSigner y se verifica con el validador XMLDSig del JDK (independiente). */
class FiscalTest {
    private val password = "clave-de-prueba".toCharArray()

    /** Certificado de prueba hecho con keytool, con la cédula en SERIALNUMBER como los de las entidades. */
    private val cert: FiscalCertificate by lazy {
        val dir = Files.createTempDirectory("cert").toFile()
        val p12 = File(dir, "prueba.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "firma", "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA",
            "-dname", "CN=MARIA PEREZ RODRIGUEZ, SERIALNUMBER=IDCDO-00113918205, O=ENTIDAD DE PRUEBA, C=DO",
            "-validity", "365", "-storetype", "PKCS12", "-keystore", p12.path, "-storepass", String(password), "-keypass", String(password),
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { out }
        p12.inputStream().use { FiscalCertificate.load(it, password) }
    }

    /** Semilla tal como la devolvió el ambiente de pruebas de la DGII (con sangría y espacios de nombres). */
    private val semilla = """<?xml version="1.0" encoding="utf-8"?>
<SemillaModel xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <valor>gCy6SC8MDE9WjzBJPyOpsddMGyc34noImFcCsBHkoTTfQKofT+nn3x/medTosps0qLhT0/2l5+buC34stwfl5foul/6scTP6mKBhVbYfduCUstBnZcSYYalYWFk7t/fufmF4zpuDxaaIXIrXgfibYA==</valor>
  <fecha>2026-11-25T22:25:07.5729917-04:00</fecha>
</SemillaModel>"""

    private val ecf = """<?xml version="1.0" encoding="utf-8"?><ECF><Encabezado><Version>1.0</Version><IdDoc><TipoeCF>32</TipoeCF><eNCF>E320000000123</eNCF></IdDoc><Emisor><RNCEmisor>101010632</RNCEmisor><RazonSocialEmisor>MI TIENDA &amp; ACCESORIOS SRL</RazonSocialEmisor></Emisor><Totales><MontoTotal>1770.00</MontoTotal></Totales></Encabezado><DetallesItems><Item><NumeroLinea>1</NumeroLinea><NombreItem>Cargador "rápido" &lt;20 W&gt;</NombreItem></Item></DetallesItems><FechaHoraFirma>26-11-2026 10:42:15</FechaHoraFirma></ECF>"""

    /** Verificación con la implementación estándar de Java (Apache Santuario dentro del JDK). */
    private fun jdkVerify(xml: String): Boolean {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val nl = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature")
        assertEquals(1, nl.length, "una sola firma")
        val ctx = DOMValidateContext(cert.cert.publicKey, nl.item(0))
        val sig = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx)
        // Que la firma use exactamente los algoritmos que pide la DGII.
        assertEquals(XmlSigner.C14N, sig.signedInfo.canonicalizationMethod.algorithm)
        assertEquals(XmlSigner.RSA_SHA256, sig.signedInfo.signatureMethod.algorithm)
        return sig.validate(ctx)
    }

    @Test
    fun firmaSemillaRealYeCF() {
        for (xml in listOf(semilla, ecf)) {
            val signed = cert.sign(xml)
            assertTrue(jdkVerify(signed.xml), "el JDK acepta la firma:\n${signed.xml.take(400)}")
            assertTrue(XmlSigner.verify(signed.xml), "verificación propia")
            assertEquals(6, signed.securityCode.length)
            assertTrue(signed.xml.trimEnd().endsWith("</Signature></${if (xml === semilla) "SemillaModel" else "ECF"}>"), "la firma va al final")
            // Cambiar un solo carácter invalida la firma.
            val tampered = signed.xml.replace("E320000000123", "E320000000124").replace("gCy6SC8", "gCy6SC9")
            assertFalse(jdkVerify(tampered))
            assertFalse(XmlSigner.verify(tampered))
        }
        System.getProperty("fiscalOut")?.let { File(it).apply { mkdirs() }.resolve("semilla-firmada.xml").writeText(cert.sign(semilla).xml) }
    }

    @Test
    fun datosDelCertificado() {
        assertEquals("MARIA PEREZ RODRIGUEZ", cert.holder)
        assertEquals("00113918205", cert.holderId)
        assertEquals("ENTIDAD DE PRUEBA", cert.issuer)
        assertTrue(cert.looksLikePerson)
        assertTrue(cert.isValidAt(System.currentTimeMillis()))
        // Contraseña equivocada: mensaje claro.
        val dir = Files.createTempDirectory("c2").toFile()
        val msg = runCatching { FiscalCertificate.load(ByteArrayInputStream(ByteArray(10)), "x".toCharArray()) }.exceptionOrNull()?.message
        assertTrue(msg!!.contains("contraseña"), msg)
        dir.delete()
    }

    @Test
    fun rncYCedula() {
        assertTrue(TaxId.check("101-01063-2").valid)          // RNC de la DGII
        assertEquals(TaxId.Kind.RNC, TaxId.check("101010632").kind)
        assertFalse(TaxId.check("101010633").valid)
        assertTrue(TaxId.check("001-1391820-5").valid)
        assertFalse(TaxId.check("00113918206").valid)
        assertEquals("1-01-01063-2", TaxId.check("101010632").formatted)
        assertEquals("001-1391820-5", TaxId.check("00113918205").formatted)
        assertFalse(TaxId.check("1234").valid)
    }

    /** Solo con -DdgiiLive: pide una semilla real a TesteCF, la firma y la envía. Con un certificado de prueba la DGII debe rechazar el certificado, no el formato. */
    @Test
    fun semillaContraTesteCF() {
        if (System.getProperty("dgiiLive") == null) return
        val base = "https://ecf.dgii.gov.do/testecf/autenticacion/api/Autenticacion"
        val seed = java.net.URL("$base/Semilla").readText()
        val signed = cert.sign(seed).xml
        val boundary = "----vitrina" + System.nanoTime()
        val body = ("--$boundary\r\nContent-Disposition: form-data; name=\"xml\"; filename=\"semilla.xml\"\r\nContent-Type: text/xml\r\n\r\n" + signed + "\r\n--$boundary--\r\n").toByteArray()
        val c = java.net.URL("$base/ValidarSemilla").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        println("DGII ValidarSemilla -> HTTP $code: $text")
        System.getProperty("fiscalOut")?.let { File(it).apply { mkdirs() }.resolve("dgii-respuesta.txt").writeText("HTTP $code\n$text") }
    }
}
