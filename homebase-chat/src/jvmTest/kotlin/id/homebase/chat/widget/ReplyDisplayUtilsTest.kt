package id.homebase.chat.widget

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
