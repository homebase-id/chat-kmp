package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.GlobalTransitIdFileIdentifier
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.GroupReactionItem
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.config.filesyncLabeledDrive
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostCommentContent
import id.homebase.core.filesync.FilesyncProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** How many reactions one person may leave on a file; the server doesn't count them. */
const val MAX_OWN_REACTIONS = 5

@Immutable
data class FilesyncReactionCount(val emoji: String, val count: Int)

@Immutable
data class FilesyncComment(val id: Uuid, val author: OdinId?, val mine: Boolean, val body: String, val createdMs: Long)

/** A file's reactions and comments, as the info pane shows them. */
@Immutable
data class FilesyncDiscussion(
    val nodeId: Uuid,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val reactions: List<FilesyncReactionCount> = emptyList(),
    val mine: List<String> = emptyList(),
    val comments: List<FilesyncComment> = emptyList(),
    val sending: Boolean = false,
) {
    val canAddReaction: Boolean get() = mine.size < MAX_OWN_REACTIONS

    /** [emoji] added when not [mine], else taken away. */
    fun toggled(emoji: String): FilesyncDiscussion {
        val adding = emoji !in mine
        val delta = if (adding) 1 else -1
        val counted = if (reactions.any { it.emoji == emoji }) {
            reactions.map { if (it.emoji == emoji) it.copy(count = it.count + delta) else it }
        } else {
            reactions + FilesyncReactionCount(emoji, 1)
        }
        return copy(mine = if (adding) mine + emoji else mine - emoji, reactions = counted.filter { it.count > 0 })
    }
}

/**
 * Reactions are the server's group reactions on the file. Comments are files on the Comment file system, grouped by
 * the file's global transit id; on this SelectiveShare drive each is encrypted with the commented file's key under an
 * IV of its own, so whoever may read the file reads its comments.
 */
class FilesyncDiscussions(
    private val reactionProvider: DriveFileGroupReactionProvider,
    private val driveQueryProvider: DriveQueryProvider,
    private val driveUploadProvider: DriveUploadProvider,
    private val credentialsManager: CredentialsManager,
    private val clock: Clock = Clock.System,
) {

    suspend fun load(nodeId: Uuid, handle: FilesyncHandle): FilesyncDiscussion = coroutineScope {
        val self = credentialsManager.getActiveCredentials()?.domain
        val reactions = async { listReactions(handle.fileId) }
        val comments = async { listComments(handle, self) }
        val rows = reactions.await().sortedBy { it.created }.mapNotNull { row -> emojiOf(row.reactionContent)?.let { row.odinId to it } }
        FilesyncDiscussion(
            nodeId = nodeId,
            loading = false,
            reactions = rows.groupingBy { it.second }.eachCount().map { (emoji, count) -> FilesyncReactionCount(emoji, count) },
            mine = rows.filter { it.first == self }.map { it.second }.distinct(),
            comments = comments.await(),
        )
    }

    suspend fun react(handle: FilesyncHandle, emoji: String) =
        reactionProvider.addReaction(FilesyncProtocol.DriveAlias, handle.fileId, reactionOf(emoji), recipients = emptyList())

    suspend fun unreact(handle: FilesyncHandle, emoji: String) =
        reactionProvider.deleteReaction(FilesyncProtocol.DriveAlias, handle.fileId, reactionOf(emoji), recipients = emptyList())

    suspend fun comment(handle: FilesyncHandle, body: String): FilesyncComment {
        val globalTransitId = requireNotNull(handle.globalTransitId) { "file ${handle.fileId} has no global transit id" }
        // A copy: the upload may clear its key header, and this one's key is the file's.
        val keyHeader = KeyHeader(ByteArrayUtil.getRndByteArray(16), SecureByteArray(handle.keyHeader.aesKey.unsafeBytes.copyOf()))
        val id = Uuid.random()
        val createdMs = clock.now().toEpochMilliseconds()
        val metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            accessControlList = AccessControlList(requiredSecurityGroup = SecurityGroupType.Owner.value),
            appData = UploadAppFileMetaData(
                uniqueId = id,
                fileType = FeedProtocol.CommentFileType,
                dataType = 0,
                userDate = createdMs,
                content = OdinSystemSerializer.serialize(PostCommentContent(body = body)),
            ),
            referencedFile = GlobalTransitIdFileIdentifier(globalTransitId, filesyncLabeledDrive.drive),
        ).encryptContent(keyHeader)
        driveUploadProvider.uploadFile(
            UploadFileRequest(
                driveId = FilesyncProtocol.DriveAlias,
                keyHeader = keyHeader,
                metadata = metadata,
                fileSystemType = FileSystemType.Comment,
                withCommentedFileKey = true,
            )
        )
        return FilesyncComment(id, credentialsManager.getActiveCredentials()?.domain, mine = true, body = body, createdMs = createdMs)
    }

    private suspend fun listReactions(fileId: Uuid): List<GroupReactionItem> {
        val all = mutableListOf<GroupReactionItem>()
        var cursor: Int? = null
        do {
            val page = reactionProvider.listReactions(FilesyncProtocol.DriveAlias, fileId, cursor, REACTION_PAGE)
            all += page.reactions
            cursor = page.cursor.takeIf { page.reactions.size == REACTION_PAGE && it != cursor }
        } while (cursor != null)
        return all
    }

    private suspend fun listComments(handle: FilesyncHandle, self: OdinId?): List<FilesyncComment> {
        val globalTransitId = handle.globalTransitId ?: return emptyList()
        val page = driveQueryProvider.queryBatch(
            driveId = FilesyncProtocol.DriveAlias,
            request = QueryBatchRequest(
                queryParams = FileQueryParams(fileType = listOf(FeedProtocol.CommentFileType), groupId = listOf(globalTransitId)),
                resultOptionsRequest = QueryBatchResultOptionsRequest(maxRecords = COMMENT_LIMIT, includeMetadataHeader = true),
            ),
            fileSystemType = FileSystemType.Comment,
            commentedFileKey = handle.keyHeader.aesKey,
        )
        return page.searchResults.mapNotNull { it.toComment(self) }.sortedBy { it.createdMs }
    }

    private fun HomebaseFile.toComment(self: OdinId?): FilesyncComment? {
        val body = fileMetadata.appData.content
            ?.let { runCatching { OdinSystemSerializer.deserialize<PostCommentContent>(it).body }.getOrNull() }
            ?: return null
        val author = fileMetadata.originalAuthor ?: fileMetadata.senderOdinId
        return FilesyncComment(
            id = fileMetadata.appData.uniqueId ?: fileId,
            author = author,
            mine = author == null || author == self,
            body = body,
            createdMs = fileMetadata.created.milliseconds,
        )
    }

    private fun reactionOf(emoji: String) = OdinSystemSerializer.serialize(ReactionContent(emoji))

    private fun emojiOf(reaction: String) = runCatching { OdinSystemSerializer.deserialize<ReactionContent>(reaction).emoji }.getOrNull()

    private companion object {
        const val REACTION_PAGE = 100
        const val COMMENT_LIMIT = 1000
    }
}
