package id.homebase.api.client.drives.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PayloadDescriptorMediaTest {

    private fun payload(contentType: String?, descriptorContent: String? = null) =
        PayloadDescriptor(key = "chat_web0", contentType = contentType, descriptorContent = descriptorContent)

    private data class Kind(val image: Boolean, val video: Boolean, val visual: Boolean, val audio: Boolean)

    @Test
    fun classifiesContentTypes() {
        val cases = listOf(
            "image/jpeg" to Kind(image = true, video = false, visual = true, audio = false),
            "image/gif" to Kind(image = true, video = false, visual = true, audio = false),
            "video/mp4" to Kind(image = false, video = true, visual = true, audio = false),
            HLS_PLAYLIST_CONTENT_TYPE to Kind(image = false, video = true, visual = true, audio = false),
            "audio/mp4" to Kind(image = false, video = false, visual = false, audio = true),
            "application/pdf" to Kind(image = false, video = false, visual = false, audio = false),
            null to Kind(image = false, video = false, visual = false, audio = false),
        )
        for ((contentType, expected) in cases) {
            val p = payload(contentType)
            assertEquals(expected, Kind(p.isImage(), p.isVideo(), p.isVisualMedia(), p.isAudio()), "$contentType")
        }
    }

    @Test
    fun audioLengthSeconds_isTheRecordedLengthOrNull() {
        val recorded = DescriptorContent.descriptorContentFromAudioFile("voice.m4a", 42)
        assertEquals(42, payload("audio/mp4", recorded).audioLengthSeconds())
        assertNull(payload("audio/mp4").audioLengthSeconds())
        assertNull(payload("audio/mp4", """{"name":"song.mp3"}""").audioLengthSeconds())
        assertNull(payload("audio/mp4", DescriptorContent.descriptorContentFromAudioFile("song.mp3", 0)).audioLengthSeconds())
        assertNull(payload("application/octet-stream", recorded).audioLengthSeconds())
    }
}
