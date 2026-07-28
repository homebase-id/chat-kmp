package id.homebase.core.feed

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.ReactAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `reactAccess` on the wire is dotyoucore-js's `true | false | 'comment' | 'emoji'` union, not the
 * Kotlin enum name. Posts carrying the boolean form used to fail the whole [PostContent] parse and
 * drop out of the feed, so both directions of [id.homebase.core.feed.services.ReactAccessSerializer]
 * are pinned here.
 */
class ReactAccessSerializerTest {

    private fun decode(reactAccessJson: String): ReactAccess {
        val json = """
            {
              "version": 1,
              "id": "abc",
              "channelId": "chan",
              "type": "Tweet",
              "caption": "hi",
              "slug": "hi",
              "reactAccess": $reactAccessJson
            }
        """.trimIndent()
        return OdinSystemSerializer.deserialize<PostContent>(json).reactAccess
    }

    @Test
    fun decode_webBooleanTrue_isAll() {
        assertEquals(ReactAccess.All, decode("true"))
    }

    @Test
    fun decode_webBooleanFalse_isNone() {
        assertEquals(ReactAccess.None, decode("false"))
    }

    @Test
    fun decode_webCommentString_isCommentOnly() {
        assertEquals(ReactAccess.CommentOnly, decode("\"comment\""))
    }

    @Test
    fun decode_webEmojiString_isEmojiOnly() {
        assertEquals(ReactAccess.EmojiOnly, decode("\"emoji\""))
    }

    @Test
    fun decode_nativeEnumNames_areAlsoAccepted() {
        assertEquals(ReactAccess.CommentOnly, decode("\"CommentOnly\""))
        assertEquals(ReactAccess.EmojiOnly, decode("\"EmojiOnly\""))
        assertEquals(ReactAccess.None, decode("\"None\""))
    }

    @Test
    fun decode_unknownValue_fallsBackToAll() {
        assertEquals(ReactAccess.All, decode("\"banana\""))
        assertEquals(ReactAccess.All, decode("42"))
        assertEquals(ReactAccess.All, decode("""{ "nested": true }"""))
    }

    @Test
    fun decode_absentField_defaultsToAll() {
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
        assertEquals(ReactAccess.All, OdinSystemSerializer.deserialize<PostContent>(json).reactAccess)
    }

    @Test
    fun encode_writesTheWebWireFormTheJsClientAccepts() {
        fun encoded(value: ReactAccess) = OdinSystemSerializer.serialize(
            PostContent(
                version = 1,
                id = "abc",
                channelId = "chan",
                caption = "hi",
                slug = "hi",
                reactAccess = value,
            )
        )

        assertTrue(encoded(ReactAccess.All).contains("\"reactAccess\":true"))
        assertTrue(encoded(ReactAccess.None).contains("\"reactAccess\":false"))
        assertTrue(encoded(ReactAccess.CommentOnly).contains("\"reactAccess\":\"comment\""))
        assertTrue(encoded(ReactAccess.EmojiOnly).contains("\"reactAccess\":\"emoji\""))
    }

    @Test
    fun encodeThenDecode_roundTripsEveryValue() {
        for (value in ReactAccess.entries) {
            val post = PostContent(
                version = 1,
                id = "abc",
                channelId = "chan",
                caption = "hi",
                slug = "hi",
                reactAccess = value,
            )
            val decoded: PostContent =
                OdinSystemSerializer.deserialize(OdinSystemSerializer.serialize(post))
            assertEquals(value, decoded.reactAccess)
        }
    }
}
