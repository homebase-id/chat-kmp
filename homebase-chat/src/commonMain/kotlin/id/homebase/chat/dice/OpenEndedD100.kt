package id.homebase.chat.dice

import kotlin.random.Random
import kotlin.time.Clock

/**
 * Pure helpers for Rolemaster Standard System (RMSS) 1d100 open-ended rolls.
 *
 * Convention: raw d10 face values stay in `1..10` (matching the rest of the
 * dice subsystem). For percentile math, a `10` is read as `0`. A `(0, 0)`
 * reading is `100`.
 *
 * Chain rules:
 *  - **UP** chain (first pair ≥ 96): each subsequent pair is *added*. Chain
 *    continues while the latest pair value is ≥ 96. The stopping pair (one
 *    that's < 96) is still included.
 *  - **DOWN** chain (first pair ≤ 4): each subsequent pair is *subtracted*
 *    from the base. Chain continues while the latest pair value is ≤ 4.
 *    The stopping pair is still subtracted. Final result can be negative.
 *  - **Normal** (first pair 5..95): no chain — single pair result.
 *
 * Direction is locked by the *first* pair; later "extreme" pairs cannot
 * flip direction.
 */

/** Pair value: tens × 10 + ones, with `10` treated as `0` and `(0,0) → 100`. */
internal fun percentilePair(tens: Int, ones: Int): Int {
    val t = if (tens == 10) 0 else tens
    val o = if (ones == 10) 0 else ones
    return if (t == 0 && o == 0) 100 else t * 10 + o
}

/** Split a flat list of raw d10s into 2-die percentile pair values. */
internal fun percentilePairs(rawResults: List<Int>): List<Int> {
    require(rawResults.size % 2 == 0) { "OE rolls come in pairs" }
    val out = ArrayList<Int>(rawResults.size / 2)
    var i = 0
    while (i < rawResults.size) {
        out += percentilePair(rawResults[i], rawResults[i + 1])
        i += 2
    }
    return out
}

/**
 * Signed total for an OE result. Positive for UP chain or single normal pair,
 * potentially negative for DOWN chain.
 */
internal fun computeOpenEndedSum(rawResults: List<Int>): Int {
    val pairs = percentilePairs(rawResults)
    if (pairs.isEmpty()) return 0
    val first = pairs.first()
    return when {
        first >= 96 -> {
            var s = 0
            for (p in pairs) s += p
            s
        }
        first <= 4 -> {
            var s = first
            for (i in 1 until pairs.size) s -= pairs[i]
            s
        }
        else -> first
    }
}

/** Hard safety cap on chain length. 10 pairs ≈ 1-in-10-billion probability. */
internal const val OE_MAX_PAIRS = 10

/**
 * Roll a single OE chain. Returns a flat list of raw d10s (length is even,
 * 2..OE_MAX_PAIRS*2).
 */
internal fun rollOpenEndedD100(shakeSamples: List<Long>?): List<Int> {
    val rng = makeRng(shakeSamples)
    val out = ArrayList<Int>(2)
    var direction = 0
    while (out.size < OE_MAX_PAIRS * 2) {
        val tens = rng.nextInt(1, 11)
        val ones = rng.nextInt(1, 11)
        out += tens
        out += ones
        val pair = percentilePair(tens, ones)
        if (direction == 0) {
            direction = when {
                pair >= 96 -> 1
                pair <= 4 -> -1
                else -> break
            }
        } else {
            val triggers = if (direction == 1) pair >= 96 else pair <= 4
            if (!triggers) break
        }
    }
    return out
}

/**
 * Build the RNG for a roll: when shake samples are present XOR them into the
 * wall clock seed; otherwise use the default secure source. Shared between
 * standard and open-ended rolling so both paths get the same seeding policy.
 */
internal fun makeRng(shakeSamples: List<Long>?): Random {
    if (shakeSamples.isNullOrEmpty()) return Random.Default
    var seed = Clock.System.now().toEpochMilliseconds()
    for (sample in shakeSamples) seed = seed xor sample
    return Random(seed)
}
