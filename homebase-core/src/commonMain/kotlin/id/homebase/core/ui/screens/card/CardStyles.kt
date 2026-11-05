package id.homebase.core.ui.screens.card

/** A [CardStyle] resolved against one design; what the design cannot take is null and left to its preset. */
internal data class ResolvedCardStyle(
    val id: String,
    val ground: String,
    val ink: String,
    val accent: String?,
    val display: String?,
    val text: String?,
)

/** A ready-made look on one of a design's own schemes: that scheme's [accentIndex]th accent and a font pair. One per scheme, named after it. */
internal data class CardStyle(val schemeId: String, val accentIndex: Int, val display: String, val text: String)

private val CARD_STYLES: Map<String, List<CardStyle>> = mapOf(
    CardDesign.POSTER to listOf(
        CardStyle("midnight", 0, "archivo-black", "montserrat"),
        CardStyle("wine", 0, "newsreader", "newsreader"),
        CardStyle("pine", 0, "montserrat-alt", "montserrat"),
        CardStyle("cobalt", 0, "space-mono", "space-mono"),
        CardStyle("rust", 0, "caveat", "montserrat"),
    ),
    CardDesign.BOARD to listOf(
        CardStyle("forest", 0, "archivo-black", "montserrat"),
        CardStyle("plum", 1, "newsreader", "montserrat"),
        CardStyle("terracotta", 1, "caveat", "montserrat"),
        CardStyle("charcoal", 1, "space-mono", "space-mono"),
        CardStyle("sky", 0, "montserrat-alt", "montserrat"),
        CardStyle("butter", 2, "archivo-black", "newsreader"),
        CardStyle("blush", 0, "newsreader", "newsreader"),
    ),
    CardDesign.COLLAGE to listOf(
        CardStyle("sage", 0, "caveat", "montserrat"),
        CardStyle("mist", 0, "montserrat-alt", "montserrat"),
        CardStyle("rose", 0, "newsreader", "newsreader"),
        CardStyle("lilac", 0, "caveat", "newsreader"),
        CardStyle("kraft", 0, "space-mono", "space-mono"),
        CardStyle("night", 0, "newsreader", "montserrat"),
        CardStyle("mono", 1, "archivo-black", "montserrat"),
    ),
    CardDesign.DOSSIER to listOf(
        CardStyle("ink", 0, "space-mono", "montserrat"),
        CardStyle("moss", 1, "montserrat-alt", "space-mono"),
        CardStyle("oxblood", 0, "newsreader", "newsreader"),
        CardStyle("graphite", 0, "archivo-black", "montserrat"),
        CardStyle("paper", 0, "newsreader", "space-mono"),
        CardStyle("blueprint", 0, "space-mono", "space-mono"),
        CardStyle("sand", 0, "caveat", "newsreader"),
    ),
    CardDesign.LINKS to listOf(
        CardStyle("cloud", 0, "montserrat", "montserrat"),
        CardStyle("slate", 0, "archivo-black", "montserrat"),
        CardStyle("mint", 0, "montserrat-alt", "montserrat"),
        CardStyle("peach", 0, "caveat", "montserrat"),
        CardStyle("lavender", 0, "newsreader", "montserrat"),
        CardStyle("navy", 1, "space-mono", "montserrat"),
        CardStyle("lemon", 1, "archivo-black", "newsreader"),
    ),
)

internal fun cardStylesFor(design: String): List<ResolvedCardStyle> =
    CardDesignSpecs.of(design)?.let(::cardStylesFor).orEmpty()

internal fun cardStylesFor(spec: CardDesignSpec): List<ResolvedCardStyle> {
    if (CardOption.COLOURS !in spec.options) return emptyList()
    return CARD_STYLES[spec.design].orEmpty().mapNotNull { style ->
        val scheme = spec.schemes.firstOrNull { it.id == style.schemeId } ?: return@mapNotNull null
        ResolvedCardStyle(
            id = scheme.id,
            ground = scheme.ground,
            ink = scheme.ink,
            accent = scheme.accents.getOrNull(style.accentIndex)?.takeIf { CardOption.ACCENT in spec.options },
            display = style.display.takeIf { CardOption.DISPLAY_FONT in spec.options },
            text = style.text.takeIf { CardOption.TEXT_FONT in spec.options },
        )
    }
}

internal fun CardOverrides.withStyle(style: ResolvedCardStyle, design: String): CardOverrides {
    var edited = with(CardOption.COLOURS, style.ground, design = design)
    style.accent?.let { edited = edited.with(CardOption.ACCENT, it) }
    style.display?.let { edited = edited.with(CardOption.DISPLAY_FONT, it) }
    style.text?.let { edited = edited.with(CardOption.TEXT_FONT, it) }
    return edited
}

internal fun ResolvedCardStyle.matches(overrides: CardOverrides): Boolean =
    overrides.valueOf(CardOption.COLOURS).equals(ground, ignoreCase = true) &&
        (accent == null || overrides.valueOf(CardOption.ACCENT).equals(accent, ignoreCase = true)) &&
        (display == null || overrides.valueOf(CardOption.DISPLAY_FONT) == display) &&
        (text == null || overrides.valueOf(CardOption.TEXT_FONT) == text)

/** The card's own colours as a tile, when they are set but no style in [styles] matches them, so the row shows why none is chosen. */
internal fun customStyle(styles: List<ResolvedCardStyle>, overrides: CardOverrides, design: String): ResolvedCardStyle? {
    val ground = overrides.valueOf(CardOption.COLOURS) ?: return null
    if (styles.any { it.matches(overrides) }) return null
    return ResolvedCardStyle(
        id = CUSTOM_STYLE_ID,
        ground = ground,
        ink = overrides.palette?.ink ?: "#" + (CardDesign.inkArgb(design) and 0xFFFFFF).toString(16).padStart(6, '0'),
        accent = overrides.valueOf(CardOption.ACCENT),
        display = overrides.valueOf(CardOption.DISPLAY_FONT),
        text = overrides.valueOf(CardOption.TEXT_FONT),
    )
}
