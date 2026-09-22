package id.homebase.core.emoji

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Lives in jvmTest, not commonTest: it reads the emoji_data.json compose resource, and the Karma
// harness the wasmJs test task uses does not serve composeResources (404).
class EmojiShortcodeIndexJvmTest {

    @Test
    fun indexMatchesThePickerDatasetAndIsBuiltOnce() = runTest {
        val index = EmojiShortcodes.index()
        val pickerEmojis = EmojiParser.loadEmojiData().emojis.map { it.emoji }.toSet()

        EXPECTED_SHORTCODE_EMOJI.forEach { (shortcode, emoji) ->
            assertEquals(emoji, index[shortcode], shortcode)
        }
        assertNull(index["notreal"])
        assertTrue(index.size > 2000, "expected the full shortcode set, got ${index.size}")
        index.values.forEach { assertTrue(it in pickerEmojis, "$it is not in the picker dataset") }

        assertTrue(EmojiShortcodes.index() === index)
    }
}
