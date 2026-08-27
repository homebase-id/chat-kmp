package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.CommentPreview
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.LocalAppMetadata
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostCommentContent
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.feed.services.decodeReactionEmoji
import id.homebase.core.feed.services.previewBody
import id.homebase.core.feed.services.toCommentItem
import id.homebase.core.feed.services.toFeedPostItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class FeedModelsTest {

    private val driveId = SystemDriveConstants.publicPostChannelDrive.alias

    private fun keyHeader() = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16)))

    private fun postContentJson(caption: String) = OdinSystemSerializer.serialize(
        PostContent(
            version = FeedProtocol.PostVersion,
            id = Uuid.random().toString(),
            channelId = driveId.toString(),
            type = PostType.Media,
            caption = caption,
            slug = "a-slug",
            reactAccess = ReactAccess.EmojiOnly,
        )
    )

    private fun file(
        uniqueId: Uuid? = null,
        globalTransitId: Uuid? = null,
        groupId: Uuid? = null,
        fileType: Int = FeedProtocol.PostFileType,
        content: String? = null,
        createdMs: Long = 0,
        userDate: Long? = null,
        localReactions: List<String>? = null,
        reactionPreview: ReactionSummary? = null,
        senderOdinId: OdinId? = null,
        originalAuthor: OdinId? = null,
        versionTag: Uuid? = null,
    ) = HomebaseFile(
        fileId = Uuid.random(),
        driveId = driveId,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = keyHeader(),
        fileMetadata = FileMetadata(
            globalTransitId = globalTransitId,
            created = UnixTimeUtc(createdMs),
            updated = UnixTimeUtc(createdMs),
            senderOdinId = senderOdinId,
            originalAuthor = originalAuthor,
            versionTag = versionTag,
            reactionPreview = reactionPreview,
            localAppData = localReactions?.let { LocalAppMetadata(localReactions = it) },
            appData = AppFileMetaData(
                uniqueId = uniqueId,
                groupId = groupId,
                fileType = fileType,
                userDate = userDate,
                content = content,
            ),
        ),
        serverMetadata = ServerMetadata(),
    )

    @Test
    fun toFeedPostItem_feedReferenceWithoutUniqueId_fallsBackToGlobalTransitId() {
        val gtid = Uuid.random()
        val item = file(
            uniqueId = null,
            globalTransitId = gtid,
            content = postContentJson("a followed identity's post"),
        ).toFeedPostItem()

        assertNotNull(item, "a feed-drive reference carries no uniqueId and must not be dropped")
        assertEquals(gtid, item.id)
        assertEquals(gtid, item.globalTransitId)
    }

    @Test
    fun toFeedPostItem_withUniqueId_prefersUniqueIdOverGlobalTransitId() {
        val uniqueId = Uuid.random()
        val gtid = Uuid.random()
        val item = file(
            uniqueId = uniqueId,
            globalTransitId = gtid,
            content = postContentJson("my own post"),
        ).toFeedPostItem()

        assertEquals(uniqueId, item?.id)
        assertEquals(gtid, item?.globalTransitId)
    }

    @Test
    fun toFeedPostItem_withoutUniqueIdOrGlobalTransitId_returnsNull() {
        assertNull(file(content = postContentJson("unaddressable")).toFeedPostItem())
    }

    @Test
    fun toFeedPostItem_malformedContent_yieldsBestEffortItemWithEnvelopeIntact() {
        val uniqueId = Uuid.random()
        val author = OdinId("frodo.homebase.id")
        val versionTag = Uuid.random()
        val item = file(
            uniqueId = uniqueId,
            content = "{not json at all",
            createdMs = 4_200,
            userDate = 1_700,
            senderOdinId = author,
            originalAuthor = author,
            versionTag = versionTag,
            reactionPreview = ReactionSummary(totalCommentCount = 7),
        ).toFeedPostItem()

        assertNotNull(item, "a parse failure must not drop the post")
        assertEquals("", item.caption)
        assertEquals(PostType.Tweet, item.type)
        assertEquals("", item.channelId)
        assertEquals("", item.slug)
        assertEquals(ReactAccess.All, item.reactAccess)
        assertNull(item.embeddedPost)

        // Envelope fields come off the HomebaseFile, so they survive the failed parse.
        assertEquals(uniqueId, item.id)
        assertEquals(4_200, item.createdMs)
        assertEquals(1_700, item.userDateMs)
        assertEquals(author, item.senderOdinId)
        assertEquals(author, item.originalAuthor)
        assertEquals(versionTag, item.versionTag)
        assertEquals(7, item.commentCount)
    }

    @Test
    fun toFeedPostItem_absentContent_yieldsBestEffortItem() {
        val item = file(uniqueId = Uuid.random(), content = null).toFeedPostItem()

        assertNotNull(item)
        assertEquals("", item.caption)
        assertEquals(PostType.Tweet, item.type)
    }

    @Test
    fun toFeedPostItem_liftsPostContentFields() {
        val item = file(
            uniqueId = Uuid.random(),
            content = postContentJson("Sunset over the bay"),
        ).toFeedPostItem()

        assertEquals("Sunset over the bay", item?.caption)
        assertEquals(PostType.Media, item?.type)
        assertEquals(driveId.toString(), item?.channelId)
        assertEquals("a-slug", item?.slug)
        assertEquals(ReactAccess.EmojiOnly, item?.reactAccess)
    }

    @Test
    fun toFeedPostItem_decodesOwnReactionsAndDropsUndecodableEntries() {
        val heart = OdinSystemSerializer.serialize(ReactionContent(emoji = "❤️"))
        val item = file(
            uniqueId = Uuid.random(),
            content = postContentJson("react to me"),
            localReactions = listOf(heart, "not-json"),
        ).toFeedPostItem()

        assertEquals(listOf("❤️"), item?.ownReactions)
    }

    @Test
    fun toCommentItem_groupIdEqualToPost_isTopLevel() {
        val postId = Uuid.random()
        val commentId = Uuid.random()
        val item = file(
            uniqueId = commentId,
            groupId = postId,
            fileType = FeedProtocol.CommentFileType,
            content = OdinSystemSerializer.serialize(
                PostCommentContent(version = FeedProtocol.CommentVersion, body = "nice one")
            ),
        ).toCommentItem(topLevelPostId = postId)

        assertNotNull(item)
        assertEquals(commentId, item.id)
        assertEquals(postId, item.postId)
        assertEquals("nice one", item.body)
        assertNull(item.replyToId, "a comment whose groupId is the post is top-level")
    }

    @Test
    fun toCommentItem_groupIdDifferentFromPost_isReplyToThatGroupId() {
        val postId = Uuid.random()
        val parentCommentId = Uuid.random()
        val item = file(
            uniqueId = Uuid.random(),
            groupId = parentCommentId,
            fileType = FeedProtocol.CommentFileType,
            content = OdinSystemSerializer.serialize(
                PostCommentContent(version = FeedProtocol.CommentVersion, body = "a reply")
            ),
        ).toCommentItem(topLevelPostId = postId)

        assertEquals(postId, item?.postId)
        assertEquals(parentCommentId, item?.replyToId)
    }

    @Test
    fun toCommentItem_withoutUniqueId_returnsNull() {
        val postId = Uuid.random()
        assertNull(
            file(groupId = postId, fileType = FeedProtocol.CommentFileType)
                .toCommentItem(topLevelPostId = postId)
        )
    }

    @Test
    fun toCommentItem_withoutGroupId_returnsNull() {
        assertNull(
            file(uniqueId = Uuid.random(), fileType = FeedProtocol.CommentFileType)
                .toCommentItem(topLevelPostId = Uuid.random())
        )
    }

    @Test
    fun toCommentItem_malformedContent_yieldsEmptyBodyNotNull() {
        val postId = Uuid.random()
        val item = file(
            uniqueId = Uuid.random(),
            groupId = postId,
            fileType = FeedProtocol.CommentFileType,
            content = "{",
        ).toCommentItem(topLevelPostId = postId)

        assertNotNull(item, "a parse failure must not drop the comment")
        assertEquals("", item.body)
        assertNull(item.mediaPayloadKey)
    }

    @Test
    fun decodeReactionEmoji_validReactionContent_returnsGlyph() {
        val raw = OdinSystemSerializer.serialize(ReactionContent(emoji = "🎉"))
        assertEquals("🎉", decodeReactionEmoji(raw))
    }

    @Test
    fun decodeReactionEmoji_garbage_returnsNull() {
        assertNull(decodeReactionEmoji("🎉"))
        assertNull(decodeReactionEmoji(""))
    }

    private fun commentPreview(content: String, isEncrypted: Boolean = false) = CommentPreview(
        created = 0,
        updated = 0,
        fileId = Uuid.random().toString(),
        isEncrypted = isEncrypted,
        odinId = "sam.homebase.id",
        content = content,
    )

    @Test
    fun previewBody_validCommentContent_returnsBody() {
        val content = OdinSystemSerializer.serialize(
            PostCommentContent(version = FeedProtocol.CommentVersion, body = "great shot 😍")
        )
        assertEquals("great shot 😍", commentPreview(content).previewBody())
    }

    @Test
    fun previewBody_encryptedOrBlank_returnsBlank() {
        val content = OdinSystemSerializer.serialize(
            PostCommentContent(version = FeedProtocol.CommentVersion, body = "secret")
        )
        assertEquals("", commentPreview(content, isEncrypted = true).previewBody())
        assertEquals("", commentPreview("   ").previewBody())
    }

    @Test
    fun previewBody_malformedJson_returnsBlank() {
        assertEquals("", commentPreview("just some text").previewBody())
    }
}
