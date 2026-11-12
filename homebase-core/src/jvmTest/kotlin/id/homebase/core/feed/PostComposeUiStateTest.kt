package id.homebase.core.feed

import id.homebase.core.feed.services.PostAudience
import id.homebase.core.ui.screens.feed.PostComposeUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostComposeUiStateTest {

    @Test
    fun emptyCompose_cannotPost() {
        assertFalse(PostComposeUiState().canPost)
        assertFalse(PostComposeUiState(caption = "   ").canPost)
    }

    @Test
    fun text_canPost_unlessAlreadyPosting() {
        assertTrue(PostComposeUiState(caption = "hi").canPost)
        assertFalse(PostComposeUiState(caption = "hi", isPosting = true).canPost)
    }

    @Test
    fun circlesAudience_needsACircleChosen() {
        val circles = PostComposeUiState(caption = "hi", audience = PostAudience.Circles)
        assertFalse(circles.canPost)
        assertTrue(circles.copy(selectedCircleIds = setOf("c1")).canPost)
    }
}
