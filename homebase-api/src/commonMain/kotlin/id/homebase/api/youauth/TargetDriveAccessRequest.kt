package id.homebase.homebasekmppoc.lib.youauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Represents a target drive access request, matching the TypeScript TargetDriveAccessRequest
 * interface.
 *
 * @param alias Drive alias identifier
 * @param type Drive type identifier
 * @param name Human-readable name for the drive
 * @param description Description of the drive purpose
 * @param permissions List of permission types requested
 * @param attributes Optional key-value attributes for the drive
 * @param allowAnonymousRead Whether anonymous read access is allowed
 * @param allowSubscriptions Whether subscriptions are allowed
 */
data class TargetDriveAccessRequest(
        val alias: String,
        val type: String,
        val name: String,
        val description: String,
        val permissions: List<DrivePermissionType>,
        val attributes: Map<String, String>? = null,
        val allowAnonymousRead: Boolean? = null,
        val allowSubscriptions: Boolean? = null
) {
    /**
     * Convert to a map for serialization using short keys matching the API.
     * - a: alias
     * - t: type
     * - n: name
     * - d: description
     * - p: permissions (sum of bitwise values)
     * - r: allowAnonymousRead
     * - s: allowSubscriptions
     * - at: attributes (JSON encoded)
     */
    fun toMap(): Map<String, Any?> = buildMap {
        put("a", alias)
        put("t", type)
        put("n", name)
        put("d", description)
        put("p", DrivePermissionType.combine(permissions))
        allowAnonymousRead?.let { put("r", it) }
        allowSubscriptions?.let { put("s", it) }
        attributes?.let { put("at", Json.encodeToString(kotlinx.serialization.serializer(), it)) }
    }

    /** Convert to JsonObject for proper serialization. */
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("a", JsonPrimitive(alias))
        put("t", JsonPrimitive(type))
        put("n", JsonPrimitive(name))
        put("d", JsonPrimitive(description))
        put("p", JsonPrimitive(DrivePermissionType.combine(permissions)))
        allowAnonymousRead?.let { put("r", JsonPrimitive(it)) }
        allowSubscriptions?.let { put("s", JsonPrimitive(it)) }
        attributes?.let {
            put("at", JsonPrimitive(Json.encodeToString(kotlinx.serialization.serializer(), it)))
        }
    }

    /** Convert to JSON string for embedding in request parameters. */
    fun toJson(): String = Json.encodeToString(JsonObject.serializer(), toJsonObject())

    companion object {
        /** Encode a list of TargetDriveAccessRequest to JSON string for API. */
        fun encodeList(drives: List<TargetDriveAccessRequest>): String {
            val jsonArray = drives.map { it.toJsonObject() }
            return Json.encodeToString(kotlinx.serialization.serializer(), jsonArray)
        }
    }
}
