package id.homebase.api.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

fun JsonObject.strictString(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.strictBoolean(key: String): Boolean? = strictNumberOrBool(key)?.booleanOrNull

fun JsonObject.strictInt(key: String): Int? = strictNumberOrBool(key)?.intOrNull

fun JsonObject.strictLong(key: String): Long? = strictNumberOrBool(key)?.longOrNull

fun JsonObject.strictDouble(key: String): Double? = strictNumberOrBool(key)?.doubleOrNull

private fun JsonObject.strictNumberOrBool(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }
