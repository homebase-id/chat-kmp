package id.homebase.chat.dice

import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Tests for [computeDiceBubbleLines] — the pure decision function that drives
 * what `DiceRollBubble` puts on screen.
 *
 * The bubble shows up to two lines:
 *  - **Sender line** ("You rolled 3" / "Sam rolled 3") — always present, identifies
 *    whose roll the bubble represents. Today's bubble *omits* this line for
 *    battles, which is the "I see 2 × You" bug Sam's roll exposed: the original
 *    standalone bubble said "You rolled X" and Sam's battle response said "You
 *    lead with X" (because the user was leading), so the user saw "You" twice
 *    and never saw "Sam" anywhere.
 *  - **Result line** (leader/winner/tie) — only for battles.
 */
class DiceBubbleLinesTest {

    private val selfOdinId = "frodo.demo.rocks"
    private val sam = OdinId("sam.demo.rocks")
    private val self = OdinId(selfOdinId)
    private val gandalf = OdinId("gandalf.demo.rocks")
    private val pippin = OdinId("pippin.demo.rocks")
    private val merry = OdinId("merry.demo.rocks")

    // ---- Standalone rolls: sender line only, no battle result ----

    @Test
    fun standalone_self_single_die() {
        val desc = standalone(self, listOf(5))
        val lines = computeDiceBubbleLines(desc, selfOdinId, chainCap = 2)

        assertTrue(lines.senderIsSelf)
        assertEquals(5, lines.senderSum)
        assertEquals(listOf(5), lines.senderResults)
        assertNull(lines.battleResult)
    }

    @Test
    fun standalone_other_multi_die() {
        val desc = standalone(sam, listOf(3, 4, 5))
        val lines = computeDiceBubbleLines(desc, selfOdinId, chainCap = 2)

        assertEquals(false, lines.senderIsSelf)
        assertEquals("sam", lines.senderName)
        assertEquals(12, lines.senderSum)
        assertEquals(listOf(3, 4, 5), lines.senderResults)
        assertNull(lines.battleResult)
    }

    // ---- The bug: battle bubble must still show who rolled THIS bubble ----

    @Test
    fun battle_received_from_other_includes_other_sender_line() {
        // 1:1 chat. User rolled 5, Sam dueled with 3. From user's screen, Sam's
        // battle bubble should still call out "Sam rolled 3" — the "2 × You"
        // bug was that this line was missing entirely, so Sam's bubble only
        // said "You lead with 5" and never named Sam.
        val desc = battle(
            entry(self, listOf(5)),
            entry(sam, listOf(3)),
        )
        val lines = computeDiceBubbleLines(desc, selfOdinId, chainCap = 2)

        assertEquals(false, lines.senderIsSelf)
        assertEquals("sam", lines.senderName)
        assertEquals(3, lines.senderSum)

        val result = assertNotNull(lines.battleResult)
        assertTrue(result.isClosed) // 1:1 chat (chainCap=2), 2 rolls → closed
        assertEquals(5, result.maxSum)
        assertEquals(1, result.leaders.size)
        assertTrue(result.leaders.single().isSelf)
        assertTrue(result.isSelfAmong)
    }

    @Test
    fun battle_sent_by_self_includes_self_sender_line() {
        // User dueled Sam. Sam's prior roll was 3, user's roll is 5.
        val desc = battle(
            entry(sam, listOf(3)),
            entry(self, listOf(5)),
        )
        val lines = computeDiceBubbleLines(desc, selfOdinId, chainCap = 2)

        assertTrue(lines.senderIsSelf)
        assertEquals(5, lines.senderSum)
        assertNotNull(lines.battleResult)
    }

    // ---- Winner branch (closed chain) ----

    @Test
    fun closed_chain_with_outright_winner_self() {
        val desc = battle(
            entry(self, listOf(6)),
            entry(sam, listOf(2)),
        )
        val result = assertNotNull(
            computeDiceBubbleLines(desc, selfOdinId, chainCap = 2).battleResult
        )
        assertTrue(result.isClosed)
        assertEquals(1, result.leaders.size)
        assertTrue(result.leaders.single().isSelf)
        assertTrue(result.isSelfAmong)
    }

    @Test
    fun closed_chain_with_outright_winner_other() {
        val desc = battle(
            entry(self, listOf(2)),
            entry(sam, listOf(6)),
        )
        val result = assertNotNull(
            computeDiceBubbleLines(desc, selfOdinId, chainCap = 2).battleResult
        )
        assertTrue(result.isClosed)
        val leader = result.leaders.single()
        assertEquals(false, leader.isSelf)
        assertEquals("sam", leader.name)
        assertEquals(false, result.isSelfAmong)
    }

    @Test
    fun closed_chain_at_max_participants() {
        // Group with 6+ members: chainCap = 5 (MAX_BATTLE_PARTICIPANTS),
        // and the chain just hit 5 → closed even though more roster remains.
        val desc = battle(
            entry(self, listOf(2)),
            entry(sam, listOf(3)),
            entry(gandalf, listOf(4)),
            entry(pippin, listOf(5)),
            entry(merry, listOf(6)),
        )
        val result = assertNotNull(
            computeDiceBubbleLines(desc, selfOdinId, chainCap = 5).battleResult
        )
        assertTrue(result.isClosed)
        assertEquals("merry", result.leaders.single().name)
    }

    // ---- Leader branch (open chain) ----

    @Test
    fun open_chain_in_large_group_uses_leader_not_winner() {
        // Group of 5+, chainCap=5, only 3 rolled so far → still open.
        val desc = battle(
            entry(self, listOf(6)),
            entry(sam, listOf(3)),
            entry(gandalf, listOf(4)),
        )
        val result = assertNotNull(
            computeDiceBubbleLines(desc, selfOdinId, chainCap = 5).battleResult
        )
        assertEquals(false, result.isClosed)
        assertTrue(result.leaders.single().isSelf)
    }

    // ---- Tie cases ----

    @Test
    fun closed_chain_tie_marks_self_among_when_self_ties() {
        val desc = battle(
            entry(self, listOf(5)),
            entry(sam, listOf(5)),
        )
        val result = assertNotNull(
            computeDiceBubbleLines(desc, selfOdinId, chainCap = 2).battleResult
        )
        assertTrue(result.isClosed)
        assertEquals(2, result.leaders.size)
        assertTrue(result.isSelfAmong)
        // Order preserved from descriptor.rolls.
        assertTrue(result.leaders[0].isSelf)
        assertEquals("sam", result.leaders[1].name)
    }

    @Test
    fun open_chain_tie_between_others_only() {
        val desc = battle(
            entry(self, listOf(2)),
            entry(sam, listOf(5)),
            entry(gandalf, listOf(5)),
        )
        val result = assertNotNull(
            computeDiceBubbleLines(desc, selfOdinId, chainCap = 5).battleResult
        )
        assertEquals(false, result.isClosed)
        assertEquals(false, result.isSelfAmong)
        assertEquals(listOf("sam", "gandalf"), result.leaders.map { it.name })
    }

    // ---- chainCap = null → defensive default, never reports closed ----

    @Test
    fun null_chainCap_keeps_chain_open_for_safety() {
        // DisplayOnly previews / link previews call the bubble without a
        // roster — the bubble must not falsely declare a winner.
        val desc = battle(
            entry(self, listOf(5)),
            entry(sam, listOf(3)),
        )
        val result = assertNotNull(
            computeDiceBubbleLines(desc, selfOdinId, chainCap = null).battleResult
        )
        assertEquals(false, result.isClosed)
    }

    // ---- chainCap formula ----

    @Test
    fun chainCap_one_on_one_two_rolls_closes() {
        // 1:1 with Sam — `ConversationUiModel.participants` is `[me, sam]`, so
        // size=2. After both rolled (rolls.size=2), the chain must be closed
        // and the bubble must announce a winner. This is the regression that
        // the desktop test caught: the formula previously added +1 and the
        // chain never closed in 1:1.
        val cap = computeBattleChainCap(rosterSize = 2)
        assertEquals(2, cap)

        val desc = battle(
            entry(self, listOf(21)),
            entry(sam, listOf(24)),
        )
        val result = assertNotNull(
            computeDiceBubbleLines(desc, selfOdinId, chainCap = cap).battleResult
        )
        assertTrue(result.isClosed, "1:1 chain at 2 rolls must close")
    }

    @Test
    fun chainCap_self_chat_one_roll_closes() {
        // Self chat — participants list is just `[me]`.
        assertEquals(1, computeBattleChainCap(rosterSize = 1))
    }

    @Test
    fun chainCap_small_group_uses_roster() {
        // 3-person group — full roster fits inside MAX, so the chain closes
        // when everyone rolled.
        assertEquals(3, computeBattleChainCap(rosterSize = 3))
    }

    @Test
    fun chainCap_large_group_caps_at_max() {
        // 6+ members — the protocol cap kicks in first.
        assertEquals(DiceRollDescriptor.MAX_BATTLE_PARTICIPANTS, computeBattleChainCap(rosterSize = 6))
        assertEquals(DiceRollDescriptor.MAX_BATTLE_PARTICIPANTS, computeBattleChainCap(rosterSize = 50))
    }

    @Test
    fun chainCap_empty_roster_falls_back_to_one() {
        // Defensive: an empty list never produces 0, which would close every
        // chain immediately.
        assertEquals(1, computeBattleChainCap(rosterSize = 0))
    }

    // ---- helpers ----

    private fun entry(odinId: OdinId, results: List<Int>): ChainRoll = ChainRoll(
        messageId = Uuid.random(),
        odinId = odinId,
        results = results,
        rolledAtUtcMs = 1_700_000_000_000L,
    )

    private fun standalone(odinId: OdinId, results: List<Int>): DiceRollDescriptor =
        DiceRollDescriptor(faces = 6, rolls = listOf(entry(odinId, results)))

    private fun battle(vararg entries: ChainRoll): DiceRollDescriptor {
        val withTimestamps = entries.mapIndexed { idx, e ->
            e.copy(rolledAtUtcMs = 1_700_000_000_000L + idx)
        }
        return DiceRollDescriptor(faces = 6, rolls = withTimestamps)
    }
}
