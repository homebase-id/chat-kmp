package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import java.io.File
import kotlin.uuid.Uuid

suspend fun brainTest(profile: String, latestImage: Boolean = false) {
    val text = generateSequence(::readLine).joinToString("\n")
    val trigger = ChatMsg(Uuid.random(), ChatProtocol.ConversationWithYourselfId, OdinId("attacker.example.com"), text, System.currentTimeMillis())
    val attachments = ArrayList<Attachment>()
    if (latestImage) {
        val session = openSession(profile)
        val msg = fetchMessages(session, ChatProtocol.ConversationWithYourselfId, 50)
            .lastOrNull { m -> payloadLabels(m.payloads).any { it == "[image]" } } ?: error("no image message in note-to-self")
        attachments += AttachmentLoader(sessionFetcher(session), null, ::println).load(listOf(msg.copy(id = trigger.id) to false))
        System.err.println("loaded ${attachments.size} attachment(s): ${attachments.joinToString { "${it.contentType} ${formatSize(it.size)} viewable=${it.viewableImage} ${it.note.orEmpty()}" }}")
    }
    val tmp = File(System.getProperty("java.io.tmpdir"))
    fun leftovers() = tmp.listFiles { f -> f.name.startsWith("chat-agent-brain") || f.name.startsWith("chat-agent-attach") }?.size ?: 0
    val before = leftovers()
    val outcome = runBrain(Brain.LOCKED, buildPrompt(listOf(trigger), emptyList(), attachments = attachments), attachments = attachments)
    println(brainReply(outcome) ?: "(silent)")
    System.err.println("temp dirs before=$before after=${leftovers()}")
}

// live round-trip through the same tool functions the watcher serves: send, react, edit, delete; note-to-self on `me` only
suspend fun toolsCheck(profile: String) {
    require(profile == DELEGATE_PROFILE) { "tools-check runs only on the $DELEGATE_PROFILE profile" }
    val session = openSession(profile)
    val config = loadConfig(profile, session.identity)
    val conversation = ChatProtocol.ConversationWithYourselfId
    val backend = WatcherBackend(session, config, config.allowlist.copy(conversation, false), null) { it }
    fun args(vararg pairs: Pair<String, String>) = scopedArguments(kotlinx.serialization.json.JsonObject(pairs.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) }), conversation)
    suspend fun step(label: String, block: suspend () -> ToolReply): ToolReply {
        var reply = block()
        repeat(6) { if (reply.isError && "not found" in reply.text) { kotlinx.coroutines.delay(1500); reply = block() } }
        println("$label: ${reply.text}")
        return reply
    }
    val sent = step("send") { toolSendMessage(backend, args("text" to "tools-check")) }
    val id = sent.text.removePrefix("sent ").take(8)
    step("react") { toolReact(backend, args("messageId" to id, "emoji" to "👍"), add = true) }
    step("edit") { toolEditMessage(backend, args("messageId" to id, "text" to "tools-check edited")) }
    kotlinx.coroutines.delay(2000)
    println("read: " + toolReadMessages(backend, args("limit" to "3")).text)
    step("delete") { toolDeleteMessage(backend, args("messageId" to id)) }
    kotlinx.coroutines.delay(2000)
    println("read: " + toolReadMessages(backend, args("limit" to "3")).text)
}

// one poll (then a vote), location, event and contact in note-to-self on `me`; left in place for the owner to look at
suspend fun toolsCheckTyped(profile: String, voteOnly: String? = null) {
    require(profile == DELEGATE_PROFILE) { "tools-check runs only on the $DELEGATE_PROFILE profile" }
    val session = openSession(profile)
    val config = loadConfig(profile, session.identity)
    val conversation = ChatProtocol.ConversationWithYourselfId
    val backend = WatcherBackend(session, config, config.allowlist.copy(conversation, false), null) { it }
    fun args(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = scopedArguments(kotlinx.serialization.json.JsonObject(mapOf(*pairs)), conversation)
    fun text(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
    fun list(vararg values: String) = kotlinx.serialization.json.JsonArray(values.map(::text))
    val pollId = voteOnly ?: toolSendPoll(backend, args("question" to text("tools-check: which one?"), "options" to list("first", "second", "third")))
        .also { println("poll: ${it.text}") }.text.removePrefix("sent poll ").take(8)
    var vote = ToolReply("not found", true)
    repeat(8) { if (vote.isError && "not found" in vote.text) { kotlinx.coroutines.delay(1500); vote = toolVotePoll(backend, args("messageId" to text(pollId), "option" to text("2"))) } }
    println("vote: ${vote.text}")
    println("vote again: ${toolVotePoll(backend, args("messageId" to text(pollId), "option" to text("2"))).text}")
    if (voteOnly != null) return println("read: " + toolReadMessages(backend, args("limit" to kotlinx.serialization.json.JsonPrimitive(6))).text)
    println("location: ${toolSendLocation(backend, args("lat" to kotlinx.serialization.json.JsonPrimitive(52.5163), "lon" to kotlinx.serialization.json.JsonPrimitive(13.3777), "label" to text("tools-check: Brandenburg Gate"))).text}")
    println("event: ${toolSendEvent(backend, args("title" to text("tools-check event"), "start" to text("2026-12-01T18:00"), "timezone" to text("Europe/Berlin"), "place" to text("Somewhere"), "description" to text("Sent by the chat-agent tools check"))).text}")
    println("contact: ${toolSendContact(backend, args("name" to text("Ada Lovelace"), "phones" to list("+14155550123"), "emails" to list("ada@example.com"), "organization" to text("Analytical Engines"))).text}")
    kotlinx.coroutines.delay(3000)
    println("read: " + toolReadMessages(backend, args("limit" to kotlinx.serialization.json.JsonPrimitive(6))).text)
}
