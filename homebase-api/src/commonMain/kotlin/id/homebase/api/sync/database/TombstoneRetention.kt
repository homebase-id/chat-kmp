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

    internal fun carryOver(incoming: HomebaseFile, storedContent: () -> String?): HomebaseFile {
        val appData = incoming.fileMetadata.appData
        if (!incoming.isSoftDeleted() || appData.dataType != VIEW_ONCE_DATA_TYPE || !appData.content.isNullOrBlank()) return incoming
        val kept = viewOnceKindOnly(storedContent())
        if (kept.isEmpty()) return incoming
        return incoming.copy(fileMetadata = incoming.fileMetadata.copy(appData = appData.copy(content = kept)))
    }
}
