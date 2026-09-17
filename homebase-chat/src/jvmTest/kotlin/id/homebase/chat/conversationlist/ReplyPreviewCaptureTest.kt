package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.ReplyContext
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ReplyPreviewCaptureTest {

    private val thumb = EmbeddedThumb(pixelWidth = 20, pixelHeight = 4, contentType = "image/webp", content = "AAAA")

    private fun message(content: String, vararg payloads: PayloadDescriptor) = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = content,
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = OdinId("alice.example.com"),
        sender = OdinId("alice.example.com"),
        displayName = "Alice",
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = thumb,
        payloads = persistentListOf(*payloads),
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
    )

    private fun voiceNote(lengthSeconds: Int) = PayloadDescriptor(
        key = "chat_web0",
        contentType = "audio/mp4",
        descriptorContent = DescriptorContent.descriptorContentFromAudioFile("rec.m4a", lengthSeconds),
    )

    @Test
    fun voiceNote_dropsWaveformThumb_andCarriesAudioContextWithLength() {
        val preview = message("", voiceNote(15)).toReplyPreview()

        assertNull(preview.previewThumbnail)
        assertEquals(ReplyContext.Audio(15), ReplyContext.fromJson(preview.context))
    }

    @Test
    fun voiceNote_withUnknownLength_carriesAudioContextWithoutLength() {
        val preview = message("", voiceNote(0)).toReplyPreview()

        assertEquals(ReplyContext.Audio(null), ReplyContext.fromJson(preview.context))
    }

    @Test
    fun audioWithCaption_keepsCaption_andDropsThumb() {
        val preview = message("  listen to this", voiceNote(3)).toReplyPreview()

        assertEquals("listen to this", preview.message)
        assertNull(preview.previewThumbnail)
        assertEquals(ReplyContext.Audio(3), ReplyContext.fromJson(preview.context))
    }

    @Test
    fun image_keepsThumb_andHasNoContext() {
        val image = PayloadDescriptor(key = "chat_web0", contentType = "image/jpeg")
        val preview = message("", image).toReplyPreview()

        assertEquals(thumb, preview.previewThumbnail)
        assertNull(preview.context)
    }

    @Test
    fun textOnly_keepsThumbField_andHasNoContext() {
        val preview = message("hello").toReplyPreview()

        assertEquals(thumb, preview.previewThumbnail)
        assertNull(preview.context)
    }
}
