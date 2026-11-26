package id.homebase.photobase.social

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.GetGroupReactionCountsResponse
import id.homebase.api.client.drives.files.reactions.PeerReactionProvider
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.codePointCount
import id.homebase.chat.services.decodeReactionCode
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // One toggle at a time: a quick second tap must see the first one's result.
    private val toggling = Mutex()

    /** Adds our [emoji] to the photo, or takes it away when it's ours already. */
    suspend fun toggleReaction(photo: PhotoTarget, emoji: String) = toggling.withLock {
        if (emoji.isBlank() || emoji.codePointCount() > MAX_EMOJI_CODEPOINTS) return@withLock
        val reaction = OdinSystemSerializer.serialize(ReactionContent(emoji = emoji))
        val on = on(photo)
        // Read now, not from the last read: another device may have reacted since.
        if (emoji in on.own(credentialsManager.getActiveCredentials()?.domain ?: return@withLock).mapNotNull(::decodeReactionCode)) {
            on.delete(reaction)
        } else {
            on.add(reaction)
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
        val on = on(photo)
        val summary = async { on.summary() }
        val own = async { on.own(self) }
        PhotoReactions(summary.await().toSummary(), own.await().mapNotNull(::decodeReactionCode).distinct())
    }

    // Where the target's reactions are: here by file id, or on its owner's drive over peer by global transit id.
    private fun on(photo: PhotoTarget): ReactionsOn {
        val owner = photo.owner
        return if (owner == null) {
            val fileSystem = photo.fileSystemType
            object : ReactionsOn {
                override suspend fun add(reaction: String) = reactionProvider.addReaction(drive, photo.fileId, reaction, recipients = emptyList(), fileSystem)
                override suspend fun delete(reaction: String) = reactionProvider.deleteReaction(drive, photo.fileId, reaction, recipients = emptyList(), fileSystem)
                override suspend fun summary() = reactionProvider.getReactionSummary(drive, photo.fileId, fileSystem)
                override suspend fun own(self: OdinId) = reactionProvider.listReactionsByIdentity(drive, photo.fileId, self, fileSystem)
            }
        } else {
            val gtid = photo.globalTransitId
            object : ReactionsOn {
                override suspend fun add(reaction: String) = peerReactions.addReaction(owner, drive, gtid, reaction)
                override suspend fun delete(reaction: String) = peerReactions.deleteReaction(owner, drive, gtid, reaction)
                override suspend fun summary() = peerReactions.getReactionSummary(owner, drive, gtid)
                override suspend fun own(self: OdinId) = peerReactions.listReactionsByIdentity(owner, drive, gtid, self)
            }
        }
    }

    private interface ReactionsOn {
        suspend fun add(reaction: String)
        suspend fun delete(reaction: String)
        suspend fun summary(): GetGroupReactionCountsResponse
        suspend fun own(self: OdinId): List<String>
    }

    private companion object {
        // A family or flag is several code points; the longest the picker offers fit.
        const val MAX_EMOJI_CODEPOINTS = 10
    }
}

internal fun GetGroupReactionCountsResponse.toSummary(): ReactionSummary? =
    reactions.filter { it.count > 0 }.takeIf { it.isNotEmpty() }
        ?.let { counts -> ReactionSummary(reactions = counts.associate { it.reactionContent to ReactionEntry(it.reactionContent, it.count, it.reactionContent) }) }
