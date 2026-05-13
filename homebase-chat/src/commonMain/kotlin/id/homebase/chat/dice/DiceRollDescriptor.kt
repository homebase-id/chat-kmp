package id.homebase.chat.dice

import id.homebase.api.common.OdinId
import id.homebase.api.serialization.UuidSerializer
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/**
 * Wire format for a dice-roll message. Serialized as JSON into the chat message
 * header's `appData.content` field (alongside `appData.dataType =
 * ChatProtocol.ChatDiceRollMessageDataType`), so it loads with the message
 * index — no payload fetch on scroll.
 *
 * The chain history lives entirely in [rolls]:
 *  - `rolls.size == 1` → standalone roll (or chain root that hasn't been
 *    battled yet — wire-identical until someone embeds it as a prior).
 *  - `rolls.size >= 2` → battle. The last entry is THIS message's roll; earlier
 *    entries are the chain history copied forward.
 *
 * Bubbles render entirely from this descriptor — no in-memory chain walk, no
 * baked snapshot. The leader line is computed from [rolls] directly. Historical
 * bubbles never change as new battles arrive.
 */
@Serializable
data class DiceRollDescriptor(
    val faces: Int,
    val rolls: List<ChainRoll>,
    val schemaVersion: Int = 1,
) {
    val latest: ChainRoll get() = rolls.last()
    val sum: Int get() = latest.sum
    val isBattle: Boolean get() = rolls.size > 1

    fun summaryLine(): String = if (isBattle) {
        "Battle: rolled $sum"
    } else {
        "Rolled $sum (${latest.results.size}d$faces)"
    }

    fun isValid(): Boolean {
        if (faces !in ALLOWED_FACES) return false
        if (rolls.isEmpty() || rolls.size > MAX_BATTLE_PARTICIPANTS) return false

        val expectedCount = rolls.first().results.size
        if (expectedCount !in 1..MAX_DICE) return false

        // Per-entry validation.
        for (r in rolls) {
            if (r.results.size != expectedCount) return false
            if (r.results.any { it !in 1..faces }) return false
        }
        // Chronological order across the chain.
        for (i in 1 until rolls.size) {
            if (rolls[i].rolledAtUtcMs < rolls[i - 1].rolledAtUtcMs) return false
        }
        // Distinct odinIds (no rebattle).
        val odinIds = rolls.map { it.odinId }
        if (odinIds.toSet().size != odinIds.size) return false
        // Distinct messageIds (defensive — duplicates would imply the same
        // message appearing twice in the chain).
        val messageIds = rolls.map { it.messageId }
        if (messageIds.toSet().size != messageIds.size) return false
        return true
    }

    companion object {
        val ALLOWED_FACES = listOf(4, 6, 8, 10, 12, 20)
        const val MAX_DICE = 10
        const val MAX_BATTLE_PARTICIPANTS = 5
    }
}

/**
 * One roll in a dice chain. [messageId] is the chat-message uniqueId of the
 * message that originally carried this roll — a back-reference to that
 * message's envelope. For the latest entry in the array, that's also the
 * current message's id; for earlier entries, it points at the chat message
 * that previously embedded the same roll.
 */
@Serializable
data class ChainRoll(
    @Serializable(with = UuidSerializer::class) val messageId: Uuid,
    val odinId: OdinId,
    val results: List<Int>,
    val rolledAtUtcMs: Long,
    val source: RollSource = RollSource.LocalRandom,
) {
    val sum: Int get() = results.sum()
}

@Serializable
enum class RollSource {
    LocalRandom,
    ShakeSeeded,
    // RandomOrg — reserved for a later remote-RNG branch.
}
