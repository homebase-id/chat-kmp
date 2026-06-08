package id.homebase.chat.widget

/** Tabs in the composer's expression panel. GIFs is reserved for a later feature. */
enum class ExpressionTab {
    Emoji,
    Stickers,
    Gifs;

    companion object {
        /** The panel opens on this tab (it is launched by the emoji button). */
        val Default = Emoji
    }
}

/**
 * The ordered tab list. GIFs is omitted until that feature ships — flip [gifsEnabled]
 * (one call site) to append it; no other change needed.
 */
fun expressionTabs(gifsEnabled: Boolean = false): List<ExpressionTab> = buildList {
    add(ExpressionTab.Emoji)
    add(ExpressionTab.Stickers)
    if (gifsEnabled) add(ExpressionTab.Gifs)
}
