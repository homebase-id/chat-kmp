package id.homebase.chat.conversationlist

import id.homebase.api.client.link.LinkPreview
import id.homebase.api.client.location.LocationPreview
import id.homebase.chat.services.renderer.LinkPreviewRenderer
import id.homebase.chat.services.renderer.LocationPreviewRenderer
import id.homebase.core.util.stripComposerLineBreakArtifacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the composer never-send-empty policy (#1104).
 *
 * The bug had two shapes, both from the composer gating on the editor's `annotatedString` while
 * sending `toMarkdown()`: a typed/pasted URL could momentarily serialize to a blank body, and — the
 * common real-world case — a stray blank line serializes to a non-blank `"<br>"` artifact that a
 * plain `isNotBlank()` gate happily sent as a blank bubble (mobile) / literal `<br>` (web). The fix
 * feeds the NORMALIZED body (`toMessageMarkdown`, which strips the `<br>` artifact) to
 * [shouldSendComposerMessage], so both shapes resolve to an empty body and are withheld.
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

    @Test
    fun brArtifactBody_afterNormalization_doesNotSend() {
        // A stray blank line in the editor serializes to "\n<br>" — non-blank, so a plain gate
        // would send it. The composer normalizes first (toMessageMarkdown → strip), collapsing it
        // to "", which the gate correctly withholds. This is the dominant real-world #1104 case.
        val normalized = "\n<br>".stripComposerLineBreakArtifacts()
        assertFalse(shouldSendComposerMessage(normalized, listOf(linkPreview)))
    }

    @Test
    fun brArtifactAboveLink_afterNormalization_sendsCleanBody() {
        // "<br>\nurl" (leading blank line then a link) must send the link — with NO stray <br>.
        val normalized = "\n<br>\nhttps://homebase.id".stripComposerLineBreakArtifacts()
        assertEquals("https://homebase.id", normalized)
        assertTrue(shouldSendComposerMessage(normalized, emptyList()))
    }
}
