package id.homebase.core.ui.screens.card

enum class CardOption {
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
)

object CardDesignSpecs {
    val ACCENT_SWATCHES = listOf("#4FD1C5", "#F26B5B", "#F2B84B", "#7FD4E8", "#B4553A", "#8B7CF6", "#6BCB77", "#E8E8E8")
    val FONTS = listOf("montserrat", "montserrat-alt", "newsreader", "caveat", "archivo-black", "space-mono")
    val PORTRAIT_SHAPES = listOf("circle", "square", "rounded", "ellipse")
    val SOCIALS_STYLES = listOf("glyphs", "bar", "wordmark", "handles")
    val BLOCK_KINDS = listOf("chat", "links", "moments", "posts")

    private val ALL_OPTIONS = CardOption.entries.toSet()

    // Rule: an option is listed when the design's card or page draws it with the preset's defaults (poster draws no portrait and no accent).
    private val specs = listOf(
        CardDesignSpec(
            CardDesign.POSTER,
            setOf(CardOption.DISPLAY_FONT, CardOption.TEXT_FONT, CardOption.SOCIALS_STYLE, CardOption.BLOCK_ORDER),
        ),
        CardDesignSpec(
            CardDesign.BOARD,
            ALL_OPTIONS,
            portraitSlots = 1,
        ),
        CardDesignSpec(
            CardDesign.COLLAGE,
            ALL_OPTIONS,
            portraitSlots = 2,
        ),
        CardDesignSpec(
            CardDesign.DOSSIER,
            ALL_OPTIONS,
            portraitSlots = 1,
        ),
    ).associateBy { it.design }

    fun of(design: String): CardDesignSpec? = specs[design]

    /** The `CardOverrides` field each option writes, as a dotted path (`[]` for a list element). */
    fun fieldOf(option: CardOption): String = when (option) {
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
    return CardOverrides(
        palette = palette?.accent?.takeIf { has(CardOption.ACCENT) }?.let { CardPalette(accent = it) },
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
