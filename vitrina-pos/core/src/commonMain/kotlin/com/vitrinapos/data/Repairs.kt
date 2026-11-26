package com.vitrinapos.data

import com.vitrinapos.db.RepairOrder
import kotlinx.serialization.Serializable

/** Tipo de estado: decide en qué lista sale la orden y de qué color. */
enum class StatusKind(val title: String) {
    WORK("En proceso"), WAIT("En espera"), READY("Listo para entregar"), NOT_REPAIRED("No reparado"), DELIVERED("Entregado")
}

/** Un estado del taller. Los fijos ([locked]) se pueden renombrar, pero no quitar. */
data class StatusDef(val key: String, val label: String, val kind: StatusKind, val locked: Boolean = false)

/**
 * Estados de una reparación, en el orden en que suele avanzar.
 * La lista se puede editar en Configuración → Taller (se guarda en [AppSettings.repairStatuses]).
 */
object RepairStatus {
    const val RECEIVED = "RECIBIDO"
    const val DIAGNOSIS = "DIAGNOSTICO"
    const val APPROVAL = "APROBACION"
    const val PARTS = "REPUESTO"
    const val REPAIRING = "REPARACION"
    const val READY = "LISTO"
    const val DELIVERED = "ENTREGADO"
    const val NOT_REPAIRED = "NO_REPARADO"

    private val LOCKED = setOf(RECEIVED, READY, DELIVERED, NOT_REPAIRED)

    val defaults = listOf(
        StatusDef(RECEIVED, "Recibido", StatusKind.WORK, true),
        StatusDef(DIAGNOSIS, "En diagnóstico", StatusKind.WORK),
        StatusDef(APPROVAL, "Esperando aprobación", StatusKind.WAIT),
        StatusDef(PARTS, "Esperando repuesto", StatusKind.WAIT),
        StatusDef(REPAIRING, "En reparación", StatusKind.WORK),
        StatusDef(READY, "Listo para entregar", StatusKind.READY, true),
        StatusDef(DELIVERED, "Entregado", StatusKind.DELIVERED, true),
        StatusDef(NOT_REPAIRED, "No reparado", StatusKind.NOT_REPAIRED, true),
    )

    private val shortNames = mapOf(
        RECEIVED to "Recibido", DIAGNOSIS to "Diagnóstico", APPROVAL to "Aprobación", PARTS to "Repuesto",
        REPAIRING to "Reparando", READY to "Listo", DELIVERED to "Entregado", NOT_REPAIRED to "No reparado",
    )

    private var state = androidx.compose.runtime.mutableStateOf(defaults)
    val defs: List<StatusDef> get() = state.value

    /** Texto guardado: una línea por estado, "CLAVE|Nombre|TIPO". */
    fun encode(list: List<StatusDef>): String = list.joinToString("\n") { "${it.key}|${it.label.replace('|', '/').replace('\n', ' ')}|${it.kind.name}" }

    fun decode(text: String): List<StatusDef> {
        if (text.isBlank()) return defaults
        val parsed = text.lines().mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 3 || p[0].isBlank()) return@mapNotNull null
            val kind = runCatching { StatusKind.valueOf(p[2]) }.getOrNull() ?: return@mapNotNull null
            StatusDef(p[0], p[1].ifBlank { p[0] }, kind, p[0] in LOCKED)
        }.distinctBy { it.key }
        // Los estados fijos siempre existen, aunque el texto esté dañado.
        val missing = defaults.filter { d -> d.locked && parsed.none { it.key == d.key } }
        return (parsed + missing).map { if (it.key in LOCKED) it.copy(kind = defaults.first { d -> d.key == it.key }.kind, locked = true) else it }
    }

    fun configure(text: String) {
        val list = decode(text)
        if (list != state.value) state.value = list
    }

    /** Clave nueva a partir del nombre (sin acentos ni espacios). */
    fun newKey(label: String, taken: Collection<String>): String {
        val base = java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_').take(20).ifBlank { "ESTADO" }
        var k = base
        var i = 2
        while (k in taken) k = "${base}_${i++}"
        return k
    }

    fun def(s: String): StatusDef = defs.firstOrNull { it.key == s } ?: StatusDef(s, s.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' '), StatusKind.WORK)
    fun kind(s: String) = def(s).kind

    /** Orden en que se muestran: los de trabajo y espera, luego listo, no reparado y entregado. */
    val all: List<String> get() = defs.map { it.key }
    val flow: List<String> get() = defs.filter { it.kind != StatusKind.NOT_REPAIRED }.map { it.key }

    fun label(s: String) = def(s).label
    fun short(s: String): String {
        val d = def(s)
        val default = defaults.firstOrNull { it.key == s }
        return if (default != null && default.label == d.label) shortNames[s] ?: d.label else d.label
    }

    /** Todo lo que todavía no está listo cuenta como pendiente. */
    fun isPending(s: String) = kind(s) == StatusKind.WORK || kind(s) == StatusKind.WAIT
    fun isWaiting(s: String) = kind(s) == StatusKind.WAIT
    fun isReady(s: String) = kind(s) == StatusKind.READY || kind(s) == StatusKind.NOT_REPAIRED
    fun isClosed(s: String) = kind(s) == StatusKind.DELIVERED

    /** Siguiente paso sugerido: el próximo estado de trabajo (o "Listo"), saltando los de espera. */
    fun next(s: String): String? {
        if (!isPending(s)) return null
        val list = defs
        val i = list.indexOfFirst { it.key == s }
        return list.drop(i + 1).firstOrNull { it.kind == StatusKind.WORK || it.kind == StatusKind.READY }?.key ?: READY
    }
}

object DeviceTypes {
    const val PHONE = "TELEFONO"
    const val TABLET = "TABLET"
    const val LAPTOP = "LAPTOP"
    const val PC = "PC"
    const val OTHER = "OTRO"
    val all = listOf(PHONE, TABLET, LAPTOP, PC, OTHER)
    fun label(t: String) = when (t) { PHONE -> "Teléfono"; TABLET -> "Tablet"; LAPTOP -> "Laptop"; PC -> "PC"; else -> "Otro" }
    fun serialLabel(t: String) = if (t == PHONE || t == TABLET) "IMEI o serial" else "Número de serie"

    val brands = mapOf(
        PHONE to listOf("Samsung", "Apple", "Xiaomi", "Motorola", "Huawei", "Honor", "Oppo", "Tecno", "Infinix", "ZTE", "Realme"),
        TABLET to listOf("Samsung", "Apple", "Lenovo", "Huawei", "Xiaomi", "Amazon"),
        LAPTOP to listOf("HP", "Dell", "Lenovo", "Asus", "Acer", "Apple", "MSI", "Toshiba"),
        PC to listOf("HP", "Dell", "Lenovo", "Armada"),
        OTHER to emptyList(),
    )
}

/** Cómo llega el equipo: cada punto puede estar bien, mal o sin revisar. */
object Condition {
    val items = listOf("Enciende", "Pantalla", "Táctil", "Carga", "Cámara", "Audio", "Botones", "Señal / Wi-Fi", "Sin humedad")
    const val OK = "ok"
    const val BAD = "mal"
    const val UNKNOWN = "?"

    fun parse(text: String): Map<String, String> =
        text.split(';').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    fun format(m: Map<String, String>): String = m.filterValues { it != UNKNOWN }.entries.joinToString(";") { "${it.key}=${it.value}" }

    fun summary(text: String): String = parse(text).entries.joinToString(" · ") { (k, v) -> if (v == OK) "✓ $k" else "✕ $k" }
}

object Accessories {
    val all = listOf("SIM", "Memoria", "Funda", "Cargador", "Cable", "Caja", "Lápiz", "Batería")
}

object UnlockTypes {
    const val NONE = "NONE"
    const val PIN = "PIN"
    const val PATTERN = "PATTERN"
    const val PASSWORD = "PASSWORD"
    fun label(t: String) = when (t) { PIN -> "PIN"; PATTERN -> "Patrón"; PASSWORD -> "Contraseña"; else -> "Ninguno" }
}

@Serializable
data class RepairDraft(
    val id: Long? = null,
    val clientId: Long? = null,
    val clientName: String = "",
    val clientPhone: String = "",
    val clientEmail: String = "",
    val deviceType: String = DeviceTypes.PHONE,
    val brand: String = "",
    val model: String = "",
    val serial: String = "",
    val color: String = "",
    val unlockType: String = UnlockTypes.NONE,
    val unlockCode: String = "",
    val problem: String = "",
    val conditionText: String = "",
    val accessories: String = "",
    val photos: String = "",
    val estimate: Long = 0,
    val deposit: Long = 0,
    val diagnosisFee: Long = 0,
    val estimatedAt: Long? = null,
    val technician: String = "",
    val notes: String = "",
    val warrantyDays: Int = 30,
)

fun repairCode(number: Long) = "REP-" + number.toString().padStart(4, '0')

val RepairOrder.code: String get() = repairCode(number)
val RepairOrder.deviceName: String get() = listOf(brand, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { DeviceTypes.label(deviceType) }
val RepairOrder.photoList: List<String> get() = photos.split(',').filter { it.isNotBlank() }

/** Lee el número de una orden desde el QR o el texto "REP-0042". */
fun parseRepairCode(text: String): Long? = Regex("""(?i)^\s*REP-?(\d{1,8})\s*$""").find(text)?.groupValues?.get(1)?.toLongOrNull()

/** Días que lleva listo esperando que lo retiren (0 si no está listo). */
fun RepairOrder.daysWaiting(now: Long = Time.now()): Int =
    if (!RepairStatus.isReady(status)) 0 else ((Time.startOfDay(now) - Time.startOfDay(updatedAt)) / 86_400_000L).toInt()

fun RepairOrder.isAbandoned(abandonDays: Int, now: Long = Time.now()): Boolean = abandonDays > 0 && daysWaiting(now) >= abandonDays

/** Fin de la garantía de un equipo entregado reparado (null si no tiene). */
val RepairOrder.warrantyUntil: Long? get() =
    deliveredAt?.takeIf { RepairStatus.isClosed(status) && warrantyDays > 0 }?.let { Time.addDays(Time.startOfDay(it), warrantyDays.toInt()) + 86_399_999L }

/** Teléfono para WhatsApp: solo números, con el código del país si falta. */
fun whatsappNumber(phone: String, countryCode: String): String? {
    val d = phone.filter(Char::isDigit)
    if (d.length < 7) return null
    val cc = countryCode.filter(Char::isDigit)
    return if (d.length <= 10 && cc.isNotEmpty()) cc + d.trimStart('0') else d
}

/** Días desde la entrega estimada (positivo = atrasado). */
fun RepairOrder.daysLate(now: Long = Time.now()): Int {
    val due = estimatedAt ?: return 0
    if (!RepairStatus.isPending(status)) return 0
    return ((Time.startOfDay(now) - Time.startOfDay(due)) / 86_400_000L).toInt()
}
