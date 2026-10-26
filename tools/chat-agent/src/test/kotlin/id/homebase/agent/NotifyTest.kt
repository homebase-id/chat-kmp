package id.homebase.agent

import id.homebase.api.common.OdinId
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class NotifyTest {
    private val owner = OdinId("owner.example.com")
    private val bot = OdinId("bot.example.com")
    private val dm = Uuid.random()
    private val minute = 60_000L
    private val base = 1_700_000_000_000L

    private class Rig(
        scope: CoroutineScope,
        val clock: () -> Long,
        val dir: File,
        val dm: Uuid,
        owner: OdinId,
        policy: NotifyPolicy,
        tts: Boolean,
    ) {
        var ownerConversation: Uuid? = dm
        var outcome = CallOutcome.MISSED
        var sendFails = false
        val sent = CopyOnWriteArrayList<Pair<Uuid, String>>()
        val voices = CopyOnWriteArrayList<String>()
        val calls = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<String>()
        val caller = object : OwnerCaller {
            override suspend fun call(peer: OdinId, say: String): CallOutcome {
                calls += "$peer:$say"
                return outcome
            }
        }
        val notifier = OwnerNotifier(
            scope = scope, clock = clock, owner = owner, conversation = { ownerConversation }, policy = policy,
            send = { c, t -> if (sendFails) error("net down"); sent += c to t; Uuid.random() },
            speak = if (tts) { t -> OutVoice("v.m4a", ByteArray(1), "audio/mp4", 2).also { voices += t } } else null,
            sendVoice = if (tts) { c, _, _ -> Uuid.random() } else null,
            caller = caller,
            store = File(dir, "escalations.json"),
            calls = CallBudget(File(dir, "calls.txt"), policy.maxCallsPerDay, clock),
            prefix = "",
            log = { logs += it },
        )
        val texts get() = sent.map { it.second }
    }

    private fun TestScope.rig(
        policy: NotifyPolicy = NotifyPolicy(zone = ZoneOffset.UTC),
        tts: Boolean = false,
        dir: File = Files.createTempDirectory("quag-notify").toFile(),
        scope: CoroutineScope = backgroundScope,
    ) = Rig(scope, { base + testScheduler.currentTime }, dir, dm, owner, policy, tts).also { it.notifier.start() }

    private fun ownerMsg(r: Rig, text: String = "ok", at: Long = r.clock(), parent: Uuid? = null) = ChatMsg(
        Uuid.random(), dm, owner, text, at, sender = owner,
        rawContent = parent?.let { """{"replyPreview":{"replyUniqueId":"$it","authorOdinId":"x","message":"m"},"message":"$text","version":1}""" },
    )

    @Test
    fun infoSendsTextOnlyAndNeverEscalates() = runTest {
        val r = rig(tts = true)
        r.notifier.notify("job done", Urgency.INFO, "s")
        runCurrent()
        assertEquals(listOf("job done"), r.texts)
        assertTrue(r.voices.isEmpty())
        advanceTimeBy(60 * minute)
        assertTrue(r.calls.isEmpty())
        assertEquals(0, r.notifier.pendingCount())
    }

    @Test
    fun attentionAddsAVoiceNoteOnlyWithTtsAndOutsideQuietHours() = runTest {
        val noTts = rig()
        noTts.notifier.notify("blocked", Urgency.ATTENTION, "s")
        runCurrent()
        assertEquals(listOf("blocked"), noTts.texts)
        assertTrue(noTts.voices.isEmpty())

        val withTts = rig(tts = true)
        withTts.notifier.notify("blocked", Urgency.ATTENTION, "s")
        runCurrent()
        assertEquals(listOf("blocked"), withTts.voices)
        assertEquals(0, withTts.notifier.pendingCount())

        val quiet = rig(NotifyPolicy(quiet = QuietHours(java.time.LocalTime.MIN, java.time.LocalTime.MAX), zone = ZoneOffset.UTC), tts = true)
        quiet.notifier.notify("blocked", Urgency.ATTENTION, "s")
        runCurrent()
        assertEquals(listOf("blocked"), quiet.texts)
        assertTrue(quiet.voices.isEmpty())
    }

    @Test
    fun urgentWithNoReplyCallsAfterTenMinutesAndTextsTheOutcome() = runTest {
        val r = rig()
        r.notifier.notify("prod is down", Urgency.URGENT, "s")
        assertEquals(1, r.notifier.pendingCount())
        advanceTimeBy(10 * minute - 1)
        runCurrent()
        assertTrue(r.calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("$owner:prod is down"), r.calls)
        assertEquals("tried to call you (missed) about: prod is down", r.texts.last())
        assertEquals(0, r.notifier.pendingCount())
    }

    @Test
    fun everyOutcomeGetsAFollowUpText() = runTest {
        val expected = mapOf(
            CallOutcome.ANSWERED to "called you (answered)",
            CallOutcome.DECLINED to "tried to call you (declined)",
            CallOutcome.UNAVAILABLE to "could not call you (calling is not available)",
            CallOutcome.FAILED to "tried to call you (the call failed)",
        )
        for ((outcome, text) in expected) {
            val r = rig()
            r.outcome = outcome
            r.notifier.notify("x", Urgency.URGENT, "s")
            advanceTimeBy(10 * minute)
            runCurrent()
            assertTrue(r.texts.last().startsWith(text), "$outcome -> ${r.texts.last()}")
        }
    }

    @Test
    fun noCallerMeansNoEscalationAtAll() = runTest {
        val sent = CopyOnWriteArrayList<String>()
        val n = OwnerNotifier(backgroundScope, { base + testScheduler.currentTime }, owner, { dm }, NotifyPolicy(), { _, t -> sent += t; Uuid.random() })
        n.notify("x", Urgency.URGENT, "a")
        assertEquals(0, n.pendingCount())
        advanceTimeBy(20 * minute)
        runCurrent()
        assertEquals(listOf("x"), sent)
    }

    @Test
    fun aReplyFromAPhoneWithASlowClockStillStopsTheCall() = runTest {
        val r = rig()
        r.notifier.notify("prod is down", Urgency.URGENT, "s")
        advanceTimeBy(minute)
        r.notifier.observe(ownerMsg(r, at = r.clock() - 5 * minute))
        assertEquals(0, r.notifier.pendingCount())
    }

    @Test
    fun aNewUrgentWhileACallIsRingingDoesNotCancelIt() = runTest {
        val r = rig()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ringing = object : OwnerCaller {
            override suspend fun call(peer: OdinId, say: String): CallOutcome {
                r.calls += say
                release.await()
                return CallOutcome.ANSWERED
            }
        }
        val n = OwnerNotifier(backgroundScope, r.clock, owner, { dm }, NotifyPolicy(), { c, t -> r.sent += c to t; Uuid.random() }, caller = ringing)
        n.notify("first", Urgency.URGENT, "same")
        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals(listOf("first"), r.calls)
        n.notify("second", Urgency.URGENT, "same")
        release.complete(Unit)
        runCurrent()
        assertTrue(r.texts.any { it.startsWith("called you (answered) about: first") }, r.texts.toString())
    }

    @Test
    fun aReplyBeforeTheCheckMeansNoCall() = runTest {
        val r = rig()
        r.notifier.notify("prod is down", Urgency.URGENT, "s")
        advanceTimeBy(5 * minute)
        r.notifier.observe(ownerMsg(r))
        assertEquals(0, r.notifier.pendingCount())
        advanceTimeBy(10 * minute)
        runCurrent()
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun aMessageFromElsewhereOrSomeoneElseDoesNotCancel() = runTest {
        val r = rig()
        r.notifier.notify("prod is down", Urgency.URGENT, "s")
        advanceTimeBy(minute)
        r.notifier.observe(ChatMsg(Uuid.random(), Uuid.random(), owner, "hi", r.clock(), sender = owner))
        r.notifier.observe(ChatMsg(Uuid.random(), dm, OdinId("x.example.com"), "hi", r.clock(), sender = OdinId("x.example.com")))
        assertEquals(1, r.notifier.pendingCount())
        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals(1, r.calls.size)
    }

    @Test
    fun quotingTheNoticeCancelsItAndSurfacesItsText() = runTest {
        val r = rig()
        val id = Uuid.random()
        val n = OwnerNotifier(backgroundScope, r.clock, owner, { dm }, NotifyPolicy(), { _, _ -> id }, caller = r.caller)
        n.notify("db migration needs a yes or no", Urgency.URGENT, "s")
        val reply = ownerMsg(r, "yes", parent = id)
        n.observe(reply)
        assertEquals(0, n.pendingCount())
        assertEquals("db migration needs a yes or no", n.replyingTo(reply.id))
        advanceTimeBy(20 * minute)
        runCurrent()
        assertTrue(r.calls.isEmpty())
        assertNull(n.replyingTo(Uuid.random()))
    }

    @Test
    fun aNewUrgentForTheSameSourceReplacesTheOldOne() = runTest {
        val r = rig()
        r.notifier.notify("first", Urgency.URGENT, "same")
        advanceTimeBy(5 * minute)
        r.notifier.notify("second", Urgency.URGENT, "same")
        r.notifier.notify("other", Urgency.URGENT, "different")
        assertEquals(2, r.notifier.pendingCount())
        advanceTimeBy(5 * minute)
        runCurrent()
        assertTrue(r.calls.isEmpty(), "the replaced escalation must not fire at its old time")
        advanceTimeBy(5 * minute)
        runCurrent()
        assertEquals(setOf("$owner:second", "$owner:other"), r.calls.toSet())
        assertEquals(2, r.calls.size)
    }

    @Test
    fun dailyCallBudgetCapsCallsAndPersists() = runTest {
        val dir = Files.createTempDirectory("quag-notify").toFile()
        val policy = NotifyPolicy(maxCallsPerDay = 2, zone = ZoneOffset.UTC)
        val r = rig(policy, dir = dir)
        for (i in 1..3) {
            r.notifier.notify("m$i", Urgency.URGENT, "s$i")
            advanceTimeBy(10 * minute)
            runCurrent()
        }
        assertEquals(2, r.calls.size)
        assertTrue(r.logs.any { "daily call limit" in it })
        assertFalse(CallBudget(File(dir, "calls.txt"), 2, r.clock).allows())
        advanceTimeBy(24 * 60 * minute)
        r.notifier.notify("m4", Urgency.URGENT, "s4")
        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals(3, r.calls.size)
    }

    @Test
    fun quietHoursSuppressTheCallUnlessUrgentOverrides() = runTest {
        val allDay = QuietHours(java.time.LocalTime.MIN, java.time.LocalTime.MAX)
        val strict = rig(NotifyPolicy(quiet = allDay, urgentOverridesQuiet = false, zone = ZoneOffset.UTC))
        strict.notifier.notify("x", Urgency.URGENT, "s")
        advanceTimeBy(10 * minute)
        runCurrent()
        assertTrue(strict.calls.isEmpty())
        assertTrue(strict.logs.any { "quiet hours" in it })
        assertEquals(0, strict.notifier.pendingCount())

        val override = rig(NotifyPolicy(quiet = allDay, urgentOverridesQuiet = true, zone = ZoneOffset.UTC), tts = true)
        override.notifier.notify("x", Urgency.URGENT, "s")
        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals(1, override.calls.size)
        assertEquals(listOf("x"), override.voices)
    }

    @Test
    fun quietHoursWrapMidnight() {
        val q = parseQuietHours("22:00-07:00")!!
        fun at(h: Int) = java.time.LocalDate.of(2026, 1, 5).atTime(h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertTrue(q.contains(at(23), ZoneOffset.UTC))
        assertTrue(q.contains(at(3), ZoneOffset.UTC))
        assertFalse(q.contains(at(7), ZoneOffset.UTC))
        assertFalse(q.contains(at(12), ZoneOffset.UTC))
        assertNull(parseQuietHours("22:00-22:00"))
        assertNull(parseQuietHours("night"))
        assertNull(parseQuietHours("25:00-07:00"))
    }

    private fun TestScope.crashable() = CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job())

    @Test
    fun escalationsSurviveARestart() = runTest {
        val dir = Files.createTempDirectory("quag-notify").toFile()
        val before = crashable()
        val a = rig(dir = dir, scope = before)
        a.notifier.notify("pending", Urgency.URGENT, "p")
        assertTrue("pending" in File(dir, "escalations.json").readText())
        advanceTimeBy(3 * minute)
        before.cancel()
        val b = rig(dir = dir)
        assertEquals(1, b.notifier.pendingCount())
        advanceTimeBy(7 * minute - 1)
        runCurrent()
        assertTrue(b.calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("$owner:pending"), b.calls)
        assertTrue(a.calls.isEmpty())
    }

    @Test
    fun anOverdueEscalationWaitsAGraceAfterRestartThenCalls() = runTest {
        val dir = Files.createTempDirectory("quag-notify").toFile()
        val before = crashable()
        val a = rig(dir = dir, scope = before)
        a.notifier.notify("late", Urgency.URGENT, "p")
        before.cancel()
        advanceTimeBy(2 * 60 * minute)
        val b = rig(dir = dir)
        assertEquals(1, b.notifier.pendingCount())
        runCurrent()
        assertTrue(b.calls.isEmpty())
        advanceTimeBy(minute)
        runCurrent()
        assertEquals(1, b.calls.size)
    }

    @Test
    fun escalationsOlderThanADayAreDroppedOnStart() = runTest {
        val dir = Files.createTempDirectory("quag-notify").toFile()
        val old = Uuid.random()
        File(dir, "escalations.json").writeText("""[{"id":"aa","messageId":"$old","sentAt":${base - 25 * 60 * minute},"text":"stale","source":"s"}]""")
        val r = rig(dir = dir)
        assertEquals(0, r.notifier.pendingCount())
        advanceTimeBy(60 * minute)
        runCurrent()
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun aMissingOwnerConversationIsAnErrorAndLogged() = runTest {
        val r = rig()
        r.ownerConversation = null
        assertFailsWith<IllegalArgumentException> { r.notifier.notify("x", Urgency.INFO, "s") }
        assertTrue(r.logs.any { "owner has no 1:1 with the bot" in it })
        assertTrue(r.sent.isEmpty())
    }

    private class ToolBackend(override val notifier: OwnerNotifier?, override val filesDir: File? = null, override val conversation: Uuid? = null) : AgentBackend {
        val sentKinds = CopyOnWriteArrayList<Pair<Uuid, String>>()
        var mediaFails = false
        override val allowlist = Allowlist.default(OdinId("owner.example.com"))
        override suspend fun messages(conversationId: Uuid, limit: Int, beforeMs: Long?) = emptyList<ChatMsg>()
        override suspend fun send(conversationId: Uuid, text: String, replyTo: id.homebase.chat.services.ReplyPreview?) = Uuid.random()
        override suspend fun sendFile(conversationId: Uuid, file: OutFile, caption: String, replyTo: id.homebase.chat.services.ReplyPreview?): Uuid {
            if (mediaFails) error("upload failed")
            sentKinds += conversationId to "file"
            return Uuid.random()
        }
        override suspend fun sendVoice(conversationId: Uuid, voice: OutVoice, caption: String, replyTo: id.homebase.chat.services.ReplyPreview?): Uuid {
            sentKinds += conversationId to "voice"
            return Uuid.random()
        }
    }

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun theToolIsOperatorOnlyAndLockedTiersNeverSeeIt() {
        assertTrue(NOTIFY_TOOL in chatToolNames(Tier.OPERATOR))
        assertFalse(NOTIFY_TOOL in chatToolNames(Tier.LOCKED))
        assertTrue(scopedTools(chatToolNames(Tier.LOCKED), null).none { it.name == NOTIFY_TOOL })
        assertTrue(scopedTools(chatToolNames(Tier.OPERATOR), null).any { it.name == NOTIFY_TOOL })
        assertFalse(NOTIFY_TOOL in chatToolCommandNames())
    }

    @Test
    fun theToolSendsTaggedTextAndCapsAtSixPerHour() = runTest {
        val r = rig()
        val tagged = OwnerNotifier(backgroundScope, r.clock, owner, { dm }, NotifyPolicy(), { c, t -> r.sent += c to t; Uuid.random() }, prefix = BOT_PREFIX)
        val b = ToolBackend(tagged)
        val first = toolNotifyOwner(b, args("text" to "need a decision", "urgency" to "attention", "conversationId" to Uuid.random().toString()))
        assertFalse(first.isError, first.text)
        assertEquals("$BOT_PREFIX need a decision", r.texts.single())
        repeat(5) { assertFalse(toolNotifyOwner(b, args("text" to "n$it")).isError) }
        val seventh = toolNotifyOwner(b, args("text" to "too many"))
        assertTrue(seventh.isError)
        assertTrue("per hour" in seventh.text)
        assertEquals(6, r.sent.size)
        advanceTimeBy(61 * minute)
        assertFalse(toolNotifyOwner(b, args("text" to "later")).isError)
    }

    @Test
    fun theToolRefusesBadInputAndAMissingOwnerConversation() = runTest {
        val r = rig()
        val b = ToolBackend(r.notifier)
        assertTrue(toolNotifyOwner(b, args()).isError)
        assertTrue(toolNotifyOwner(b, args("text" to "x", "urgency" to "panic")).isError)
        assertTrue(toolNotifyOwner(b, args("text" to "x".repeat(NOTIFY_TEXT_CODEPOINTS + 1))).isError)
        assertTrue(r.sent.isEmpty())
        r.ownerConversation = null
        val reply = toolNotifyOwner(b, args("text" to "hello"))
        assertTrue(reply.isError)
        assertTrue("no 1:1" in reply.text)
        assertTrue(toolNotifyOwner(ToolBackend(null), args("text" to "x")).isError)
    }

    @Test
    fun theToolSendsAnyFileThroughTheBackendToTheOwnerConversation() = runTest {
        val files = Files.createTempDirectory("quag-files").toFile()
        File(files, "report.pdf").writeBytes(ByteArray(10) { 1 })
        File(files, "note.m4a").writeBytes(ByteArray(10) { 2 })
        val r = rig()
        val b = ToolBackend(r.notifier, files)
        assertFalse(toolNotifyOwner(b, args("text" to "see", "path" to "report.pdf")).isError)
        assertEquals(listOf(dm to "file"), b.sentKinds)
        assertFalse(toolNotifyOwner(b, args("text" to "listen", "path" to "note.m4a")).isError)
        assertEquals(dm to "voice", b.sentKinds.last())
        assertTrue(toolNotifyOwner(b, args("text" to "x", "path" to "../etc/passwd")).isError)
        assertTrue(toolNotifyOwner(ToolBackend(r.notifier, null), args("text" to "x", "path" to "report.pdf")).isError)
        b.mediaFails = true
        val reply = toolNotifyOwner(b, args("text" to "again", "path" to "report.pdf"))
        assertFalse(reply.isError)
        assertTrue("attachment failed" in reply.text)
        assertEquals("again", r.texts.last())
    }

    @Test
    fun escalationsFromDifferentChatsKeepTheirOwnKeys() = runTest {
        val r = rig()
        val a = ToolBackend(r.notifier, conversation = Uuid.random())
        val c = ToolBackend(r.notifier, conversation = Uuid.random())
        toolNotifyOwner(a, args("text" to "from a", "urgency" to "urgent"))
        toolNotifyOwner(c, args("text" to "from c", "urgency" to "urgent"))
        assertEquals(2, r.notifier.pendingCount())
    }

    @Test
    fun aMissingOwnerConversationDoesNotSpendAHourlySlot() = runTest {
        val r = rig()
        val b = ToolBackend(r.notifier)
        r.ownerConversation = null
        repeat(10) { assertTrue(toolNotifyOwner(b, args("text" to "x")).isError) }
        r.ownerConversation = dm
        assertFalse(toolNotifyOwner(b, args("text" to "x")).isError)
    }

    @Test
    fun allowlistFindsOnlyTheExactOneToOneWithTheOwner() {
        val group = Uuid.random()
        val other = Uuid.random()
        val allow = Allowlist(emptySet(), null, Kind.BOT, memberMode = true)
        allow.learn(listOf(
            ConversationInfo(group, "g", listOf(bot, owner, OdinId("c.example.com"))),
            ConversationInfo(other, "o", listOf(bot, OdinId("c.example.com"))),
        ))
        assertNull(allow.directWith(bot, owner))
        allow.learn(listOf(
            ConversationInfo(group, "g", listOf(bot, owner, OdinId("c.example.com"))),
            ConversationInfo(other, "o", listOf(bot, OdinId("c.example.com"))),
            ConversationInfo(dm, "owner", listOf(owner, bot)),
        ))
        assertEquals(dm, allow.directWith(bot, owner))
    }

    @Test
    fun jobNoticeRules() {
        val ownerConv = dm
        val elsewhere = Uuid.random()
        fun report(conv: Uuid, status: JobStatus, ms: Long, line: String = "all good", schedule: String? = null) = JobReport(3, conv, status, ms, line, schedule)
        assertNull(jobNotice(report(ownerConv, JobStatus.DONE, minute), "owner", ownerConv, 2 * minute))
        assertNull(jobNotice(report(ownerConv, JobStatus.FAILED, minute), "owner", ownerConv, 2 * minute))
        assertNull(jobNotice(report(ownerConv, JobStatus.DONE, 5 * minute), "owner", ownerConv, 2 * minute))
        assertNull(jobNotice(report(elsewhere, JobStatus.DONE, 1), "ops", ownerConv, 2 * minute))
        assertEquals(Urgency.INFO to "job 3 done in ops: all good", jobNotice(report(elsewhere, JobStatus.DONE, 2 * minute), "ops", ownerConv, 2 * minute))
        assertEquals(Urgency.ATTENTION to "job 3 failed in ops: boom", jobNotice(report(elsewhere, JobStatus.FAILED, 1, "boom"), "ops", ownerConv, 2 * minute))
        assertEquals(Urgency.ATTENTION to "scheduled task ab12 (job 3) failed in owner: boom", jobNotice(report(ownerConv, JobStatus.FAILED, 1, "boom", "ab12"), "owner", ownerConv, 2 * minute))
        assertNull(jobNotice(report(ownerConv, JobStatus.DONE, 1, schedule = "ab12"), "owner", ownerConv, 2 * minute))
        assertEquals(Urgency.INFO to "job 3 cancelled in ops", jobNotice(report(elsewhere, JobStatus.CANCELLED, 3 * minute, ""), "ops", ownerConv, 2 * minute))
        assertEquals(Urgency.INFO to "job 3 done in ops", jobNotice(report(elsewhere, JobStatus.DONE, 3 * minute, ""), "ops", ownerConv, 2 * minute))
    }

    @Test
    fun urgencyParsing() {
        assertEquals(Urgency.INFO, Urgency.parse(null))
        assertEquals(Urgency.URGENT, Urgency.parse(" Urgent "))
        assertFailsWith<IllegalArgumentException> { Urgency.parse("now") }
    }
}
