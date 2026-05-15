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
 *
 * [mode] selects the scoring rules. `Standard` is plain `Nd{F}` (sum of faces).
 * `OpenEndedD100` is RMSS 1d100OE: results are pairs of raw d10 face values
 * (always even length, possibly varying per chain entry), scored via
 * [computeOpenEndedSum].
 */
@Serializable
data class DiceRollDescriptor(
    val faces: Int,
    val rolls: List<ChainRoll>,
    val mode: DiceRollMode = DiceRollMode.Standard,
    val schemaVersion: Int = 1,
) {
    val latest: ChainRoll get() = rolls.last()
    val sum: Int get() = scoredSumOf(latest)
    val isBattle: Boolean get() = rolls.size > 1

    /** Mode-aware score for one chain entry. Used for leader computation. */
    fun scoredSumOf(roll: ChainRoll): Int = when (mode) {
        DiceRollMode.Standard -> roll.results.sum()
        DiceRollMode.OpenEndedD100 -> computeOpenEndedSum(roll.results)
    }

    fun summaryLine(): String = when {
        isBattle -> "Battle: rolled $sum"
        mode == DiceRollMode.OpenEndedD100 -> "Rolled $sum (1d100OE)"
        else -> "Rolled $sum (${latest.results.size}d$faces)"
    }

    fun isValid(): Boolean {
        if (faces !in ALLOWED_FACES) return false
        if (rolls.isEmpty() || rolls.size > MAX_BATTLE_PARTICIPANTS) return false

        when (mode) {
            DiceRollMode.Standard -> {
                val expectedCount = rolls.first().results.size
                if (expectedCount !in 1..MAX_DICE) return false
                for (r in rolls) {
                    if (r.results.size != expectedCount) return false
                    if (r.results.any { it !in 1..faces }) return false
                }
            }
            DiceRollMode.OpenEndedD100 -> {
                if (faces != 10) return false
                for (r in rolls) {
                    if (r.results.size !in 2..MAX_DICE) return false
                    if (r.results.size % 2 != 0) return false
                    if (r.results.any { it !in 1..faces }) return false
                }
            }
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
        /** Wire-level cap per chain entry. OE chains can grow up to 10 pairs (20 raw d10s). */
        const val MAX_DICE = 20
        /** Composer cap for standard `Nd{F}` rolls — unchanged from before OE. */
        const val MAX_STANDARD_DICE = 10
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

@Serializable
enum class DiceRollMode {
    Standard,
    OpenEndedD100,
}

/**
 * Pure roll function for standard `Nd{F}` rolls — extracted so it's
 * unit-testable. When [shakeSamples] is non-empty we XOR the samples into the
 * seed for an extra dose of fun-grade entropy; otherwise we fall back to
 * [kotlin.random.Random.Default].
 */
internal fun roll(count: Int, faces: Int, shakeSamples: List<Long>?): List<Int> {
    require(count >= 1) { "count must be >= 1" }
    require(faces in DiceRollDescriptor.ALLOWED_FACES) { "faces $faces not allowed" }
    val rng = makeRng(shakeSamples)
    return List(count) { rng.nextInt(1, faces + 1) }
}
