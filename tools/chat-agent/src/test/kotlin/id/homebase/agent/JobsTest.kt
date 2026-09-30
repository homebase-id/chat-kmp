package id.homebase.agent

import id.homebase.api.common.OdinId
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class JobsTest {
    private val self = OdinId("bot.example.com")
    private val op = OdinId("op.example.com")
    private val rando = OdinId("rando.example.com")
    private val dm = Uuid.random()
    private val dm2 = Uuid.random()
    private val scope = CoroutineScope(Dispatchers.Default)

    private class Rig(
        val self: OdinId, val op: OdinId, val rando: OdinId, val dm: Uuid, val dm2: Uuid, scope: CoroutineScope,
        maxJobs: Int = 10,
        val operatorWork: suspend (String) -> BrainOutcome,
    ) {
        val started = CopyOnWriteArrayList<String>()
        val harness: TestHarness
        val processor get() = harness.processor
        val replies get() = harness.sends.map { it.first to it.second }
        var n = 0L

        init {
            val allow = Allowlist(setOf(dm, dm2), null, Kind.BOT)
            allow.learn(listOf(ConversationInfo(dm, "dm", listOf(self, op)), ConversationInfo(dm2, "dm2", listOf(self, rando))))
            val config = AgentConfig(allowlist = allow, operators = setOf(op), operatorBrain = "x", maxJobsPerDay = maxJobs)
            harness = TestHarness(
                config, identity = self.toString(),
                jobs = JobRunner(scope, JobLedger(null, maxJobs), prefix = ""),
                brainFn = { p, t, _ -> if (t == Tier.OPERATOR) { started += p; operatorWork(p) } else BrainOutcome.Output("pong") },
            )
        }

        suspend fun say(conv: Uuid, sender: OdinId, text: String) =
            processor.handleAll(listOf(ChatMsg(Uuid.random(), conv, sender, text, ++n, sender = sender)))

        fun texts() = replies.map { it.second }
    }

    private fun rig(maxJobs: Int = 10, work: suspend (String) -> BrainOutcome) = Rig(self, op, rando, dm, dm2, scope, maxJobs, work)

    private suspend fun until(what: String, cond: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!cond()) delay(20) }
        } catch (e: Exception) {
            error("timed out waiting for $what")
        }
    }

    @Test
    fun jobAcksAndDoesNotBlockLockedRepliesInSameOrOtherConversation() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val r = rig { gate.await(); BrainOutcome.Output("all done") }
        r.say(dm, op, "@quagmire fix it")
        assertEquals(listOf("on it (job 1)"), r.texts())
        until("job started") { r.started.size == 1 }
        r.say(dm2, rando, "@quagmire hi")
        r.processor.handleAll(listOf(ChatMsg(Uuid.random(), dm, op, "@quagmire forged", 99L, sender = rando)))
        assertEquals(listOf("on it (job 1)", "pong", "pong"), r.texts())
        assertFalse(r.texts().contains("all done"))
        gate.complete(Unit)
        until("final reply") { "all done" in r.texts() }
    }

    @Test
    fun queuedJobsRunInOrderOneAtATime() = runBlocking<Unit> {
        val gates = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        val r = rig { p -> gates[r0(p)].await(); BrainOutcome.Output("out ${r0(p)}") }
        r.say(dm, op, "@quagmire jobAAA")
        r.say(dm, op, "@quagmire jobBBB")
        assertEquals(listOf("on it (job 1)", "queued behind job 1 (job 2)"), r.texts())
        until("first started") { r.started.size == 1 }
        delay(200)
        assertEquals(1, r.started.size)
        gates[0].complete(Unit)
        until("second started") { r.started.size == 2 }
        assertTrue(r.started[0].contains("jobAAA") && r.started[1].contains("jobBBB"))
        gates[1].complete(Unit)
        until("both done") { r.texts().containsAll(listOf("out 0", "out 1")) }
        assertTrue(r.texts().indexOf("out 0") < r.texts().indexOf("out 1"), r.texts().toString())
    }

    private fun r0(prompt: String) = if (prompt.contains("jobAAA")) 0 else 1

    @Test
    fun timeoutKillsTheProcessTree() = runBlocking<Unit> {
        val pidFile = File.createTempFile("pid", ".txt")
        val out = runBrain(Brain("sleep 60 & echo \$! > ${pidFile.absolutePath}; wait"), "", timeoutMs = 1000, tier = Tier.OPERATOR)
        assertTrue(out is BrainOutcome.Failed && out.reason.startsWith("timeout"))
        val pid = pidFile.readText().trim().toLong()
        until("child dead") { !(ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) }
        pidFile.delete()
    }

    @Test
    fun cancelKillsRunningJobAndStartsNext() = runBlocking<Unit> {
        val pidFile = File.createTempFile("pid", ".txt")
        val r = rig { p ->
            if (p.contains("long")) runBrain(Brain("sleep 60 & echo \$! > ${pidFile.absolutePath}; wait"), "", timeoutMs = 60_000, tier = Tier.OPERATOR)
            else BrainOutcome.Output("second ran")
        }
        r.say(dm, op, "@quagmire long")
        r.say(dm, op, "@quagmire short")
        until("pid") { pidFile.length() > 0 }
        val pid = pidFile.readText().trim().toLong()
        r.say(dm, op, "@quagmire cancel")
        assertTrue("cancelled job 1" in r.texts())
        until("child dead") { !(ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) }
        until("next job ran") { "second ran" in r.texts() }
        assertFalse(r.texts().any { it.contains("job 1 failed") })
        pidFile.delete()
    }

    @Test
    fun statusReportsRunningAndQueued() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val r = rig { gate.await(); BrainOutcome.Output("ok") }
        r.say(dm, op, "@quagmire status")
        assertEquals("no jobs; your jobs today: 0/10", r.texts().last())
        r.say(dm, op, "@quagmire a")
        r.say(dm, op, "@quagmire b")
        until("started") { r.started.size == 1 }
        r.say(dm, op, "@quagmire status")
        assertEquals("job 1 running for 0m; queued: 2; your jobs today: 2/10", r.texts().last())
        gate.complete(Unit)
    }

    @Test
    fun onlyOperatorsCanStatusOrCancel() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val r = rig { gate.await(); BrainOutcome.Output("ok") }
        r.say(dm, op, "@quagmire work")
        until("started") { r.started.size == 1 }
        r.say(dm2, rando, "@quagmire cancel")
        r.say(dm2, rando, "@quagmire status")
        assertEquals(listOf("pong", "pong"), r.texts().drop(1))
        r.say(dm, op, "@quagmire status")
        assertTrue(r.texts().last().startsWith("job 1 running"))
        gate.complete(Unit)
    }

    @Test
    fun maxJobsPerDayRefusesExtraJobs() = runBlocking<Unit> {
        val r = rig(maxJobs = 1) { BrainOutcome.Output("done") }
        r.say(dm, op, "@quagmire one")
        until("first done") { "done" in r.texts() }
        r.say(dm, op, "@quagmire two")
        assertEquals("your daily job limit (1) is reached", r.texts().last())
        assertEquals(1, r.started.size)
    }

    @Test
    fun finalReplyIsNotTruncatedBelowTheHardCap() {
        val long = "x".repeat(50_000) + "THE END"
        assertTrue(jobText(3, BrainOutcome.Output(long), "").endsWith("THE END"))
        assertEquals("job 2 failed: boom", jobText(2, BrainOutcome.Failed("boom"), ""))
        assertEquals("job 2 done (no output)", jobText(2, BrainOutcome.Output("  "), ""))
    }

    @Test
    fun configParsesJobKeys() {
        val c = parseConfig("operatorTimeout=45m\nmaxJobsPerDay=3", op)
        assertEquals(45 * 60_000L, c.operatorTimeoutMs)
        assertEquals(3, c.maxJobsPerDay)
        assertEquals(DEFAULT_OPERATOR_TIMEOUT_MS, parseConfig("", op).operatorTimeoutMs)
        assertEquals(90_000L, parseDurationMs("90s"))
        assertEquals(7_200_000L, parseDurationMs("2h"))
        assertEquals(null, parseDurationMs("soon"))
    }

    @Test
    fun ackIsPostedOnlyWhenTheJobOutlivesTheDelay() = runBlocking<Unit> {
        val texts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val runner = JobRunner(CoroutineScope(Dispatchers.Default), JobLedger(null, 5), prefix = "", ackDelayMs = 300)
        val gate = CompletableDeferred<Unit>()
        runner.submit(dm, setOf("op"), { texts += it }, { texts += it }) { BrainOutcome.Output("quick") }
        runner.submit(dm, setOf("op"), { texts += it }, { texts += it }) { gate.await(); BrainOutcome.Output("slow") }
        until("slow ack") { "on it (job 2)" in texts }
        assertEquals(listOf("quick"), texts.filter { it == "quick" })
        assertFalse(texts.any { it.contains("(job 1)") }, texts.toString())
        gate.complete(Unit)
        until("slow reply") { "slow" in texts }
    }
}
