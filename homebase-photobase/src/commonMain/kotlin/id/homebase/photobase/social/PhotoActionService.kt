package id.homebase.photobase.social

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.client.drives.files.reactions.ToggleReactionResultType
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.util.codePointCount
import id.homebase.chat.services.decodeReactionCode
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

data class PhotoReactions(
    val summary: ReactionSummary? = null,
    val own: ImmutableList<String> = persistentListOf(),
)

/**
 * Reactions on photos and their comments, as moments have them: the server's group reactions on the local file,
 * sent to the photo's [audience].
 */
class PhotoActionService(
    private val optimisticWriter: OptimisticWriter,
    private val credentialsManager: CredentialsManager,
    private val databaseManager: DatabaseManager,
    private val eventBus: EventBus,
    private val audience: PhotoAudience = PhotoAudience.OwnerOnly,
) {
    private val drive = PhotobaseProtocol.DriveAlias

    /** Toggles our [emoji] on a photo or a comment, both by unique id. */
    suspend fun toggleReaction(targetId: Uuid, emoji: String): ToggleReactionResultType {
        if (!isReactionEmoji(emoji)) return ToggleReactionResultType.None
        val reaction = OdinSystemSerializer.serialize(ReactionContent(emoji = emoji))
        return optimisticWriter.toggleReaction(drive, targetId, reaction) { original ->
            val photoId = if (original.fileMetadata.appData.fileType == PhotobaseProtocol.CommentFileType) {
                original.fileMetadata.appData.groupId ?: error("comment $targetId has no photo")
            } else {
                targetId
            }
            audience.of(photoId)
        }.first
    }

    /** The photo's or comment's reactions as held locally, again on every change to it. */
    fun reactionsOf(targetId: Uuid): Flow<PhotoReactions> =
        eventBus.rereadOn(drive, { it.fileMetadata.appData.uniqueId == targetId }) { read(targetId) }

    private suspend fun read(targetId: Uuid): PhotoReactions {
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return PhotoReactions()
        val file = databaseManager.driveMainIndex.selectHomebaseFileByUnique(identityId, drive, targetId) ?: return PhotoReactions()
        return file.toPhotoReactions()
    }
}

// A family or a flag is several code points; the feed allows as many.
private const val MAX_EMOJI_CODE_POINTS = 12

internal fun isReactionEmoji(emoji: String): Boolean = emoji.isNotBlank() && emoji.codePointCount() <= MAX_EMOJI_CODE_POINTS

internal fun HomebaseFile.toPhotoReactions() = PhotoReactions(
    fileMetadata.reactionPreview,
    // Stored as ReactionContent JSON; the list compares bare emoji.
    fileMetadata.localAppData?.localReactions.orEmpty().mapNotNull(::decodeReactionCode).toImmutableList(),
)
