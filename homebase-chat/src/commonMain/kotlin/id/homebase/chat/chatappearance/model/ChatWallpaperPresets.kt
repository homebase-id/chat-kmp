package id.homebase.chat.chatappearance.model

object ChatWallpaperPresets {

    private val standardPositions = listOf(
        0.0000f, 0.0807f, 0.1554f, 0.2250f, 0.2904f, 0.3526f, 0.4125f, 0.4710f,
        0.5290f, 0.5875f, 0.6474f, 0.7096f, 0.7750f, 0.8446f, 0.9193f, 1.0000f,
    )

    // region Solid wallpapers

    val blush = ChatWallpaper.SolidColor(id = "blush", colorArgb = 0xFFE26983)
    val copper = ChatWallpaper.SolidColor(id = "copper", colorArgb = 0xFFDF9171)
    val dust = ChatWallpaper.SolidColor(id = "dust", colorArgb = 0xFF9E9887)
    val celadon = ChatWallpaper.SolidColor(id = "celadon", colorArgb = 0xFF89AE8F)
    val rainforest = ChatWallpaper.SolidColor(id = "rainforest", colorArgb = 0xFF146148)
    val pacific = ChatWallpaper.SolidColor(id = "pacific", colorArgb = 0xFF32C7E2)
    val frost = ChatWallpaper.SolidColor(id = "frost", colorArgb = 0xFF7C99B6)
    val navy = ChatWallpaper.SolidColor(id = "navy", colorArgb = 0xFF403B91)
    val lilac = ChatWallpaper.SolidColor(id = "lilac", colorArgb = 0xFFC988E7)
    val pink = ChatWallpaper.SolidColor(id = "pink", colorArgb = 0xFFE297C3)
    val eggplant = ChatWallpaper.SolidColor(id = "eggplant", colorArgb = 0xFF624249)
    val silver = ChatWallpaper.SolidColor(id = "silver", colorArgb = 0xFFA2A2AA)

    // endregion

    // region Gradient wallpapers

    val sunset = ChatWallpaper.GradientColor(
        id = "sunset",
        colorsArgb = listOf(
            0xFFF3DC47, 0xFFF3DA47, 0xFFF2D546, 0xFFF2CC46,
            0xFFF1C146, 0xFFEFB445, 0xFFEEA544, 0xFFEC9644,
            0xFFEB8743, 0xFFE97743, 0xFFE86942, 0xFFE65C41,
            0xFFE55041, 0xFFE54841, 0xFFE44240, 0xFFE44040,
        ),
        positions = standardPositions,
        angleDegrees = 168f,
    )

    val noir = ChatWallpaper.GradientColor(
        id = "noir",
        colorsArgb = listOf(
            0xFF16161D, 0xFF17171E, 0xFF1A1A22, 0xFF1F1F28,
            0xFF26262F, 0xFF2D2D38, 0xFF353542, 0xFF3E3E4C,
            0xFF474757, 0xFF4F4F61, 0xFF57576B, 0xFF5F5F74,
            0xFF65657C, 0xFF6A6A82, 0xFF6D6D85, 0xFF6E6E87,
        ),
        positions = standardPositions,
        angleDegrees = 180f,
    )

    val heatmap = ChatWallpaper.GradientColor(
        id = "heatmap",
        colorsArgb = listOf(
            0xFFF53844, 0xFFF33845, 0xFFEC3848, 0xFFE2384C,
            0xFFD63851, 0xFFC73857, 0xFFB6385E, 0xFFA43866,
            0xFF93376D, 0xFF813775, 0xFF70377C, 0xFF613782,
            0xFF553787, 0xFF4B378B, 0xFF44378E, 0xFF42378F,
        ),
        positions = listOf(
            0.0000f, 0.0075f, 0.0292f, 0.0637f,
            0.1097f, 0.1659f, 0.2310f, 0.3037f,
            0.3827f, 0.4666f, 0.5541f, 0.6439f,
            0.7347f, 0.8252f, 0.9141f, 1.0000f,
        ),
        angleDegrees = 192f,
    )

    val aqua = ChatWallpaper.GradientColor(
        id = "aqua",
        colorsArgb = listOf(
            0xFF0093E9, 0xFF0294E9, 0xFF0696E7, 0xFF0D99E5,
            0xFF169EE3, 0xFF21A3E0, 0xFF2DA8DD, 0xFF3AAEDA,
            0xFF46B5D6, 0xFF53BBD3, 0xFF5FC0D0, 0xFF6AC5CD,
            0xFF73CACB, 0xFF7ACDC9, 0xFF7ECFC7, 0xFF80D0C7,
        ),
        positions = standardPositions,
        angleDegrees = 180f,
    )

    val iridescent = ChatWallpaper.GradientColor(
        id = "iridescent",
        colorsArgb = listOf(
            0xFFF04CE6, 0xFFEE4BE6, 0xFFE54AE5, 0xFFD949E5,
            0xFFC946E4, 0xFFB644E3, 0xFFA141E3, 0xFF8B3FE2,
            0xFF743CE1, 0xFF5E39E0, 0xFF4936DF, 0xFF3634DE,
            0xFF2632DD, 0xFF1930DD, 0xFF112FDD, 0xFF0E2FDD,
        ),
        positions = standardPositions,
        angleDegrees = 192f,
    )

    val monstera = ChatWallpaper.GradientColor(
        id = "monstera",
        colorsArgb = listOf(
            0xFF65CDAC, 0xFF64CDAB, 0xFF60CBA8, 0xFF5BC8A3,
            0xFF55C49D, 0xFF4DC096, 0xFF45BB8F, 0xFF3CB687,
            0xFF33B17F, 0xFF2AAC76, 0xFF21A76F, 0xFF1AA268,
            0xFF139F62, 0xFF0E9C5E, 0xFF0B9A5B, 0xFF0A995A,
        ),
        positions = standardPositions,
        angleDegrees = 180f,
    )

    val bliss = ChatWallpaper.GradientColor(
        id = "bliss",
        colorsArgb = listOf(
            0xFFD8E1FA, 0xFFD8E0F9, 0xFFD8DEF7, 0xFFD8DBF3,
            0xFFD8D6EE, 0xFFD7D1E8, 0xFFD7CCE2, 0xFFD7C6DB,
            0xFFD7BFD4, 0xFFD7B9CD, 0xFFD6B4C7, 0xFFD6AFC1,
            0xFFD6AABC, 0xFFD6A7B8, 0xFFD6A5B6, 0xFFD6A4B5,
        ),
        positions = standardPositions,
        angleDegrees = 180f,
    )

    val sky = ChatWallpaper.GradientColor(
        id = "sky",
        colorsArgb = listOf(
            0xFFD8EBFD, 0xFFD7EAFD, 0xFFD5E9FD, 0xFFD2E7FD,
            0xFFCDE5FD, 0xFFC8E3FD, 0xFFC3E0FD, 0xFFBDDDFC,
            0xFFB7DAFC, 0xFFB2D7FC, 0xFFACD4FC, 0xFFA7D1FC,
            0xFFA3CFFB, 0xFFA0CDFB, 0xFF9ECCFB, 0xFF9DCCFB,
        ),
        positions = standardPositions,
        angleDegrees = 180f,
    )

    val peach = ChatWallpaper.GradientColor(
        id = "peach",
        colorsArgb = listOf(
            0xFFFFE5C2, 0xFFFFE4C1, 0xFFFFE2BF, 0xFFFFDFBD,
            0xFFFEDBB9, 0xFFFED6B5, 0xFFFED1B1, 0xFFFDCCAC,
            0xFFFDC6A8, 0xFFFDC0A3, 0xFFFCBB9F, 0xFFFCB69B,
            0xFFFCB297, 0xFFFCAF95, 0xFFFCAD93, 0xFFFCAC92,
        ),
        positions = standardPositions,
        angleDegrees = 192f,
    )

    // endregion

    val solids: List<ChatWallpaper.SolidColor> = listOf(
        blush, copper, dust, celadon, rainforest, pacific,
        frost, navy, lilac, pink, eggplant, silver,
    )

    val gradientsList: List<ChatWallpaper.GradientColor> = listOf(
        sunset, noir, heatmap, aqua, iridescent,
        monstera, bliss, sky, peach,
    )

    val all: List<ChatWallpaper> = solids + gradientsList

    fun findById(id: String): ChatWallpaper? = all.find { it.id == id }
}
