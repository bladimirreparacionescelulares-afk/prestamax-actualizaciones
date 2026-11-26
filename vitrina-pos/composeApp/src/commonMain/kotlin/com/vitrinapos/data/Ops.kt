package com.vitrinapos.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cada cambio de datos es una operación. En un equipo solo o en el principal se aplica directo;
 * en un equipo conectado se envía al principal, que la aplica y avisa a todos.
 */
@Serializable
sealed class Op {
    @Serializable @SerialName("settings")
    data class SaveSettings(val values: Map<String, String>) : Op()

    @Serializable @SerialName("category")
    data class SaveCategory(val id: Long?, val name: String, val color: Long) : Op()

    @Serializable @SerialName("categoryDel")
    data class DeleteCategory(val id: Long) : Op()

    @Serializable @SerialName("product")
    data class SaveProduct(val draft: ProductDraft, val user: String) : Op()

    /** Asigna un código interno a los productos que no tienen. Devuelve {id: código}. */
    @Serializable @SerialName("ensureCodes")
    data class EnsureCodes(val ids: List<Long>) : Op()

    @Serializable @SerialName("productDel")
    data class DeleteProduct(val id: Long) : Op()

    @Serializable @SerialName("adjust")
    data class AdjustStock(val productId: Long, val newStock: Double, val note: String, val user: String) : Op()

    @Serializable @SerialName("entry")
    data class RegisterEntry(val lines: List<EntryLine>, val supplierName: String?, val note: String, val user: String) : Op()

    @Serializable @SerialName("client")
    data class SaveClient(
        val id: Long?, val name: String, val phone: String, val email: String,
        val docId: String, val address: String, val notes: String,
    ) : Op()

    @Serializable @SerialName("clientDel")
    data class DeleteClient(val id: Long) : Op()

    @Serializable @SerialName("supplier")
    data class SaveSupplier(
        val id: Long?, val name: String, val contact: String, val phone: String, val email: String,
        val docId: String, val address: String, val notes: String,
    ) : Op()

    @Serializable @SerialName("supplierDel")
    data class DeleteSupplier(val id: Long) : Op()

    @Serializable @SerialName("sale")
    data class RecordSale(
        val lines: List<CartLine>,
        val clientId: Long?,
        val clientName: String,
        val discount: Long,
        val paymentMethod: String,
        val received: Long,
        val quoteId: Long?,
        val deviceId: String,
        val userId: Long?,
        val userName: String,
        val createdAt: Long,
        /** Comprobante que pidió el cliente (tipo, RNC y nombre). */
        val fiscal: FiscalChoice? = null,
        /** e-CF ya firmado en una caja sin conexión. */
        val ecf: com.vitrinapos.fiscal.EcfIssued? = null,
    ) : Op()

    @Serializable @SerialName("saleVoid")
    data class VoidSale(val saleId: Long, val user: String, val reason: String = "") : Op()

    /** Reserva un bloque de números e-NCF del rango autorizado para que una caja pueda facturar sin conexión. */
    @Serializable @SerialName("fiscalBlock")
    data class ReserveFiscalBlock(val ecfType: Int, val deviceId: String, val size: Int) : Op()

    /** Registra un rango de e-NCF autorizado por la DGII. */
    @Serializable @SerialName("fiscalRange")
    data class SaveFiscalRange(val ecfType: Int, val fromNo: Long, val toNo: Long, val expiresAt: Long?) : Op()

    @Serializable @SerialName("fiscalRangeDelete")
    data class DeleteFiscalRange(val id: Long) : Op()

    @Serializable @SerialName("quote")
    data class SaveQuote(
        val id: Long?, val clientId: Long?, val clientName: String, val lines: List<CartLine>,
        val discount: Long, val validDays: Int, val notes: String, val status: String, val user: String,
    ) : Op()

    @Serializable @SerialName("quoteStatus")
    data class SetQuoteStatus(val id: Long, val status: String) : Op()

    @Serializable @SerialName("quoteDel")
    data class DeleteQuote(val id: Long) : Op()

    @Serializable @SerialName("cashOpen")
    data class OpenCash(val amount: Long, val deviceId: String, val user: String) : Op()

    @Serializable @SerialName("cashClose")
    data class CloseCash(val sessionId: Long, val counted: Long, val notes: String) : Op()

    /** Recibe o edita un equipo. El abono (si hay) entra como cobro en Caja. */
    @Serializable @SerialName("repair")
    data class SaveRepair(
        val draft: RepairDraft,
        val depositMethod: String,
        val deviceId: String,
        val userId: Long?,
        val userName: String,
    ) : Op()

    @Serializable @SerialName("repairStatus")
    data class SetRepairStatus(val id: Long, val status: String, val note: String, val user: String) : Op()

    /** Entrega del equipo: cobra el saldo (repuestos y servicio menos el abono) y cierra la orden. */
    @Serializable @SerialName("repairDeliver")
    data class DeliverRepair(
        val id: Long,
        val lines: List<CartLine>,
        val repaired: Boolean,
        val paymentMethod: String,
        val received: Long,
        val note: String,
        val deviceId: String,
        val userId: Long?,
        val userName: String,
    ) : Op()

    @Serializable @SerialName("user")
    data class SaveUser(val id: Long?, val name: String, val pinHash: String?, val role: String, val active: Boolean) : Op()

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "op" }
    }
}

/** Roles de usuario y lo que puede hacer cada uno. */
object Roles {
    const val ADMIN = "ADMIN"
    const val SELLER = "VENDEDOR"
    const val TECH = "TECNICO"
    val all = listOf(ADMIN, SELLER, TECH)

    fun label(r: String) = when (r) { ADMIN -> "Administrador"; SELLER -> "Vendedor"; TECH -> "Técnico"; else -> r }

    fun description(r: String) = when (r) {
        ADMIN -> "Todo: configuración, usuarios, anular ventas, reportes y equipos de la red."
        SELLER -> "Vende, cotiza y atiende clientes. No anula ventas ni cambia la configuración."
        TECH -> "Como vendedor, y además trabaja las órdenes del taller."
        else -> ""
    }
}

enum class Permission { VOID_SALES, SETTINGS, USERS, REPORTS, DELETE_RECORDS, ADJUST_STOCK, CASH_HISTORY }

fun can(role: String?, p: Permission): Boolean = when (role) {
    null, Roles.ADMIN -> true // sin usuarios configurados, todo está permitido
    else -> false
}
