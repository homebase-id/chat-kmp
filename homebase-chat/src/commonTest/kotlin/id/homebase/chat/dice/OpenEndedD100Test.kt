package id.homebase.chat.dice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the pure RMSS 1d100OE helpers — percentile pair math, signed
 * scoring across UP/DOWN/normal chains, and the safety-capped chain roller.
 */
class OpenEndedD100Test {

    // ---- percentilePair: tens & ones with the 10→0 convention ----

    @Test
    fun percentile_normal_pair_no_zero() {
        assertEquals(77, percentilePair(7, 7))
        assertEquals(12, percentilePair(1, 2))
        assertEquals(95, percentilePair(9, 5))
    }

    @Test
    fun percentile_tens_ten_maps_to_zero() {
        // tens=10 → 0 → "0x"
        assertEquals(1, percentilePair(10, 1))
        assertEquals(2, percentilePair(10, 2))
        assertEquals(9, percentilePair(10, 9))
    }

    @Test
    fun percentile_ones_ten_maps_to_zero() {
        // ones=10 → 0 → "x0"
        assertEquals(70, percentilePair(7, 10))
        assertEquals(10, percentilePair(1, 10))
    }

    @Test
    fun percentile_double_zero_is_one_hundred() {
        assertEquals(100, percentilePair(10, 10))
    }

    // ---- computeOpenEndedSum: three regimes ----

    @Test
    fun sum_users_worked_example_up_chain() {
        // [10,10,9,8,10,2] → pairs (100, 98, 2). First ≥96 → UP → sum=200.
        assertEquals(200, computeOpenEndedSum(listOf(10, 10, 9, 8, 10, 2)))
    }

    @Test
    fun sum_normal_single_pair_just_returns_pair_value() {
        // 4 & 7 → 47, 5..95 → no chain, return as-is.
        assertEquals(47, computeOpenEndedSum(listOf(4, 7)))
    }

    @Test
    fun sum_down_chain_subtracts_subsequent_pairs_and_goes_negative() {
        // 0,3 → 03 (DOWN), then 0,2 → 02, then 5,0 → 50. Result = 3 - 2 - 50 = -49.
        assertEquals(-49, computeOpenEndedSum(listOf(10, 3, 10, 2, 5, 10)))
    }

    @Test
    fun sum_down_chain_single_low_then_stop() {
        // First pair 02 (DOWN), second pair 47 (stops chain, still subtracted).
        // Result = 2 - 47 = -45.
        assertEquals(-45, computeOpenEndedSum(listOf(10, 2, 4, 7)))
    }

    @Test
    fun sum_empty_results_returns_zero() {
        assertEquals(0, computeOpenEndedSum(emptyList()))
    }

    // ---- rollOpenEndedD100: shape and safety ----

    @Test
    fun roll_always_returns_even_length_at_least_two() {
        repeat(50) {
            val out = rollOpenEndedD100(shakeSamples = null)
            assertTrue(out.size >= 2, "got ${out.size}")
            assertEquals(0, out.size % 2, "odd length: $out")
            assertTrue(out.size <= OE_MAX_PAIRS * 2, "exceeded cap: ${out.size}")
            assertTrue(out.all { it in 1..10 }, "out-of-range face: $out")
        }
    }

    @Test
    fun roll_with_shake_seed_stays_in_range() {
        val samples = listOf(0x1234567890abcdefL, 42L)
        val out = rollOpenEndedD100(samples)
        assertTrue(out.size in 2..OE_MAX_PAIRS * 2)
        assertEquals(0, out.size % 2)
    }
}
