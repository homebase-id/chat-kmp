package id.homebase.core.ui.screens.card

/**
 * A preset: one of a design's schemes with its first accent. Presets set colours only; the layout fields are filled only on the custom tile.
 * The [isDefault] one is the first scheme, drawn as the card's built-in look; applying it clears every option a preset touches.
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
    return spec.schemes.mapIndexed { i, scheme ->
        if (i == 0) {
            ResolvedCardStyle(scheme.id, argbToHex(CardDesign.baseArgb(spec.design)), argbToHex(CardDesign.inkArgb(spec.design)), accent = null, isDefault = true)
        } else {
            ResolvedCardStyle(scheme.id, scheme.ground, scheme.ink, scheme.accents.firstOrNull()?.takeIf { CardOption.ACCENT in spec.options })
        }
    }
}

private val PRESET_OPTIONS = listOf(
    CardOption.COLOURS,
    CardOption.ACCENT,
    CardOption.DISPLAY_FONT,
    CardOption.TEXT_FONT,
    CardOption.PORTRAIT_SHAPE,
    CardOption.SOCIALS_STYLE,
    CardOption.BLOCK_STYLE,
)

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
    if (style.isDefault) return PRESET_OPTIONS.fold(this) { edited, option -> edited.with(option, null, presetOrder, design) }.prunedFor(design)
    return style.values().fold(with(CardOption.COLOURS, style.ground, design = design)) { edited, (option, value) ->
        if (value == null) edited else edited.with(option, value, presetOrder, design)
    }.prunedFor(design)
}

// A template's palette is the design's first scheme, so an untouched templated card is still Default.
internal fun ResolvedCardStyle.matches(overrides: CardOverrides): Boolean {
    val colours = overrides.valueOf(CardOption.COLOURS)
    if (isDefault) {
        return (colours == null || colours.equals(ground, ignoreCase = true)) &&
            PRESET_OPTIONS.drop(1).all { overrides.valueOf(it) == null } &&
            overrides.blocks.orEmpty().all { it.presentation == null }
    }
    return colours.equals(ground, ignoreCase = true) &&
        values().all { (option, value) -> value == null || overrides.valueOf(option).equals(value, ignoreCase = true) }
}

/** The card's own colours as a tile, when they are set but no style in [styles] matches them, so the row shows why none is chosen. */
internal fun customStyle(styles: List<ResolvedCardStyle>, overrides: CardOverrides, design: String): ResolvedCardStyle? {
    val ground = overrides.valueOf(CardOption.COLOURS) ?: return null
    if (styles.any { it.matches(overrides) }) return null
    return ResolvedCardStyle(
        id = CUSTOM_STYLE_ID,
        ground = ground,
        ink = overrides.palette?.ink ?: argbToHex(CardDesign.inkArgb(design)),
        accent = overrides.valueOf(CardOption.ACCENT),
        display = overrides.valueOf(CardOption.DISPLAY_FONT),
        text = overrides.valueOf(CardOption.TEXT_FONT),
        portrait = overrides.valueOf(CardOption.PORTRAIT_SHAPE),
        socials = overrides.valueOf(CardOption.SOCIALS_STYLE),
        blocks = overrides.valueOf(CardOption.BLOCK_STYLE),
    )
}
