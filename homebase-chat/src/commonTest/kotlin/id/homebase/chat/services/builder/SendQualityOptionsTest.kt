package id.homebase.chat.services.builder

import id.homebase.api.image.MediaQuality
import id.homebase.api.image.SendQuality
import id.homebase.api.video.VideoQuality
import id.homebase.upload.MetadataPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class SendQualityOptionsTest {
    @Test
    fun photosAndVideosEachGetTheirOwnSetting() {
        val options = SendQuality(MediaQuality.ORIGINAL, MediaQuality.STANDARD).uploadOptions()
        assertEquals(MediaQuality.ORIGINAL, options.quality)
        assertEquals(VideoQuality.STANDARD, options.videoQuality)
        assertEquals(MetadataPolicy.STRIP, options.metadata)

        assertEquals(VideoQuality.HIGH, SendQuality(MediaQuality.STANDARD, MediaQuality.HD).uploadOptions().videoQuality)
    }
}
