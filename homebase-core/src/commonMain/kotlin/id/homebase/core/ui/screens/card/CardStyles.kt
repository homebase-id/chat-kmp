package id.homebase.core.ui.screens.card

/**
 * A preset: one of a design's schemes with its first accent. Presets set colours only; the layout fields are filled only on the custom tile.
 * The default one is the design's built-in look (odin-js presets.ts), ahead of the schemes; applying it clears the colours and accent.
 */
internal data class ResolvedCardStyle(
    val id: String,
    val ground: String,
    val ink: String,
    val accent: String?,
    val portrait: String? = null,
    val socials: String? = null,
    val blocks: String? = null,
) {
    val isDefault: Boolean get() = id == DEFAULT_STYLE_ID
}

internal const val CUSTOM_STYLE_ID = "custom"
internal const val DEFAULT_STYLE_ID = "default"

internal fun cardStylesFor(design: String): List<ResolvedCardStyle> =
    CardDesignSpecs.of(design)?.let(::cardStylesFor).orEmpty()

internal fun cardStylesFor(spec: CardDesignSpec): List<ResolvedCardStyle> {
    if (CardOption.COLOURS !in spec.options) return emptyList()
    val default = ResolvedCardStyle(DEFAULT_STYLE_ID, argbToHex(CardDesign.baseArgb(spec.design)), argbToHex(CardDesign.inkArgb(spec.design)), accent = null)
    return listOf(default) + spec.schemes.map { scheme ->
        ResolvedCardStyle(scheme.id, scheme.ground, scheme.ink, scheme.accents.firstOrNull()?.takeIf { CardOption.ACCENT in spec.options })
    }
}

internal fun CardOverrides.withStyle(style: ResolvedCardStyle, design: String): CardOverrides {
    if (style.isDefault) return with(CardOption.COLOURS, null, design = design).with(CardOption.ACCENT, null).prunedFor(design)
    val coloured = with(CardOption.COLOURS, style.ground, design = design)
    return (style.accent?.let { coloured.with(CardOption.ACCENT, it) } ?: coloured).prunedFor(design)
}

// A scheme with no accent picked is still that preset: audience templates and cards saved from them carry none.
internal fun ResolvedCardStyle.matches(overrides: CardOverrides): Boolean {
    val accent = overrides.valueOf(CardOption.ACCENT)
    if (isDefault) return overrides.valueOf(CardOption.COLOURS) == null && accent == null
    return overrides.valueOf(CardOption.COLOURS).equals(ground, ignoreCase = true) &&
        (accent == null || accent.equals(this.accent, ignoreCase = true))
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
        portrait = overrides.valueOf(CardOption.PORTRAIT_SHAPE),
        socials = overrides.valueOf(CardOption.SOCIALS_STYLE),
        blocks = overrides.valueOf(CardOption.BLOCK_STYLE),
    )
}
