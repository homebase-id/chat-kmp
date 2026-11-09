package id.homebase.core.ui.screens.card

import id.homebase.api.util.compareStringUuId

enum class CardOption {
    COLOURS,
    ACCENT,
    DISPLAY_FONT,
    TEXT_FONT,
    PORTRAIT_SHAPE,
    SOCIALS_STYLE,
    BLOCK_ORDER,
    BLOCK_STYLE,
}

/** [portraitSlots] is how many portraits the layout's preset draws; the web replaces the preset's list, so a shape override must fill every slot. */
data class CardDesignSpec(
    val design: String,
    val options: Set<CardOption>,
    val portraitSlots: Int = 0,
    val schemes: List<CardColourScheme> = emptyList(),
    val orderedKinds: List<String> = CardDesignSpecs.ORDERED_KINDS,
    val presetOrder: List<String> = CardDesignSpecs.ORDERED_KINDS,
) {
    /** Chat and moments always render; links only when the card has some. */
    fun reorderable(hasLinks: Boolean): List<String> = orderedKinds.filter { hasLinks || it != "links" }
}

/** Ground, ink and surfaces set together so the ink always reads; [accents] are the curated accents that suit this ground, the first being the fallback. [id] names it in the UI. */
data class CardColourScheme(
    val id: String,
    val ground: String,
    val ink: String,
    val muted: String,
    val surface: String,
    val surfaceInk: String,
    val accents: List<String>,
) {
    fun applyTo(palette: CardPalette?): CardPalette =
        (palette ?: CardPalette()).copy(ground = ground, ink = ink, muted = muted, surface = surface, surfaceInk = surfaceInk)
}

/** `#RRGGBB` as an opaque ARGB int. */
internal fun hexToArgb(hex: String): Int = (hex.removePrefix("#").toLong(16) or 0xFF000000).toInt()

internal fun argbToHex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

object CardDesignSpecs {
    val FONTS = listOf("montserrat", "montserrat-alt", "newsreader", "caveat", "archivo-black", "space-mono")
    val PORTRAIT_SHAPES = listOf("circle", "square", "rounded", "ellipse")
    val SOCIALS_STYLES = listOf("glyphs", "bar", "wordmark", "handles")
    val PRESENTATIONS = listOf("bare", "boxed", "row", "button")
    val BLOCK_KINDS = listOf("chat", "links", "moments", "posts")
    val ORDERED_KINDS = BLOCK_KINDS - "posts"

    // These renderers pin chat and socials outside the ordered list.
    private val PINNED_CHAT_KINDS = listOf("links", "moments")
    private val ALL_OPTIONS = CardOption.entries.toSet()

    // The web draws Simple's links and Collage's links and chat the same for every style, so the option would change nothing.
    private val WITHOUT_BLOCK_STYLE = ALL_OPTIONS - CardOption.BLOCK_STYLE

    // Poster's ground tints a photo and its paper section is set in the ground colour, so it only takes dark grounds.
    private val specs = listOf(
        CardDesignSpec(
            CardDesign.POSTER,
            setOf(CardOption.COLOURS, CardOption.DISPLAY_FONT, CardOption.TEXT_FONT, CardOption.SOCIALS_STYLE, CardOption.BLOCK_ORDER, CardOption.BLOCK_STYLE),
            presetOrder = listOf("chat", "moments", "links"),
            schemes = listOf(
                CardColourScheme("midnight", "#0F1626", "#EEF2FA", "#B4BCCB", "#4A505E", "#EEF2FA", listOf("#7FD4E8", "#8B7CF6", "#4FD1C5", "#F2B84B")),
                CardColourScheme("wine", "#2A0E14", "#F7E9EC", "#C9AEB4", "#5A3F45", "#F7E9EC", listOf("#F26B5B", "#F2B84B", "#E8A0B4", "#F5C6A5")),
                CardColourScheme("pine", "#0E1E16", "#E9F3EC", "#A9BFB1", "#3F4F46", "#E9F3EC", listOf("#6BCB77", "#4FD1C5", "#F2B84B", "#C5E86C")),
                CardColourScheme("cobalt", "#10215C", "#EEF1FF", "#B3BCE6", "#3F4C80", "#EEF1FF", listOf("#7FD4E8", "#F2B84B", "#9DB4FF", "#6BCB77")),
                CardColourScheme("rust", "#3A1708", "#FBEDE4", "#D6B5A3", "#67463A", "#FBEDE4", listOf("#F2B84B", "#F28C5B", "#F5C6A5", "#E8D26B")),
                CardColourScheme("slate", "#272B36", "#EEF0F5", "#B3B8C4", "#454A57", "#EEF0F5", listOf("#EEF0F5", "#B6C5FA", "#9FB0D2", "#B7A9D8")),
                CardColourScheme("plum", "#22192F", "#F1EDF7", "#BBB1CB", "#40354F", "#F1EDF7", listOf("#F1EDF7", "#C9B8EA", "#A9B6DE", "#9473CA")),
            ),
        ),
        CardDesignSpec(
            CardDesign.BOARD,
            ALL_OPTIONS,
            portraitSlots = 1,
            presetOrder = listOf("links", "moments", "chat"),
            schemes = listOf(
                CardColourScheme("forest", "#1E5B45", "#FFFFFF", "#BFD6CC", "#FFFFFF", "#173B2E", listOf("#F2B84B", "#9BE3B8", "#7FD4E8", "#F5C6A5")),
                CardColourScheme("plum", "#5B2A6E", "#FFFFFF", "#D9C3E2", "#FFFFFF", "#3A1A47", listOf("#F2B84B", "#E8A0D4", "#B4A5FF", "#7FD4E8")),
                CardColourScheme("terracotta", "#9A3B26", "#FFFFFF", "#F1CFC5", "#FFF6F1", "#5A1F12", listOf("#F2B84B", "#FFD6A5", "#7FD4E8", "#E8E8E8")),
                CardColourScheme("charcoal", "#23262B", "#F2F2F2", "#A9AFB8", "#F2F2F2", "#23262B", listOf("#F26B5B", "#4FD1C5", "#F2B84B", "#B4A5FF")),
                CardColourScheme("sky", "#DCEBFA", "#10273F", "#3F5A75", "#FFFFFF", "#10273F", listOf("#1F6FB5", "#B4553A", "#2E8B6A", "#6A4FC4")),
                CardColourScheme("butter", "#F7E7A6", "#3B2F05", "#6B5A1C", "#FFFFFF", "#3B2F05", listOf("#8A5A00", "#B4553A", "#2E6B4A", "#4F4FA8")),
                CardColourScheme("blush", "#F6D6D9", "#4A1820", "#7A3F48", "#FFFFFF", "#4A1820", listOf("#B02A47", "#7A3F8F", "#1F6F6A", "#8A4B00")),
                CardColourScheme("purple", "#553781", "#FFFFFF", "#D3C8E3", "#FFFFFF", "#36225A", listOf("#D7C9F0", "#DBE1FC", "#B6C5FA", "#B5C9BF")),
            ),
        ),
        CardDesignSpec(
            CardDesign.COLLAGE,
            WITHOUT_BLOCK_STYLE,
            orderedKinds = PINNED_CHAT_KINDS,
            portraitSlots = 2,
            presetOrder = listOf("chat", "links", "moments"),
            schemes = listOf(
                CardColourScheme("sage", "#DDE5D3", "#26331F", "#56664A", "#FFFFFF", "#26331F", listOf("#3F7A3A", "#B4553A", "#2E6F8F", "#8A5A00")),
                CardColourScheme("mist", "#DCE6F0", "#1E2C3B", "#4D5E70", "#FFFFFF", "#1E2C3B", listOf("#2E6FB5", "#5A4FB5", "#1F7A70", "#B4553A")),
                CardColourScheme("rose", "#F3DCDC", "#3E2224", "#7A5155", "#FFFFFF", "#3E2224", listOf("#B02A47", "#8A3F6A", "#1F6F6A", "#A5521F")),
                CardColourScheme("lilac", "#E6DFF0", "#2E2540", "#5E5272", "#FFFFFF", "#2E2540", listOf("#6A3FB5", "#B02A6A", "#2E6F8F", "#1F7A5A")),
                CardColourScheme("kraft", "#C9A882", "#2B1E10", "#4F3822", "#FFF8EE", "#2B1E10", listOf("#6B2A14", "#1F4A5A", "#3F4F1F", "#5A1F4A")),
                CardColourScheme("night", "#1F1C19", "#F0E8DC", "#B3A592", "#F0E8DC", "#1F1C19", listOf("#F2B84B", "#E8886B", "#9BC47A", "#7FD4E8")),
                CardColourScheme("mono", "#EDEDED", "#1A1A1A", "#555555", "#FFFFFF", "#1A1A1A", listOf("#1A1A1A", "#C23B2A", "#2E5FB5", "#1F7A4A")),
                CardColourScheme("blue", "#C8D5F3", "#0E1D45", "#3A4A70", "#F4F7FE", "#0E1D45", listOf("#1F45A8", "#34486E", "#52347D", "#00634A")),
                CardColourScheme("slate", "#3B4459", "#F0F3F9", "#BCC4D4", "#F0F3F9", "#2A3142", listOf("#B6C5FA", "#C9B8EA", "#A9BBDF", "#A9C8BF")),
                CardColourScheme("ink", "#191C24", "#ECEEF3", "#A8ADB9", "#2C303B", "#ECEEF3", listOf("#B6C5FA", "#9473CA", "#748EBD", "#7FB3A1")),
            ),
        ),
        CardDesignSpec(
            CardDesign.DOSSIER,
            ALL_OPTIONS,
            orderedKinds = PINNED_CHAT_KINDS,
            portraitSlots = 1,
            presetOrder = listOf("chat", "moments", "links"),
            schemes = listOf(
                CardColourScheme("ink", "#0D1B2A", "#E3ECF5", "#8EA3B8", "#1B2E44", "#E3ECF5", listOf("#7FD4E8", "#F2B84B", "#4FD1C5", "#8B7CF6")),
                CardColourScheme("moss", "#0F1A14", "#E4EFE7", "#8FA697", "#1E2E24", "#E4EFE7", listOf("#6BCB77", "#C5E86C", "#F2B84B", "#4FD1C5")),
                CardColourScheme("oxblood", "#1C0F10", "#F2E6E4", "#A88E8B", "#33201F", "#F2E6E4", listOf("#F26B5B", "#F2B84B", "#E8A0A0", "#F5C6A5")),
                CardColourScheme("graphite", "#2A2D33", "#F1F2F4", "#A6ACB6", "#3A3E46", "#F1F2F4", listOf("#4FD1C5", "#F2B84B", "#F26B5B", "#8B7CF6")),
                CardColourScheme("paper", "#F4F1EA", "#1C1B19", "#6B675E", "#E2DDD2", "#1C1B19", listOf("#B4553A", "#1F5F8F", "#2E6F4A", "#6A3FB5")),
                CardColourScheme("blueprint", "#12355B", "#EAF2FB", "#9DB6D1", "#1E4A78", "#EAF2FB", listOf("#F2B84B", "#7FD4E8", "#9BE3B8", "#F5C6A5")),
                CardColourScheme("sand", "#E8DCC6", "#2A2216", "#6E604A", "#D6C7AB", "#2A2216", listOf("#8A3A14", "#1F5F7A", "#3F6F2E", "#7A2F5F")),
                CardColourScheme("plum", "#1E1729", "#EFEAF6", "#ADA3BE", "#33293F", "#EFEAF6", listOf("#C9B8EA", "#B6C5FA", "#9473CA", "#8FA6D2")),
                CardColourScheme("mist", "#EDEFF3", "#16181C", "#555A64", "#DCE0E8", "#16181C", listOf("#2C58C3", "#425985", "#603E90", "#007A59")),
            ),
        ),
        CardDesignSpec(
            CardDesign.LINKS,
            WITHOUT_BLOCK_STYLE,
            portraitSlots = 1,
            presetOrder = listOf("links", "moments", "chat"),
            schemes = listOf(
                CardColourScheme("cloud", "#F1F3F6", "#1B1F24", "#5C6673", "#FFFFFF", "#1B1F24", listOf("#2E6FB5", "#5A4FB5", "#1F7A70", "#C23B2A")),
                CardColourScheme("slate", "#1B1F24", "#F1F3F6", "#A3ACB8", "#2A3037", "#F1F3F6", listOf("#4FD1C5", "#7FD4E8", "#F2B84B", "#F26B5B")),
                CardColourScheme("mint", "#DDF0E6", "#12301F", "#4A6B58", "#FFFFFF", "#12301F", listOf("#1F7A4A", "#1F6F8F", "#8A5A00", "#B02A47")),
                CardColourScheme("peach", "#FBE3D6", "#43210F", "#7D5A45", "#FFFFFF", "#43210F", listOf("#B4452A", "#8A3F6A", "#1F6F6A", "#7A5200")),
                CardColourScheme("lavender", "#E7E3F7", "#2A2350", "#5F5A85", "#FFFFFF", "#2A2350", listOf("#5A3FB5", "#B02A6A", "#1F6F8F", "#2E7A4A")),
                CardColourScheme("navy", "#14213D", "#F0F4FA", "#A9B7CE", "#22345A", "#F0F4FA", listOf("#7FD4E8", "#F2B84B", "#9BB4FF", "#6BCB77")),
                CardColourScheme("lemon", "#F8F2C8", "#3B3405", "#6E6420", "#FFFFFF", "#3B3405", listOf("#7A5200", "#2E6B4A", "#4F4FA8", "#B02A47")),
                CardColourScheme("blue", "#DCE5F9", "#151D2C", "#475066", "#FFFFFF", "#151D2C", listOf("#2C58C3", "#3A4F78", "#5A3A88", "#006F52")),
                CardColourScheme("cobalt", "#24489E", "#FFFFFF", "#C6D1EC", "#F4F7FE", "#14275A", listOf("#D2DFFB", "#B6C5FA", "#D7C9F0", "#B5C9BF")),
                CardColourScheme("plum", "#3A2A58", "#F3EFF9", "#C3B8D6", "#F3EFF9", "#2E2147", listOf("#C9B8EA", "#B6C5FA", "#DBE1FC", "#A9C8BF")),
            ),
        ),
    ).associateBy { it.design }

    fun of(design: String): CardDesignSpec? = specs[design]

    /** [design]'s own scheme on [ground], or null for the preset's colours or a ground the design doesn't offer. */
    fun schemeFor(design: String, ground: String?): CardColourScheme? =
        of(design)?.schemes?.firstOrNull { it.ground.equals(ground, ignoreCase = true) }

    /** The accents on offer under [ground]; with no scheme picked the design's first scheme stands in, as the preset has none of its own. */
    fun accentsFor(design: String, ground: String?): List<String> =
        (schemeFor(design, ground) ?: of(design)?.schemes?.firstOrNull())?.accents.orEmpty()

    /** A scheme is found by its ground, which is unique across every design. */
    fun schemeByGround(ground: String?): CardColourScheme? =
        ground?.let { g -> specs.values.firstNotNullOfOrNull { spec -> spec.schemes.firstOrNull { it.ground.equals(g, ignoreCase = true) } } }
}

/** Keeps only what [design] exposes; an unknown design keeps nothing but [CardOverrides.fields], which are not part of a design. */
fun CardOverrides.prunedFor(design: String): CardOverrides {
    val spec = CardDesignSpecs.of(design) ?: return CardOverrides(fields = fields)
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
        blocks = blocks?.takeIf { has(CardOption.BLOCK_ORDER) }?.let { withBlockOrder(blockOrder()).blocks }
            ?.map { if (has(CardOption.BLOCK_STYLE)) it else it.copy(presentation = null) },
        socials = socials?.takeIf { has(CardOption.SOCIALS_STYLE) },
        fields = fields,
    )
}

/** What a fixed card renders until first saved, and what its reset returns to. */
internal data class CardTemplate(val design: String, val overrides: CardOverrides)

private fun template(design: String, scheme: String) = CardTemplate(
    design,
    CardOverrides(palette = CardDesignSpecs.of(design)!!.schemes.first { it.id == scheme }.applyTo(null)),
)

private val FIXED_CIRCLE_TEMPLATES = listOf(
    FRIENDS_CIRCLE_ID to template(CardDesign.COLLAGE, "sage"),
    FAMILY_CIRCLE_ID to template(CardDesign.POSTER, "wine"),
    WORK_CIRCLE_ID to template(CardDesign.LINKS, "cloud"),
)

internal val PUBLIC_CARD_TEMPLATE = template(CardDesign.LINKS, "mint")

internal fun cardTemplateFor(audience: CardAudience): CardTemplate? = when (audience) {
    CardAudience.Public -> PUBLIC_CARD_TEMPLATE
    is CardAudience.Circle -> FIXED_CIRCLE_TEMPLATES.firstOrNull { compareStringUuId(it.first, audience.id) }?.second
}

internal fun ProfileCard.withTemplate(template: CardTemplate): ProfileCard = copy(design = template.design, overrides = template.overrides)

/** A design switch: like [prunedFor], but an accent left on a preset ground must be one that preset offers. */
fun CardOverrides.switchedTo(design: String): CardOverrides {
    val pruned = prunedFor(design)
    val accent = pruned.palette?.accent ?: return pruned
    if (pruned.palette.ground != null || CardDesignSpecs.accentsFor(design, null).any { it.equals(accent, ignoreCase = true) }) return pruned
    return pruned.with(CardOption.ACCENT, null)
}
