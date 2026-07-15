package id.homebase.core.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedContentDescriptorTest {

    private fun descriptor(
        text: String?,
        url: String?,
        fileNames: List<String> = emptyList(),
    ) = SharedContentDescriptor(
        contentType = if (url != null) SharedContentType.URL else SharedContentType.TEXT,
        text = text,
        url = url,
        fileNames = fileNames,
        mimeTypes = fileNames.map { "image/jpeg" },
        targetConversationId = "c0ffee",
    )

    /**
     * The #1097 regression: iOS vends an empty `public.plain-text` next to the real
     * Google Maps link, so `text` is blank-but-not-null. A `text ?: url` fallback
     * returns that blank and sends an empty message while the link sits unused.
     */
    @Test
    fun blank_text_falls_through_to_url() {
        val mapsLink = "https://maps.app.goo.gl/abc123"
        assertEquals(mapsLink, descriptor(text = "", url = mapsLink).resolveMessageBody())
        assertEquals(mapsLink, descriptor(text = "   ", url = mapsLink).resolveMessageBody())
        assertEquals(mapsLink, descriptor(text = "\n", url = mapsLink).resolveMessageBody())
    }

    @Test
    fun absent_text_falls_through_to_url() {
        val link = "https://example.com"
        assertEquals(link, descriptor(text = null, url = link).resolveMessageBody())
    }

    /**
     * A caption must ride *with* its link, not replace it — Android sends the whole
     * EXTRA_TEXT (caption + link), and dropping the link here is the same data loss
     * as #1097 wearing a different hat.
     */
    @Test
    fun caption_and_url_are_sent_together() {
        assertEquals(
            "Taj Mahal\nhttps://maps.app.goo.gl/abc123",
            descriptor(text = "Taj Mahal", url = "https://maps.app.goo.gl/abc123").resolveMessageBody(),
        )
    }

    /** The common case: the host puts the link in the text too. Don't send it twice. */
    @Test
    fun url_already_present_in_text_is_not_duplicated() {
        val link = "https://maps.app.goo.gl/abc123"
        assertEquals("Taj Mahal $link", descriptor(text = "Taj Mahal $link", url = link).resolveMessageBody())
        assertEquals(link, descriptor(text = link, url = link).resolveMessageBody())
    }

    @Test
    fun plain_text_share_is_unaffected() {
        assertEquals("hello", descriptor(text = "hello", url = null).resolveMessageBody())
    }

    /** Nothing usable resolves to blank, which the caller turns into an error instead of a send. */
    @Test
    fun nothing_usable_resolves_blank() {
        assertEquals("", descriptor(text = null, url = null).resolveMessageBody())
        assertEquals("", descriptor(text = "  ", url = "  ").resolveMessageBody())
    }

    // --- never-send-empty guard -------------------------------------------------
    // The policy behind processPendingSharedContent's refusal branch. Sending a share
    // that resolved to nothing produces a blank bubble, which is silent data loss: the
    // sender believes they shared something. Refusing turns any future extraction miss
    // into a visible failure instead.

    /** The #1097 shape that reached users: nothing survived extraction. Must not send. */
    @Test
    fun share_with_nothing_usable_is_not_sendable() {
        assertFalse(descriptor(text = null, url = null).hasSendableContent())
        assertFalse(descriptor(text = "", url = null).hasSendableContent())
        assertFalse(descriptor(text = "   ", url = "  ").hasSendableContent())
    }

    /** A blank text next to a real link is sendable — the link is the content (#1097). */
    @Test
    fun blank_text_with_a_url_is_still_sendable() {
        assertTrue(descriptor(text = "", url = "https://maps.app.goo.gl/abc123").hasSendableContent())
    }

    /** Files carry no body text by design, so an image share must not be refused. */
    @Test
    fun file_only_share_is_sendable_without_any_text() {
        assertTrue(descriptor(text = null, url = null, fileNames = listOf("share_1.jpg")).hasSendableContent())
        assertTrue(descriptor(text = "  ", url = null, fileNames = listOf("share_1.jpg")).hasSendableContent())
    }

    @Test
    fun ordinary_text_share_is_sendable() {
        assertTrue(descriptor(text = "hello", url = null).hasSendableContent())
    }
}
