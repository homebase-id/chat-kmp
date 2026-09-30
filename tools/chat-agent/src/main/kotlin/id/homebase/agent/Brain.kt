package id.homebase.agent

import id.homebase.api.util.truncateToCodePoints
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

const val BRAIN_TIMEOUT_MS = 120_000L
const val NO_REPLY = "NO_REPLY"
const val REPLY_CODEPOINTS = 1500
const val MAX_TEXT_BYTES = 200_000
const val FAILURE_CODEPOINTS = 120

sealed interface BrainOutcome {
    class Output(val stdout: String, val files: List<OutFile> = emptyList(), val session: SessionUsage? = null) : BrainOutcome
    class Failed(val reason: String) : BrainOutcome
}

fun failureLine(reason: String) = sanitizeReply(reason).oneLine(FAILURE_CODEPOINTS)

fun capTextBytes(text: String, maxBytes: Int = MAX_TEXT_BYTES): String {
    if (text.encodeToByteArray().size <= maxBytes) return text
    var end = 0
    var used = 0
    while (end < text.length) {
        val next = text.offsetByCodePoints(end, 1)
        val size = text.substring(end, next).encodeToByteArray().size
        if (used + size > maxBytes) break
        used += size
        end = next
    }
    return text.substring(0, end).trimEnd() + "\n\n…(truncated: over ${maxBytes / 1000} KB)"
}

fun brainReply(outcome: BrainOutcome, prefix: String = BOT_PREFIX, operator: Boolean = false): String? = when (outcome) {
    is BrainOutcome.Failed -> tagged(prefix, "failed: ${failureLine(outcome.reason)}")
    is BrainOutcome.Output -> sanitizeReply(outcome.stdout).let {
        if (it.isEmpty() || it == NO_REPLY) null else tagged(prefix, if (operator) capTextBytes(it) else it.truncateToCodePoints(REPLY_CODEPOINTS))
    }
}

private val DISCLOSURE_SPOOF = Regex("^\\S+'s AI assistant:\\s*")

fun sanitizeReply(raw: String): String {
    var text = raw.trim()
    while (true) {
        val next = text.removePrefix(BOT_PREFIX).trimStart().replace(DISCLOSURE_SPOOF, "")
        if (next == text) return text
        text = next
    }
}

fun withAttachments(output: BrainOutcome.Output, root: File?): BrainOutcome.Output {
    val attached = collectAttachments(output.stdout, root)
    attached.skipped.forEach { System.err.println("attachment skipped: $it") }
    return if (attached.files.isEmpty() && attached.skipped.isEmpty()) output else BrainOutcome.Output(attached.text, attached.files, output.session)
}

const val MCP_PLACEHOLDER = "{mcp}"

private val SHELL_SAFE = Regex("[A-Za-z0-9_@%+=:,./-]+")

private fun shellWord(path: String) = if (SHELL_SAFE.matches(path)) path else "'" + path.replace("'", "'\\''") + "'"

// private by default: {mcp} puts this path in argv for every local user to see; operatorGroup is the only way to share it, never world-readable
private fun writeMcpConfig(dir: File, lease: ToolLease, group: String?): File {
    val file = File(dir, "mcp.json")
    file.writeText(lease.configJson())
    val shared = group != null && runCatching {
        val principal = dir.toPath().fileSystem.userPrincipalLookupService.lookupPrincipalByGroupName(group)
        for (path in listOf(dir.toPath(), file.toPath())) Files.getFileAttributeView(path, PosixFileAttributeView::class.java).setGroup(principal)
    }.onFailure { System.err.println("WARNING: operatorGroup '$group' unusable (${it.message}); the MCP token file stays private to the watcher user") }.isSuccess
    val perms = if (shared) "rwxr-x---" to "rw-r-----" else "rwx------" to "rw-------"
    Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString(perms.first))
    Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(perms.second))
    return file
}

private val LOCKED_ENV_KEYS = listOf("PATH", "HOME", "USER", "LANG")

// own process group so a double-forked daemon dies with the brain (perl fallback: no setsid on macOS)
private const val GROUP_LAUNCHER =
    "if command -v setsid >/dev/null 2>&1; then exec setsid sh -c \"\$1\"; " +
        "elif command -v perl >/dev/null 2>&1; then exec perl -e 'use POSIX; setpgid(0,0); exec @ARGV' sh -c \"\$1\"; " +
        "else exec sh -c \"\$1\"; fi"

private fun killGroup(pid: Long) {
    runCatching {
        ProcessBuilder("sh", "-c", "kill -KILL -- -$pid").redirectErrorStream(true).start().apply { outputStream.close() }
            .also { it.inputStream.readBytes(); it.waitFor(2, TimeUnit.SECONDS) }
    }
}

suspend fun runBrain(
    brain: Brain,
    prompt: String,
    timeoutMs: Long = BRAIN_TIMEOUT_MS,
    tier: Tier = Tier.LOCKED,
    operatorCwd: String? = null,
    attachments: List<Attachment> = emptyList(),
    lease: ToolLease? = null,
    mcpGroup: String? = null,
    sessionFlags: String? = null,
): BrainOutcome {
    val scratch = if (tier == Tier.LOCKED) tempDir("brain") else null
    val attachDir = scratch ?: attachments.takeIf { a -> a.any { it.bytes != null } }?.let { tempDir("attach") }
    val cwd = scratch ?: operatorCwd?.let(::File)
    val mcpDir = lease?.let { tempDir("mcp") }
    try {
        val files = attachDir?.let { writeAttachments(it, attachments) }.orEmpty()
        val mcpConfig = lease?.let { writeMcpConfig(mcpDir!!, it, mcpGroup.takeIf { tier == Tier.OPERATOR }) }
        val env = (if (files.isEmpty()) emptyMap() else mapOf("CHAT_AGENT_ATTACHMENTS" to files.joinToString("\n") { it.absolutePath })) +
            (if (lease != null && mcpConfig != null) mcpEnvironment(lease, mcpConfig) else emptyMap())
        val vision = brain.streamJson && attachments.any { it.modelBlock }
        val withMcp = mcpConfig?.let { brain.command.replace(MCP_PLACEHOLDER, shellWord(it.absolutePath)) } ?: brain.command
        val command = if (tier == Tier.OPERATOR) withMcp.replace(SESSION_PLACEHOLDER, sessionFlags.orEmpty()) else withMcp
        val outcome = runBrainProcess(
            if (vision) "$command $STREAM_JSON_FLAGS" else command,
            if (vision) streamJsonInput(prompt, attachments) else prompt,
            timeoutMs, tier, cwd, env,
        )
        val parsed = when {
            vision && outcome is BrainOutcome.Output -> parseStreamResult(outcome.stdout)
            sessionFlags != null && tier == Tier.OPERATOR && outcome is BrainOutcome.Output -> parseSessionOutput(outcome.stdout) ?: outcome
            else -> outcome
        }
        return if (parsed is BrainOutcome.Output) withAttachments(parsed, cwd) else parsed
    } finally {
        mcpDir?.deleteRecursively()
        scratch?.deleteRecursively()
        if (attachDir != null && attachDir !== scratch) attachDir.deleteRecursively()
    }
}

private suspend fun runBrainProcess(command: String, prompt: String, timeoutMs: Long, tier: Tier, cwd: File?, extraEnv: Map<String, String>): BrainOutcome =
    withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder("sh", "-c", GROUP_LAUNCHER, "chat-agent-brain", command).apply {
                cwd?.let { directory(it) }
                if (tier == Tier.LOCKED) {
                    val keep = LOCKED_ENV_KEYS.mapNotNull { k -> System.getenv(k)?.let { k to it } }
                    environment().apply { clear(); putAll(keep) }
                }
                environment().putAll(extraEnv)
            }.start()
        } catch (e: Exception) {
            return@withContext BrainOutcome.Failed("could not start: ${e.message}")
        }
        val out = CompletableFuture.supplyAsync { process.inputStream.readBytes().decodeToString() }
        val err = CompletableFuture.supplyAsync { process.errorStream.readBytes().decodeToString() }
        Thread {
            runCatching { process.outputStream.use { it.write(prompt.encodeToByteArray()) } }
        }.apply { isDaemon = true }.start()
        try {
            if (!runInterruptible { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }) {
                return@withContext BrainOutcome.Failed("timeout after ${timeoutMs / 1000}s")
            }
        } finally {
            val descendants = process.descendants().toList()
            killGroup(process.pid())
            if (process.isAlive) {
                descendants.forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
        val code = process.exitValue()
        if (code != 0) {
            val detail = err.get(2, TimeUnit.SECONDS).trim().lineSequence().firstOrNull().orEmpty()
            return@withContext BrainOutcome.Failed("exit $code${if (detail.isNotEmpty()) ": $detail" else ""}")
        }
        BrainOutcome.Output(out.get(2, TimeUnit.SECONDS))
    }
