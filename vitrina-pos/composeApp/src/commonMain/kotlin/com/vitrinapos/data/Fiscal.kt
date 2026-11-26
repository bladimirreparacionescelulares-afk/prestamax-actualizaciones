package com.vitrinapos.data

import com.vitrinapos.db.EcfDoc
import com.vitrinapos.db.FiscalSequence
import com.vitrinapos.db.Sale
import com.vitrinapos.db.SaleItem
import com.vitrinapos.fiscal.DgiiClient
import com.vitrinapos.fiscal.DgiiEnv
import com.vitrinapos.fiscal.DgiiException
import com.vitrinapos.fiscal.DgiiStatus
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
import com.vitrinapos.fiscal.TaxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.util.Base64

/** Comprobante que pide el cliente al cobrar. */
@Serializable
data class FiscalChoice(val type: Int = 32, val rnc: String = "", val name: String = "")

/** Estados de un e-CF dentro de la app. */
object EcfStatus {
    const val SIGNED = "SIGNED"          // firmado, falta enviarlo a la DGII
    const val SENT = "SENT"              // enviado, esperando resultado
    const val ACCEPTED = "ACCEPTED"
    const val CONDITIONAL = "CONDITIONAL"
    const val REJECTED = "REJECTED"
    const val ERROR = "ERROR"            // la DGII no lo recibió y hay que revisarlo

    fun label(s: String) = when (s) {
        SIGNED -> "Por enviar"; SENT -> "En proceso"; ACCEPTED -> "Aceptado"; CONDITIONAL -> "Aceptado condicional"
        REJECTED -> "Rechazado"; ERROR -> "Con error"; else -> s
    }
    fun isFinal(s: String) = s == ACCEPTED || s == CONDITIONAL || s == REJECTED
}

class FiscalException(message: String) : IllegalStateException(message)

/** Pasos del trámite que el negocio va marcando como hechos. */
object FiscalSteps {
    const val CERT_BOUGHT = "cert"
    const val OFV_ACCESS = "ofv"
    const val SIGNER_ROLE = "firmante"
    const val REQUEST_SENT = "solicitud"
    const val TESTS_DONE = "pruebas"
    const val SWORN = "declaracion"
    const val AUTHORIZED = "autorizado"
    fun parse(s: String) = s.split(',').filter { it.isNotBlank() }.toSet()
    fun format(set: Set<String>) = set.sorted().joinToString(",")
}

/**
 * Facturación electrónica: certificado de este equipo, números e-NCF, emisión y firma de e-CF,
 * y envío a la DGII (solo en el equipo principal o en un equipo que trabaja solo).
 */
class Fiscal(private val repo: Repository) {
    private val db get() = repo.db
    private val settings get() = repo.settings.value

    // ---------------- Certificado ----------------

    private var cached: FiscalCertificate? = null
    private val _certInfo = MutableStateFlow<FiscalCertificate?>(null)
    /** Certificado cargado en este equipo (o null). */
    val certificate: StateFlow<FiscalCertificate?> = _certInfo

    init { runCatching { loadStored() } }

    private fun loadStored() {
        val enc = repo.metaGet(CERT_KEY)?.takeIf { it.isNotBlank() } ?: return
        val raw = repo.platform.unprotect(Base64.getDecoder().decode(enc))
        val split = raw.indexOf(0)
        val pw = String(raw, 0, split, Charsets.UTF_8).toCharArray()
        val p12 = raw.copyOfRange(split + 1, raw.size)
        cached = FiscalCertificate.load(ByteArrayInputStream(p12), pw, repo.platform.extraKeyStoreProvider)
        _certInfo.value = cached
    }

    /** Abre el .p12, lo prueba firmando y lo guarda cifrado en este equipo. */
    fun installCertificate(p12: ByteArray, password: CharArray): FiscalCertificate {
        val cert = FiscalCertificate.load(ByteArrayInputStream(p12), password, repo.platform.extraKeyStoreProvider)
        val test = cert.sign("<?xml version=\"1.0\" encoding=\"utf-8\"?><Prueba><Valor>Vitrina POS</Valor></Prueba>")
        if (!com.vitrinapos.fiscal.XmlSigner.verify(test.xml)) throw FiscalException("El certificado no pudo firmar un documento de prueba.")
        val raw = String(password).toByteArray(Charsets.UTF_8) + byteArrayOf(0) + p12
        repo.metaPut(CERT_KEY, Base64.getEncoder().encodeToString(repo.platform.protect(raw)))
        cached = cert
        _certInfo.value = cert
        return cert
    }

    /** Certificado en claro para mandarlo por el canal cifrado de la red a una caja autorizada. */
    fun certificateForDevices(): ByteArray? = repo.metaGet(CERT_KEY)?.takeIf { it.isNotBlank() }?.let {
        runCatching { repo.platform.unprotect(Base64.getDecoder().decode(it)) }.getOrNull()
    }

    fun installShared(raw: ByteArray) {
        val split = raw.indexOf(0)
        installCertificate(raw.copyOfRange(split + 1, raw.size), String(raw, 0, split, Charsets.UTF_8).toCharArray())
    }

    fun removeCertificate() {
        repo.metaPut(CERT_KEY, "")
        cached = null
        _certInfo.value = null
    }

    private fun cert(): FiscalCertificate = cached ?: throw FiscalException("Este equipo no tiene el certificado digital. Agrégalo en Configuración → Facturación electrónica.")

    fun sign(xml: String) = cert().sign(xml)

    // ---------------- Datos del emisor ----------------

    fun emitter(s: AppSettings = settings) = EcfEmitter(
        rnc = TaxId.digits(s.fiscalRnc), name = s.fiscalName.ifBlank { s.businessName },
        tradeName = s.fiscalTradeName.ifBlank { s.businessName.takeIf { it != s.fiscalName } ?: "" },
        address = s.fiscalAddress.ifBlank { s.address }, phone = s.fiscalPhone.ifBlank { s.phone }, email = s.fiscalEmail,
    )

    /** Lo que falta para poder facturar (vacío = listo). */
    fun missing(s: AppSettings = settings): List<String> = buildList {
        val e = emitter(s)
        if (!TaxId.check(e.rnc).valid && !(s.fiscalKind == "FISICA" && e.rnc.length == 11)) add("RNC o cédula del negocio")
        if (e.name.isBlank()) add("Razón social o nombre")
        if (e.address.isBlank()) add("Dirección")
        if (cached == null) add("Certificado digital en este equipo")
        if (s.taxRate != 18.0 && s.taxRate != 16.0 && s.taxRate != 0.0) add("La tasa de ITBIS debe ser 18 %, 16 % o 0 %")
    }

    val active: Boolean get() = settings.fiscalEnabled
    val env: DgiiEnv get() = DgiiEnv.of(settings.fiscalEnv)

    // ---------------- Números e-NCF ----------------

    private fun usedKey(blockId: Long) = "ecf.used.$blockId"

    /** Siguiente número del bloque de este equipo (dentro de una transacción). Pide bloque nuevo si este equipo es el principal. */
    fun takeNumber(type: EcfType, now: Long): Pair<String, Long?> {
        val dev = repo.deviceId
        repeat(2) {
            for (b in db.fiscalQueries.blocksFor(dev, type.code.toLong()).executeAsList()) {
                if (b.expiresAt != null && b.expiresAt < now) continue
                val local = repo.metaGet(usedKey(b.id))?.toLongOrNull() ?: (b.fromNo - 1)
                val used = maxOf(local, usedInDocs(type, b))
                if (used < b.toNo) {
                    repo.metaPut(usedKey(b.id), (used + 1).toString())
                    return Ecf.encf(type, used + 1) to b.expiresAt
                }
            }
            if (repo.remote != null) throw FiscalException("Esta caja no tiene números e-NCF ${type.prefix} libres. Conéctala al equipo principal para recibir más.")
            reserveLocal(type.code, dev, settings.fiscalBlockSize.coerceIn(10, 1000), now)
                ?: throw FiscalException("No quedan números e-NCF ${type.prefix} autorizados. Registra un rango nuevo de la DGII en Facturación electrónica → Secuencias.")
        }
        throw FiscalException("No se pudo tomar un número e-NCF")
    }

    private fun usedInDocs(type: EcfType, b: FiscalSequence): Long {
        // Los e-CF que ya están en la base cuentan como usados aunque el contador local se haya perdido.
        return db.fiscalQueries.maxUsedBetween(type.code.toLong(), b.fromNo, b.toNo).executeAsOneOrNull()?.max ?: (b.fromNo - 1)
    }

    /** Reserva un bloque del rango autorizado (solo en el principal). Devuelve el id del bloque. */
    fun reserveLocal(type: Int, deviceId: String, size: Int, now: Long): Long? {
        for (range in db.fiscalQueries.ranges().executeAsList().filter { it.type == type.toLong() }) {
            if (range.expiresAt != null && range.expiresAt < now) continue
            val next = (db.fiscalQueries.blocksOfRange(range.id).executeAsList().maxOfOrNull { it.toNo } ?: (range.fromNo - 1)) + 1
            if (next > range.toNo) continue
            val to = minOf(range.toNo, next + size - 1)
            db.fiscalQueries.insertSequence(type.toLong(), next, to, range.expiresAt, deviceId, range.id, now)
            return db.fiscalQueries.lastSequenceId().executeAsOne()
        }
        return null
    }

    /** Números libres de este equipo para un tipo. */
    fun freeNumbers(type: EcfType, now: Long = Time.now()): Long =
        db.fiscalQueries.blocksFor(repo.deviceId, type.code.toLong()).executeAsList()
            .filter { it.expiresAt == null || it.expiresAt >= now }
            .sumOf { b -> b.toNo - maxOf(repo.metaGet(usedKey(b.id))?.toLongOrNull() ?: (b.fromNo - 1), usedInDocs(type, b)) }

    // ---------------- Emisión ----------------

    private fun lineOf(l: CartLine) = EcfLine(l.name, l.code, l.qty, l.price, l.taxable, service = l.service || l.productId == null)

    private fun buyerFor(choice: FiscalChoice?, clientId: Long?): EcfBuyer {
        val c = clientId?.let { db.clientQueries.selectById(it).executeAsOneOrNull() }
        val rnc = TaxId.digits(choice?.rnc?.ifBlank { null } ?: c?.docId ?: "")
        val name = choice?.name?.ifBlank { null } ?: c?.name ?: ""
        return if (rnc.length == 9 || rnc.length == 11) EcfBuyer(rnc, name) else EcfBuyer()
    }

    /** Arma y firma el e-CF de una venta (dentro de la transacción de la venta). */
    fun issueForSale(op: Op.RecordSale, total: Long, now: Long): EcfIssued {
        val s = settings
        val type = EcfType.of(op.fiscal?.type ?: 32)
        val buyer = buyerFor(op.fiscal, op.clientId)
        if ((type == EcfType.CREDITO_FISCAL || type == EcfType.GUBERNAMENTAL) && buyer.rnc.isEmpty())
            throw FiscalException("Para ${type.short.lowercase()} hace falta el RNC del cliente.")
        val (encf, expires) = takeNumber(type, now)
        val draft = EcfDraft(
            type = type, encf = encf, emitter = emitter(s), buyer = buyer,
            lines = Ecf.spreadDiscount(op.lines.map(::lineOf), op.discount), pricesIncludeTax = s.pricesIncludeTax, taxRate = s.taxRate,
            issuedAt = now, sequenceExpires = expires, payments = listOf(EcfPayment(EcfPay.fromMethod(op.paymentMethod), total)),
        )
        return issue(draft, now)
    }

    /** Nota de crédito E34 que anula una venta ya facturada. */
    fun issueCreditNote(sale: Sale, items: List<SaleItem>, original: EcfDoc, reason: String, now: Long): EcfIssued {
        val s = settings
        val (encf, _) = takeNumber(EcfType.NOTA_CREDITO, now)
        val lines = items.map { item ->
            val isService = item.productId == null || item.productId.let { db.productQueries.selectById(it).executeAsOneOrNull()?.isService == 1L }
            EcfLine(item.name, item.code, item.qty, item.price, item.taxable == 1L, service = isService)
        }
        val draft = EcfDraft(
            type = EcfType.NOTA_CREDITO, encf = encf, emitter = emitter(s), buyer = EcfBuyer(original.buyerRnc, original.buyerName),
            lines = Ecf.spreadDiscount(lines, sale.discount), pricesIncludeTax = sale.taxIncluded == 1L, taxRate = s.taxRate,
            issuedAt = now, reference = EcfReference(original.encf, original.signedAt, 1, reason.ifBlank { "Anulación de la venta" }),
        )
        return issue(draft, now)
    }

    private fun issue(draft: EcfDraft, now: Long): EcfIssued {
        val signed = sign(Ecf.xml(draft, now))
        val t = Ecf.totals(draft)
        return EcfIssued(
            type = draft.type.code, encf = draft.encf, xml = signed.xml, securityCode = signed.securityCode, signedAt = now,
            total = t.total, buyerRnc = draft.buyer.rnc, buyerName = draft.buyer.name, reference = draft.reference?.encf ?: "",
        ).also { lastDraft = draft }
    }

    /** Último borrador emitido (para armar el resumen RFCE al guardar). */
    private var lastDraft: EcfDraft? = null

    /** Guarda el e-CF emitido (dentro de la transacción). */
    fun store(e: EcfIssued, saleId: Long?, deviceId: String, now: Long) {
        val summary = if (e.type == 32 && e.total < Ecf.RFCE_LIMIT) {
            lastDraft?.takeIf { it.encf == e.encf }?.let { Ecf.rfce(it, e.securityCode) } ?: rfceFromXml(e)
        } else ""
        db.fiscalQueries.insertDoc(
            saleId, e.type.toLong(), e.encf, EcfStatus.SIGNED, e.xml, summary, e.securityCode, e.signedAt, e.total,
            e.buyerRnc, e.buyerName, e.reference, deviceId, settings.fiscalEnv, now, now,
        )
    }

    /** Resumen RFCE a partir del e-CF firmado (cuando lo firmó otra caja). */
    fun rfceFromXml(e: EcfIssued): String {
        val doc = com.vitrinapos.fiscal.XmlSigner.parse(e.xml)
        fun v(tag: String) = doc.getElementsByTagName(tag).item(0)?.textContent ?: ""
        fun cents(tag: String) = v(tag).takeIf { it.isNotBlank() }?.let { java.math.BigDecimal(it).movePointRight(2).toLong() } ?: 0L
        val pays = (0 until doc.getElementsByTagName("FormaDePago").length).map { i ->
            val n = doc.getElementsByTagName("FormaDePago").item(i) as org.w3c.dom.Element
            EcfPayment(n.getElementsByTagName("FormaPago").item(0).textContent.toInt(), java.math.BigDecimal(n.getElementsByTagName("MontoPago").item(0).textContent).movePointRight(2).toLong())
        }
        val taxedBase = cents("MontoGravadoTotal")
        val itbis = cents("TotalITBIS")
        val exempt = cents("MontoExento")
        // Se reconstruyen líneas equivalentes para obtener los mismos totales.
        val lines = buildList {
            if (taxedBase > 0) add(EcfLine("Gravado", "", 1.0, taxedBase + itbis, true))
            if (exempt > 0) add(EcfLine("Exento", "", 1.0, exempt, false))
        }
        val draft = EcfDraft(
            EcfType.CONSUMO, e.encf, EcfEmitter(v("RNCEmisor"), v("RazonSocialEmisor"), address = "-"), EcfBuyer(e.buyerRnc, e.buyerName),
            lines, pricesIncludeTax = true, taxRate = settings.taxRate, issuedAt = e.signedAt, payments = pays,
        )
        return Ecf.rfce(draft, e.securityCode)
    }

    // ---------------- Envío a la DGII ----------------

    private val _sending = MutableStateFlow("")
    /** Último aviso del envío (para la pantalla de comprobantes). */
    val sendingNote: StateFlow<String> = _sending
    private var worker: Job? = null
    /** Transporte hacia la DGII (las pruebas lo reemplazan). */
    var http: DgiiClient.Http = DgiiClient.Http.Default
    private var client: DgiiClient? = null
    private var clientEnv: DgiiEnv? = null

    private fun dgii(): DgiiClient {
        val e = env
        val c = client
        if (c != null && clientEnv == e) return c
        return DgiiClient(e, { xml -> sign(xml).xml }, http).also { client = it; clientEnv = e }
    }

    /** Prueba de conexión: pide un token a la DGII con el certificado de este equipo. */
    fun testConnection(): String { client = null; dgii().token(); return "La DGII (${env.title}) aceptó el certificado." }

    /** Envía lo pendiente una vez. Devuelve cuántos documentos cambiaron. */
    fun sendPending(now: Long = Time.now()): Int {
        if (cached == null) return 0
        var changed = 0
        for (d in db.fiscalQueries.pendingDocs().executeAsList()) {
            try {
                if (d.status == EcfStatus.SIGNED) sendOne(d, now) else checkOne(d, now)
                changed++
            } catch (e: DgiiException) {
                _sending.value = e.message ?: "Sin conexión con la DGII"
                if (!e.retry && d.status == EcfStatus.SIGNED) update(d, EcfStatus.ERROR, d.trackId, e.message ?: "Error", now, sent = false)
                else update(d, d.status, d.trackId, e.message ?: "", now, sent = false)
                if (e.retry) break
            }
        }
        if (changed > 0) _sending.value = ""
        return changed
    }

    private fun sendOne(d: EcfDoc, now: Long) {
        val rnc = emitter().rnc
        if (d.summaryXml.isNotBlank()) {
            val r = dgii().sendRfce(Ecf.fileName(rnc, d.encf), sign(d.summaryXml).xml)
            update(d, statusOf(r.status), "", r.message, now, sent = true)
        } else {
            val r = dgii().sendEcf(Ecf.fileName(rnc, d.encf), d.xml)
            update(d, EcfStatus.SENT, r.trackId, r.message, now, sent = true)
        }
    }

    private fun checkOne(d: EcfDoc, now: Long) {
        if (d.trackId.isBlank()) return
        val r = dgii().status(d.trackId)
        update(d, statusOf(r.status), d.trackId, r.message, now, sent = false)
    }

    private fun statusOf(s: DgiiStatus) = when (s) {
        DgiiStatus.ACCEPTED -> EcfStatus.ACCEPTED
        DgiiStatus.CONDITIONAL -> EcfStatus.CONDITIONAL
        DgiiStatus.REJECTED -> EcfStatus.REJECTED
        else -> EcfStatus.SENT
    }

    private fun update(d: EcfDoc, status: String, track: String, msg: String, now: Long, sent: Boolean) {
        synchronized(repo.writeLock) {
            db.fiscalQueries.setDocStatus(status, track, msg.take(500), if (sent) 1 else 0, if (sent) now else null, now, d.id)
        }
        repo.driver.notifyListeners("ecfDoc")
    }

    /** Reintenta un documento con error. */
    fun retry(id: Long) {
        val d = db.fiscalQueries.docById(id).executeAsOneOrNull() ?: return
        update(d, if (d.trackId.isBlank()) EcfStatus.SIGNED else EcfStatus.SENT, d.trackId, "", Time.now(), sent = false)
    }

    /** Envío en segundo plano (solo en el equipo principal o en un equipo solo). */
    fun startWorker(scope: CoroutineScope) {
        worker?.cancel()
        worker = scope.launch {
            while (isActive) {
                val wait = runCatching {
                    if (active && repo.remote != null && cached != null) {
                        // Caja conectada: se asegura de tener números para facturar si se cae la red.
                        ensureOfflineNumbers()
                        120_000L
                    } else if (active && repo.remote == null) {
                        val pending = db.fiscalQueries.pendingDocs().executeAsList()
                        if (pending.isNotEmpty()) { sendPending(); 20_000L } else 30_000L
                    } else 60_000L
                }.getOrDefault(60_000L)
                delay(wait)
            }
        }
    }

    fun stopWorker() { worker?.cancel(); worker = null }

    /** Tipos para los que una caja guarda números de reserva. */
    private val offlineTypes = listOf(EcfType.CONSUMO, EcfType.CREDITO_FISCAL)

    suspend fun ensureOfflineNumbers() {
        for (t in offlineTypes) {
            val hasRange = repo.fiscalRanges.value.any { it.type == t.code.toLong() }
            if (hasRange && freeNumbers(t) < 10) runCatching { repo.reserveFiscalBlock(t.code) }
        }
    }

    companion object {
        private const val CERT_KEY = "fiscal.cert"
        /** Plazo para enviar un e-CF firmado sin conexión. */
        const val CONTINGENCY_MS = 72L * 3_600_000L
    }
}
