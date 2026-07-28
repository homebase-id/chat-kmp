package id.homebase.chat.widget

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [hasContent] decides whether a message body paints as text (vs. being treated as media-only /
 * reply-only). richeditor's `<br>` empty-paragraph artifact — a bare `"<br>"` or a `"\n<br>"` from
 * an older or other-platform sender that predates the composer normalization — must read as no
 * content so the bubble doesn't paint a stray break next to its media (#1104).
 */
class MessageContentHasContentTest {

    @Test
    fun realText_hasContent() {
        assertTrue("hello".hasContent())
        assertTrue("https://homebase.id".hasContent())
        assertTrue("para one\n\npara two".hasContent())
    }

    @Test
    fun blankAndArtifactBodies_haveNoContent() {
        assertFalse("".hasContent())
        assertFalse("   ".hasContent())
        assertFalse("<br>".hasContent())
        assertFalse("\n<br>".hasContent()) // the case the old `== "<br>"` exact-match missed
    }

    @Test
    fun brArtifactAboveRealText_stillHasContent() {
        assertTrue("\n<br>\nhttps://homebase.id".hasContent())
    }
}
