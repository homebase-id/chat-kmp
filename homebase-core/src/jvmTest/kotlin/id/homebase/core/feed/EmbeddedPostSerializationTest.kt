package id.homebase.core.feed

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.feed.services.EmbeddedPost
import id.homebase.core.feed.services.PostType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The repost compose route carries the quoted source as an [EmbeddedPost] serialized to JSON and
 * re-parsed on the other side. This guards the riskiest bit of the create-repost flow: that the
 * fields survive the [OdinSystemSerializer] round-trip the nav arg relies on.
 */
class EmbeddedPostSerializationTest {

    @Test
    fun embeddedPostRoundTrips() {
        val original = EmbeddedPost(
            author = "frodo.dotyou.cloud",
            caption = "One does not simply walk into Mordor 🌋",
            type = PostType.Tweet,
            fileId = "11112222-3333-4444-5555-666677778888",
            globalTransitId = "aaaabbbb-cccc-dddd-eeee-ffff00001111",
            userDate = 1_718_000_000_000L,
            previewThumbnail = null,
        )

        val json = OdinSystemSerializer.serialize(original)
        val parsed = OdinSystemSerializer.deserialize<EmbeddedPost>(json)

        assertEquals(original, parsed)
        assertEquals(original.author, parsed.author)
        assertEquals(original.caption, parsed.caption)
        assertEquals(PostType.Tweet, parsed.type)
        assertEquals(original.fileId, parsed.fileId)
        assertEquals(original.globalTransitId, parsed.globalTransitId)
        assertEquals(original.userDate, parsed.userDate)
    }
}
