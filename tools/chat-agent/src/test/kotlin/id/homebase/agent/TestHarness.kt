package id.homebase.agent

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

class TestHarness(
    val config: AgentConfig,
    store: ProcessedStore = ProcessedStore(null),
    var outcome: BrainOutcome = BrainOutcome.Output("pong"),
    limiter: RunLimiter? = null,
    val outcomes: MutableList<BrainOutcome> = mutableListOf(),
    var sendFailures: Int = 0,
    away: AwayFlag = AwayFlag(null),
    identity: String = "owner.example.com",
    var history: List<ChatMsg> = emptyList(),
    jobs: JobRunner? = null,
    loader: AttachmentLoader? = null,
    brainFn: (suspend (String, Tier, List<Attachment>) -> BrainOutcome)? = null,
    leaseFor: ((Uuid, Tier, id.homebase.api.common.OdinId?) -> ToolLease?)? = null,
    sessions: SessionStore? = null,
    schedules: ScheduleStore? = null,
    var now: Long = 1_000_000L,
) {
    val sends = CopyOnWriteArrayList<Triple<Uuid, String, List<OutFile>>>()
    val logs = CopyOnWriteArrayList<String>()
    val prompts = CopyOnWriteArrayList<String>()
    val flags = CopyOnWriteArrayList<String?>()
    val tiers = CopyOnWriteArrayList<Tier>()
    val attachments = CopyOnWriteArrayList<List<Attachment>>()
    var brainRuns = 0

    val replies get() = sends.map { it.second }
    val replyTargets get() = sends.map { it.first }

    val processor = WatchProcessor(
        config, identity, store,
        history = { history },
        brain = { prompt, tier, files, _, sessionFlags ->
            brainRuns++
            flags += sessionFlags
            prompts += prompt
            tiers += tier
            attachments += files
            brainFn?.invoke(prompt, tier, files) ?: outcomes.removeFirstOrNull() ?: outcome
        },
        send = { conversation, text, files ->
            if (sendFailures > 0) { sendFailures--; error("boom") }
            sends += Triple(conversation, text, files)
        },
        log = { logs += it },
        limiter = limiter ?: RunLimiter(null, config.maxRunsPerHour, config.maxRunsPerDay),
        away = away,
        jobs = jobs,
        loader = loader,
        leaseFor = leaseFor,
        sessions = sessions,
        schedules = schedules,
        clock = { now },
    )

    suspend fun handle(msg: ChatMsg): String = processor.handleAll(listOf(msg))[msg.id] ?: "seen"

    suspend fun handleAll(msgs: List<ChatMsg>) = processor.handleAll(msgs)
}
