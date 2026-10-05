package id.homebase.core.ui.screens.card

enum class CardOption {
    COLOURS,
    ACCENT,
    DISPLAY_FONT,
    TEXT_FONT,
    PORTRAIT_SHAPE,
    SOCIALS_STYLE,
    BLOCK_ORDER,
}

/** [portraitSlots] is how many portraits the layout's preset draws; the web replaces the preset's list, so a shape override must fill every slot. */
data class CardDesignSpec(
    val design: String,
    val options: Set<CardOption>,
    val portraitSlots: Int = 0,
    val schemes: List<CardColourScheme> = emptyList(),
)

/** Everything but the accent, set together so the ink always reads on its ground; [id] names it in the UI. */
data class CardColourScheme(
    val id: String,
    val ground: String,
    val ink: String,
    val muted: String,
    val surface: String,
    val surfaceInk: String,
) {
    fun applyTo(palette: CardPalette?): CardPalette =
        (palette ?: CardPalette()).copy(ground = ground, ink = ink, muted = muted, surface = surface, surfaceInk = surfaceInk)
}

object CardDesignSpecs {
    val ACCENT_SWATCHES = listOf("#4FD1C5", "#F26B5B", "#F2B84B", "#7FD4E8", "#B4553A", "#8B7CF6", "#6BCB77", "#E8E8E8")
    val FONTS = listOf("montserrat", "montserrat-alt", "newsreader", "caveat", "archivo-black", "space-mono")
    val PORTRAIT_SHAPES = listOf("circle", "square", "rounded", "ellipse")
    val SOCIALS_STYLES = listOf("glyphs", "bar", "wordmark", "handles")
    val BLOCK_KINDS = listOf("chat", "links", "moments", "posts")

    private val ALL_OPTIONS = CardOption.entries.toSet()

    private fun scheme(id: String, ground: String, ink: String, muted: String, surface: String, surfaceInk: String) =
        CardColourScheme(id, ground, ink, muted, surface, surfaceInk)

    // Poster's ground tints a photo and its paper section is set in the ground colour, so it only takes dark grounds.
    private val specs = listOf(
        CardDesignSpec(
            CardDesign.POSTER,
            setOf(CardOption.COLOURS, CardOption.DISPLAY_FONT, CardOption.TEXT_FONT, CardOption.SOCIALS_STYLE, CardOption.BLOCK_ORDER),
            schemes = listOf(
                scheme("midnight", "#0F1626", "#EEF2FA", "#B4BCCB", "#4A505E", "#EEF2FA"),
                scheme("wine", "#2A0E14", "#F7E9EC", "#C9AEB4", "#5A3F45", "#F7E9EC"),
                scheme("pine", "#0E1E16", "#E9F3EC", "#A9BFB1", "#3F4F46", "#E9F3EC"),
                scheme("cobalt", "#10215C", "#EEF1FF", "#B3BCE6", "#3F4C80", "#EEF1FF"),
                scheme("rust", "#3A1708", "#FBEDE4", "#D6B5A3", "#67463A", "#FBEDE4"),
            ),
        ),
        CardDesignSpec(
            CardDesign.BOARD,
            ALL_OPTIONS,
            portraitSlots = 1,
            schemes = listOf(
                scheme("forest", "#1E5B45", "#FFFFFF", "#BFD6CC", "#FFFFFF", "#173B2E"),
                scheme("plum", "#5B2A6E", "#FFFFFF", "#D9C3E2", "#FFFFFF", "#3A1A47"),
                scheme("terracotta", "#9A3B26", "#FFFFFF", "#F1CFC5", "#FFF6F1", "#5A1F12"),
                scheme("charcoal", "#23262B", "#F2F2F2", "#A9AFB8", "#F2F2F2", "#23262B"),
                scheme("sky", "#DCEBFA", "#10273F", "#3F5A75", "#FFFFFF", "#10273F"),
                scheme("butter", "#F7E7A6", "#3B2F05", "#6B5A1C", "#FFFFFF", "#3B2F05"),
                scheme("blush", "#F6D6D9", "#4A1820", "#7A3F48", "#FFFFFF", "#4A1820"),
            ),
        ),
        CardDesignSpec(
            CardDesign.COLLAGE,
            ALL_OPTIONS,
            portraitSlots = 2,
            schemes = listOf(
                scheme("sage", "#DDE5D3", "#26331F", "#56664A", "#FFFFFF", "#26331F"),
                scheme("mist", "#DCE6F0", "#1E2C3B", "#4D5E70", "#FFFFFF", "#1E2C3B"),
                scheme("rose", "#F3DCDC", "#3E2224", "#7A5155", "#FFFFFF", "#3E2224"),
                scheme("lilac", "#E6DFF0", "#2E2540", "#5E5272", "#FFFFFF", "#2E2540"),
                scheme("kraft", "#C9A882", "#2B1E10", "#4F3822", "#FFF8EE", "#2B1E10"),
                scheme("night", "#1F1C19", "#F0E8DC", "#B3A592", "#F0E8DC", "#1F1C19"),
                scheme("mono", "#EDEDED", "#1A1A1A", "#555555", "#FFFFFF", "#1A1A1A"),
            ),
        ),
        CardDesignSpec(
            CardDesign.DOSSIER,
            ALL_OPTIONS,
            portraitSlots = 1,
            schemes = listOf(
                scheme("ink", "#0D1B2A", "#E3ECF5", "#8EA3B8", "#1B2E44", "#E3ECF5"),
                scheme("moss", "#0F1A14", "#E4EFE7", "#8FA697", "#1E2E24", "#E4EFE7"),
                scheme("oxblood", "#1C0F10", "#F2E6E4", "#A88E8B", "#33201F", "#F2E6E4"),
                scheme("graphite", "#2A2D33", "#F1F2F4", "#A6ACB6", "#3A3E46", "#F1F2F4"),
                scheme("paper", "#F4F1EA", "#1C1B19", "#6B675E", "#E2DDD2", "#1C1B19"),
                scheme("blueprint", "#12355B", "#EAF2FB", "#9DB6D1", "#1E4A78", "#EAF2FB"),
                scheme("sand", "#E8DCC6", "#2A2216", "#6E604A", "#D6C7AB", "#2A2216"),
            ),
        ),
    ).associateBy { it.design }

    fun of(design: String): CardDesignSpec? = specs[design]

    /** A scheme is found by its ground, which is unique across every design. */
    fun schemeByGround(ground: String?): CardColourScheme? =
        ground?.let { g -> specs.values.firstNotNullOfOrNull { spec -> spec.schemes.firstOrNull { it.ground.equals(g, ignoreCase = true) } } }

    // The preset ink each page draws on CardDesign.baseArgb, for the "Default" swatch.
    fun presetInkArgb(design: String): Int = when (design) {
        CardDesign.POSTER -> 0xFFF4F0EA
        CardDesign.COLLAGE -> 0xFF3A2E22
        CardDesign.DOSSIER -> 0xFFE9ECF1
        else -> 0xFFFFFFFF
    }.toInt()

    /** The `CardOverrides` field each option writes, as a dotted path (`[]` for a list element). */
    fun fieldOf(option: CardOption): String = when (option) {
        CardOption.COLOURS -> "palette.ground"
        CardOption.ACCENT -> "palette.accent"
        CardOption.DISPLAY_FONT -> "type.display"
        CardOption.TEXT_FONT -> "type.text"
        CardOption.PORTRAIT_SHAPE -> "portraits[].shape"
        CardOption.SOCIALS_STYLE -> "socials"
        CardOption.BLOCK_ORDER -> "blocks[].kind"
    }
}

/** Keeps only what [design] exposes; an unknown design keeps nothing. */
fun CardOverrides.prunedFor(design: String): CardOverrides {
    val spec = CardDesignSpecs.of(design) ?: return CardOverrides.EMPTY
    fun has(option: CardOption) = option in spec.options
    val shape = portraits?.firstOrNull()?.shape
    // Colours only travel as one of this design's schemes, so another design's ink can never land on a ground it wasn't checked against.
    val scheme = CardDesignSpecs.schemeByGround(palette?.ground)?.takeIf { has(CardOption.COLOURS) && it in spec.schemes }
    val colours = scheme?.applyTo(null)
    val accent = palette?.accent?.takeIf { has(CardOption.ACCENT) }
    return CardOverrides(
        palette = (colours ?: CardPalette()).copy(accent = accent).takeUnless { it == CardPalette() },
        type = CardTypeface(
            display = type?.display?.takeIf { has(CardOption.DISPLAY_FONT) },
            text = type?.text?.takeIf { has(CardOption.TEXT_FONT) },
        ).takeIf { it.display != null || it.text != null },
        portraits = shape?.takeIf { has(CardOption.PORTRAIT_SHAPE) }?.let {
            List(spec.portraitSlots) { slot -> if (slot == 0) CardPortrait(shape = it) else CardPortrait() }
        },
        blocks = blocks?.takeIf { has(CardOption.BLOCK_ORDER) }?.map { CardBlock(it.kind) },
        socials = socials?.takeIf { has(CardOption.SOCIALS_STYLE) },
    )
}
