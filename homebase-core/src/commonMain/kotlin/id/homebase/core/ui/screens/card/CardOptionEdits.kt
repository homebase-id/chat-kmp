package id.homebase.core.ui.screens.card

/** The value [option] currently holds; null means the design's preset applies. */
fun CardOverrides.valueOf(option: CardOption): String? = when (option) {
    CardOption.ACCENT -> palette?.accent
    CardOption.DISPLAY_FONT -> type?.display
    CardOption.TEXT_FONT -> type?.text
    CardOption.PORTRAIT_SHAPE -> portraits?.firstOrNull()?.shape
    CardOption.SOCIALS_STYLE -> socials
    CardOption.BLOCK_ORDER -> null
}

/** The block order in effect: the override, then whatever kinds it leaves out in default order. */
fun CardOverrides.blockOrder(): List<String> {
    val chosen = blocks?.map { it.kind }.orEmpty().filter { it in CardDesignSpecs.BLOCK_KINDS }.distinct()
    return chosen + CardDesignSpecs.BLOCK_KINDS.filter { it !in chosen }
}

/** Sets a single-valued [option] to [value], or back to the preset for null. */
fun CardOverrides.with(option: CardOption, value: String?): CardOverrides = when (option) {
    CardOption.ACCENT -> copy(palette = value?.let { (palette ?: CardPalette()).copy(accent = it) })
    CardOption.DISPLAY_FONT -> copy(type = (type ?: CardTypeface()).copy(display = value).takeUnless { it == CardTypeface() })
    CardOption.TEXT_FONT -> copy(type = (type ?: CardTypeface()).copy(text = value).takeUnless { it == CardTypeface() })
    CardOption.PORTRAIT_SHAPE -> copy(portraits = value?.let { listOf(CardPortrait(shape = it)) })
    CardOption.SOCIALS_STYLE -> copy(socials = value)
    CardOption.BLOCK_ORDER -> this
}

fun CardOverrides.withBlockOrder(kinds: List<String>?): CardOverrides =
    copy(blocks = kinds?.map { CardBlock(it) })
