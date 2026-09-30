package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.api.util.mentionsIdentity
import id.homebase.chat.services.ChatProtocol
import java.io.File
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
    val delegate = profile == DELEGATE_PROFILE
    val conversationKeys = list("allowConversations")
        ?: if (bot) listOf("member") else listOf("self")
    val memberMode = conversationKeys.any { it.equals("member", ignoreCase = true) }
    require(!(delegate && memberMode)) { "allowConversations=member is not permitted for the me profile; list conversation uuids explicitly" }
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
        allowlist = Allowlist(conversations, authors, memberMode, anyMember, groupSend = bot && !delegate && ownerKey != owner, delegate = delegate),
    )
}

private const val WORD_CHARS = "[\\p{L}\\p{N}_]"

fun matchesNickname(text: String, nickname: String): Boolean {
    if (nickname.isEmpty()) return false
    val nick = Regex.escape(nickname)
    val anywhere = Regex("(?<!$WORD_CHARS)@$nick(?!$WORD_CHARS)", RegexOption.IGNORE_CASE)
    val firstWord = Regex("^\\s*$nick(?!$WORD_CHARS)", RegexOption.IGNORE_CASE)
    return anywhere.containsMatchIn(text) || firstWord.containsMatchIn(text)
}

fun shouldTrigger(text: String, nickname: String, bot: Boolean, identity: String, awayMention: Boolean = false): Boolean {
    if (text.trimStart().startsWith(BOT_PREFIX)) return false
    if (matchesNickname(text, nickname)) return true
    return (bot || awayMention) && mentionsIdentity(text, identity)
}

fun awayCommand(text: String, nickname: String): Boolean? {
    val parts = text.trim().split(Regex("\\s+"))
    if (parts.size != 2 || !parts[0].equals("@$nickname", ignoreCase = true)) return null
    return when (parts[1].lowercase()) {
        "away" -> true
        "back" -> false
        else -> null
    }
}

class AwayFlag(private val file: File?) {
    private var value = file?.exists() == true

    var on: Boolean
        get() = value
        set(v) {
            value = v
            if (v) file?.writeText("on\n") else file?.delete()
        }
}
