package id.homebase.chat.chatappearance.model

object ChatColorsMapper {
    private val wallpaperToColor: Map<String, ChatColor> = mapOf(
        "blush" to ChatColorPresets.crimson,
        "copper" to ChatColorPresets.vermilion,
        "dust" to ChatColorPresets.burlap,
        "celadon" to ChatColorPresets.forest,
        "rainforest" to ChatColorPresets.wintergreen,
        "pacific" to ChatColorPresets.teal,
        "frost" to ChatColorPresets.blue,
        "navy" to ChatColorPresets.indigo,
        "lilac" to ChatColorPresets.violet,
        "pink" to ChatColorPresets.plum,
        "eggplant" to ChatColorPresets.taupe,
        "silver" to ChatColorPresets.steel,
        "sunset" to ChatColorPresets.ember,
        "noir" to ChatColorPresets.midnight,
        "heatmap" to ChatColorPresets.infrared,
        "aqua" to ChatColorPresets.lagoon,
        "iridescent" to ChatColorPresets.fluorescent,
        "monstera" to ChatColorPresets.basil,
        "bliss" to ChatColorPresets.sublime,
        "sky" to ChatColorPresets.sea,
        "peach" to ChatColorPresets.tangerine,
    )

    fun resolve(wallpaper: ChatWallpaper): ChatColor =
        wallpaperToColor[wallpaper.id] ?: ChatColorPresets.default
}
