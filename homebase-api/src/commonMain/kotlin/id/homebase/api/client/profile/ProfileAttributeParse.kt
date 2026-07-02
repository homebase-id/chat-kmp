@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.profile

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Parses a ProfileDrive attribute file (`fileType = 77`) into a [ProfileAttribute], or null if it
 * can't be read as one. The single parser behind [ProfileRepository] — a pure function so it can be
 * pinned directly in tests (the query response has already decrypted `appData.content`, so this only
 * deals in plaintext JSON).
 *
 * Returns null for: a soft-deleted file, a missing uniqueId/versionTag, missing or unparseable
 * content, or content without a `type`.
 */
internal fun HomebaseFile.toProfileAttribute(): ProfileAttribute? {
    if (isSoftDeleted()) return null

    val appData = fileMetadata.appData
    val id = appData.uniqueId ?: return null
    val versionTag = fileMetadata.versionTag ?: return null
    val content = appData.content ?: return null

    val root = runCatching {
        OdinSystemSerializer.json.decodeFromString<JsonObject>(content)
    }.getOrElse {
        Logger.w(throwable = it, tag = "ProfileAttributeParse") { "attribute $id content parse failed" }
        return null
    }

    val rawType = (root["type"] as? JsonPrimitive)?.contentOrNull ?: return null
    val data = (root["data"] as? JsonObject) ?: JsonObject(emptyMap())
    val visibility = ProfileVisibility.fromWire(
        serverMetadata.accessControlList?.requiredSecurityGroup
    )

    return ProfileAttribute(
        id = id,
        type = ProfileAttribute.normalizeType(rawType),
        versionTag = versionTag,
        visibility = visibility,
        data = data,
        fileId = fileId,
        driveId = driveId,
        keyHeader = keyHeader,
        payloads = fileMetadata.payloads,
    )
}
