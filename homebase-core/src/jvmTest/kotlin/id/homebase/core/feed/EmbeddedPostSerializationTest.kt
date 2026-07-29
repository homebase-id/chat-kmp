package id.homebase.core.feed

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.feed.services.EmbeddedPost
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The quoted-source card renders straight off [EmbeddedPost], so every field it reads has to
 * survive the [OdinSystemSerializer] round-trip *and* match the web wire key exactly — with
 * `ignoreUnknownKeys` a misnamed field parses silently to null and blanks the card rather than
 * failing loudly (which is exactly how `author` vs `authorOdinId` went unnoticed).
 */
class EmbeddedPostSerializationTest {

    @Test
    fun embeddedPostRoundTrips() {
        val original = EmbeddedPost(
            authorOdinId = "frodo.dotyou.cloud",
            caption = "One does not simply walk into Mordor 🌋",
            type = PostType.Tweet,
            channelId = "11112222-3333-4444-5555-666677778888",
            fileId = "11112222-3333-4444-5555-666677778888",
            globalTransitId = "aaaabbbb-cccc-dddd-eeee-ffff00001111",
            permalink = "https://frodo.dotyou.cloud/posts/public/one-does-not",
            userDate = 1_718_000_000_000L,
            previewThumbnail = null,
        )

        val json = OdinSystemSerializer.serialize(original)
        val parsed = OdinSystemSerializer.deserialize<EmbeddedPost>(json)

        assertEquals(original, parsed)
    }

    /**
     * A repost exactly as dotyoucore-js writes it (`RepostButton` spreads the whole source
     * `PostContent` and adds the envelope fields). Guards the field names the quote card depends
     * on, and that web-only keys we don't model (`lastModified`, `primaryMediaFile`,
     * `captionAsRichText`, `isCollaborative`) don't fail the parse and drop the repost.
     */
    @Test
    fun webRepostWireShapeParses() {
        val wire = """
            {
              "type": "Tweet",
              "caption": "worth a read",
              "id": "9c1f1a11-0000-4000-8000-000000000001",
              "slug": "9c1f1a11-0000-4000-8000-000000000001",
              "channelId": "20d1b3c8-0000-4000-8000-0000000000aa",
              "reactAccess": true,
              "embeddedPost": {
                "id": "5d5c2b22-0000-4000-8000-000000000002",
                "channelId": "20d1b3c8-0000-4000-8000-0000000000bb",
                "caption": "One does not simply walk into Mordor 🌋",
                "slug": "one-does-not",
                "type": "Media",
                "reactAccess": true,
                "isCollaborative": false,
                "authorOdinId": "frodo.dotyou.cloud",
                "fileId": "11112222-3333-4444-5555-666677778888",
                "globalTransitId": "aaaabbbb-cccc-dddd-eeee-ffff00001111",
                "lastModified": 1718000000001,
                "permalink": "https://frodo.dotyou.cloud/posts/20d1b3c8-0000-4000-8000-0000000000bb/one-does-not",
                "userDate": 1718000000000,
                "previewThumbnail": {
                  "pixelWidth": 100,
                  "pixelHeight": 75,
                  "contentType": "image/webp",
                  "content": "UklGRg=="
                },
                "payloads": [
                  { "key": "pst_mdi_00", "contentType": "image/webp", "bytesWritten": 4096 },
                  { "key": "pst_mdi_01", "contentType": "image/webp", "bytesWritten": 2048 }
                ],
                "primaryMediaFile": {
                  "fileKey": "pst_mdi_00",
                  "fileId": "11112222-3333-4444-5555-666677778888",
                  "type": "image/webp"
                },
                "captionAsRichText": [
                  { "type": "paragraph", "children": [{ "text": "One does not simply" }] }
                ]
              }
            }
        """.trimIndent()

        val post = OdinSystemSerializer.deserialize<PostContent>(wire)
        val embedded = assertNotNull(post.embeddedPost, "repost dropped by the parser")

        assertEquals("frodo.dotyou.cloud", embedded.authorOdinId)
        assertEquals("20d1b3c8-0000-4000-8000-0000000000bb", embedded.channelId)
        assertEquals("11112222-3333-4444-5555-666677778888", embedded.fileId)
        assertEquals("aaaabbbb-cccc-dddd-eeee-ffff00001111", embedded.globalTransitId)
        assertEquals(1_718_000_000_000L, embedded.userDate)
        assertEquals(PostType.Media, embedded.type)
        assertTrue(embedded.permalink!!.startsWith("https://frodo.dotyou.cloud/posts/"))
        assertEquals(listOf("pst_mdi_00", "pst_mdi_01"), embedded.payloads?.map { it.key })
        assertNotNull(embedded.previewThumbnail, "preview thumbnail object dropped")
    }

    /**
     * A caption-less media repost — the source post is a photo with no text. Every text field is
     * absent, so the card has to fall back to author + media instead of rendering an empty box.
     */
    @Test
    fun captionLessMediaRepostKeepsAuthorAndPayloads() {
        val wire = """
            {
              "type": "Tweet",
              "caption": "",
              "channelId": "20d1b3c8-0000-4000-8000-0000000000aa",
              "embeddedPost": {
                "channelId": "20d1b3c8-0000-4000-8000-0000000000bb",
                "caption": "",
                "type": "Media",
                "authorOdinId": "samwise.dotyou.cloud",
                "fileId": "11112222-3333-4444-5555-666677778888",
                "globalTransitId": "aaaabbbb-cccc-dddd-eeee-ffff00001111",
                "userDate": 1718000000000,
                "payloads": [{ "key": "pst_mdi_00", "contentType": "image/webp" }]
              }
            }
        """.trimIndent()

        val embedded = assertNotNull(
            OdinSystemSerializer.deserialize<PostContent>(wire).embeddedPost,
        )
        assertEquals("samwise.dotyou.cloud", embedded.authorOdinId)
        assertEquals(listOf("pst_mdi_00"), embedded.payloads?.map { it.key })
    }
}
