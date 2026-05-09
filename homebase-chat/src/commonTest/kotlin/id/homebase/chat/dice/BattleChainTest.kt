package id.homebase.chat.dice

import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class BattleChainTest {

    private val aliceId = Uuid.random()
    private val bobId = Uuid.random()
    private val carolId = Uuid.random()
    private val danId = Uuid.random()
    private val newcomerId = Uuid.random()

    private val alice = OdinId("alice.test")
    private val bob = OdinId("bob.test")
    private val carol = OdinId("carol.test")
    private val dan = OdinId("dan.test")
    private val newcomer = OdinId("newcomer.test")

    @Test
    fun chainRootMessageId_is_first_entry_for_battle() {
        val battle = battle(
            entry(aliceId, alice, 10),
            entry(bobId, bob, 15),
        )
        assertEquals(aliceId, battle.chainRootMessageId())
    }

    @Test
    fun chainRootMessageId_is_self_for_standalone() {
        val standalone = standalone(aliceId, alice, 10)
        assertEquals(aliceId, standalone.chainRootMessageId())
    }

    @Test
    fun findChainNewest_returns_target_when_no_chainmate_in_memory() {
        val target = standalone(aliceId, alice, 10)
        val newest = findChainNewest(target, listOf(target))
        assertEquals(target, newest)
    }

    @Test
    fun findChainNewest_returns_latest_battle_in_chain() {
        val root = standalone(aliceId, alice, 10)
        val bobBattle = battle(
            entry(aliceId, alice, 10),
            entry(bobId, bob, 15),
        )
        val carolBattle = battle(
            entry(aliceId, alice, 10),
            entry(bobId, bob, 15),
            entry(carolId, carol, 20),
        )
        // Long-press the original root — newest in memory is carol's.
        val newest = findChainNewest(root, listOf(root, bobBattle, carolBattle))
        assertEquals(carolBattle, newest)
    }

    @Test
    fun findChainNewest_ignores_other_chains() {
        val chainA = standalone(aliceId, alice, 10)
        val chainBRoot = Uuid.random()
        val chainBSecond = Uuid.random()
        val chainB = battle(
            entry(chainBRoot, carol, 5),
            entry(chainBSecond, dan, 8),
        )
        val newest = findChainNewest(chainA, listOf(chainA, chainB))
        assertEquals(chainA, newest)
    }

    @Test
    fun canBattle_blocks_null_caller() {
        val target = standalone(aliceId, alice, 10)
        assertFalse(canBattle(target, currentOdinId = null, listOf(target)))
    }

    @Test
    fun canBattle_blocks_self() {
        val target = standalone(aliceId, alice, 10)
        // Alice can't battle her own standalone roll.
        assertFalse(canBattle(target, currentOdinId = alice, listOf(target)))
    }

    @Test
    fun canBattle_blocks_already_in_chain() {
        val carolBattle = battle(
            entry(aliceId, alice, 10),
            entry(bobId, bob, 15),
            entry(carolId, carol, 20),
        )
        // Bob already battled — can't go again.
        assertFalse(canBattle(carolBattle, currentOdinId = bob, listOf(carolBattle)))
    }

    @Test
    fun canBattle_blocks_when_chain_full() {
        val full = battle(
            entry(Uuid.random(), OdinId("p1.test"), 1),
            entry(Uuid.random(), OdinId("p2.test"), 2),
            entry(Uuid.random(), OdinId("p3.test"), 3),
            entry(Uuid.random(), OdinId("p4.test"), 4),
            entry(Uuid.random(), OdinId("p5.test"), 5),
        )
        assertEquals(DiceRollDescriptor.MAX_BATTLE_PARTICIPANTS, full.rolls.size)
        assertFalse(canBattle(full, currentOdinId = newcomer, listOf(full)))
    }

    @Test
    fun canBattle_allows_fresh_caller_in_open_chain() {
        val bobBattle = battle(
            entry(aliceId, alice, 10),
            entry(bobId, bob, 15),
        )
        assertTrue(canBattle(bobBattle, currentOdinId = carol, listOf(bobBattle)))
    }

    @Test
    fun canBattle_uses_newest_chain_state_not_long_pressed_message() {
        // Long-press the original root, but a newer battle in memory has carol
        // already in the chain. canBattle should reflect that.
        val root = standalone(aliceId, alice, 10)
        val bobBattle = battle(
            entry(aliceId, alice, 10),
            entry(bobId, bob, 15),
        )
        val carolBattle = battle(
            entry(aliceId, alice, 10),
            entry(bobId, bob, 15),
            entry(carolId, carol, 20),
        )
        val all = listOf(root, bobBattle, carolBattle)
        // Carol pressing on the root — already in chain, should be blocked.
        assertFalse(canBattle(root, currentOdinId = carol, all))
        // Dan fresh — allowed.
        assertTrue(canBattle(root, currentOdinId = dan, all))
    }

    // ---- helpers ----

    private fun entry(messageId: Uuid, odinId: OdinId, sum: Int): ChainRoll = ChainRoll(
        messageId = messageId,
        odinId = odinId,
        results = listOf(sum.coerceIn(1, 20)),
        rolledAtUtcMs = 1_700_000_000_000L,
    )

    private fun standalone(messageId: Uuid, odinId: OdinId, sum: Int): DiceRollDescriptor =
        DiceRollDescriptor(
            faces = 20,
            rolls = listOf(entry(messageId, odinId, sum)),
        )

    /** Builds a battle with chronologically-ordered entries (1ms apart). */
    private fun battle(vararg entries: ChainRoll): DiceRollDescriptor {
        val withTimestamps = entries.mapIndexed { idx, e ->
            e.copy(rolledAtUtcMs = 1_700_000_000_000L + idx)
        }
        return DiceRollDescriptor(faces = 20, rolls = withTimestamps)
    }
}
