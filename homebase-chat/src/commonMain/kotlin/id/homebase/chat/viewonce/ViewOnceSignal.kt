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

    fun openedChange(): ReactionSetChange =
        ReactionSetChange(scope = OPENED_SCOPE, add = setOf(OPENED_CODE), remove = emptySet())

    fun screenshotChange(): ReactionSetChange =
        ReactionSetChange(scope = SCREENSHOT_SCOPE, add = setOf(SCREENSHOT_CODE), remove = emptySet())

    fun count(summary: ReactionSummary?, code: String): Int =
        summary?.reactions?.values.orEmpty()
            .filter { decodeReactionCode(it.reactionContent) == code }
            .sumOf { it.count }
}
