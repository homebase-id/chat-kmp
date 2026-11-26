package id.homebase.photobase.social

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.GetGroupReactionCountsResponse
import id.homebase.api.client.drives.files.reactions.PeerReactionProvider
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlin.uuid.Uuid

data class PhotoReactions(
    val summary: ReactionSummary? = null,
    val own: List<String> = emptyList(),
)

/**
 * Reactions on photos and their comments: the server's group reactions on the file where it lives, on this identity's
 * drive or, for a guest, on its owner's over peer by global transit id (which finds a comment too).
 */
class PhotoActionService(
    private val reactionProvider: DriveFileGroupReactionProvider,
    private val peerReactions: PeerReactionProvider,
    private val credentialsManager: CredentialsManager,
) {
    private val drive = PhotobaseProtocol.DriveAlias
    private val toggled = MutableSharedFlow<Uuid>(extraBufferCapacity = 8)

    /** Adds our [emoji] to the photo, or takes it away when it's ours already. */
    suspend fun toggleReaction(photo: PhotoTarget, emoji: String) {
        if (emoji.isBlank() || emoji.length > MAX_EMOJI_LENGTH) return
        val reaction = OdinSystemSerializer.serialize(ReactionContent(emoji = emoji))
        val mine = emoji in read(photo).own
        val owner = photo.owner
        when {
            owner == null && mine -> reactionProvider.deleteReaction(drive, photo.fileId, reaction, recipients = emptyList(), photo.fileSystemType)
            owner == null -> reactionProvider.addReaction(drive, photo.fileId, reaction, recipients = emptyList(), photo.fileSystemType)
            mine -> peerReactions.deleteReaction(owner, drive, photo.globalTransitId, reaction)
            else -> peerReactions.addReaction(owner, drive, photo.globalTransitId, reaction)
        }
        toggled.emit(photo.globalTransitId)
    }

    /** The photo's reactions, read again after each toggle from here. */
    fun reactionsOf(photo: PhotoTarget): Flow<PhotoReactions> = flow {
        emit(read(photo))
        toggled.filter { it == photo.globalTransitId }.collect { emit(read(photo)) }
    }

    private suspend fun read(photo: PhotoTarget): PhotoReactions = coroutineScope {
        val self = credentialsManager.getActiveCredentials()?.domain ?: return@coroutineScope PhotoReactions()
        val owner = photo.owner
        val summary = async {
            if (owner == null) reactionProvider.getReactionSummary(drive, photo.fileId, photo.fileSystemType) else peerReactions.getReactionSummary(owner, drive, photo.globalTransitId)
        }
        val own = async {
            if (owner == null) reactionProvider.listReactionsByIdentity(drive, photo.fileId, self, photo.fileSystemType)
            else peerReactions.listReactionsByIdentity(owner, drive, photo.globalTransitId, self)
        }
        PhotoReactions(summary.await().toSummary(), own.await().mapNotNull(::emojiOf).distinct())
    }

    private companion object {
        const val MAX_EMOJI_LENGTH = 8
    }
}

internal fun GetGroupReactionCountsResponse.toSummary(): ReactionSummary? =
    reactions.filter { it.count > 0 }.takeIf { it.isNotEmpty() }
        ?.let { counts -> ReactionSummary(reactions = counts.associate { it.reactionContent to ReactionEntry(it.reactionContent, it.count, it.reactionContent) }) }

internal fun emojiOf(reactionContent: String): String? =
    runCatching { OdinSystemSerializer.deserialize<ReactionContent>(reactionContent).emoji }.getOrNull()
