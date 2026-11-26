package id.homebase.photobase.social

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.client.drives.files.reactions.ToggleReactionResultType
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.chat.services.decodeReactionCode
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.core.widget.EmojiReaction
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
    private val driveFileProvider: DriveFileProvider,
    private val reactionProvider: DriveFileGroupReactionProvider,
    private val optimisticWriter: OptimisticWriter,
    private val credentialsManager: CredentialsManager,
    private val databaseManager: DatabaseManager,
    private val eventBus: EventBus,
    private val audience: PhotoAudience = PhotoAudience.OwnerOnly,
) {
    private val drive = PhotobaseProtocol.DriveAlias

    /** Toggles our [emoji] on a photo or a comment, both by unique id. */
    suspend fun toggleReaction(targetId: Uuid, emoji: String): ToggleReactionResultType {
        if (emoji.isBlank() || emoji.length > MAX_EMOJI_LENGTH) return ToggleReactionResultType.None
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
        return PhotoReactions(file.fileMetadata.reactionPreview, file.fileMetadata.localAppData?.localReactions.orEmpty().toImmutableList())
    }

    /** Who reacted with what, from the server. */
    suspend fun listReactions(targetId: Uuid): List<EmojiReaction> {
        val target = driveFileProvider.getFileHeaderByUid(drive, targetId) ?: return emptyList()
        return reactionProvider.listReactions(drive, target.fileId).reactions.mapNotNull {
            EmojiReaction(
                messageId = targetId,
                odinId = it.odinId,
                created = UnixTimeUtc(it.created),
                emoji = decodeReactionCode(it.reactionContent) ?: return@mapNotNull null,
            )
        }
    }

    private companion object {
        const val MAX_EMOJI_LENGTH = 8
    }
}
