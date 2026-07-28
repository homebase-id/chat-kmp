package id.homebase.chat.widget

import id.homebase.api.util.truncateToCodePoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyDisplayUtilsTest {

    // ── resolveReplyAuthorName ──

    @Test
    fun authorName_showsYou_whenCurrentUserMatchesAuthor() {
        val result = resolveReplyAuthorName(
            authorOdinId = "biswa.homebase.id",
            currentOdinId = "biswa.homebase.id",
            resolvedDisplayName = "Bishwajeet Parhi",
            youLabel = "You",
        )
        assertEquals("You", result)
    }

    @Test
    fun authorName_showsResolvedName_whenDifferentUser() {
        val result = resolveReplyAuthorName(
            authorOdinId = "todd.mitchell.me",
            currentOdinId = "biswa.homebase.id",
            resolvedDisplayName = "Todd Mitchell",
            youLabel = "You",
        )
        assertEquals("Todd Mitchell", result)
    }

    @Test
    fun authorName_fallsBackToOdinId_whenNoResolvedName() {
        val result = resolveReplyAuthorName(
            authorOdinId = "michael.seifert.page",
            currentOdinId = "biswa.homebase.id",
            resolvedDisplayName = null,
            youLabel = "You",
        )
        assertEquals("michael.seifert.page", result)
    }

    @Test
    fun authorName_fallsBackToOdinId_whenCurrentOdinIdEmpty() {
        val result = resolveReplyAuthorName(
            authorOdinId = "biswa.homebase.id",
            currentOdinId = "",
            resolvedDisplayName = null,
            youLabel = "You",
        )
        assertEquals("biswa.homebase.id", result)
    }

    @Test
    fun authorName_showsResolvedName_whenCurrentOdinIdEmpty() {
        val result = resolveReplyAuthorName(
            authorOdinId = "todd.mitchell.me",
            currentOdinId = "",
            resolvedDisplayName = "Todd Mitchell",
            youLabel = "You",
        )
        assertEquals("Todd Mitchell", result)
    }

    // ── resolveReplyContentText ──

    @Test
    fun contentText_showsReplyText_whenPresent() {
        val result = resolveReplyContentText(
            replyText = "Hello world",
            contentLabelText = null,
            hasThumbnail = false,
            hasMedia = false,
            mediaFallbackLabel = "Media",
        )
        assertEquals("Hello world", result)
    }

    @Test
    fun contentText_showsLabel_whenNoTextAndNoThumbnail() {
        val result = resolveReplyContentText(
            replyText = "",
            contentLabelText = "Audio",
            hasThumbnail = false,
            hasMedia = true,
            mediaFallbackLabel = "Media",
        )
        assertEquals("Audio", result)
    }

    @Test
    fun contentText_suppressesLabel_whenThumbnailVisible() {
        val result = resolveReplyContentText(
            replyText = "",
            contentLabelText = "Image",
            hasThumbnail = true,
            hasMedia = true,
            mediaFallbackLabel = "Media",
        )
        assertEquals("Media", result)
    }

    @Test
    fun contentText_showsCaption_whenThumbnailVisibleWithText() {
        val result = resolveReplyContentText(
            replyText = "Check this out",
            contentLabelText = null,
            hasThumbnail = true,
            hasMedia = true,
            mediaFallbackLabel = "Media",
        )
        assertEquals("Check this out", result)
    }

    @Test
    fun contentText_showsMediaFallback_whenNoTextNoLabelButHasMedia() {
        val result = resolveReplyContentText(
            replyText = "",
            contentLabelText = null,
            hasThumbnail = false,
            hasMedia = true,
            mediaFallbackLabel = "Media",
        )
        assertEquals("Media", result)
    }

    @Test
    fun contentText_showsEmpty_whenNothingAvailable() {
        val result = resolveReplyContentText(
            replyText = "",
            contentLabelText = null,
            hasThumbnail = false,
            hasMedia = false,
            mediaFallbackLabel = "Media",
        )
        assertEquals("", result)
    }

    @Test
    fun contentText_prefersLabel_overMediaFallback_whenNoThumbnail() {
        val result = resolveReplyContentText(
            replyText = "",
            contentLabelText = "Video",
            hasThumbnail = false,
            hasMedia = true,
            mediaFallbackLabel = "Media",
        )
        assertEquals("Video", result)
    }

    // ── reply-quote "…" regression (leading whitespace) ──
    // A replied-to message whose content starts with newlines/spaces rendered
    // as a bare "…": maxLines=1 lays out the leading blank line and
    // TextOverflow.Ellipsis paints the overflow marker on it. The body itself
    // renders clean because it goes through the markdown parser (which collapses
    // leading blank lines); the quote path did not. Trim at render so already-sent
    // messages display correctly without a re-send.

    @Test
    fun contentText_trimsLeadingNewlines_soQuoteIsRealTextNotEllipsis() {
        val result = resolveReplyContentText(
            replyText = "\n\nWhats wrong with this one",
            contentLabelText = null,
            hasThumbnail = false,
            hasMedia = false,
            mediaFallbackLabel = "Media",
        )
        assertEquals("Whats wrong with this one", result)
    }

    @Test
    fun contentText_trimsSurroundingWhitespace() {
        val result = resolveReplyContentText(
            replyText = "   Hello   ",
            contentLabelText = null,
            hasThumbnail = false,
            hasMedia = false,
            mediaFallbackLabel = "Media",
        )
        assertEquals("Hello", result)
    }

    @Test
    fun contentText_whitespaceOnlyBecomesEmpty_notBlankEllipsis() {
        val result = resolveReplyContentText(
            replyText = "\n \n",
            contentLabelText = null,
            hasThumbnail = false,
            hasMedia = false,
            mediaFallbackLabel = "Media",
        )
        assertEquals("", result)
    }

    @Test
    fun contentText_whitespaceOnlyWithMedia_fallsBackToMediaLabel() {
        val result = resolveReplyContentText(
            replyText = "\n\n",
            contentLabelText = null,
            hasThumbnail = false,
            hasMedia = true,
            mediaFallbackLabel = "Media",
        )
        assertEquals("Media", result)
    }

    @Test
    fun contentText_interiorNewlinesSurvive_onlyEdgesTrimmed() {
        val result = resolveReplyContentText(
            replyText = "\nLine1\nLine2\n",
            contentLabelText = null,
            hasThumbnail = false,
            hasMedia = false,
            mediaFallbackLabel = "Media",
        )
        assertEquals("Line1\nLine2", result)
    }

    @Test
    fun capture_trimsBeforeTruncate_soBudgetHoldsRealTextNotNewlines() {
        // toReplyPreview() / ReplyPreviewBar must trim BEFORE truncateToCodePoints(80):
        // a message led by 80 newlines would otherwise capture zero real text.
        val content = "\n".repeat(80) + "Real text after the blank lines"
        assertEquals(
            "Real text after the blank lines",
            content.trim().truncateToCodePoints(80),
        )
    }

    // ── shouldShowContentIcon ──

    @Test
    fun contentIcon_shown_whenNoThumbnailAndLabelExists() {
        assertTrue(shouldShowContentIcon(hasThumbnail = false, contentLabelText = "Image"))
    }

    @Test
    fun contentIcon_hidden_whenThumbnailVisible() {
        assertFalse(shouldShowContentIcon(hasThumbnail = true, contentLabelText = "Image"))
    }

    @Test
    fun contentIcon_hidden_whenNoLabel() {
        assertFalse(shouldShowContentIcon(hasThumbnail = false, contentLabelText = null))
    }
}
