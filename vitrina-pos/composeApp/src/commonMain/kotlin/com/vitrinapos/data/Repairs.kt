package com.vitrinapos.data

import com.vitrinapos.db.RepairOrder
import kotlinx.serialization.Serializable

/** Estados de una reparación, en el orden en que suele avanzar. */
object RepairStatus {
    const val RECEIVED = "RECIBIDO"
    const val DIAGNOSIS = "DIAGNOSTICO"
    const val APPROVAL = "APROBACION"
    const val PARTS = "REPUESTO"
    const val REPAIRING = "REPARACION"
    const val READY = "LISTO"
    const val DELIVERED = "ENTREGADO"
    const val NOT_REPAIRED = "NO_REPARADO"

    val flow = listOf(RECEIVED, DIAGNOSIS, APPROVAL, PARTS, REPAIRING, READY, DELIVERED)
    val all = flow + NOT_REPAIRED

    fun label(s: String) = when (s) {
        RECEIVED -> "Recibido"
        DIAGNOSIS -> "En diagnóstico"
        APPROVAL -> "Esperando aprobación"
        PARTS -> "Esperando repuesto"
        REPAIRING -> "En reparación"
        READY -> "Listo para entregar"
        DELIVERED -> "Entregado"
        NOT_REPAIRED -> "No reparado"
        else -> s
    }

    fun short(s: String) = when (s) {
        RECEIVED -> "Recibido"; DIAGNOSIS -> "Diagnóstico"; APPROVAL -> "Aprobación"; PARTS -> "Repuesto"
        REPAIRING -> "Reparando"; READY -> "Listo"; DELIVERED -> "Entregado"; NOT_REPAIRED -> "No reparado"; else -> s
    }

    /** Todo lo que todavía no está listo cuenta como pendiente. */
    fun isPending(s: String) = s in setOf(RECEIVED, DIAGNOSIS, APPROVAL, PARTS, REPAIRING)
    fun isReady(s: String) = s == READY || s == NOT_REPAIRED
    fun isClosed(s: String) = s == DELIVERED

    /** Siguiente paso sugerido. */
    fun next(s: String): String? = when (s) {
        RECEIVED -> DIAGNOSIS
        DIAGNOSIS -> APPROVAL
        APPROVAL -> REPAIRING
        PARTS -> REPAIRING
        REPAIRING -> READY
        else -> null
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

/** Días desde la entrega estimada (positivo = atrasado). */
fun RepairOrder.daysLate(now: Long = Time.now()): Int {
    val due = estimatedAt ?: return 0
    if (!RepairStatus.isPending(status)) return 0
    return ((Time.startOfDay(now) - Time.startOfDay(due)) / 86_400_000L).toInt()
}
