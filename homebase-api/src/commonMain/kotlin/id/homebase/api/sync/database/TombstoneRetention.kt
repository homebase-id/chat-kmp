package id.homebase.api.sync.database

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.LocalAppMetadata
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The server blanks a soft-deleted file's content, reactions and local app data. An opened view-once
 * stays a placeholder that is replied to and reacted to, so its kind, id and reactions survive the
 * live row it replaces; its payloads and caption never do.
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
        val metadata = incoming.fileMetadata
        val appData = metadata.appData
        if (!incoming.isSoftDeleted() || appData.dataType != VIEW_ONCE_DATA_TYPE) return incoming
        val storedMetadata = stored()?.fileMetadata ?: return incoming
        val storedAppData = storedMetadata.appData
        val content = if (appData.content.isNullOrBlank()) viewOnceKindOnly(storedAppData.content) else appData.content
        return incoming.copy(
            fileMetadata = metadata.copy(
                appData = appData.copy(content = content, uniqueId = appData.uniqueId ?: storedAppData.uniqueId),
                reactionPreview = keepReactions(metadata.reactionPreview, storedMetadata.reactionPreview),
                localAppData = keepLocalReactions(metadata.localAppData, storedMetadata.localAppData),
            )
        )
    }

    // The server restarts the summary from null after the delete, so it only ever knows the reactions since.
    private fun keepReactions(incoming: ReactionSummary?, stored: ReactionSummary?): ReactionSummary? {
        if (incoming == null || stored == null) return incoming ?: stored
        val known = incoming.reactions.values.mapTo(HashSet()) { it.reactionContent }
        return incoming.copy(reactions = stored.reactions.filterValues { it.reactionContent !in known } + incoming.reactions)
    }

    private fun keepLocalReactions(incoming: LocalAppMetadata?, stored: LocalAppMetadata?): LocalAppMetadata? {
        if (incoming == null || stored == null) return incoming ?: stored
        return incoming.copy(localReactions = (stored.localReactions.orEmpty() + incoming.localReactions.orEmpty()).distinct())
    }
}
