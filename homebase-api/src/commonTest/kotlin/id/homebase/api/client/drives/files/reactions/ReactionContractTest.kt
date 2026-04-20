@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.drives.files.reactions

import id.homebase.api.client.drives.files.LocalAppMetadata
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

class ReactionContractTest {

    @Test
    fun reactionContentRoundTripsThroughOdinSystemSerializer() {
        val encoded = OdinSystemSerializer.serialize(ReactionContent(emoji = "❤️"))
        assertEquals("""{"emoji":"❤️"}""", encoded)

        val decoded = OdinSystemSerializer.deserialize<ReactionContent>("""{"emoji":"🚀"}""")
        assertEquals("🚀", decoded.emoji)
    }

    @Test
    fun localAppMetadataTolerates_missingLocalReactions() {
        val decoded = OdinSystemSerializer.deserialize<LocalAppMetadata>("{}")
        assertNull(decoded.localReactions)
    }

    @Test
    fun localAppMetadataTolerates_explicitNullLocalReactions() {
        val decoded = OdinSystemSerializer.deserialize<LocalAppMetadata>(
            """{"localReactions": null}"""
        )
        assertNull(decoded.localReactions)
    }

    @Test
    fun localAppMetadataPreservesLocalReactionsAsRawJsonStrings() {
        val decoded = OdinSystemSerializer.deserialize<LocalAppMetadata>(
            """{"localReactions": ["{\"emoji\":\"❤️\"}", "{\"emoji\":\"🚀\"}"]}"""
        )
        assertEquals(
            listOf("""{"emoji":"❤️"}""", """{"emoji":"🚀"}"""),
            decoded.localReactions
        )
    }
}
