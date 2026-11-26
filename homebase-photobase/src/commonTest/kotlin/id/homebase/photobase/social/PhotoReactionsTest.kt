package id.homebase.photobase.social

import id.homebase.api.client.drives.files.LocalAppMetadata
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.photobase.gallery.galleryTestFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoReactionsTest {
    @Test
    fun ourReactionsAreTheBareEmojiTheListComparesAgainst() {
        val stored = listOf(OdinSystemSerializer.serialize(ReactionContent(emoji = "❤️")), "not json")
        val photo = galleryTestFile(1).let { it.copy(fileMetadata = it.fileMetadata.copy(localAppData = LocalAppMetadata(localReactions = stored))) }

        assertEquals(listOf("❤️"), photo.toPhotoReactions().own)
    }

    @Test
    fun aFamilyOrAFlagIsAReactionButTextIsnt() {
        assertTrue(isReactionEmoji("👨‍👩‍👧‍👦"))
        assertTrue(isReactionEmoji("🏳️‍🌈"))
        assertFalse(isReactionEmoji(" "))
        assertFalse(isReactionEmoji("this is not one emoji"))
    }
}
