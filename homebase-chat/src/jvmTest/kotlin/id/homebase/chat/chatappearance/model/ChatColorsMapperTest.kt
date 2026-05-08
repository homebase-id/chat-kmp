package id.homebase.chat.chatappearance.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ChatColorsMapperTest {

    // region Every wallpaper-to-color mapping

    @Test
    fun blushMapsToCrimson() {
        assertEquals(ChatColorPresets.crimson, ChatColorsMapper.resolve(ChatWallpaperPresets.blush))
    }

    @Test
    fun copperMapsToVermilion() {
        assertEquals(ChatColorPresets.vermilion, ChatColorsMapper.resolve(ChatWallpaperPresets.copper))
    }

    @Test
    fun dustMapsToBurlap() {
        assertEquals(ChatColorPresets.burlap, ChatColorsMapper.resolve(ChatWallpaperPresets.dust))
    }

    @Test
    fun celadonMapsToForest() {
        assertEquals(ChatColorPresets.forest, ChatColorsMapper.resolve(ChatWallpaperPresets.celadon))
    }

    @Test
    fun rainforestMapsToWintergreen() {
        assertEquals(ChatColorPresets.wintergreen, ChatColorsMapper.resolve(ChatWallpaperPresets.rainforest))
    }

    @Test
    fun pacificMapsToTeal() {
        assertEquals(ChatColorPresets.teal, ChatColorsMapper.resolve(ChatWallpaperPresets.pacific))
    }

    @Test
    fun frostMapsToBlue() {
        assertEquals(ChatColorPresets.blue, ChatColorsMapper.resolve(ChatWallpaperPresets.frost))
    }

    @Test
    fun navyMapsToIndigo() {
        assertEquals(ChatColorPresets.indigo, ChatColorsMapper.resolve(ChatWallpaperPresets.navy))
    }

    @Test
    fun lilacMapsToViolet() {
        assertEquals(ChatColorPresets.violet, ChatColorsMapper.resolve(ChatWallpaperPresets.lilac))
    }

    @Test
    fun pinkMapsToPlum() {
        assertEquals(ChatColorPresets.plum, ChatColorsMapper.resolve(ChatWallpaperPresets.pink))
    }

    @Test
    fun eggplantMapsToTaupe() {
        assertEquals(ChatColorPresets.taupe, ChatColorsMapper.resolve(ChatWallpaperPresets.eggplant))
    }

    @Test
    fun silverMapsToSteel() {
        assertEquals(ChatColorPresets.steel, ChatColorsMapper.resolve(ChatWallpaperPresets.silver))
    }

    @Test
    fun sunsetMapsToEmber() {
        assertEquals(ChatColorPresets.ember, ChatColorsMapper.resolve(ChatWallpaperPresets.sunset))
    }

    @Test
    fun noirMapsToMidnight() {
        assertEquals(ChatColorPresets.midnight, ChatColorsMapper.resolve(ChatWallpaperPresets.noir))
    }

    @Test
    fun heatmapMapsToInfrared() {
        assertEquals(ChatColorPresets.infrared, ChatColorsMapper.resolve(ChatWallpaperPresets.heatmap))
    }

    @Test
    fun aquaMapsToLagoon() {
        assertEquals(ChatColorPresets.lagoon, ChatColorsMapper.resolve(ChatWallpaperPresets.aqua))
    }

    @Test
    fun iridescentMapsToFluorescent() {
        assertEquals(ChatColorPresets.fluorescent, ChatColorsMapper.resolve(ChatWallpaperPresets.iridescent))
    }

    @Test
    fun monsteraMapsToBasil() {
        assertEquals(ChatColorPresets.basil, ChatColorsMapper.resolve(ChatWallpaperPresets.monstera))
    }

    @Test
    fun blissMapsToSublime() {
        assertEquals(ChatColorPresets.sublime, ChatColorsMapper.resolve(ChatWallpaperPresets.bliss))
    }

    @Test
    fun skyMapsToSea() {
        assertEquals(ChatColorPresets.sea, ChatColorsMapper.resolve(ChatWallpaperPresets.sky))
    }

    @Test
    fun peachMapsToTangerine() {
        assertEquals(ChatColorPresets.tangerine, ChatColorsMapper.resolve(ChatWallpaperPresets.peach))
    }

    // endregion

    // region Fallback cases

    @Test
    fun noneMapsToUltramarine() {
        assertEquals(ChatColorPresets.ultramarine, ChatColorsMapper.resolve(ChatWallpaper.None))
    }

    @Test
    fun photoMapsToUltramarine() {
        val photo = ChatWallpaper.Photo(id = "my_photo", payloadKey = "chat_wlpr")
        assertEquals(ChatColorPresets.ultramarine, ChatColorsMapper.resolve(photo))
    }

    // endregion

    // region Determinism

    @Test
    fun resolveIsDeterministic() {
        val first = ChatColorsMapper.resolve(ChatWallpaperPresets.frost)
        val second = ChatColorsMapper.resolve(ChatWallpaperPresets.frost)
        assertEquals(first, second)
    }

    // endregion

    // region Coverage

    @Test
    fun everyBuiltInWallpaperHasAMappingToValidPreset() {
        ChatWallpaperPresets.all.forEach { wallpaper ->
            val resolved = ChatColorsMapper.resolve(wallpaper)
            assertNotNull(resolved, "Wallpaper ${wallpaper.id} should resolve to a color")
            val found = ChatColorPresets.all.firstOrNull { it.id == resolved.id }
            assertNotNull(found, "Resolved color ${resolved.id} for wallpaper ${wallpaper.id} should be a known preset")
        }
    }

    // endregion
}
