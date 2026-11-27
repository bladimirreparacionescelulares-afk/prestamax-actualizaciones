package com.vitrinapos.server

import com.vitrinapos.data.Op
import com.vitrinapos.data.Permission
import com.vitrinapos.data.Permissions
import com.vitrinapos.db.AppUser
import kotlinx.serialization.Serializable

/** Claves de los módulos que se venden por plan (ver docs/plan-2.0.md). */
object Modules {
    const val SALES = "ventas"
    const val INVENTORY = "inventario"
    const val WORKSHOP = "taller"
    const val IMEI = "imei"
    const val VARIANTS = "variantes"
    const val QUOTES = "cotizaciones"
    const val LABELS = "etiquetas"
    const val FISCAL = "facturacion"
    const val AUDIT = "actividad"
    const val MULTI = "multiequipo"
    val all = listOf(SALES, INVENTORY, WORKSHOP, IMEI, VARIANTS, QUOTES, LABELS, FISCAL, AUDIT, MULTI)
}

/** Estado del pago de un negocio. */
enum class PlanState { TRIAL, ACTIVE, GRACE, READ_ONLY, SUSPENDED }

@Serializable
data class Entitlements(
    val plan: String,
    val planName: String,
    val state: String,
    /** Hasta cuándo puede vender sin pagar (fin de la prueba o del periodo pagado, más la gracia). */
    val until: Long?,
    val modules: List<String>,
    val maxDevices: Int,
    val maxUsers: Int,
)

object Rules {
    const val GRACE_DAYS = 7
    private const val DAY = 86_400_000L

    fun state(sub: Subscription?, suspended: Boolean, now: Long = System.currentTimeMillis()): PlanState {
        if (suspended) return PlanState.SUSPENDED
        if (sub == null) return PlanState.READ_ONLY
        val paid = sub.paidUntil ?: 0
        val trial = sub.trialUntil ?: 0
        return when {
            paid >= now -> PlanState.ACTIVE
            trial >= now -> PlanState.TRIAL
            maxOf(paid, trial) + GRACE_DAYS * DAY >= now -> PlanState.GRACE
            else -> PlanState.READ_ONLY
        }
    }

    /** Las operaciones que se permiten en solo lectura: ninguna que cambie datos del negocio, salvo anotar en la actividad. */
    fun allowedReadOnly(op: Op) = op is Op.LogEvent

    /** Módulo que hace falta para la operación (null = incluida en todos los planes). */
    fun moduleFor(op: Op): String? = when (op) {
        is Op.SavePurchase, is Op.VoidPurchase, is Op.StockLoss, is Op.ApplyCount, is Op.ReturnSale, is Op.MergeProducts -> Modules.INVENTORY
        is Op.SaveRepair, is Op.SetRepairStatus, is Op.DeliverRepair -> Modules.WORKSHOP
        is Op.AddUnits -> Modules.IMEI
        is Op.SaveQuote, is Op.SetQuoteStatus, is Op.DeleteQuote -> Modules.QUOTES
        is Op.ReserveFiscalBlock, is Op.SaveFiscalRange, is Op.DeleteFiscalRange -> Modules.FISCAL
        is Op.RecordSale -> if (op.fiscal != null || op.ecf != null) Modules.FISCAL else null
        is Op.SaveProduct -> when {
            op.draft.serialized -> Modules.IMEI
            op.draft.variantGroup.isNotBlank() -> Modules.VARIANTS
            else -> null
        }
        else -> null
    }

    /** Permiso que necesita el usuario para la operación (null = cualquiera que haya entrado). */
    fun permissionFor(op: Op): List<Permission>? = when (op) {
        is Op.SaveSettings -> listOf(Permission.SETTINGS)
        is Op.SaveCategory, is Op.SaveProduct -> listOf(Permission.CATALOG_EDIT)
        is Op.DeleteCategory, is Op.DeleteProduct, is Op.DeleteClient, is Op.DeleteSupplier, is Op.DeleteQuote -> listOf(Permission.DELETE_RECORDS)
        is Op.EnsureCodes -> listOf(Permission.LABELS, Permission.CATALOG_EDIT)
        is Op.MergeProducts -> listOf(Permission.CATALOG_EDIT)
        is Op.AdjustStock, is Op.RegisterEntry, is Op.StockLoss, is Op.ApplyCount, is Op.AddUnits -> listOf(Permission.ADJUST_STOCK)
        is Op.SavePurchase, is Op.VoidPurchase -> listOf(Permission.PURCHASES)
        is Op.SaveClient -> listOf(Permission.CLIENTS, Permission.SELL, Permission.WORKSHOP)
        is Op.SaveSupplier -> listOf(Permission.SUPPLIERS)
        is Op.RecordSale -> listOf(Permission.SELL)
        is Op.VoidSale -> listOf(Permission.VOID_SALES)
        is Op.ReturnSale -> listOf(Permission.RETURNS)
        is Op.SaveQuote, is Op.SetQuoteStatus -> listOf(Permission.QUOTES)
        is Op.OpenCash, is Op.CloseCash, is Op.CashMove -> listOf(Permission.CASH)
        is Op.SaveRepair, is Op.SetRepairStatus -> listOf(Permission.WORKSHOP)
        is Op.DeliverRepair -> listOf(Permission.WORKSHOP_DELIVER)
        is Op.ReserveFiscalBlock -> listOf(Permission.SELL, Permission.FISCAL)
        is Op.SaveFiscalRange, is Op.DeleteFiscalRange -> listOf(Permission.FISCAL)
        is Op.SetUserPermissions, is Op.SaveUser -> listOf(Permission.USERS)
        is Op.LogEvent -> null
    }

    fun can(user: AppUser, p: Permission) = p in Permissions.effective(user.role, user.permissions)

    /**
     * Revisa la operación para [user] (null = el dueño o un negocio sin usuarios). Devuelve el motivo del rechazo o null.
     * Un usuario puede cambiar su propio PIN aunque no administre usuarios.
     */
    fun deny(op: Op, user: AppUser?): String? {
        if (user == null) return null
        if (user.active != 1L) return "Tu usuario está desactivado"
        if (op is Op.SaveUser && op.id == user.id && op.role == user.role && op.active && op.name.trim() == user.name) return null
        val need = permissionFor(op) ?: return null
        if (need.none { can(user, it) }) return "No tienes permiso para esto (${need.first().title})"
        if (op is Op.RecordSale && op.allowNoStock && !can(user, Permission.SELL_NO_STOCK)) return "No tienes permiso para vender sin existencias"
        return null
    }

    /** Pone en la operación el usuario verificado por el servidor (no el que diga el equipo). */
    fun stamp(op: Op, user: AppUser?, deviceId: String): Op {
        val name = user?.name ?: "Dueño"
        val id = user?.id
        return when (op) {
            is Op.RecordSale -> op.copy(userId = id, userName = name, deviceId = deviceId)
            is Op.SaveRepair -> op.copy(userId = id, userName = name, deviceId = deviceId)
            is Op.DeliverRepair -> op.copy(userId = id, userName = name, deviceId = deviceId)
            is Op.SaveProduct -> op.copy(user = name)
            is Op.AdjustStock -> op.copy(user = name)
            is Op.RegisterEntry -> op.copy(user = name)
            is Op.CashMove -> op.copy(user = name, deviceId = deviceId)
            is Op.VoidSale -> op.copy(user = name, deviceId = deviceId)
            is Op.SaveQuote -> op.copy(user = name)
            is Op.OpenCash -> op.copy(user = name, deviceId = deviceId)
            is Op.CloseCash -> op.copy(user = name)
            is Op.SetRepairStatus -> op.copy(user = name)
            is Op.SavePurchase -> op.copy(user = name)
            is Op.VoidPurchase -> op.copy(user = name)
            is Op.StockLoss -> op.copy(user = name)
            is Op.ApplyCount -> op.copy(user = name)
            is Op.ReturnSale -> op.copy(user = name, deviceId = deviceId)
            is Op.AddUnits -> op.copy(user = name)
            is Op.ReserveFiscalBlock -> op.copy(deviceId = deviceId)
            else -> op
        }
    }
}
