package id.homebase.chat.conversationlist

import id.homebase.api.client.link.LinkPreview
import id.homebase.api.client.location.LocationPreview
import id.homebase.chat.services.renderer.LinkPreviewRenderer
import id.homebase.chat.services.renderer.LocationPreviewRenderer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the composer never-send-empty policy (#1104).
 *
 * The bug: the composer gated on the editor's `annotatedString` (non-blank) but sent
 * `toMarkdown()`. When a typed/pasted URL momentarily serialized to a blank markdown body,
 * the non-blank gate passed and an empty message was sent on both sides. The fix gates on the
 * serialized body via [shouldSendComposerMessage] — so a blank body with only an auto-detected
 * link preview staged must NOT send.
 */
class ComposerSendPolicyTest {

    private val linkPreview = LinkPreviewRenderer(
        LinkPreview(
            title = "Example",
            url = "https://example.com",
            description = "",
            imageUrl = null,
            imageHeight = null,
            imageWidth = null,
        ),
    )

    // A user-initiated (non-link) renderer: staging it means the user chose to send it, so it
    // enables send even with no text.
    private val locationRenderer = LocationPreviewRenderer(
        LocationPreview(
            lat = 1.0,
            lon = 2.0,
            address = "somewhere",
            imageUrl = null,
            imageWidth = null,
            imageHeight = null,
        ),
    )

    @Test
    fun blankBodyWithOnlyLinkPreview_doesNotSend() {
        // The exact #1104 case: URL typed, preview auto-staged, but toMarkdown() came back blank.
        assertFalse(shouldSendComposerMessage("", listOf(linkPreview)))
    }

    @Test
    fun blankBodyWithNothingStaged_doesNotSend() {
        assertFalse(shouldSendComposerMessage("", emptyList()))
    }

    @Test
    fun nonBlankBody_sends() {
        assertTrue(shouldSendComposerMessage("https://example.com", emptyList()))
        assertTrue(shouldSendComposerMessage("hello", listOf(linkPreview)))
    }

    @Test
    fun blankBodyWithUserInitiatedAttachment_sends() {
        // Attachment-only send (location) is legitimate even with no text.
        assertTrue(shouldSendComposerMessage("", listOf(locationRenderer)))
    }

    @Test
    fun whitespaceOnlyBody_isTreatedAsBlank() {
        assertFalse(shouldSendComposerMessage("   \n ", listOf(linkPreview)))
    }
}
