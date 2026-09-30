package id.homebase.agent

import id.homebase.api.common.OdinId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SenderTierTest {
    private val self = OdinId("bot.example.com")
    private val op1 = OdinId("op1.example.com")
    private val op2 = OdinId("op2.example.com")
    private val rando = OdinId("rando.example.com")
    private val grp = Uuid.random()
    private var n = 0L

    private fun cfg(brain: String? = "full", operators: Set<OdinId> = setOf(op1, op2)): AgentConfig {
        val allow = Allowlist(setOf(grp), null, Kind.BOT)
        allow.learn(listOf(ConversationInfo(grp, "mixed", listOf(self, op1, op2, rando))))
        return AgentConfig(allowlist = allow, operators = operators, operatorBrain = brain)
    }

    private fun msg(who: OdinId?, text: String, author: OdinId? = who, raw: String? = null) =
        ChatMsg(Uuid.random(), grp, author, text, ++n, sender = who, rawContent = raw)

    private fun trigger(who: OdinId?, author: OdinId? = who) = msg(who, "@quagmire hi from ${who?.domainName}", author)

    @Test
    fun operatorTriggerInMixedGroupGetsOperatorBrainWithOnlyOperatorHistory() = runBlocking<Unit> {
        val hist = listOf(
            msg(op2, "operator earlier"),
            msg(rando, "RANDO SECRET INJECTION"),
            msg(self, "BOT EARLIER REPLY"),
            msg(null, "NULL SENDER TEXT", author = op1),
        )
        val h = TestHarness(cfg(), identity = self.toString(), history = hist)
        h.handle(trigger(op1))
        assertEquals(listOf(Tier.OPERATOR), h.tiers)
        val p = h.prompts.single()
        assertTrue("operator earlier" in p)
        assertFalse("RANDO SECRET INJECTION" in p)
        assertFalse("BOT EARLIER REPLY" in p)
        assertFalse("NULL SENDER TEXT" in p)
    }

    @Test
    fun nonOperatorTriggerInSameRoomIsLockedWithNormalHistory() = runBlocking<Unit> {
        val h = TestHarness(cfg(), identity = self.toString(), history = listOf(msg(op2, "operator earlier"), msg(rando, "rando earlier")))
        h.handle(trigger(rando))
        assertEquals(listOf(Tier.LOCKED), h.tiers)
        assertTrue("rando earlier" in h.prompts.single() && "operator earlier" in h.prompts.single())
    }

    @Test
    fun mixedPollRunsTwiceOneEachTier() = runBlocking<Unit> {
        val h = TestHarness(cfg(), identity = self.toString())
        val results = h.handleAll(listOf(trigger(op1), trigger(rando), trigger(op2)))
        assertEquals(2, h.brainRuns)
        assertEquals(listOf(Tier.OPERATOR, Tier.LOCKED), h.tiers)
        val (operatorPrompt, lockedPrompt) = h.prompts
        assertTrue("from op1" in operatorPrompt && "from op2" in operatorPrompt && "from rando" !in operatorPrompt)
        assertTrue("from rando" in lockedPrompt && "from op1" !in lockedPrompt)
        assertEquals(3, results.size)
        assertTrue(results.values.all { it == "replied" })
    }

    @Test
    fun replyParentFromNonOperatorIsOmittedFromOperatorPrompt() = runBlocking<Unit> {
        val parent = msg(rando, "PARENT TEXT FROM RANDO")
        val raw = """{"replyPreview":{"replyUniqueId":"${parent.id}","authorOdinId":"rando","message":"PARENT PREVIEW"},"message":"@quagmire hi","version":1}"""
        val h = TestHarness(cfg(), identity = self.toString(), history = listOf(parent))
        h.handle(msg(op1, "@quagmire what about that?", raw = raw))
        val p = h.prompts.single()
        assertTrue("[replied-to message from a non-operator omitted]" in p)
        assertFalse("PARENT TEXT FROM RANDO" in p)
        val opParent = msg(op2, "operator parent")
        val raw2 = raw.replace(parent.id.toString(), opParent.id.toString())
        val h2 = TestHarness(cfg(), identity = self.toString(), history = listOf(opParent))
        h2.handle(msg(op1, "@quagmire again", raw = raw2))
        assertFalse("omitted" in h2.prompts.single())
    }

    @Test
    fun forgedOrNullSenderIsLockedInMixedRoom() = runBlocking<Unit> {
        val h = TestHarness(cfg(), identity = self.toString())
        h.handle(trigger(rando, author = op1))
        h.handle(trigger(null, author = op1))
        assertEquals(listOf(Tier.LOCKED, Tier.LOCKED), h.tiers)
    }

    @Test
    fun noOperatorBrainIsAlwaysLocked() = runBlocking<Unit> {
        val h = TestHarness(cfg(brain = null), identity = self.toString())
        h.handle(trigger(op1))
        assertEquals(listOf(Tier.LOCKED), h.tiers)
        assertEquals(Tier.LOCKED, TrustPolicy(cfg(brain = null), self).tier(listOf(self, op1), false, setOf(op1)))
    }

    @Test
    fun operatorsAloneAreEnoughToParse() {
        val c = parseConfig("operators=${op1}\noperatorBrain=x", op1)
        assertTrue(c.operatorRooms.isEmpty())
        assertEquals(Tier.OPERATOR, TrustPolicy(c, self).tier(listOf(self, op1, rando), false, setOf(op1)))
    }

    @Test
    fun bannerSaysOperatorsGetOperatorBrainEverywhere() {
        val lines = tierBanner(cfg())
        assertTrue(lines.any { it.contains("operators=$op1,$op2") && it.contains("EVERY allowed conversation") }, lines.toString())
    }

    private class Clock(var t: Long = 1_000_000_000L) : () -> Long { override fun invoke() = t }

    @Test
    fun ledgerCountsPerOperatorAndPersistsAcrossRestart() {
        val clock = Clock()
        val f = File.createTempFile("jobs", ".txt")
        val a = JobLedger(f, 2, clock)
        a.record("op1"); a.record("op1"); a.record("op2")
        assertFalse(a.allows("op1"))
        assertTrue(a.allows("op2"))
        val b = JobLedger(f, 2, clock)
        assertEquals(2, b.used("op1"))
        assertEquals(1, b.used("op2"))
        assertFalse(b.allows("op1"))
        assertTrue(b.markNotified("op1"))
        assertFalse(JobLedger(f, 2, clock).markNotified("op1"))
        clock.t += 25 * 3_600_000L
        assertTrue(JobLedger(f, 2, clock).allows("op1"))
        f.delete()
    }

    @Test
    fun overCapOperatorGetsOneNoticeAndOtherOperatorIsUnaffected() = runBlocking<Unit> {
        val runner = JobRunner(CoroutineScope(Dispatchers.Default), JobLedger(null, 1), prefix = "")
        val h = TestHarness(cfg(), identity = self.toString(), jobs = runner, brainFn = { _, _, _ -> BrainOutcome.Output("done") })
        suspend fun say(who: OdinId) = h.handleAll(listOf(trigger(who)))
        suspend fun until(what: String, c: () -> Boolean) = withTimeout(5_000) { while (!c()) delay(20) }
        say(op1)
        until("op1 job") { "done" in h.replies }
        say(op1)
        say(op1)
        assertEquals(1, h.replies.count { it == "your daily job limit (1) is reached" })
        say(op2)
        until("op2 job") { h.replies.count { it == "done" } == 2 }
        assertEquals(2, h.brainRuns)
        say(op1)
        assertEquals(1, h.replies.count { it.startsWith("your daily job limit") })
        h.handleAll(listOf(msg(op1, "@quagmire status")))
        assertTrue(h.replies.last().contains("your jobs today: 1/1"), h.replies.toString())
    }
}
