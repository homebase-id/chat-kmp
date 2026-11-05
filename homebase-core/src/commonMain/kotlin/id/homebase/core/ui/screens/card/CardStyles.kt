package id.homebase.core.ui.screens.card

/** A [CardStyle] resolved against one design; what the design cannot take is null and left to its preset. */
internal data class ResolvedCardStyle(
    val id: String,
    val ground: String,
    val ink: String,
    val accent: String?,
    val display: String?,
    val text: String?,
    val portrait: String?,
    val socials: String?,
    val blocks: String?,
)

/** A ready-made look on one of a design's own schemes: that scheme's [accentIndex]th accent, a font pair and a layout. One per scheme, named after it. */
internal data class CardStyle(
    val schemeId: String,
    val accentIndex: Int,
    val display: String,
    val text: String,
    val portrait: String? = null,
    val socials: String? = null,
    val blocks: String? = null,
)

private val CARD_STYLES: Map<String, List<CardStyle>> = mapOf(
    CardDesign.POSTER to listOf(
        CardStyle("midnight", 0, "archivo-black", "montserrat", socials = "bar", blocks = "row"),
        CardStyle("wine", 0, "newsreader", "newsreader", socials = "wordmark", blocks = "bare"),
        CardStyle("pine", 0, "montserrat-alt", "montserrat", socials = "glyphs", blocks = "boxed"),
        CardStyle("cobalt", 0, "space-mono", "space-mono", socials = "handles", blocks = "row"),
        CardStyle("rust", 0, "caveat", "montserrat", socials = "glyphs", blocks = "button"),
    ),
    CardDesign.BOARD to listOf(
        CardStyle("forest", 0, "archivo-black", "montserrat", portrait = "circle", socials = "glyphs", blocks = "boxed"),
        CardStyle("plum", 1, "newsreader", "montserrat", portrait = "ellipse", socials = "wordmark", blocks = "bare"),
        CardStyle("terracotta", 1, "caveat", "montserrat", portrait = "rounded", socials = "glyphs", blocks = "button"),
        CardStyle("charcoal", 1, "space-mono", "space-mono", portrait = "square", socials = "handles", blocks = "row"),
        CardStyle("sky", 0, "montserrat-alt", "montserrat", portrait = "circle", socials = "bar", blocks = "boxed"),
        CardStyle("butter", 2, "archivo-black", "newsreader", portrait = "rounded", socials = "bar", blocks = "button"),
        CardStyle("blush", 0, "newsreader", "newsreader", portrait = "ellipse", socials = "wordmark", blocks = "bare"),
    ),
    CardDesign.COLLAGE to listOf(
        CardStyle("sage", 0, "caveat", "montserrat", portrait = "rounded", socials = "glyphs"),
        CardStyle("mist", 0, "montserrat-alt", "montserrat", portrait = "circle", socials = "bar"),
        CardStyle("rose", 0, "newsreader", "newsreader", portrait = "ellipse", socials = "wordmark"),
        CardStyle("lilac", 0, "caveat", "newsreader", portrait = "rounded", socials = "wordmark"),
        CardStyle("kraft", 0, "space-mono", "space-mono", portrait = "square", socials = "handles"),
        CardStyle("night", 0, "newsreader", "montserrat", portrait = "square", socials = "bar"),
        CardStyle("mono", 1, "archivo-black", "montserrat", portrait = "square", socials = "glyphs"),
    ),
    CardDesign.DOSSIER to listOf(
        CardStyle("ink", 0, "space-mono", "montserrat", portrait = "square", socials = "handles", blocks = "row"),
        CardStyle("moss", 1, "montserrat-alt", "space-mono", portrait = "rounded", socials = "glyphs", blocks = "bare"),
        CardStyle("oxblood", 0, "newsreader", "newsreader", portrait = "ellipse", socials = "wordmark", blocks = "bare"),
        CardStyle("graphite", 0, "archivo-black", "montserrat", portrait = "square", socials = "bar", blocks = "boxed"),
        CardStyle("paper", 0, "newsreader", "space-mono", portrait = "square", socials = "handles", blocks = "row"),
        CardStyle("blueprint", 0, "space-mono", "space-mono", portrait = "square", socials = "handles", blocks = "boxed"),
        CardStyle("sand", 0, "caveat", "newsreader", portrait = "rounded", socials = "wordmark", blocks = "bare"),
    ),
    CardDesign.LINKS to listOf(
        CardStyle("cloud", 0, "montserrat", "montserrat", portrait = "circle", socials = "glyphs"),
        CardStyle("slate", 0, "archivo-black", "montserrat", portrait = "square", socials = "bar"),
        CardStyle("mint", 0, "montserrat-alt", "montserrat", portrait = "rounded", socials = "glyphs"),
        CardStyle("peach", 0, "caveat", "montserrat", portrait = "ellipse", socials = "wordmark"),
        CardStyle("lavender", 0, "newsreader", "montserrat", portrait = "circle", socials = "wordmark"),
        CardStyle("navy", 1, "space-mono", "montserrat", portrait = "rounded", socials = "handles"),
        CardStyle("lemon", 1, "archivo-black", "newsreader", portrait = "square", socials = "bar"),
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
            portrait = style.portrait?.takeIf { CardOption.PORTRAIT_SHAPE in spec.options },
            socials = style.socials?.takeIf { CardOption.SOCIALS_STYLE in spec.options },
            blocks = style.blocks?.takeIf { CardOption.BLOCK_STYLE in spec.options },
        )
    }
}

private fun ResolvedCardStyle.values(): List<Pair<CardOption, String?>> = listOf(
    CardOption.ACCENT to accent,
    CardOption.DISPLAY_FONT to display,
    CardOption.TEXT_FONT to text,
    CardOption.PORTRAIT_SHAPE to portrait,
    CardOption.SOCIALS_STYLE to socials,
    CardOption.BLOCK_STYLE to blocks,
)

internal fun CardOverrides.withStyle(style: ResolvedCardStyle, design: String): CardOverrides {
    val presetOrder = CardDesignSpecs.of(design)?.presetOrder ?: CardDesignSpecs.ORDERED_KINDS
    return style.values().fold(with(CardOption.COLOURS, style.ground, design = design)) { edited, (option, value) ->
        if (value == null) edited else edited.with(option, value, presetOrder, design)
    }.prunedFor(design)
}

internal fun ResolvedCardStyle.matches(overrides: CardOverrides): Boolean =
    overrides.valueOf(CardOption.COLOURS).equals(ground, ignoreCase = true) &&
        values().all { (option, value) -> value == null || overrides.valueOf(option).equals(value, ignoreCase = true) }

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
        portrait = overrides.valueOf(CardOption.PORTRAIT_SHAPE),
        socials = overrides.valueOf(CardOption.SOCIALS_STYLE),
        blocks = overrides.valueOf(CardOption.BLOCK_STYLE),
    )
}
