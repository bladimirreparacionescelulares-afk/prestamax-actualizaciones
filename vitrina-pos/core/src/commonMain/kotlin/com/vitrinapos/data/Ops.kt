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
        /** Venta hecha sin conexión: el principal la acepta aunque falte stock (queda anotado). */
        val offline: Boolean = false,
        /** Quien vende tiene permiso de vender sin existencias. */
        val allowNoStock: Boolean = false,
    ) : Op()

    /** Entrada o salida de efectivo en la caja abierta de un equipo. */
    @Serializable @SerialName("cashMove")
    data class CashMove(val deviceId: String, val kind: String, val amount: Long, val note: String, val user: String) : Op()

    @Serializable @SerialName("saleVoid")
    data class VoidSale(val saleId: Long, val user: String, val reason: String = "", val deviceId: String = "") : Op()

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
    data class CloseCash(val sessionId: Long, val counted: Long, val notes: String, val authorizedBy: String = "", val user: String = "") : Op()

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

    @Serializable @SerialName("userPermissions")
    data class SetUserPermissions(val id: Long, val permissions: String) : Op()

    // ---------- Inventario (1.9) ----------

    /** Compra a un proveedor: suma existencias, recalcula el costo promedio y guarda el NCF. */
    @Serializable @SerialName("purchase")
    data class SavePurchase(
        val supplierId: Long?, val supplierName: String, val supplierRnc: String, val ncf: String, val docDate: Long,
        val lines: List<PurchaseLine>, val tax: Long, val paymentMethod: String, val note: String, val user: String,
    ) : Op()

    @Serializable @SerialName("purchaseVoid")
    data class VoidPurchase(val id: Long, val reason: String, val user: String) : Op()

    /** Merma: mercancía dañada, vencida, perdida o de uso interno. */
    @Serializable @SerialName("stockLoss")
    data class StockLoss(val productId: Long, val qty: Double, val reason: String, val note: String, val serials: List<String>, val user: String, val authorizedBy: String = "") : Op()

    /** Conteo físico: ajusta cada producto contado a lo que se contó (descontando lo vendido mientras se contaba). */
    @Serializable @SerialName("count")
    data class ApplyCount(val lines: List<CountLine>, val note: String, val user: String) : Op()

    /** Devolución de una venta, total o parcial. */
    @Serializable @SerialName("saleReturn")
    data class ReturnSale(
        val saleId: Long, val lines: List<ReturnLine>, val reason: String, val refundMethod: String, val deviceId: String, val user: String,
        val authorizedBy: String = "",
    ) : Op()

    /** Une productos repetidos en uno: existencias, historial, IMEI y códigos. */
    @Serializable @SerialName("merge")
    data class MergeProducts(val keepId: Long, val mergeIds: List<Long>, val name: String) : Op()

    /** Anota en la actividad algo que pasó en el equipo (por ejemplo, ver la clave de un equipo del taller). */
    @Serializable @SerialName("log")
    data class LogEvent(val action: String, val detail: String) : Op()

    /** Registra los IMEI o números de serie de las unidades que ya están en existencia. */
    @Serializable @SerialName("units")
    data class AddUnits(val productId: Long, val serials: List<String>, val user: String) : Op()

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
        SELLER -> "Vende, cotiza, atiende clientes y abre la caja. No anula ventas ni cambia la configuración."
        TECH -> "Como vendedor, y además trabaja las órdenes del taller."
        else -> ""
    }
}

/** Lo que cada usuario puede ver y hacer. El administrador lo ajusta por usuario en Usuarios. */
enum class Permission(val title: String, val detail: String) {
    SELL("Vender y cobrar", "Pantalla Vender, cobro y escáner"),
    DISCOUNTS("Dar descuentos", "Hasta el tope de Configuración; más pide el PIN de un administrador"),
    CHANGE_PRICES("Cambiar precios al vender", "Modificar el precio de un artículo en el carrito"),
    SELL_NO_STOCK("Vender sin existencias", "Vender aunque el sistema diga que no hay stock"),
    CATALOG_EDIT("Crear y editar productos", "Agregar productos, precios, fotos y categorías"),
    ADJUST_STOCK("Inventario", "Ajustar existencias, conteo físico, mermas, IMEI y kardex"),
    PURCHASES("Compras", "Registrar compras a proveedores con su NCF y su costo"),
    CLIENTS("Clientes", "Ver y registrar clientes"),
    SUPPLIERS("Proveedores", "Ver y registrar proveedores"),
    QUOTES("Cotizaciones", "Crear y enviar cotizaciones"),
    SALES_HISTORY("Historial de ventas", "Ver las ventas anteriores"),
    VOID_SALES("Anular ventas", "Anular ventas y emitir notas de crédito"),
    RETURNS("Devoluciones", "Recibir devoluciones de una venta (todo o parte) y reembolsar"),
    CASH("Caja", "Abrir y cerrar la caja"),
    REPORTS("Reportes y ganancias", "Ver reportes, ganancias y costos"),
    AUDIT("Ver actividad", "Quién cambió precios, permisos, caja, anulaciones y borrados"),
    WORKSHOP("Taller", "Recibir equipos y cambiar su estado"),
    WORKSHOP_DELIVER("Entregar equipos", "Cobrar y entregar equipos del taller"),
    LABELS("Etiquetas", "Imprimir etiquetas de productos"),
    DELETE_RECORDS("Borrar registros", "Borrar productos, clientes, proveedores y cotizaciones"),
    FISCAL("Facturación electrónica", "Certificado, secuencias y comprobantes e-CF"),
    SETTINGS("Configuración del negocio", "Datos del negocio, impuestos, ticket, respaldo y estados del taller"),
    NETWORK("Trabajo en red", "Unir equipos y quitarles el acceso"),
    USERS("Usuarios y permisos", "Crear usuarios y cambiar lo que pueden hacer"),
}

object Permissions {
    private val seller = setOf(Permission.SELL, Permission.DISCOUNTS, Permission.CLIENTS, Permission.QUOTES, Permission.SALES_HISTORY, Permission.CASH, Permission.LABELS)

    /** Lo que trae cada rol de entrada. */
    fun defaults(role: String?): Set<Permission> = when (role) {
        null, Roles.ADMIN -> Permission.entries.toSet()
        Roles.TECH -> seller + Permission.WORKSHOP + Permission.WORKSHOP_DELIVER
        else -> seller
    }

    /** Permisos guardados del usuario ("" = los de su rol). El administrador siempre lo tiene todo. */
    fun effective(role: String?, stored: String): Set<Permission> {
        if (role == null || role == Roles.ADMIN || stored.isBlank()) return defaults(role)
        return stored.split(',').mapNotNull { n -> Permission.entries.firstOrNull { it.name == n.trim() } }.toSet()
    }

    fun encode(set: Set<Permission>) = Permission.entries.filter { it in set }.joinToString(",") { it.name }
}

fun can(role: String?, p: Permission, stored: String = ""): Boolean = p in Permissions.effective(role, stored)
