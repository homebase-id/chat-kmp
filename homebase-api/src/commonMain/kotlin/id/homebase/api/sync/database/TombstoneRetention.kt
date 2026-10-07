package id.homebase.api.sync.database

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The server blanks a soft-deleted file's content. A view-once tombstone must still say whether it
 * was a photo or a video, so the kind survives the live row it replaces; nothing else does.
 */
object TombstoneRetention {
    const val VIEW_ONCE_DATA_TYPE = 216

    private val viewOnceKinds = setOf("image", "video")

    fun viewOnceKindOnly(content: String?): String {
        if (content.isNullOrBlank()) return ""
        return try {
            val obj = OdinSystemSerializer.json.parseToJsonElement(content).jsonObject
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
            if (kind in viewOnceKinds) {
                val version = obj["schemaVersion"] ?: JsonPrimitive(1)
                JsonObject(mapOf("kind" to JsonPrimitive(kind), "schemaVersion" to version)).toString()
            } else ""
        } catch (_: Exception) {
            ""
        }
    }

    // The server's tombstone has no uniqueId, so without it the item's id drifts to its fileId and a reply quoting it no longer finds it.
    internal fun carryOver(incoming: HomebaseFile, stored: () -> HomebaseFile?): HomebaseFile {
        val appData = incoming.fileMetadata.appData
        if (!incoming.isSoftDeleted() || appData.dataType != VIEW_ONCE_DATA_TYPE) return incoming
        if (!appData.content.isNullOrBlank() && appData.uniqueId != null) return incoming
        val storedAppData = stored()?.fileMetadata?.appData ?: return incoming
        val content = if (appData.content.isNullOrBlank()) viewOnceKindOnly(storedAppData.content) else appData.content
        val uniqueId = appData.uniqueId ?: storedAppData.uniqueId
        if (content == appData.content && uniqueId == appData.uniqueId) return incoming
        return incoming.copy(fileMetadata = incoming.fileMetadata.copy(appData = appData.copy(content = content, uniqueId = uniqueId)))
    }
}
