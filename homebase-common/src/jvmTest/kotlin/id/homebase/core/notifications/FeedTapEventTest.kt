package id.homebase.core.notifications

import id.homebase.core.ui.navigation.Route
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedTapEventTest {

    // FeedNotificationMapper: a post or comment carries the post's globalTransitId, a reaction our post's fileId.
    private val tagId = "6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b"

    @Test
    fun postIdTag_nativeFeed_opensPostDetail() {
        assertEquals(
            NotificationNavigationEvent.OpenFeedPost(tagId),
            buildFeedTapEvent(tagId, useNativeFeed = true),
        )
    }

    @Test
    fun postIdTag_webViewFeed_opensFeedTab() {
        assertEquals(
            NotificationNavigationEvent.OpenApp(Route.Feed),
            buildFeedTapEvent(tagId, useNativeFeed = false),
        )
    }

    @Test
    fun blankEmptyOrMalformedTag_opensFeedTab() {
        for (tag in listOf("", "00000000-0000-0000-0000-000000000000", "post-7")) {
            assertEquals(
                NotificationNavigationEvent.OpenApp(Route.Feed),
                buildFeedTapEvent(tag, useNativeFeed = true),
                "tag=$tag",
            )
        }
    }
}
