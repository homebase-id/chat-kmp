package id.homebase.chat.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which file the sender sees for an image they sent: [localImageOnly] and [imageStandIn]. */
class SentImageRuleTest {
    private val image = LocalAttachmentContext.Image(localFilePath = "/tmp/photo.jpg", aspectRatio = null)
    private val video = LocalAttachmentContext.Video(ByteArray(1), localFilePath = "/tmp/clip.mp4", aspectRatio = null)

    @Test
    fun anEncryptedImageShowsTheSentCopyOnceItHasItsIv() {
        assertNull(image.localImageOnly(isEncrypted = true, ivPresent = true))
        assertEquals("/tmp/photo.jpg", image.imageStandIn, "while it loads, and if it can't")
    }

    @Test
    fun anEncryptedImageWithNoIvYetShowsTheSendersFile() {
        assertEquals("/tmp/photo.jpg", image.localImageOnly(isEncrypted = true, ivPresent = false))
    }

    @Test
    fun aPlaintextImageShowsTheSentCopyItNeedsNoIv() {
        assertNull(image.localImageOnly(isEncrypted = false, ivPresent = false))
        assertEquals("/tmp/photo.jpg", image.imageStandIn, "while it loads, and if it can't")
    }

    @Test
    fun nothingStandsInForAVideoOrAMissingFile() {
        assertNull(video.imageStandIn)
        assertNull(video.localImageOnly(isEncrypted = true, ivPresent = false))
        val none: LocalAttachmentContext? = null
        assertNull(none.imageStandIn)
        assertNull(none.localImageOnly(isEncrypted = true, ivPresent = false))
    }
}
