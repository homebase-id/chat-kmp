package id.homebase.core.ui.screens.feed

import id.homebase.core.feed.services.PostType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArticleWebUrlTest {
    private fun url(
        type: PostType = PostType.Article,
        author: String? = null,
        channel: String = "chan-id",
        slug: String = "my-post",
    ) = articleWebUrl("frodo.dotyou.cloud", author, type, channel, slug, "post-id")

    @Test
    fun articleOpensTheFeedPreviewPageOnOwnHost() {
        assertEquals("https://frodo.dotyou.cloud/apps/feed/preview/frodo.dotyou.cloud/chan-id/my-post", url())
    }

    @Test
    fun otherAuthorIsAddressedByIdentityKeyButServedFromOwnHost() {
        assertEquals(
            "https://frodo.dotyou.cloud/apps/feed/preview/sam.dotyou.cloud/chan-id/my-post",
            url(author = "sam.dotyou.cloud"),
        )
    }

    @Test
    fun blankSlugFallsBackToPostIdAndSegmentsAreEncoded() {
        assertEquals(
            "https://frodo.dotyou.cloud/apps/feed/preview/frodo.dotyou.cloud/chan-id/post-id",
            url(slug = ""),
        )
        assertEquals(
            "https://frodo.dotyou.cloud/apps/feed/preview/frodo.dotyou.cloud/chan-id/a%2Fb",
            url(slug = "a/b"),
        )
    }

    @Test
    fun otherPostTypesStayNative() {
        assertNull(url(type = PostType.Tweet))
        assertNull(url(type = PostType.Media))
    }
}
