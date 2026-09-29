package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.api.util.mentionsIdentity
import id.homebase.chat.services.ChatProtocol
import kotlin.uuid.Uuid

const val BOT_PREFIX = "🤖"

class AgentConfig(
    val nickname: String = "quagmire",
    val brain: String = "claude -p --model haiku --max-turns 3",
    val bot: Boolean = false,
    val allowlist: Allowlist,
)

fun parseConfig(text: String, owner: OdinId): AgentConfig {
    val values = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    fun list(key: String) = values[key]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    val conversations = list("allowConversations")?.map {
        if (it.equals("self", ignoreCase = true)) ChatProtocol.ConversationWithYourselfId else Uuid.parse(it)
    }?.toSet() ?: setOf(ChatProtocol.ConversationWithYourselfId)
    val authors = list("allowAuthors")?.map { OdinId(it) }?.toSet() ?: setOf(owner)
    return AgentConfig(
        nickname = values["nickname"]?.takeIf { it.isNotEmpty() } ?: "quagmire",
        brain = values["brain"]?.takeIf { it.isNotEmpty() } ?: "claude -p --model haiku --max-turns 3",
        bot = values["bot"].equals("true", ignoreCase = true),
        allowlist = Allowlist(conversations, authors),
    )
}

private const val WORD_CHARS = "[\\p{L}\\p{N}_]"

fun shouldTrigger(text: String, nickname: String, bot: Boolean, identity: String): Boolean {
    if (text.trimStart().startsWith(BOT_PREFIX)) return false
    if (nickname.isNotEmpty()) {
        val nick = Regex.escape(nickname)
        val anywhere = Regex("(?<!$WORD_CHARS)@$nick(?!$WORD_CHARS)", RegexOption.IGNORE_CASE)
        val firstWord = Regex("^\\s*$nick(?!$WORD_CHARS)", RegexOption.IGNORE_CASE)
        if (anywhere.containsMatchIn(text) || firstWord.containsMatchIn(text)) return true
    }
    return bot && mentionsIdentity(text, identity)
}
