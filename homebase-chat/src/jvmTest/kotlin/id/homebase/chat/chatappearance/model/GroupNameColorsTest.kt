package id.homebase.chat.chatappearance.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupNameColorsTest {

    @Test
    fun paletteHasExactly36Entries() {
        assertEquals(36, GroupNameColors.palette.size)
    }

    @Test
    fun allEntriesHaveNonZeroLightTheme() {
        GroupNameColors.palette.forEachIndexed { index, color ->
            assertTrue(color.lightTheme != 0L, "Palette entry $index has zero lightTheme")
        }
    }

    @Test
    fun allEntriesHaveNonZeroDarkTheme() {
        GroupNameColors.palette.forEachIndexed { index, color ->
            assertTrue(color.darkTheme != 0L, "Palette entry $index has zero darkTheme")
        }
    }

    @Test
    fun getColorReturnsDarkThemeVariantWhenDark() {
        val odinId = "test-user-id"
        val color = GroupNameColors.getColor(odinId, isDarkTheme = true)
        val index = kotlin.math.abs(odinId.hashCode()) % GroupNameColors.palette.size
        assertEquals(GroupNameColors.palette[index].darkTheme, color)
    }

    @Test
    fun getColorReturnsLightThemeVariantWhenLight() {
        val odinId = "test-user-id"
        val color = GroupNameColors.getColor(odinId, isDarkTheme = false)
        val index = kotlin.math.abs(odinId.hashCode()) % GroupNameColors.palette.size
        assertEquals(GroupNameColors.palette[index].lightTheme, color)
    }

    @Test
    fun sameOdinIdAlwaysReturnsSameColor() {
        val odinId = "consistent-user-123"
        val first = GroupNameColors.getColor(odinId, isDarkTheme = true)
        val second = GroupNameColors.getColor(odinId, isDarkTheme = true)
        val third = GroupNameColors.getColor(odinId, isDarkTheme = true)
        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun hundredRandomIdsProduceAtLeast10DistinctColors() {
        val distinctColors = (1..100).map { i ->
            GroupNameColors.getColor("user-$i", isDarkTheme = false)
        }.distinct()
        assertTrue(
            distinctColors.size >= 10,
            "Expected at least 10 distinct colors but got ${distinctColors.size}",
        )
    }
}
