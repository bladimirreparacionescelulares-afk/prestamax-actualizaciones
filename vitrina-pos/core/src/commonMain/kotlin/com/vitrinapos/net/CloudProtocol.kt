package com.vitrinapos.net

import com.vitrinapos.data.Op
import kotlinx.serialization.Serializable

/*
 * Protocolo de la nube (servidor 2.0): lo comparten la app (CloudClient) y el servidor.
 * Todo viaja como JSON por HTTPS.
 */


@Serializable data class SignupRequest(
    val email: String, val password: String, val name: String, val businessName: String, val businessType: String = "",
    val ownerPin: String, val deviceName: String = "", val platform: String = "",
)
@Serializable data class LoginRequest(val email: String, val password: String, val deviceName: String, val platform: String = "", val businessId: Long? = null)
@Serializable data class DeviceCredentials(val deviceId: String, val deviceSecret: String, val businessId: Long, val businessName: String, val mustChangePassword: Boolean = false)
@Serializable data class TokenRequest(val deviceId: String, val deviceSecret: String)
@Serializable data class TokenResponse(val token: String, val expires: Long, val epoch: String, val entitlements: Entitlements, val deviceName: String)
@Serializable data class UserLoginRequest(val userId: Long, val pin: String)
@Serializable data class UserTokenResponse(val token: String, val expires: Long, val userName: String, val role: String)
@Serializable data class OwnerLoginRequest(val email: String, val password: String, val businessId: Long? = null)
@Serializable data class OwnerTokenResponse(val token: String, val expires: Long, val businessId: Long, val mustChangePassword: Boolean)
@Serializable data class OpRequest(val opId: String, val op: Op)
@Serializable data class ForgotRequest(val email: String)
@Serializable data class ResetRequest(val email: String, val code: String, val newPassword: String)
@Serializable data class ChangePasswordRequest(val email: String, val oldPassword: String, val newPassword: String)
@Serializable data class Message(val ok: Boolean = true, val message: String = "")
@Serializable data class ErrorBody(val ok: Boolean = false, val error: String)
@Serializable data class RenameRequest(val name: String)


/** Lo que el plan del negocio permite (módulos, equipos, usuarios) y su estado de pago. */
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

/** Un equipo autorizado del negocio. */
@Serializable
data class Device(val id: String, val businessId: Long, val name: String, val platform: String, val created: Long, val lastUse: Long?, val revoked: Boolean)
