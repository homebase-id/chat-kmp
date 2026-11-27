package id.homebase.chat.widget.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.payload.PayloadLocation
import id.homebase.api.client.payload.PeerFileRef
import id.homebase.api.common.OdinId
import id.homebase.api.file.AppCacheDirs
import id.homebase.chat.conversationlist.FullScreenOverlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Every player reads the overlay through this one converter: a field it drops is a desktop view-once in export/, or a followed video read from our own drive. */
class ToVideoDataTest {

    private val payload = PayloadDescriptor(key = "vid_payload", contentType = "video/quicktime", descriptorContent = "{\"v\":1}")

    private fun overlay(peer: PeerFileRef?, scratchSub: String) = FullScreenOverlay.VideoPlayerData(
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        payloadKey = payload.key,
        keyHeader = KeyHeader.empty(),
        payload = payload,
        peer = peer,
        scratchSub = scratchSub,
    )

    @Test
    fun theConverterCarriesWhereTheBytesLiveAndWhereTheyAreDecrypted() {
        val peer = PeerFileRef(OdinId("sam.dotyou.cloud"), Uuid.random())
        val overlay = overlay(peer, AppCacheDirs.VIEW_ONCE)

        val video = overlay.toVideoData()

        assertEquals(PayloadLocation.Peer(peer, overlay.driveId), video.location)
        assertEquals(AppCacheDirs.VIEW_ONCE, video.scratchSub)
        assertEquals(overlay.fileId, video.fileId)
        assertEquals(overlay.payloadKey, video.payloadKey)
        assertEquals(payload.descriptorContent, video.descriptorContent)
        assertEquals(payload.contentType, video.contentType)
    }

    @Test
    fun ourOwnVideoStaysOnOurDrive() {
        val overlay = overlay(peer = null, scratchSub = AppCacheDirs.EXPORT)

        assertEquals(PayloadLocation.Own(overlay.driveId, overlay.fileId), overlay.toVideoData().location)
    }
}
