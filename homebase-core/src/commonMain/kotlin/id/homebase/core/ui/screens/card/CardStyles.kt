package id.homebase.core.ui.screens.card

/**
 * A preset: one of a design's schemes with its first accent. Presets set colours only; the layout fields are filled only on the custom tile.
 * The [isDefault] one is the design's built-in look (odin-js presets.ts), ahead of the schemes; applying it clears the colours and accent.
 */
internal data class ResolvedCardStyle(
    val id: String,
    val ground: String,
    val ink: String,
    val accent: String?,
    val display: String? = null,
    val text: String? = null,
    val portrait: String? = null,
    val socials: String? = null,
    val blocks: String? = null,
    val isDefault: Boolean = false,
)

internal fun cardStylesFor(design: String): List<ResolvedCardStyle> =
    CardDesignSpecs.of(design)?.let(::cardStylesFor).orEmpty()

internal fun cardStylesFor(spec: CardDesignSpec): List<ResolvedCardStyle> {
    if (CardOption.COLOURS !in spec.options) return emptyList()
    val default = ResolvedCardStyle(DEFAULT_STYLE_ID, argbToHex(CardDesign.baseArgb(spec.design)), argbToHex(CardDesign.inkArgb(spec.design)), accent = null, isDefault = true)
    return listOf(default) + spec.schemes.map { scheme ->
        ResolvedCardStyle(scheme.id, scheme.ground, scheme.ink, scheme.accents.firstOrNull()?.takeIf { CardOption.ACCENT in spec.options })
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
    if (style.isDefault) return with(CardOption.COLOURS, null, design = design).with(CardOption.ACCENT, null, design = design).prunedFor(design)
    return style.values().fold(with(CardOption.COLOURS, style.ground, design = design)) { edited, (option, value) ->
        if (value == null) edited else edited.with(option, value, presetOrder, design)
    }.prunedFor(design)
}

// A scheme with no accent picked is still that preset: audience templates and cards saved from them carry none.
internal fun ResolvedCardStyle.matches(overrides: CardOverrides): Boolean {
    val accent = overrides.valueOf(CardOption.ACCENT)
    if (isDefault) return overrides.valueOf(CardOption.COLOURS) == null && accent == null
    return overrides.valueOf(CardOption.COLOURS).equals(ground, ignoreCase = true) &&
        (accent == null || accent.equals(this.accent, ignoreCase = true)) &&
        values().all { (option, value) -> option == CardOption.ACCENT || value == null || overrides.valueOf(option).equals(value, ignoreCase = true) }
}

/** The card's own colours as a tile, when a colour or accent is set but no style in [styles] matches them, so the row shows why none is chosen. */
internal fun customStyle(styles: List<ResolvedCardStyle>, overrides: CardOverrides, design: String): ResolvedCardStyle? {
    val ground = overrides.valueOf(CardOption.COLOURS)
    if (ground == null && overrides.valueOf(CardOption.ACCENT) == null) return null
    if (styles.any { it.matches(overrides) }) return null
    return ResolvedCardStyle(
        id = CUSTOM_STYLE_ID,
        ground = ground ?: argbToHex(CardDesign.baseArgb(design)),
        ink = overrides.palette?.ink ?: argbToHex(CardDesign.inkArgb(design)),
        accent = overrides.valueOf(CardOption.ACCENT),
        display = overrides.valueOf(CardOption.DISPLAY_FONT),
        text = overrides.valueOf(CardOption.TEXT_FONT),
        portrait = overrides.valueOf(CardOption.PORTRAIT_SHAPE),
        socials = overrides.valueOf(CardOption.SOCIALS_STYLE),
        blocks = overrides.valueOf(CardOption.BLOCK_STYLE),
    )
}
