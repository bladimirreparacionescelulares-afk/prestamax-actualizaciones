package com.vitrinapos.net

import com.vitrinapos.data.Op
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

const val SYNC_PORT = 8740
const val DISCOVERY_PORT = 8741
const val PROTOCOL_VERSION = 1

@Serializable
data class PairRequest(val deviceId: String, val name: String, val platform: String, val version: Int = PROTOCOL_VERSION)

@Serializable
data class PairResponse(val keyHex: String, val serverId: String, val serverName: String, val epoch: String)

@Serializable
data class ApiRequest(
    val type: String,
    val since: Long = 0,
    val opId: String = "",
    val op: Op? = null,
    val name: String = "",
    val data: String = "",
)

@Serializable
data class Change(val t: String, val k: String, val row: JsonObject? = null)

@Serializable
data class ApiResponse(
    val ok: Boolean = true,
    val error: String = "",
    val result: JsonElement = JsonNull,
    val epoch: String = "",
    val last: Long = 0,
    val more: Boolean = false,
    val changes: List<Change> = emptyList(),
    val data: String = "",
)

/** Respuesta del principal cuando un equipo pregunta por la red quién es el principal. */
@Serializable
data class DiscoveryInfo(val name: String, val port: Int, val serverId: String, val platform: String)

data class FoundServer(val host: String, val info: DiscoveryInfo)
