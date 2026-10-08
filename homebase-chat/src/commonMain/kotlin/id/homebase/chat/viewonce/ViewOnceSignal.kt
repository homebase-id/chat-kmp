package id.homebase.chat.viewonce

import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.chat.services.ReactionSetChange
import id.homebase.chat.services.decodeReactionCode

/**
 * Hidden reactions that carry the view-once lifecycle. The leading `_` keeps them out of the
 * reaction UI. Scopes are distinct because `setReactions` keys its outbox row per (message, scope):
 * a shared scope would let `_vo` overwrite a pending `_vs`.
 */
object ViewOnceSignal {
    const val OPENED_CODE = "_vo"
    const val SCREENSHOT_CODE = "_vs"
    const val OPENED_SCOPE = "viewonce"
    const val SCREENSHOT_SCOPE = "viewonce_shot"
    const val REACTION_SCOPE = "viewonce_react"

    private fun addOnly(scope: String, code: String) =
        ReactionSetChange(scope = scope, add = setOf(code), remove = emptySet())

    fun openedChange() = addOnly(OPENED_SCOPE, OPENED_CODE)

    fun reactionChange(emoji: String) = addOnly(REACTION_SCOPE, emoji)

    fun screenshotChange() = addOnly(SCREENSHOT_SCOPE, SCREENSHOT_CODE)

    fun count(summary: ReactionSummary?, code: String): Int =
        summary?.reactions?.values.orEmpty()
            .filter { decodeReactionCode(it.reactionContent) == code }
            .sumOf { it.count }
}
