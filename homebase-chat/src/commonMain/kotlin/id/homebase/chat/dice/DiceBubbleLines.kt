package id.homebase.chat.dice

/**
 * Pure decision layer for `DiceRollBubble` — what the bubble should display,
 * with no string-resource lookups. The Composable maps these structures to
 * `stringResource(...)` calls.
 *
 * The bubble shows up to two lines:
 *  - **Sender line** (always): identifies whose dice this bubble represents.
 *    Even on a battle response, the receiver needs to see "Sam rolled 3" —
 *    otherwise the bubble's only text is the leader line, which names the
 *    *leader*, not the *sender*. That's the "I see 2 × You" issue: the user's
 *    own original roll said "You rolled X" and Sam's battle reply said "You
 *    lead with X" because the user was leading, so Sam never got named.
 *  - **Battle result** (when `descriptor.isBattle`): leader/winner/tie summary
 *    across the chain.
 */
internal data class DiceBubbleLines(
    val senderIsSelf: Boolean,
    /** hostPortion of the latest roller's domainName (e.g. "sam"). Empty when self. */
    val senderName: String,
    val senderSum: Int,
    val senderResults: List<Int>,
    val battleResult: BattleResult?,
)

internal data class BattleResult(
    /**
     * The chain can no longer take more rollers — either it hit
     * [DiceRollDescriptor.MAX_BATTLE_PARTICIPANTS], or every chat member
     * already rolled. The leader can no longer be overtaken, so the bubble
     * announces "won" instead of "leads".
     */
    val isClosed: Boolean,
    val maxSum: Int,
    /** Order preserved from `descriptor.rolls` for stable rendering of ties. */
    val leaders: List<LeaderEntry>,
    /** Convenience: any leader is the viewer. */
    val isSelfAmong: Boolean,
)

internal data class LeaderEntry(
    val isSelf: Boolean,
    /** hostPortion of the leader's domainName (e.g. "sam"). Empty when self. */
    val name: String,
)

/**
 * Compute the bubble's line structure.
 *
 * @param chainCap `min(MAX_BATTLE_PARTICIPANTS, chatRosterSize)` for the
 *  conversation. `null` when the caller can't supply a roster (e.g. preview /
 *  link-preview composables) — in that case the chain is treated as still open
 *  so we never falsely declare a winner.
 */
internal fun computeDiceBubbleLines(
    descriptor: DiceRollDescriptor,
    currentOdinId: String,
    chainCap: Int?,
): DiceBubbleLines {
    val latest = descriptor.rolls.last()
    val latestDomain = latest.odinId.domainName
    val senderIsSelf = currentOdinId.isNotBlank() && latestDomain == currentOdinId

    val battleResult = if (descriptor.isBattle) {
        val maxSum = descriptor.rolls.maxOf { it.sum }
        // Distinct by domainName, preserving the order of first appearance so
        // a tie reads in the same order rolls were taken.
        val seen = mutableSetOf<String>()
        val leaders = mutableListOf<LeaderEntry>()
        for (roll in descriptor.rolls) {
            if (roll.sum != maxSum) continue
            val domain = roll.odinId.domainName
            if (!seen.add(domain)) continue
            val isSelf = currentOdinId.isNotBlank() && domain == currentOdinId
            leaders += LeaderEntry(
                isSelf = isSelf,
                name = if (isSelf) "" else hostPortionOf(domain),
            )
        }
        BattleResult(
            isClosed = chainCap != null && descriptor.rolls.size >= chainCap,
            maxSum = maxSum,
            leaders = leaders,
            isSelfAmong = leaders.any { it.isSelf },
        )
    } else null

    return DiceBubbleLines(
        senderIsSelf = senderIsSelf,
        senderName = if (senderIsSelf) "" else hostPortionOf(latestDomain),
        senderSum = latest.sum,
        senderResults = latest.results,
        battleResult = battleResult,
    )
}

/**
 * Display-name fallback: host portion of the odinId — `frodo.dotyou.cloud` →
 * `frodo`. Keeps things readable without a contact-book lookup. Mirrors the
 * pre-existing helper in [DiceRollBubble].
 */
private fun hostPortionOf(odinId: String): String =
    odinId.substringBefore('.').ifBlank { odinId }

/**
 * Battle chain cap for a given conversation: the chain is "closed" once
 * `rolls.size` reaches this number, at which point the bubble announces "won"
 * instead of "leads".
 *
 * The cap is the smaller of [DiceRollDescriptor.MAX_BATTLE_PARTICIPANTS] and
 * the conversation roster.
 *
 * @param rosterSize size of `ConversationUiModel.participants`, which
 *  **includes self** (per `ConversationService.kt:222`:
 *  `(normalizedRecipients + domain).distinct()`). For a 1:1 chat this is 2,
 *  for a self-chat it is 1, for a group of N it is N. Coerced to ≥1 so a
 *  defensively-empty list never makes the cap zero (which would prematurely
 *  close every chain).
 */
internal fun computeBattleChainCap(rosterSize: Int): Int =
    minOf(DiceRollDescriptor.MAX_BATTLE_PARTICIPANTS, rosterSize.coerceAtLeast(1))
