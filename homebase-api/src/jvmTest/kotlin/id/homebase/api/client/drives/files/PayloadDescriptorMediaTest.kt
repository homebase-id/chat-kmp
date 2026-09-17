package id.homebase.api.client.drives.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PayloadDescriptorMediaTest {

    private fun payload(contentType: String?, descriptorContent: String? = null) =
        PayloadDescriptor(key = "chat_web0", contentType = contentType, descriptorContent = descriptorContent)

    @Test
    fun isAudio_matchesAudioFamilyOnly() {
        assertTrue(payload("audio/mp4").isAudio())
        assertTrue(payload("audio/mpeg").isAudio())
        assertFalse(payload("video/mp4").isAudio())
        assertFalse(payload("application/pdf").isAudio())
        assertFalse(payload(null).isAudio())
    }

    @Test
    fun isVisualMedia_matchesImageVideoAndHls() {
        assertTrue(payload("image/jpeg").isVisualMedia())
        assertTrue(payload("image/gif").isVisualMedia())
        assertTrue(payload("video/mp4").isVisualMedia())
        assertTrue(payload("application/vnd.apple.mpegurl").isVisualMedia())
        assertFalse(payload("audio/mp4").isVisualMedia())
        assertFalse(payload("application/pdf").isVisualMedia())
        assertFalse(payload(null).isVisualMedia())
    }

    @Test
    fun audioLengthSeconds_readsRecordedLength() {
        val descriptor = DescriptorContent.descriptorContentFromAudioFile("voice.m4a", 42)
        assertEquals(42, payload("audio/mp4", descriptor).audioLengthSeconds())
    }

    @Test
    fun audioLengthSeconds_isNullWhenLengthMissingOrZero() {
        assertNull(payload("audio/mp4").audioLengthSeconds())
        assertNull(payload("audio/mp4", """{"name":"song.mp3"}""").audioLengthSeconds())
        assertNull(
            payload("audio/mp4", DescriptorContent.descriptorContentFromAudioFile("song.mp3", 0))
                .audioLengthSeconds()
        )
    }

    @Test
    fun audioLengthSeconds_isNullForNonAudioPayload() {
        val descriptor = DescriptorContent.descriptorContentFromAudioFile("voice.m4a", 42)
        assertNull(payload("application/octet-stream", descriptor).audioLengthSeconds())
    }
}
