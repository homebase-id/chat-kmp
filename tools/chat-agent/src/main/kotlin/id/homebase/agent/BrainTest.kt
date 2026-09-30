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
