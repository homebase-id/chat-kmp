package id.homebase.core.ui.screens.card

/** The value [option] currently holds; null means the design's preset applies. */
fun CardOverrides.valueOf(option: CardOption): String? = when (option) {
    CardOption.COLOURS -> palette?.ground
    CardOption.ACCENT -> palette?.accent
    CardOption.DISPLAY_FONT -> type?.display
    CardOption.TEXT_FONT -> type?.text
    CardOption.PORTRAIT_SHAPE -> portraits?.firstOrNull()?.shape
    CardOption.SOCIALS_STYLE -> socials
    CardOption.BLOCK_ORDER -> null
}

/** Orderable sections in effect; posts rides with moments. */
fun CardOverrides.blockOrder(): List<String> = completeOrder(blocks?.map { it.kind }.orEmpty())

private fun completeOrder(kinds: List<String>): List<String> {
    val chosen = kinds.filter { it in CardDesignSpecs.ORDERED_KINDS }.distinct()
    return chosen + CardDesignSpecs.ORDERED_KINDS.filter { it !in chosen }
}

/** Sets a single-valued [option] to [value], or back to the preset for null. */
fun CardOverrides.with(option: CardOption, value: String?): CardOverrides = when (option) {
    CardOption.COLOURS -> copy(
        palette = (CardDesignSpecs.schemeByGround(value)?.applyTo(palette) ?: CardPalette(accent = palette?.accent))
            .takeUnless { it == CardPalette() },
    )
    CardOption.ACCENT -> copy(palette = (palette ?: CardPalette()).copy(accent = value).takeUnless { it == CardPalette() })
    CardOption.DISPLAY_FONT -> copy(type = (type ?: CardTypeface()).copy(display = value).takeUnless { it == CardTypeface() })
    CardOption.TEXT_FONT -> copy(type = (type ?: CardTypeface()).copy(text = value).takeUnless { it == CardTypeface() })
    CardOption.PORTRAIT_SHAPE -> copy(portraits = value?.let { listOf(CardPortrait(shape = it)) })
    CardOption.SOCIALS_STYLE -> copy(socials = value)
    CardOption.BLOCK_ORDER -> this
}

fun CardOverrides.withBlockOrder(kinds: List<String>?): CardOverrides =
    copy(blocks = kinds?.let { completeOrder(it) }?.flatMap { if (it == "moments") listOf(it, "posts") else listOf(it) }?.map { CardBlock(it) })

/** Applies a new order of just the [kinds] a design shows, leaving the hidden kinds where they were. */
fun CardOverrides.withShownBlockOrder(kinds: List<String>, shown: List<String>): CardOverrides {
    val queue = shown.iterator()
    return withBlockOrder(blockOrder().map { if (it in kinds) queue.next() else it })
}
