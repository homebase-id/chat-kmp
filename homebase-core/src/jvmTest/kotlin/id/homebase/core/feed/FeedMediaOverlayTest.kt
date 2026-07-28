package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.ui.screens.feed.widget.feedMediaOverlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * [feedMediaOverlay] is what tells the shared chat viewers where a feed post's bytes actually live:
 * whether they're encrypted, and — for a followed identity's post — which peer to read them from.
 */
class FeedMediaOverlayTest {

    private val channelDrive = Uuid.random()
    private val author = OdinId("frodo.homebase.id")

    private fun mediaPayload(index: Int, contentType: String = "image/jpeg") = PayloadDescriptor(
        key = FeedProtocol.mediaPayloadKey(index),
        contentType = contentType,
    )

    private fun post(
        payloads: List<PayloadDescriptor>,
        senderOdinId: OdinId? = null,
        globalTransitId: Uuid? = null,
        channelId: String = channelDrive.toString(),
        isEncrypted: Boolean = false,
    ) = FeedPostItem(
        id = Uuid.random(),
        fileId = Uuid.random(),
        globalTransitId = globalTransitId,
        driveId = Uuid.random(),
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        payloads = payloads,
        caption = "a caption",
        type = PostType.Media,
        channelId = channelId,
        slug = "",
        reactAccess = ReactAccess.All,
        embeddedPost = null,
        userDateMs = 0,
        createdMs = 0,
        previewThumbnail = null,
        reactionPreview = null,
        senderOdinId = senderOdinId,
        originalAuthor = null,
        versionTag = null,
        ownReactions = emptyList(),
        commentCount = 0,
        isEncrypted = isEncrypted,
        acl = null,
    )

    @Test
    fun ownPublicPost_isFlaggedUnencryptedAndReadsLocally() {
        val item = post(listOf(mediaPayload(0)), isEncrypted = false)

        val overlay = assertIs<FullScreenOverlay.ViewMessageData>(feedMediaOverlay(item, 0, "Frodo"))
        assertEquals(false, overlay.isEncrypted)
        assertNull(overlay.remoteOdinId, "our own post's bytes are local")
        assertNull(overlay.globalTransitId)
        assertEquals(item.driveId, overlay.driveId)
    }

    @Test
    fun ownEncryptedPost_keepsTheEncryptedFlag() {
        val item = post(listOf(mediaPayload(0)), isEncrypted = true)

        val overlay = assertIs<FullScreenOverlay.ViewMessageData>(feedMediaOverlay(item, 0, "Frodo"))
        assertEquals(true, overlay.isEncrypted)
    }

    @Test
    fun peerPost_carriesTheAuthorAndTransitIdSoTheReadGoesOverPeer() {
        val gtid = Uuid.random()
        val item = post(
            payloads = listOf(mediaPayload(0), mediaPayload(1)),
            senderOdinId = author,
            globalTransitId = gtid,
        )

        val overlay = assertIs<FullScreenOverlay.ViewMessageData>(feedMediaOverlay(item, 1, "Frodo"))
        assertEquals(author, overlay.remoteOdinId)
        assertEquals(gtid, overlay.globalTransitId)
        // The peer read addresses the author's CHANNEL drive, not our feed drive.
        assertEquals(channelDrive, overlay.driveId)
        assertEquals(FeedProtocol.mediaPayloadKey(1), overlay.selectedPayloadKey)
        assertEquals(2, overlay.payloads.size, "the whole media list travels so the pager can swipe")
    }

    @Test
    fun peerPostWithUnparseableChannel_staysLocalWithNoPeerFields() {
        val item = post(
            payloads = listOf(mediaPayload(0)),
            senderOdinId = author,
            globalTransitId = Uuid.random(),
            channelId = "not-a-uuid",
        )

        val overlay = assertIs<FullScreenOverlay.ViewMessageData>(feedMediaOverlay(item, 0, "Frodo"))
        assertNull(overlay.remoteOdinId, "without a channel drive there is nothing to address over peer")
        assertNull(overlay.globalTransitId)
        assertEquals(item.driveId, overlay.driveId)
    }

    @Test
    fun videoPayload_routesToThePlayerWithTheEncryptionFlag() {
        val item = post(listOf(mediaPayload(0, contentType = "video/mp4")), isEncrypted = false)

        val overlay = assertIs<FullScreenOverlay.VideoPlayerData>(feedMediaOverlay(item, 0, "Frodo"))
        assertEquals(false, overlay.isEncrypted)
        assertEquals(FeedProtocol.mediaPayloadKey(0), overlay.payloadKey)
    }

    @Test
    fun indexPastTheEnd_yieldsNoOverlay() {
        assertNull(feedMediaOverlay(post(listOf(mediaPayload(0))), 1, "Frodo"))
    }
}
