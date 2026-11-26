package com.vitrinapos.fiscal

import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import javax.security.auth.x500.X500Principal
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Firma XML para los e-CF de la DGII, como pide el instructivo "Firmado de e-CF":
 * firma envolvente (Reference URI=""), canonicalización C14N 1.0 inclusiva,
 * digest SHA-256 y firma RSA-SHA256, con el certificado X.509 dentro de KeyInfo.
 *
 * La canonicalización está hecha aquí mismo (como en el ejemplo de la DGII) porque Android
 * no trae javax.xml.crypto. Las pruebas de escritorio la verifican con el validador del JDK.
 */
object XmlSigner {
    const val DSIG_NS = "http://www.w3.org/2000/09/xmldsig#"
    const val C14N = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315"
    const val RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"
    const val ENVELOPED = "http://www.w3.org/2000/09/xmldsig#enveloped-signature"
    const val SHA256 = "http://www.w3.org/2001/04/xmlenc#sha256"
    private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"

    data class Signed(val xml: String, val signatureValue: String) {
        /** Código de seguridad de la representación impresa: los primeros 6 caracteres del SignatureValue. */
        val securityCode: String get() = signatureValue.take(6)
    }

    /** Firma el documento [xml] (sin firma) y devuelve el XML firmado. */
    fun sign(xml: String, key: PrivateKey, cert: X509Certificate): Signed {
        val doc = parse(xml)
        val root = doc.documentElement
        // 1. Digest del documento sin la firma (la transformación "enveloped" la quita al verificar).
        val digest = b64(sha256(canonicalize(root).toByteArray(Charsets.UTF_8)))
        // 2. Se arma la firma dentro del documento para canonicalizar SignedInfo con sus espacios de nombres.
        val sig = doc.createElementNS(DSIG_NS, "Signature").also { it.setAttributeNS(XMLNS_NS, "xmlns", DSIG_NS) }
        val si = el(doc, sig, "SignedInfo")
        el(doc, si, "CanonicalizationMethod").setAttribute("Algorithm", C14N)
        el(doc, si, "SignatureMethod").setAttribute("Algorithm", RSA_SHA256)
        val ref = el(doc, si, "Reference").also { it.setAttribute("URI", "") }
        el(doc, el(doc, ref, "Transforms"), "Transform").setAttribute("Algorithm", ENVELOPED)
        el(doc, ref, "DigestMethod").setAttribute("Algorithm", SHA256)
        el(doc, ref, "DigestValue").textContent = digest
        val sv = el(doc, sig, "SignatureValue")
        el(doc, el(doc, el(doc, sig, "KeyInfo"), "X509Data"), "X509Certificate").textContent = b64(cert.encoded)
        root.appendChild(sig)
        // 3. Firma RSA-SHA256 de SignedInfo canonicalizado.
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(key)
        signer.update(canonicalize(si).toByteArray(Charsets.UTF_8))
        val value = b64(signer.sign())
        sv.textContent = value
        return Signed("<?xml version=\"1.0\" encoding=\"utf-8\"?>" + canonicalize(root), value)
    }

    /** Verificación propia (misma regla que usa la DGII): digest y firma. */
    fun verify(signedXml: String): Boolean = runCatching {
        val doc = parse(signedXml)
        val root = doc.documentElement
        val sig = root.getElementsByTagNameNS(DSIG_NS, "Signature").item(0) as Element
        val si = sig.getElementsByTagNameNS(DSIG_NS, "SignedInfo").item(0) as Element
        val digest = (si.getElementsByTagNameNS(DSIG_NS, "DigestValue").item(0) as Element).textContent.trim()
        val value = (sig.getElementsByTagNameNS(DSIG_NS, "SignatureValue").item(0) as Element).textContent.trim()
        val certB64 = (sig.getElementsByTagNameNS(DSIG_NS, "X509Certificate").item(0) as Element).textContent.trim()
        val cert = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.getMimeDecoder().decode(certB64))) as X509Certificate
        val siC14n = canonicalize(si)
        sig.parentNode.removeChild(sig)
        if (b64(sha256(canonicalize(root).toByteArray(Charsets.UTF_8))) != digest) return false
        Signature.getInstance("SHA256withRSA").run {
            initVerify(cert.publicKey)
            update(siC14n.toByteArray(Charsets.UTF_8))
            verify(Base64.getMimeDecoder().decode(value))
        }
    }.getOrDefault(false)

    // ---------- C14N 1.0 inclusiva, sin comentarios ----------

    /** Canonicaliza [node] y su contenido, incluyendo los espacios de nombres que hereda de sus ancestros. */
    fun canonicalize(node: Element): String {
        val inherited = LinkedHashMap<String, String>()
        var p = node.parentNode
        val chain = mutableListOf<Element>()
        while (p is Element) { chain.add(0, p); p = p.parentNode }
        for (a in chain) nsDecls(a).forEach { (k, v) -> inherited[k] = v }
        val sb = StringBuilder()
        // En el elemento de arriba se escriben todos los espacios de nombres que tiene en alcance.
        writeElement(node, sb, rendered = emptyMap(), inScope = inherited)
        return sb.toString()
    }

    private fun nsDecls(e: Element): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        val attrs = e.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i) as Attr
            if (a.namespaceURI == XMLNS_NS || a.name == "xmlns" || a.name.startsWith("xmlns:")) {
                val prefix = if (a.name == "xmlns") "" else a.name.removePrefix("xmlns:")
                m[prefix] = a.value
            }
        }
        return m
    }

    private fun writeElement(e: Element, sb: StringBuilder, rendered: Map<String, String>, inScope: Map<String, String>) {
        val scope = LinkedHashMap(inScope).apply { putAll(nsDecls(e)) }
        // Espacios de nombres a escribir: los que están en alcance y no se escribieron igual en un ancestro.
        val toRender = scope.filter { (k, v) ->
            val prev = rendered[k]
            if (k == "") (prev ?: "") != v else prev != v
        }.filterNot { (k, v) -> k == "" && v == "" && rendered[""] == null }
        val nowRendered = LinkedHashMap(rendered).apply { putAll(toRender) }
        val name = e.tagName
        sb.append('<').append(name)
        toRender.toSortedMap().forEach { (k, v) ->
            sb.append(' ').append(if (k == "") "xmlns" else "xmlns:$k").append("=\"").append(escAttr(v)).append('"')
        }
        val attrs = (0 until e.attributes.length).map { e.attributes.item(it) as Attr }
            .filterNot { it.namespaceURI == XMLNS_NS || it.name == "xmlns" || it.name.startsWith("xmlns:") }
            .sortedWith(compareBy<Attr>({ it.namespaceURI ?: "" }, { it.localName ?: it.name }))
        attrs.forEach { sb.append(' ').append(it.name).append("=\"").append(escAttr(it.value)).append('"') }
        sb.append('>')
        var c = e.firstChild
        while (c != null) {
            when (c.nodeType) {
                Node.ELEMENT_NODE -> writeElement(c as Element, sb, nowRendered, scope)
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sb.append(escText(c.nodeValue))
                Node.ENTITY_REFERENCE_NODE -> sb.append(escText(c.textContent))
                Node.PROCESSING_INSTRUCTION_NODE -> sb.append("<?").append(c.nodeName).let { b ->
                    val d = c.nodeValue; if (!d.isNullOrEmpty()) b.append(' ').append(d); b.append("?>")
                }
                // Los comentarios se omiten (C14N sin comentarios).
            }
            c = c.nextSibling
        }
        sb.append("</").append(name).append('>')
    }

    private fun escText(s: String) = buildString(s.length) {
        for (ch in s) when (ch) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '\r' -> append("&#xD;"); else -> append(ch) }
    }

    private fun escAttr(s: String) = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '"' -> append("&quot;")
            '\t' -> append("&#x9;"); '\n' -> append("&#xA;"); '\r' -> append("&#xD;"); else -> append(ch)
        }
    }

    private fun el(doc: Document, parent: Element, name: String): Element =
        doc.createElementNS(DSIG_NS, name).also { parent.appendChild(it) }

    fun parse(xml: String): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Sin DTD ni entidades externas.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}

/** Certificado digital cargado desde el archivo .p12 / .pfx. */
class FiscalCertificate(val key: PrivateKey, val cert: X509Certificate) {
    val subject: X500Principal get() = cert.subjectX500Principal
    private val fields: Map<String, String> by lazy { dnFields(subject) }
    private val issuerFields: Map<String, String> by lazy { dnFields(cert.issuerX500Principal) }

    /** Nombre del titular (CN). */
    val holder: String get() = fields["CN"] ?: subject.name
    /** Identificación del titular (campo SERIALNUMBER / SN del certificado): cédula, RNC o pasaporte. */
    val holderId: String get() = (fields["SERIALNUMBER"] ?: fields["SN"] ?: "").let { raw -> raw.filter(Char::isDigit).ifEmpty { raw } }
    val issuer: String get() = issuerFields["O"] ?: issuerFields["CN"] ?: cert.issuerX500Principal.name
    val notBefore: Long get() = cert.notBefore.time
    val notAfter: Long get() = cert.notAfter.time
    fun isValidAt(t: Long) = t in notBefore..notAfter
    /** Persona física: la identificación es una cédula (11 dígitos) o un pasaporte, no un RNC de empresa. */
    val looksLikePerson: Boolean get() = holderId.length != 9

    fun sign(xml: String) = XmlSigner.sign(xml, key, cert)

    companion object {
        /** Abre el .p12 con su contraseña. Lanza un error claro si la contraseña o el archivo no sirven. */
        fun load(input: InputStream, password: CharArray, fallback: java.security.Provider? = null): FiscalCertificate {
            val bytes = input.readBytes()
            // Primero con el lector del sistema; si no puede (p. ej. .p12 con AES en Android viejo), con el de respaldo.
            val ks = (listOf<java.security.Provider?>(null) + listOfNotNull(fallback)).firstNotNullOfOrNull { prov ->
                runCatching {
                    (if (prov == null) KeyStore.getInstance("PKCS12") else KeyStore.getInstance("PKCS12", prov))
                        .apply { load(ByteArrayInputStream(bytes), password) }
                }.getOrNull()
            } ?: throw IllegalArgumentException("No se pudo abrir el certificado: la contraseña no es correcta o el archivo no es un .p12 válido.")
            for (alias in ks.aliases().toList()) {
                if (!ks.isKeyEntry(alias)) continue
                val key = ks.getKey(alias, password) as? PrivateKey ?: continue
                val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
                return FiscalCertificate(key, cert)
            }
            throw IllegalArgumentException("El archivo no trae la llave privada para firmar.")
        }

        fun dnFields(p: X500Principal): Map<String, String> {
            val dn = p.getName(X500Principal.RFC2253, mapOf("2.5.4.5" to "SERIALNUMBER", "2.5.4.4" to "SURNAME", "2.5.4.42" to "GIVENNAME"))
            return splitDn(dn).mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim().uppercase() to part.substring(i + 1).trim().replace("\\,", ",").replace("\\+", "+")
            }.toMap()
        }

        private fun splitDn(dn: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var esc = false
            for (ch in dn) {
                when {
                    esc -> { sb.append('\\').append(ch); esc = false }
                    ch == '\\' -> esc = true
                    ch == ',' || ch == '+' -> { out += sb.toString(); sb.clear() }
                    else -> sb.append(ch)
                }
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }
    }
}
