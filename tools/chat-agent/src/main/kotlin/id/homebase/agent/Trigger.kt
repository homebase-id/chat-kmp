package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.api.util.mentionsIdentity
import id.homebase.chat.services.ChatProtocol
import kotlin.uuid.Uuid

const val DELEGATE_PROFILE = "me"
const val BOT_PREFIX = "🤖"

const val DEFAULT_NICKNAME = "quagmire"
const val DEFAULT_BRAIN = "claude -p --model haiku --max-turns 3"
const val DEFAULT_MAX_RUNS_PER_HOUR = 20
const val DEFAULT_MAX_RUNS_PER_DAY = 100

class AgentConfig(
    val nickname: String = DEFAULT_NICKNAME,
    val brain: String = DEFAULT_BRAIN,
    val bot: Boolean = false,
    val maxRunsPerHour: Int = DEFAULT_MAX_RUNS_PER_HOUR,
    val maxRunsPerDay: Int = DEFAULT_MAX_RUNS_PER_DAY,
    val allowlist: Allowlist,
)

fun parseConfig(text: String, owner: OdinId, profile: String = ""): AgentConfig {
    val values = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    fun list(key: String) = values[key]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    val bot = values["bot"].equals("true", ignoreCase = true)
    val conversationKeys = list("allowConversations")
        ?: if (bot) listOf("member") else listOf("self")
    val memberMode = conversationKeys.any { it.equals("member", ignoreCase = true) }
    val conversations = conversationKeys.filterNot { it.equals("member", ignoreCase = true) }.map {
        if (it.equals("self", ignoreCase = true)) ChatProtocol.ConversationWithYourselfId else Uuid.parse(it)
    }.toSet().let { if (memberMode) it + ChatProtocol.ConversationWithYourselfId else it }
    val explicitAuthors = list("allowAuthors")?.map { OdinId(it) }?.toSet()
    val ownerKey = values["owner"]?.takeIf { it.isNotEmpty() }?.let { OdinId(it) }
    // Bot without owner=/allowAuthors= lets any conversation member summon it; `me` stays owner-only.
    val anyMember = bot && explicitAuthors == null && ownerKey == null
    val authors = explicitAuthors ?: ownerKey?.let { setOf(it) } ?: if (bot) emptySet() else setOf(owner)
    return AgentConfig(
        nickname = values["nickname"]?.takeIf { it.isNotEmpty() } ?: DEFAULT_NICKNAME,
        brain = values["brain"]?.takeIf { it.isNotEmpty() } ?: DEFAULT_BRAIN,
        bot = bot,
        maxRunsPerHour = values["maxRunsPerHour"]?.toIntOrNull() ?: DEFAULT_MAX_RUNS_PER_HOUR,
        maxRunsPerDay = values["maxRunsPerDay"]?.toIntOrNull() ?: DEFAULT_MAX_RUNS_PER_DAY,
        allowlist = Allowlist(conversations, authors, memberMode, anyMember, groupSend = bot && profile != DELEGATE_PROFILE && ownerKey != owner),
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
