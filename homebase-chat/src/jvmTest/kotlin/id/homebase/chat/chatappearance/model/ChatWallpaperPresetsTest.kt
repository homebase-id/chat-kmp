package id.homebase.chat.chatappearance.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatWallpaperPresetsTest {

    @Test
    fun allPresetsHaveUniqueIds() {
        assertEquals(
            ChatWallpaperPresets.all.size,
            ChatWallpaperPresets.all.map { it.id }.distinct().size,
        )
    }

    @Test
    fun has21BuiltInWallpapers() {
        assertEquals(21, ChatWallpaperPresets.all.size)
    }

    @Test
    fun has12SolidsAnd9Gradients() {
        assertEquals(12, ChatWallpaperPresets.solids.size)
        assertEquals(9, ChatWallpaperPresets.gradientsList.size)
    }

    @Test
    fun solidWallpapersHaveNonZeroArgb() {
        ChatWallpaperPresets.solids.forEach {
            assertTrue(it.colorArgb != 0L, "${it.id} zero")
        }
    }

    @Test
    fun gradientWallpapersHave16ColorStops() {
        ChatWallpaperPresets.gradientsList.forEach {
            assertEquals(16, it.colorsArgb.size, "${it.id} colors")
            assertEquals(16, it.positions.size, "${it.id} positions")
        }
    }

    @Test
    fun gradientPositionsMonotonicallyIncreasing() {
        ChatWallpaperPresets.gradientsList.forEach { wp ->
            for (i in 1 until wp.positions.size) {
                assertTrue(wp.positions[i] >= wp.positions[i - 1], "${wp.id}[$i]")
            }
        }
    }

    @Test
    fun gradientPositionsStartAtZeroEndAtOne() {
        ChatWallpaperPresets.gradientsList.forEach {
            assertEquals(0f, it.positions.first(), "${it.id} start")
            assertEquals(1f, it.positions.last(), "${it.id} end")
        }
    }

    @Test
    fun findByIdReturnsCorrectPreset() {
        ChatWallpaperPresets.all.forEach {
            assertNotNull(ChatWallpaperPresets.findById(it.id))
            assertEquals(it.id, ChatWallpaperPresets.findById(it.id)!!.id)
        }
    }

    @Test
    fun findByIdReturnsNullForUnknown() {
        assertNull(ChatWallpaperPresets.findById("nonexistent"))
    }
}
