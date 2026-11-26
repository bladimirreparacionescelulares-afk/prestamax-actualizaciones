package com.vitrinapos.net

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Lee y escribe filas completas de las tablas compartidas como JSON,
 * para copiarlas del equipo principal a los demás.
 */
object RowCodec {
    enum class T { L, D, S }

    class Table(val name: String, val key: String, val cols: List<Pair<String, T>>) {
        val keyIsText get() = cols.first { it.first == key }.second == T.S
    }

    private fun t(name: String, vararg cols: Pair<String, T>, key: String = "id") = Table(name, key, cols.toList())
    private val L = T.L
    private val D = T.D
    private val S = T.S

    val tables: Map<String, Table> = listOf(
        t("setting", "key" to S, "content" to S, key = "key"),
        t("category", "id" to L, "name" to S, "color" to L),
        t(
            "product", "id" to L, "name" to S, "code" to S, "categoryId" to L, "supplierId" to L, "cost" to L, "price" to L,
            "stock" to D, "minStock" to D, "unit" to S, "taxable" to L, "trackStock" to L, "imagePath" to S, "description" to S,
            "active" to L, "createdAt" to L, "updatedAt" to L,
        ),
        t("client", "id" to L, "name" to S, "phone" to S, "email" to S, "docId" to S, "address" to S, "notes" to S, "createdAt" to L),
        t("supplier", "id" to L, "name" to S, "contact" to S, "phone" to S, "email" to S, "docId" to S, "address" to S, "notes" to S, "createdAt" to L),
        t(
            "sale", "id" to L, "number" to L, "clientId" to L, "clientName" to S, "gross" to L, "discount" to L, "tax" to L, "total" to L,
            "costTotal" to L, "taxIncluded" to L, "paymentMethod" to S, "received" to L, "changeDue" to L, "status" to S, "quoteId" to L,
            "createdAt" to L, "deviceId" to S, "userId" to L, "userName" to S,
        ),
        t("saleItem", "id" to L, "saleId" to L, "productId" to L, "name" to S, "code" to S, "qty" to D, "price" to L, "cost" to L, "taxable" to L),
        t(
            "quote", "id" to L, "number" to L, "clientId" to L, "clientName" to S, "gross" to L, "discount" to L, "tax" to L, "total" to L,
            "taxIncluded" to L, "validDays" to L, "notes" to S, "status" to S, "saleId" to L, "createdAt" to L, "updatedAt" to L, "userName" to S,
        ),
        t("quoteItem", "id" to L, "quoteId" to L, "productId" to L, "name" to S, "code" to S, "qty" to D, "price" to L, "taxable" to L, "imagePath" to S),
        t("stockMove", "id" to L, "productId" to L, "type" to S, "qty" to D, "note" to S, "createdAt" to L, "userName" to S),
        t(
            "cashSession", "id" to L, "openedAt" to L, "openingAmount" to L, "closedAt" to L, "expectedAmount" to L, "countedAmount" to L,
            "notes" to S, "deviceId" to S, "userName" to S,
        ),
        t("appUser", "id" to L, "name" to S, "pinHash" to S, "role" to S, "active" to L, "createdAt" to L),
        t(
            "repairOrder", "id" to L, "number" to L, "clientId" to L, "clientName" to S, "clientPhone" to S, "clientEmail" to S,
            "deviceType" to S, "brand" to S, "model" to S, "serial" to S, "color" to S, "unlockType" to S, "unlockCode" to S,
            "problem" to S, "conditionText" to S, "accessories" to S, "photos" to S, "estimate" to L, "deposit" to L, "diagnosisFee" to L,
            "estimatedAt" to L, "technician" to S, "status" to S, "notes" to S, "receivedBy" to S, "total" to L, "warrantyDays" to L,
            "deliveredAt" to L, "saleId" to L, "createdAt" to L, "updatedAt" to L,
        ),
        t("repairEvent", "id" to L, "repairId" to L, "status" to S, "note" to S, "userName" to S, "createdAt" to L),
        t("fiscalSequence", "id" to L, "type" to L, "fromNo" to L, "toNo" to L, "expiresAt" to L, "deviceId" to S, "parentId" to L, "createdAt" to L),
        t(
            "ecfDoc", "id" to L, "saleId" to L, "type" to L, "encf" to S, "status" to S, "xml" to S, "summaryXml" to S, "securityCode" to S, "signedAt" to L,
            "total" to L, "buyerRnc" to S, "buyerName" to S, "refEncf" to S, "trackId" to S, "message" to S, "attempts" to L, "sentAt" to L,
            "deviceId" to S, "env" to S, "createdAt" to L, "updatedAt" to L,
        ),
    ).associateBy { it.name }

    fun read(driver: SqlDriver, table: String, key: String): JsonObject? {
        val t = tables[table] ?: return null
        val sql = "SELECT ${t.cols.joinToString { it.first }} FROM ${t.name} WHERE ${t.key} = ?"
        return driver.executeQuery(null, sql, { c ->
            if (!c.next().value) return@executeQuery QueryResult.Value(null)
            val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
            t.cols.forEachIndexed { i, (name, type) ->
                map[name] = when (type) {
                    T.L -> c.getLong(i)?.let { JsonPrimitive(it) } ?: JsonNull
                    T.D -> c.getDouble(i)?.let { JsonPrimitive(it) } ?: JsonNull
                    T.S -> c.getString(i)?.let { JsonPrimitive(it) } ?: JsonNull
                }
            }
            QueryResult.Value(JsonObject(map))
        }, 1) {
            if (t.keyIsText) bindString(0, key) else bindLong(0, key.toLong())
        }.value
    }

    fun upsert(driver: SqlDriver, table: String, row: JsonObject) {
        val t = tables[table] ?: return
        val sql = "INSERT OR REPLACE INTO ${t.name}(${t.cols.joinToString { it.first }}) VALUES (${t.cols.joinToString { "?" }})"
        driver.execute(null, sql, t.cols.size) {
            t.cols.forEachIndexed { i, (name, type) ->
                val v = row[name]
                val p = if (v == null || v is JsonNull) null else v.jsonPrimitive
                when (type) {
                    T.L -> bindLong(i, p?.longOrNull ?: p?.doubleOrNull?.toLong())
                    T.D -> bindDouble(i, p?.doubleOrNull)
                    T.S -> bindString(i, p?.content)
                }
            }
        }
    }

    fun delete(driver: SqlDriver, table: String, key: String) {
        val t = tables[table] ?: return
        driver.execute(null, "DELETE FROM ${t.name} WHERE ${t.key} = ?", 1) {
            if (t.keyIsText) bindString(0, key) else bindLong(0, key.toLong())
        }
    }

    /** Crea los disparadores que anotan cada cambio de las tablas compartidas en changeLog. */
    fun installTriggers(driver: SqlDriver) {
        for (t in tables.values) {
            for ((suffix, event, ref) in listOf(Triple("ai", "INSERT", "NEW"), Triple("au", "UPDATE", "NEW"), Triple("ad", "DELETE", "OLD"))) {
                driver.execute(
                    null,
                    "CREATE TRIGGER IF NOT EXISTS ${t.name}_$suffix AFTER $event ON ${t.name} BEGIN " +
                        "INSERT INTO changeLog(tbl, rowKey) VALUES ('${t.name}', $ref.${t.key}); END",
                    0,
                )
            }
        }
    }

    fun clearAll(driver: SqlDriver) {
        tables.keys.forEach { driver.execute(null, "DELETE FROM $it", 0) }
    }
}
