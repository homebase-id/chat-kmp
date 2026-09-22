package id.homebase.core.feed

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.feed.services.ChannelDefinition
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostCommentContent
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostContentSerializationTest {

    @Test
    fun mediaPostRoundTrips() {
        val post = PostContent(
            version = FeedProtocol.PostVersion,
            id = "11112222-3333-4444-5555-666677778888",
            channelId = FeedProtocol.PublicChannelDriveAlias.toString(),
            type = PostType.Media,
            caption = "Sunset over the bay 🌅",
            slug = "sunset-over-the-bay",
            primaryMediaKey = FeedProtocol.mediaPayloadKey(0),
            reactAccess = ReactAccess.EmojiOnly,
        )

        val decoded: PostContent = OdinSystemSerializer.deserialize(
            OdinSystemSerializer.serialize(post),
        )

        assertEquals(post, decoded)
    }

    @Test
    fun unknownKeysAreTolerated() {
        // A newer client adds a field we don't know about; ignoreUnknownKeys must drop it.
        val json = """
            {
              "version": 1,
              "id": "abc",
              "channelId": "chan",
              "type": "Tweet",
              "caption": "hi",
              "slug": "hi",
              "futureFieldFromANewerClient": { "nested": true }
            }
        """.trimIndent()

        val decoded: PostContent = OdinSystemSerializer.deserialize(json)

        assertEquals(PostType.Tweet, decoded.type)
        assertEquals("hi", decoded.caption)
    }

    @Test
    fun omittedNullablesAndDefaultsAreApplied() {
        // Minimal JSON: every optional field is absent.
        val json = """
            {
              "version": 1,
              "id": "abc",
              "channelId": "chan",
              "type": "Tweet",
              "caption": "hi",
              "slug": "hi"
            }
        """.trimIndent()

        val decoded: PostContent = OdinSystemSerializer.deserialize(json)

        assertNull(decoded.captionRichText)
        assertNull(decoded.primaryMediaKey)
        assertNull(decoded.embeddedPost)
        assertNull(decoded.sourceUrl)
        assertNull(decoded.abstract)
        assertNull(decoded.body)
        assertEquals(ReactAccess.All, decoded.reactAccess)
    }

    @Test
    fun commentContentRoundTrips() {
        val comment = PostCommentContent(
            version = FeedProtocol.CommentVersion,
            body = "Nice shot! 😍",
            mediaPayloadKey = FeedProtocol.CommentMediaPayloadKey,
        )

        val decoded: PostCommentContent = OdinSystemSerializer.deserialize(
            OdinSystemSerializer.serialize(comment),
        )

        assertEquals(comment, decoded)
        assertNull(decoded.bodyRichText)
    }

    @Test
    fun channelDefinitionDefaultsApply() {
        val json = """
            {
              "name": "Public Posts",
              "slug": "public-posts"
            }
        """.trimIndent()

        val decoded: ChannelDefinition = OdinSystemSerializer.deserialize(json)

        assertEquals("Public Posts", decoded.name)
        assertEquals("", decoded.description)
        assertTrue(decoded.showOnHomePage)
        assertNull(decoded.templateId)
        assertEquals(false, decoded.isCollaborative)
    }
}
