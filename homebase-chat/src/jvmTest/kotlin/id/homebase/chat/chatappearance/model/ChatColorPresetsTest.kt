package id.homebase.chat.chatappearance.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatColorPresetsTest {
    @Test
    fun allPresetsHaveUniqueIds() {
        val ids = ChatColorPresets.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun has22BuiltInColors() {
        assertEquals(22, ChatColorPresets.all.size)
    }

    @Test
    fun solidColorsHaveNonZeroArgb() {
        ChatColorPresets.solids.forEach { color ->
            assertTrue(color.colorArgb != 0L, "Solid color ${color.id} has zero ARGB")
        }
    }

    @Test
    fun gradientsHaveExactlyTwoColors() {
        ChatColorPresets.gradients.forEach { gradient ->
            assertEquals(2, gradient.colorsArgb.size, "Gradient ${gradient.id} should have 2 colors")
        }
    }

    @Test
    fun gradientsHaveValidAngle() {
        ChatColorPresets.gradients.forEach { gradient ->
            assertTrue(gradient.angleDegrees in 0f..360f, "Gradient ${gradient.id} angle ${gradient.angleDegrees} out of range")
        }
    }

    @Test
    fun findByIdReturnsCorrectPreset() {
        ChatColorPresets.all.forEach { preset ->
            val found = ChatColorPresets.findById(preset.id)
            assertNotNull(found)
            assertEquals(preset.id, found.id)
        }
    }

    @Test
    fun findByIdReturnsNullForUnknown() {
        assertNull(ChatColorPresets.findById("nonexistent"))
    }

    @Test
    fun ultramarineIsDefault() {
        assertEquals("ultramarine", ChatColorPresets.default.id)
    }

    @Test
    fun has13SolidsAnd9Gradients() {
        assertEquals(13, ChatColorPresets.solids.size)
        assertEquals(9, ChatColorPresets.gradients.size)
    }
}
