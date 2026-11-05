package id.homebase.api.youauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer

/**
 * Represents a target drive access request, matching the TypeScript TargetDriveAccessRequest
 * interface.
 *
 * @param alias Drive alias identifier
 * @param type Drive type identifier
 * @param name Human-readable name for the drive
 * @param description Description of the drive purpose
 * @param permissions List of permission types requested
 * @param driveSlug Stable, human-readable identifier for this drive (e.g. "chat"). The server
 *   requires one on every drive the request may create; it matches `BuiltinDrives.DriveSlug` in odin-core.
 * @param driveTypeSlug Stable, human-readable identifier for the drive's type (e.g. "chat").
 *   Matches `BuiltinDrives.DriveTypeSlug` in odin-core.
 * @param attributes Optional key-value attributes for the drive
 * @param allowAnonymousRead Whether anonymous read access is allowed
 * @param allowSubscriptions Whether subscriptions are allowed
 * @param requireStorageKey Read counts as granted only with the drive's storage key; client-side, never sent
 * @param grantOnly Another app owns the drive: send only its address, which the owner console grants but never creates, adopts or changes
 * @param sharingModel How the drive is created when it doesn't exist yet; an existing drive keeps its own. Null means collaborative
 */
data class TargetDriveAccessRequest(
    val alias: String,
    val type: String,
    val name: String,
    val description: String,
    val permissions: List<DrivePermission>,
    val driveSlug: String? = null,
    val driveTypeSlug: String? = null,
    val attributes: Map<String, String>? = null,
    val allowAnonymousRead: Boolean? = null,
    val allowSubscriptions: Boolean? = null,
    val requireStorageKey: Boolean = false,
    val grantOnly: Boolean = false,
    val sharingModel: DriveSharingModel? = null,
) {
    /** Convert to JsonObject for proper serialization. */
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("a", JsonPrimitive(alias))
        put("t", JsonPrimitive(type))
        if (!grantOnly) {
            put("n", JsonPrimitive(name))
            put("d", JsonPrimitive(description))
        }
        put("p", JsonPrimitive(DrivePermission.combine(permissions)))
        if (!grantOnly) driveSlug?.let { put("ds", JsonPrimitive(it)) }
        driveTypeSlug?.let { put("ts", JsonPrimitive(it)) }
        if (!grantOnly) {
            allowAnonymousRead?.let { put("r", JsonPrimitive(it)) }
            allowSubscriptions?.let { put("s", JsonPrimitive(it)) }
            attributes?.let {
                put("at", JsonPrimitive(Json.encodeToString(serializer(), it)))
            }
            sharingModel?.let { put("sm", JsonPrimitive(it.wire)) }
        }
    }

    /** Convert to JSON string for embedding in request parameters. */
    fun toJson(): String = Json.encodeToString(JsonObject.serializer(), toJsonObject())

    companion object {
        /** Encode a list of TargetDriveAccessRequest to JSON string for API. */
        fun encodeList(drives: List<TargetDriveAccessRequest>): String {
            val jsonArray = drives.map { it.toJsonObject() }
            return Json.encodeToString(serializer(), jsonArray)
        }
    }
}

/** odin-core's DriveSharingModel, as the owner console reads it from a drive request's `sm`. */
enum class DriveSharingModel(val wire: String) {
    Collaborative("collaborative"),
    Private("private"),
    SelectiveShare("selectiveShare"),
}
