package id.homebase.core.feed

import id.homebase.api.crypto.Md5
import id.homebase.core.feed.services.FeedProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedProtocolTest {

    private val payloadKeyRegex = Regex("^[a-z0-9_]{8,10}$")

    @Test
    fun fileTypesMatchReference() {
        assertEquals(101, FeedProtocol.PostFileType)
        assertEquals(102, FeedProtocol.DraftPostFileType)
        assertEquals(103, FeedProtocol.ChannelDefinitionFileType)
        assertEquals(801, FeedProtocol.CommentFileType)
    }

    @Test
    fun dataTypesMatchReference() {
        assertEquals(100, FeedProtocol.TweetDataType)
        assertEquals(200, FeedProtocol.MediaDataType)
        assertEquals(300, FeedProtocol.ArticleDataType)
    }

    @Test
    fun contentVersionsAreOne() {
        assertEquals(1, FeedProtocol.PostVersion)
        assertEquals(1, FeedProtocol.CommentVersion)
    }

    @Test
    fun payloadKeyConstantsMatchReference() {
        assertEquals("pst_mdi", FeedProtocol.MediaPayloadKeyPrefix)
        assertEquals("pst_links", FeedProtocol.LinksPayloadKey)
        assertEquals("pst_text", FeedProtocol.FullTextPayloadKey)
        assertEquals("cmmnt_md", FeedProtocol.CommentMediaPayloadKey)
    }

    @Test
    fun fixedPayloadKeysSatisfyServerConstraint() {
        assertTrue(payloadKeyRegex.matches(FeedProtocol.LinksPayloadKey))
        assertTrue(payloadKeyRegex.matches(FeedProtocol.FullTextPayloadKey))
        assertTrue(payloadKeyRegex.matches(FeedProtocol.CommentMediaPayloadKey))
    }

    @Test
    fun mediaPayloadKeyIsZeroPaddedAndValid() {
        assertEquals("pst_mdi_00", FeedProtocol.mediaPayloadKey(0))
        assertEquals("pst_mdi_07", FeedProtocol.mediaPayloadKey(7))
        assertEquals("pst_mdi_12", FeedProtocol.mediaPayloadKey(12))
        assertTrue(payloadKeyRegex.matches(FeedProtocol.mediaPayloadKey(0)))
        assertTrue(payloadKeyRegex.matches(FeedProtocol.mediaPayloadKey(99)))
    }

    @Test
    fun publicChannelDriveAliasIsDerivedFromMd5() {
        assertEquals(Md5.toGuidId("public_channel_drive"), FeedProtocol.PublicChannelDriveAlias)
    }

    @Test
    fun channelDriveTypeMatchesReference() {
        assertEquals(
            "8f448716-e34c-edf9-0141-45e043ca6612",
            FeedProtocol.ChannelDriveType.toString(),
        )
    }
}
