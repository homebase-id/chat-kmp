package id.homebase.core.ui.screens.card

/** A ready-made look: the first of [schemeIds] a design offers, that scheme's [accentIndex]th accent, and a font pair, all from [CardDesignSpecs]. */
internal data class CardStyle(
    val id: String,
    val schemeIds: List<String>,
    val accentIndex: Int,
    val display: String,
    val text: String,
)

/** A [CardStyle] resolved against one design; what the design cannot take is null and left to its preset. */
internal data class ResolvedCardStyle(
    val id: String,
    val ground: String,
    val ink: String,
    val accent: String?,
    val display: String?,
    val text: String?,
)

internal val CARD_STYLES = listOf(
    CardStyle("nocturne", listOf("midnight", "ink", "slate", "charcoal", "night"), 0, "montserrat", "montserrat"),
    CardStyle("ember", listOf("wine", "oxblood", "terracotta", "rust"), 0, "archivo-black", "montserrat"),
    CardStyle("woodland", listOf("pine", "moss", "forest", "sage", "mint"), 0, "newsreader", "montserrat"),
    CardStyle("harbour", listOf("cobalt", "blueprint", "navy", "sky", "mist"), 0, "montserrat-alt", "montserrat"),
    CardStyle("editorial", listOf("paper", "cloud", "mono", "sand", "kraft"), 0, "newsreader", "newsreader"),
    CardStyle("blossom", listOf("blush", "rose", "peach", "lilac", "lavender", "plum"), 0, "caveat", "montserrat"),
    CardStyle("sunset", listOf("rust", "butter", "lemon", "peach"), 0, "archivo-black", "newsreader"),
    CardStyle("terminal", listOf("graphite", "charcoal", "slate", "ink"), 1, "space-mono", "space-mono"),
)

internal fun cardStylesFor(design: String): List<ResolvedCardStyle> =
    CardDesignSpecs.of(design)?.let(::cardStylesFor).orEmpty()

internal fun cardStylesFor(spec: CardDesignSpec): List<ResolvedCardStyle> {
    if (CardOption.COLOURS !in spec.options) return emptyList()
    return CARD_STYLES.mapNotNull { style ->
        val scheme = spec.schemes.firstOrNull { it.id in style.schemeIds } ?: return@mapNotNull null
        ResolvedCardStyle(
            id = style.id,
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
