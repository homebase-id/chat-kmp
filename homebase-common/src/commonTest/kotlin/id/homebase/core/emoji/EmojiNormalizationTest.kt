package id.homebase.core.emoji

import id.homebase.core.emoji.EmojiNormalization.containsEmoji
import id.homebase.core.emoji.EmojiNormalization.distinctByEmoji
import id.homebase.core.emoji.EmojiNormalization.equalsEmoji
import id.homebase.core.emoji.EmojiNormalization.normalize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiNormalizationTest {

    private val VS15 = "︎"
    private val VS16 = "️"
    private val THUMBS_UP = "👍"
    private val THUMBS_UP_LIGHT = "👍🏻"
    private val THUMBS_UP_DARK = "👍🏿"
    private val HEART = "❤"
    private val FAMILY_MWG = "👨‍👩‍👧"

    @Test
    fun normalize_stripsVS16() {
        assertEquals(THUMBS_UP, normalize(THUMBS_UP + VS16))
    }

    @Test
    fun normalize_stripsVS15() {
        assertEquals(HEART, normalize(HEART + VS15))
    }

    @Test
    fun normalize_idempotent() {
        val raw = THUMBS_UP + VS16
        assertEquals(normalize(raw), normalize(normalize(raw)))
    }

    @Test
    fun normalize_keepsZWJ() {
        assertEquals(FAMILY_MWG, normalize(FAMILY_MWG))
    }

    @Test
    fun normalize_keepsSkinTone() {
        assertEquals(THUMBS_UP_LIGHT, normalize(THUMBS_UP_LIGHT))
    }

    @Test
    fun normalize_emptyString() {
        assertEquals("", normalize(""))
    }

    @Test
    fun equalsEmoji_treatsVS16FormsAsEqual() {
        assertTrue(equalsEmoji(THUMBS_UP, THUMBS_UP + VS16))
        assertTrue(equalsEmoji(HEART, HEART + VS16))
    }

    @Test
    fun equalsEmoji_skinTonesAreDistinct() {
        assertFalse(equalsEmoji(THUMBS_UP_LIGHT, THUMBS_UP_DARK))
        assertFalse(equalsEmoji(THUMBS_UP, THUMBS_UP_LIGHT))
    }

    @Test
    fun distinctByEmoji_collapsesVS16Duplicates_keepsFirst() {
        val input = listOf(THUMBS_UP + VS16, "🎉", THUMBS_UP)
        assertEquals(listOf(THUMBS_UP + VS16, "🎉"), input.distinctByEmoji())
    }

    @Test
    fun distinctByEmoji_preservesOrder() {
        val input = listOf("🎉", "🔥", "👍", "🎉", "🔥")
        assertEquals(listOf("🎉", "🔥", "👍"), input.distinctByEmoji())
    }

    @Test
    fun distinctByEmoji_emptyInput() {
        assertEquals(emptyList(), emptyList<String>().distinctByEmoji())
    }

    @Test
    fun containsEmoji_findsVS16Variant() {
        val list = listOf(THUMBS_UP, "🎉")
        assertTrue(list.containsEmoji(THUMBS_UP + VS16))
    }

    @Test
    fun containsEmoji_returnsFalseForDifferentSkinTone() {
        val list = listOf(THUMBS_UP_LIGHT)
        assertFalse(list.containsEmoji(THUMBS_UP_DARK))
    }

    @Test
    fun containsEmoji_emptyList() {
        assertFalse(emptyList<String>().containsEmoji(THUMBS_UP))
    }
}
