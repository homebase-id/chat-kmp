package id.homebase.agent

import id.homebase.api.common.OdinId
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val POST_FILE = "post.json"
const val POSTS_DIR = "posts"
const val PUBLISH_TIMEOUT_MS = 10 * 60_000L
const val MAX_POST_BYTES = 100L * 1024 * 1024
private const val MAX_APPROVALS = 500
private const val KEPT_OUTPUT_CODEPOINTS = 4000
private val APPROVAL_ID = Regex("[0-9a-f]{6}")
private val SLIDE_FILE = Regex("slide-\\d+\\.(png|jpe?g)", RegexOption.IGNORE_CASE)
private val HASHED_FIELDS = setOf("text", "channels", "title", "alt", "slides")
val APPROVAL_TOOL_NAMES = setOf("request_approval", "publish_post")

enum class ApprovalStatus { PENDING, APPROVED, REJECTED, PUBLISHED }

data class Approval(
    val id: String,
    val conversation: String,
    val dir: String,
    val hash: String,
    val requestedBy: String,
    val requestedAt: Long,
    val status: ApprovalStatus = ApprovalStatus.PENDING,
    val decidedBy: String? = null,
    val decidedAt: Long? = null,
    val publishedAt: Long? = null,
    val output: String? = null,
)

fun canonicalJson(e: JsonElement): String = when (e) {
    is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + canonicalJson(it.value) }
    is JsonArray -> e.joinToString(",", "[", "]") { canonicalJson(it) }
    else -> e.toString()
}

private fun postFiles(root: Path): List<Pair<String, Path>> {
    val out = ArrayList<Pair<String, Path>>()
    Files.walk(root).use { paths ->
        paths.forEach { p ->
            val name = root.relativize(p).invariantSeparatorsPathString
            require(!Files.isSymbolicLink(p)) { "$name is a symlink; a post holds plain files only" }
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) out += name to p
        }
    }
    return out.sortedBy { it.first }
}

private fun readPostFiles(root: File): List<Pair<String, ByteArray>> {
    var total = 0L
    return postFiles(root.toPath()).map { (name, path) ->
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes((MAX_POST_BYTES - total + 1).toInt()) }
        total += bytes.size
        require(total <= MAX_POST_BYTES) { "post is over ${formatSize(MAX_POST_BYTES)}" }
        name to bytes
    }
}

private fun parsePost(bytes: ByteArray): JsonObject =
    runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrElse { throw IllegalArgumentException("$POST_FILE is not a JSON object") }

// post.json fields the brain writes after approval (approved_by, buffer, ...) stay out of the hash
fun postHash(files: List<Pair<String, ByteArray>>): String {
    val md = MessageDigest.getInstance("SHA-256")
    fun field(name: String, bytes: ByteArray) {
        md.update(name.toByteArray())
        md.update(0)
        md.update(bytes.size.toString().toByteArray())
        md.update(0)
        md.update(bytes)
    }
    val post = files.firstOrNull { it.first == POST_FILE } ?: throw IllegalArgumentException("no $POST_FILE in the post dir")
    files.filter { it.first != POST_FILE }.forEach { (name, bytes) -> field(name, bytes) }
    field(POST_FILE, canonicalJson(JsonObject(parsePost(post.second).filterKeys { it in HASHED_FIELDS })).toByteArray())
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun postHash(dir: File): String = postHash(readPostFiles(dir))

fun resolvePostDir(cwd: File, dir: String): File {
    require(dir.isNotBlank() && '\u0000' !in dir) { "dir is empty" }
    val root = try {
        File(cwd, POSTS_DIR).toPath().toRealPath()
    } catch (_: java.io.IOException) {
        throw IllegalArgumentException("${File(cwd, POSTS_DIR)} does not exist")
    }
    val real = try {
        val raw = Path.of(dir)
        (if (raw.isAbsolute) raw else cwd.toPath().toRealPath().resolve(raw)).toRealPath()
    } catch (_: java.io.IOException) {
        throw IllegalArgumentException("no such post dir: $dir")
    } catch (_: java.nio.file.InvalidPathException) {
        throw IllegalArgumentException("invalid post dir: $dir")
    }
    require(real.startsWith(root) && real != root) { "$dir is not inside $root" }
    require(Files.isDirectory(real)) { "$dir is not a directory" }
    require(Files.isRegularFile(real.resolve(POST_FILE), LinkOption.NOFOLLOW_LINKS)) { "no $POST_FILE in $dir" }
    return real.toFile()
}

// what is shown, hashed and published is this private copy, so a later write into the post dir cannot slip in
private fun snapshot(files: List<Pair<String, ByteArray>>): File {
    val dir = tempDir("post")
    for ((name, bytes) in files) File(dir, name).apply { parentFile.mkdirs() }.writeBytes(bytes)
    return dir
}

class ApprovalStore(private val file: File?, private val log: (String) -> Unit = {}, private val now: () -> Long = System::currentTimeMillis) {
    private val items = ArrayList<Approval>()

    init {
        loadJsonRecords(file, "approvals", now, log) { o ->
            fun s(k: String) = o.getValue(k).jsonPrimitive.content
            fun opt(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            items += Approval(
                s("id").also { require(APPROVAL_ID.matches(it)) }, Uuid.parse(s("conversation")).toString(), s("dir"), s("hash"), s("requestedBy"),
                o.getValue("requestedAt").jsonPrimitive.long, ApprovalStatus.valueOf(s("status").uppercase()),
                opt("decidedBy"), o["decidedAt"]?.jsonPrimitive?.longOrNull, o["publishedAt"]?.jsonPrimitive?.longOrNull, opt("output"),
            )
        }
    }

    private fun save() {
        file?.let { f ->
            atomicWrite(f, JsonArray(items.map { a ->
                fun opt(v: String?) = v?.let(::JsonPrimitive) ?: JsonNull
                fun opt(v: Long?) = v?.let(::JsonPrimitive) ?: JsonNull
                buildJsonObject {
                    put("id", a.id); put("conversation", a.conversation); put("dir", a.dir); put("hash", a.hash); put("requestedBy", a.requestedBy)
                    put("requestedAt", a.requestedAt); put("status", a.status.name.lowercase()); put("decidedBy", opt(a.decidedBy)); put("decidedAt", opt(a.decidedAt))
                    put("publishedAt", opt(a.publishedAt)); put("output", opt(a.output))
                }
            }).toString())
        }
    }

    @Synchronized fun request(conversation: Uuid, dir: String, hash: String, requestedBy: String): Pair<Approval, List<Approval>> {
        val superseded = items.filter { it.dir == dir && it.status == ApprovalStatus.PENDING }
        items.removeAll(superseded.toSet())
        val id = generateSequence { newToken(3) }.first { c -> items.none { it.id == c } }
        val a = Approval(id, conversation.toString(), dir, hash, requestedBy, now())
        items += a
        while (items.size > MAX_APPROVALS) items.firstOrNull { it.status == ApprovalStatus.REJECTED || it.status == ApprovalStatus.PUBLISHED }?.let(items::remove) ?: break
        save()
        return a to superseded
    }

    @Synchronized fun find(conversation: Uuid, id: String) = items.firstOrNull { it.id == id.lowercase() && it.conversation == conversation.toString() }

    @Synchronized fun remove(id: String) {
        if (items.removeAll { it.id == id }) save()
    }

    @Synchronized fun transition(id: String, from: Set<ApprovalStatus>, change: (Approval) -> Approval): Approval? {
        val i = items.indexOfFirst { it.id == id && it.status in from }
        if (i < 0) return null
        items[i] = change(items[i])
        save()
        return items[i]
    }

    @Synchronized fun list(conversation: Uuid) = items.filter { it.conversation == conversation.toString() }

    @Synchronized fun pendingCount() = items.count { it.status == ApprovalStatus.PENDING }
}

class ApprovalGate(
    val store: ApprovalStore,
    private val cwd: File?,
    private val nickname: String,
    private val publishCommand: String?,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val publishTimeoutMs: Long = PUBLISH_TIMEOUT_MS,
) {
    private val publishing = ConcurrentHashMap.newKeySet<String>()

    fun banner() = "approvals: ${store.pendingCount()} pending, posts in ${cwd?.let { File(it, POSTS_DIR).path } ?: "(no operatorCwd, request_approval refuses)"}, publishCommand ${if (publishCommand != null) "set" else "unset (publish_post refuses)"}"

    private fun name(a: Approval) = File(a.dir).name

    fun describe(a: Approval) =
        "${a.id} ${a.status.name.lowercase()} ${name(a)}, requested by ${a.requestedBy}" + (a.decidedBy?.let { ", ${if (a.status == ApprovalStatus.REJECTED) "rejected" else "approved"} by $it" } ?: "")

    suspend fun request(backend: AgentBackend, scope: ApprovalScope, dir: String): ToolReply {
        val base = cwd ?: return ToolReply("approvals need operatorCwd; posts live in <operatorCwd>/$POSTS_DIR", true)
        val real = resolvePostDir(base, dir)
        val files = readPostFiles(real)
        val hash = postHash(files)
        val post = parsePost(files.first { it.first == POST_FILE }.second)
        val slides = files.filter { SLIDE_FILE.matches(it.first) }.map { OutFile(File(it.first).name, it.second) }
        val (a, superseded) = store.request(scope.conversation, real.path, hash, scope.requester)
        superseded.forEach { log("approval ${it.id} superseded by ${a.id} dir=${it.dir}") }
        log("approval ${a.id} requested by ${scope.requester} in ${scope.conversation} dir=${a.dir} hash=$hash")
        try {
            slides.chunked(MAX_OUT_FILES).forEachIndexed { i, chunk ->
                val first = i * MAX_OUT_FILES + 1
                backend.sendFiles(scope.conversation, chunk, tagged(backend.sendPrefix, "${a.id}: slides $first-${first + chunk.size - 1} of ${slides.size}"))
            }
            backend.send(scope.conversation, tagged(backend.sendPrefix, approvalText(a, post, slides.size)))
        } catch (e: Exception) {
            store.remove(a.id)
            log("approval ${a.id} dropped: the request could not be sent (${e.message})")
            throw e
        }
        return ToolReply("approval ${a.id} requested. An operator must send '@$nickname approve ${a.id}' in this chat; then call publish_post with id ${a.id}. Any change to the post after this needs a new request.")
    }

    private fun approvalText(a: Approval, post: JsonObject, slides: Int): String {
        fun text(k: String): String? = when (val v = post[k]) {
            is JsonArray -> v.joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
            is JsonPrimitive -> v.contentOrNull
            else -> null
        }?.takeIf { it.isNotBlank() }
        return buildString {
            appendLine("Approval needed: ${a.id} (${name(a)}, requested by ${a.requestedBy})")
            text("channels")?.let { appendLine("Channels: $it") }
            text("title")?.let { appendLine("Title: $it") }
            appendLine("Slides: $slides")
            appendLine("Text:")
            appendLine(text("text") ?: "(none)")
            appendLine()
            append("Publish exactly this: @$nickname approve ${a.id}\nDiscard it: @$nickname reject ${a.id}")
        }
    }

    fun approve(conversation: Uuid, id: String, by: OdinId): String {
        val a = store.find(conversation, id) ?: return "no approval request $id in this conversation"
        if (a.status != ApprovalStatus.PENDING) return "${a.id} is ${a.status.name.lowercase()}, not pending"
        val current = runCatching { postHash(File(a.dir)) }.getOrElse { return "${a.id} cannot be checked (${it.message}); a new request is needed" }
        if (current != a.hash) {
            log("approval ${a.id} refused for $by: post changed since the request dir=${a.dir}")
            return "${a.id} changed since it was requested, not approved; a new request is needed"
        }
        store.transition(a.id, setOf(ApprovalStatus.PENDING)) { it.copy(status = ApprovalStatus.APPROVED, decidedBy = by.toString(), decidedAt = now()) }
            ?: return "${a.id} is no longer pending"
        log("approval ${a.id} approved by $by requestedBy=${a.requestedBy} dir=${a.dir} hash=${a.hash}")
        return "approved ${a.id} (${name(a)})"
    }

    fun reject(conversation: Uuid, id: String, by: OdinId): String {
        val a = store.find(conversation, id) ?: return "no approval request $id in this conversation"
        store.transition(a.id, setOf(ApprovalStatus.PENDING, ApprovalStatus.APPROVED)) { it.copy(status = ApprovalStatus.REJECTED, decidedBy = by.toString(), decidedAt = now()) }
            ?: return "${a.id} is ${a.status.name.lowercase()}, cannot be rejected"
        log("approval ${a.id} rejected by $by requestedBy=${a.requestedBy} dir=${a.dir}")
        return "rejected ${a.id} (${name(a)})"
    }

    fun list(conversation: Uuid) = store.list(conversation).joinToString("\n", transform = ::describe).ifEmpty { "no approval requests in this conversation" }

    suspend fun publish(conversation: Uuid, id: String, by: String): ToolReply {
        val command = publishCommand ?: return ToolReply("publishing not configured: set publishCommand in agent.conf", true)
        val a = store.find(conversation, id) ?: return ToolReply("no approval request $id in this conversation", true)
        when (a.status) {
            ApprovalStatus.PUBLISHED -> return ToolReply("${a.id} was already published at ${Instant.ofEpochMilli(a.publishedAt ?: 0)}", true)
            ApprovalStatus.PENDING -> return ToolReply("${a.id} is not approved yet; an operator must send '@$nickname approve ${a.id}'", true)
            ApprovalStatus.REJECTED -> return ToolReply("${a.id} was rejected", true)
            ApprovalStatus.APPROVED -> Unit
        }
        if (!publishing.add(a.id)) return ToolReply("${a.id} is already being published", true)
        try {
            val files = readPostFiles(File(a.dir))
            if (postHash(files) != a.hash) {
                log("approval ${a.id} publish refused: post changed since approval dir=${a.dir}")
                return ToolReply("${a.id} changed since it was approved, not published; request a new approval", true)
            }
            val snap = snapshot(files)
            try {
                log("approval ${a.id} publishing by $by approvedBy=${a.decidedBy} requestedBy=${a.requestedBy} dir=${a.dir}")
                val r = runShell(command.replace("{dir}", shellWord(snap.absolutePath)), emptyList(), mapOf("POST_DIR" to snap.absolutePath), publishTimeoutMs)
                val output = (r.out.trim() + r.errTail.takeIf { it.isNotEmpty() }?.let { "\nstderr: $it" }.orEmpty()).trim()
                if (r.timedOut || r.exit != 0) {
                    val why = if (r.timedOut) "timed out after ${publishTimeoutMs / 1000}s" else "exit ${r.exit}"
                    log("approval ${a.id} publish failed: $why")
                    return ToolReply(capTextBytes("publish of ${a.id} failed ($why); it is still approved\n$output"), true)
                }
                store.transition(a.id, setOf(ApprovalStatus.APPROVED)) {
                    it.copy(status = ApprovalStatus.PUBLISHED, publishedAt = now(), output = output.oneLineTail(KEPT_OUTPUT_CODEPOINTS))
                }
                log("approval ${a.id} published by $by approvedBy=${a.decidedBy}")
                return ToolReply(capTextBytes("published ${a.id}\n$output"))
            } finally {
                snap.deleteRecursively()
            }
        } finally {
            publishing.remove(a.id)
        }
    }
}

class ApprovalScope(val gate: ApprovalGate, val conversation: Uuid, val requester: String)

private suspend fun withApprovals(b: AgentBackend, block: suspend (ApprovalScope) -> ToolReply): ToolReply = guarded {
    block(b.approvals ?: return@guarded ToolReply("approvals are not available", true))
}

fun approvalTools(): List<ToolDef> = listOf(
    ToolDef(
        "request_approval",
        "Ask the operators in this chat to approve a prepared post before it is published. dir is the post folder under $POSTS_DIR/ (post.json plus slide-NN.png). The chat-agent itself sends the slides, text and an approval id; nothing can be published until an operator approves that id. Any change to the folder afterwards needs a new request.",
        buildJsonObject { put("dir", buildJsonObject { put("type", "string"); put("description", "Post folder, e.g. $POSTS_DIR/2026-11-05-launch") }) },
        listOf("dir"), stdio = false, write = true,
    ) { b, a -> withApprovals(b) { scope -> scope.gate.request(b, scope, stringArg(a, "dir") ?: "") } },
    ToolDef(
        "publish_post",
        "Publish a post an operator has approved, by approval id. Runs the configured publish command once on exactly the approved files and returns its output.",
        buildJsonObject { put("id", buildJsonObject { put("type", "string"); put("description", "Approval id") }) },
        listOf("id"), stdio = false, write = true,
    ) { b, a -> withApprovals(b) { scope ->
        val id = stringArg(a, "id") ?: return@withApprovals ToolReply("id is empty", true)
        scope.gate.publish(scope.conversation, id, scope.requester)
    } },
)
