package id.homebase.agent

import id.homebase.api.common.OdinId
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ApprovalsTest {
    private val self = OdinId("bot.example.com")
    private val op = OdinId("op.example.com")
    private val rando = OdinId("rando.example.com")
    private val room = Uuid.random()
    private val room2 = Uuid.random()

    private class Backend(override val approvals: ApprovalScope?) : AgentBackend {
        override val allowlist: Allowlist = Allowlist.default(OdinId("owner.example.com"))
        val texts = mutableListOf<String>()
        val files = mutableListOf<List<OutFile>>()
        override suspend fun messages(conversationId: Uuid, limit: Int, beforeMs: Long?) = emptyList<ChatMsg>()
        override suspend fun send(conversationId: Uuid, text: String, replyTo: id.homebase.chat.services.ReplyPreview?): Uuid {
            texts += text
            return Uuid.random()
        }
        override suspend fun sendFiles(conversationId: Uuid, files: List<OutFile>, caption: String): Uuid {
            this.files += files
            return Uuid.random()
        }
    }

    private inner class Rig(publishCommand: String? = "echo ran >> \"\$COUNT\"; ls {dir}; echo \"env=\$POST_DIR\"", operators: Set<OdinId> = setOf(op)) {
        val cwd: File = Files.createTempDirectory("approvals").toFile()
        val count = File(cwd, "count")
        val logs = mutableListOf<String>()
        val store = ApprovalStore(File(cwd, "approvals.json"))
        val gate = ApprovalGate(store, cwd, "quagmire", publishCommand?.replace("\$COUNT", count.absolutePath), { logs += it })
        val post = File(cwd, "posts/launch").apply { mkdirs() }
        val harness: TestHarness
        var clock = 1_790_000_000_000L

        init {
            File(post, POST_FILE).writeText("""{"text":"Hello world","channels":["linkedin","x"],"title":"Launch","slides":["slide-01.png","slide-02.png"]}""")
            File(post, "slide-01.png").writeBytes(byteArrayOf(1, 2, 3))
            File(post, "slide-02.png").writeBytes(byteArrayOf(4, 5, 6))
            val allow = Allowlist(setOf(room, room2), null, Kind.BOT)
            allow.learn(listOf(ConversationInfo(room, "r", listOf(self, op, rando)), ConversationInfo(room2, "r2", listOf(self, op))))
            harness = TestHarness(AgentConfig(allowlist = allow, operators = operators, operatorBrain = "x"), identity = self.toString(), approvals = gate)
        }

        fun backend(conversation: Uuid = room) = Backend(ApprovalScope(gate, conversation, op.toString()))

        suspend fun request(b: Backend = backend(), dir: String = "posts/launch"): String {
            val reply = tool("request_approval").run(b, buildJsonObject { put("dir", dir) })
            assertFalse(reply.isError, reply.text)
            return store.list(room).last().id
        }

        suspend fun publish(id: String, b: Backend = backend()) = tool("publish_post").run(b, buildJsonObject { put("id", id) })

        suspend fun say(sender: OdinId?, text: String, author: OdinId? = sender, conv: Uuid = room) =
            harness.handle(ChatMsg(Uuid.random(), conv, author, text, clock++, sender = sender))

        fun status(id: String) = store.list(room).first { it.id == id }.status
    }

    private fun tool(name: String) = CHAT_TOOLS.first { it.name == name }

    @Test
    fun toolsAreOperatorOnly() {
        assertTrue(APPROVAL_TOOL_NAMES.all { it in chatToolNames(Tier.OPERATOR) })
        assertTrue(APPROVAL_TOOL_NAMES.none { it in chatToolNames(Tier.LOCKED) })
    }

    @Test
    fun requestSendsSlidesAndInstructionsFromTheAgent() = runBlocking<Unit> {
        val r = Rig()
        val b = r.backend()
        val id = r.request(b)
        assertEquals(listOf("slide-01.png", "slide-02.png"), b.files.flatten().map { it.name })
        val text = b.texts.single()
        assertTrue(text.contains("Hello world") && text.contains("linkedin, x") && text.contains("@quagmire approve $id") && text.contains("@quagmire reject $id"), text)
        assertEquals(ApprovalStatus.PENDING, r.status(id))
        assertEquals(op.toString(), r.store.list(room).single().requestedBy)
        assertTrue(ApprovalStore(File(r.cwd, "approvals.json")).find(room, id) != null)
    }

    @Test
    fun newRequestSupersedesPending() = runBlocking<Unit> {
        val r = Rig()
        val first = r.request()
        val second = r.request()
        assertEquals(listOf(second), r.store.list(room).map { it.id })
        assertTrue(r.logs.any { it.contains("approval $first superseded by $second") })
    }

    @Test
    fun dirOutsidePostsIsRefused() = runBlocking<Unit> {
        val r = Rig()
        val outside = File(r.cwd, "elsewhere").apply { mkdirs(); File(this, POST_FILE).writeText("{}") }
        File(r.cwd, "posts/link").toPath().let { Files.createSymbolicLink(it, outside.toPath()) }
        for (dir in listOf("elsewhere", outside.absolutePath, "posts/../elsewhere", "posts/link", "posts", "posts/missing")) {
            val reply = tool("request_approval").run(r.backend(), buildJsonObject { put("dir", dir) })
            assertTrue(reply.isError, "$dir: ${reply.text}")
        }
        File(r.cwd, "posts/empty").mkdirs()
        assertTrue(tool("request_approval").run(r.backend(), buildJsonObject { put("dir", "posts/empty") }).text.contains("no $POST_FILE"))
        assertTrue(r.store.list(room).isEmpty())
    }

    @Test
    fun hashIgnoresFieldsWrittenAfterApproval() {
        val r = Rig()
        val before = postHash(r.post)
        File(r.post, POST_FILE).writeText("""{"slides":["slide-01.png","slide-02.png"],"title":"Launch","channels":["linkedin","x"],"text":"Hello world","approved_by":"op","buffer":{"id":"123"}}""")
        assertEquals(before, postHash(r.post))
        File(r.post, POST_FILE).writeText("""{"text":"Hello world!","channels":["linkedin","x"],"title":"Launch","slides":["slide-01.png","slide-02.png"]}""")
        assertNotEquals(before, postHash(r.post))
        File(r.post, POST_FILE).writeText("""{"text":"Hello world","channels":["linkedin","x"],"title":"Launch","slides":["slide-01.png","slide-02.png"]}""")
        File(r.post, "slide-02.png").writeBytes(byteArrayOf(4, 5, 7))
        assertNotEquals(before, postHash(r.post))
    }

    @Test
    fun operatorApproves() = runBlocking<Unit> {
        val r = Rig()
        val id = r.request()
        assertEquals("approval approve", r.say(op, "@quagmire approve $id"))
        assertEquals("approved $id (launch)", r.harness.replies.last())
        assertEquals(ApprovalStatus.APPROVED, r.status(id))
        assertEquals(op.toString(), r.store.list(room).single().decidedBy)
        assertTrue(r.logs.any { it.contains("approval $id approved by $op") })
        assertEquals(0, r.harness.brainRuns)
        r.say(op, "@quagmire approvals")
        assertTrue(r.harness.replies.last().contains("$id approved launch"), r.harness.replies.last())
    }

    @Test
    fun nonOperatorAndBotIdentityCannotApprove() = runBlocking<Unit> {
        val r = Rig(operators = setOf(op, self))
        val id = r.request()
        assertEquals("approval refused", r.say(rando, "@quagmire approve $id"))
        assertTrue(r.harness.replies.last().contains("only an operator"))
        assertEquals("skip: own message", r.say(self, "@quagmire approve $id"))
        r.say(self, "@quagmire approve $id", author = op)
        r.say(null, "@quagmire approve $id", author = op)
        assertEquals(ApprovalStatus.PENDING, r.status(id))
        assertEquals(0, r.harness.brainRuns)
        assertEquals("approval approve", r.say(op, "@quagmire approve $id", conv = room2))
        assertTrue(r.harness.replies.last().contains("no approval request"))
        assertEquals(ApprovalStatus.PENDING, r.status(id))
    }

    @Test
    fun rejectStopsPublish() = runBlocking<Unit> {
        val r = Rig()
        val id = r.request()
        r.say(op, "@quagmire reject $id")
        assertEquals(ApprovalStatus.REJECTED, r.status(id))
        assertTrue(r.publish(id).isError)
        assertFalse(r.count.exists())
    }

    @Test
    fun changeAfterRequestBlocksApprove() = runBlocking<Unit> {
        val r = Rig()
        val id = r.request()
        File(r.post, "slide-01.png").writeBytes(byteArrayOf(9))
        r.say(op, "@quagmire approve $id")
        assertTrue(r.harness.replies.last().contains("new request is needed"), r.harness.replies.last())
        assertEquals(ApprovalStatus.PENDING, r.status(id))
    }

    @Test
    fun changeAfterApprovalBlocksPublish() = runBlocking<Unit> {
        val r = Rig()
        val id = r.request()
        r.say(op, "@quagmire approve $id")
        File(r.post, POST_FILE).writeText("""{"text":"Buy crypto","channels":["linkedin","x"],"title":"Launch","slides":["slide-01.png","slide-02.png"]}""")
        val reply = r.publish(id)
        assertTrue(reply.isError && reply.text.contains("changed"), reply.text)
        assertFalse(r.count.exists())
        assertEquals(ApprovalStatus.APPROVED, r.status(id))
    }

    @Test
    fun publishNeedsApproval() = runBlocking<Unit> {
        val r = Rig()
        val id = r.request()
        val reply = r.publish(id)
        assertTrue(reply.isError && reply.text.contains("not approved"), reply.text)
        assertTrue(r.publish("abcdef").isError)
        assertFalse(r.count.exists())
    }

    @Test
    fun publishRunsOnceAndMarksPublished() = runBlocking<Unit> {
        val r = Rig()
        val id = r.request()
        r.say(op, "@quagmire approve $id")
        File(r.post, POST_FILE).writeText("""{"text":"Hello world","channels":["linkedin","x"],"title":"Launch","slides":["slide-01.png","slide-02.png"],"approved_by":"op"}""")
        assertTrue(r.publish(id, r.backend(room2)).isError)
        val reply = r.publish(id)
        assertFalse(reply.isError, reply.text)
        assertTrue(reply.text.contains("slide-01.png") && reply.text.contains(POST_FILE) && reply.text.contains("env=/"), reply.text)
        assertEquals(listOf("ran"), r.count.readLines())
        val record = r.store.list(room).single()
        assertEquals(ApprovalStatus.PUBLISHED, record.status)
        assertTrue(record.publishedAt != null && record.output!!.contains("slide-01.png"))
        val again = r.publish(id)
        assertTrue(again.isError && again.text.contains("already published"), again.text)
        assertEquals(listOf("ran"), r.count.readLines())
        assertEquals(ApprovalStatus.PUBLISHED, ApprovalStore(File(r.cwd, "approvals.json")).find(room, id)!!.status)
    }

    @Test
    fun failedPublishStaysApproved() = runBlocking<Unit> {
        val r = Rig(publishCommand = "echo nope; exit 3")
        val id = r.request()
        r.say(op, "@quagmire approve $id")
        val reply = r.publish(id)
        assertTrue(reply.isError && reply.text.contains("exit 3") && reply.text.contains("nope"), reply.text)
        assertEquals(ApprovalStatus.APPROVED, r.status(id))
    }

    @Test
    fun publishWithoutCommandIsNotConfigured() = runBlocking<Unit> {
        val r = Rig(publishCommand = null)
        val id = r.request()
        r.say(op, "@quagmire approve $id")
        assertTrue(r.publish(id).text.contains("publishing not configured"))
        assertTrue(r.gate.banner().contains("publishCommand unset"))
    }

    @Test
    fun publishCommandIsParsed() {
        assertEquals("scripts/publish.sh {dir}", parseConfig("operatorBrain=x\npublishCommand=scripts/publish.sh {dir}", op).publishCommand)
        assertTrue(parseConfig("publishCommand=x", op).warnings.any { it.contains("publishCommand is ignored") })
    }
}
