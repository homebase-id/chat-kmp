package id.homebase.chat.agentui

import androidx.compose.runtime.saveable.SaverScope
import id.homebase.api.HomebaseProtocol
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.conversationlist.imageViewerFor
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AgentUiInlineTreeTest {
    private val draft: JsonObject = Json.parseToJsonElement(AgentUiFixtures.draftPostCardJson()).jsonObject
    private val images = AgentUiFixtures.imageKeys.map { PayloadDescriptor(key = it, contentType = "image/webp", iv = Base64Bytes(ByteArray(16))) }
    private val treePayload = PayloadDescriptor(key = ChatProtocol.PAYLOAD_KEY_AGENT_UI, contentType = "application/json", iv = Base64Bytes(ByteArray(16) { 1 }), lastModified = 1)

    private fun header(d: AgentUiDescriptor) = OdinSystemSerializer.serialize(d)

    private fun message(content: String, payloads: List<PayloadDescriptor>) = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = "",
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = OdinId("agent.example.com"),
        sender = OdinId("agent.example.com"),
        displayName = "Agent",
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = payloads.toImmutableList(),
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
        messageContent = MessageContentParser.parseForDisplay(ChatProtocol.ChatAgentUiMessageDataType, content, payloads),
    )

    private fun TestScope.loader(fetches: MutableList<Uuid>, bytes: () -> ByteArray? = { AgentUiFixtures.draftPostCardBytes() }) =
        AgentUiTreeLoader(fetch = { id, _, _ -> fetches += id; bytes() }, scope = this, decodeDispatcher = StandardTestDispatcher(testScheduler))

    @Test fun small_tree_rides_inline_and_decodes_with_the_header() {
        val d = assertNotNull(AgentUiDescriptor(fallback = "Draft post").withInlineTree(draft))
        val content = assertIs<MessageContent.AgentUi>(MessageContentParser.parseForDisplay(219, header(d), images))
        val tree = assertIs<AgentUiTreeResult.Valid>(content.inlineTree).tree
        assertEquals(AgentUiFixtures.decodeDraftPostCard(), tree)
    }

    @Test fun inline_image_keys_come_from_the_message_payloads() {
        val d = assertNotNull(AgentUiDescriptor(fallback = "Draft post").withInlineTree(draft))
        val content = assertIs<MessageContent.AgentUi>(MessageContentParser.parseForDisplay(219, header(d), emptyList()))
        assertNotEquals(AgentUiFixtures.decodeDraftPostCard(), assertIs<AgentUiTreeResult.Valid>(content.inlineTree).tree)
    }

    @Test fun a_tree_over_the_header_budget_is_refused_inline() {
        val big = Json.parseToJsonElement(
            """{"root":"r","nodes":[{"id":"r","type":"column","children":[${(0 until 40).joinToString(",") { "\"t$it\"" }}]},""" +
                (0 until 40).joinToString(",") { """{"id":"t$it","type":"text","markdown":"${"w".repeat(120)}"}""" } + "]}",
        ).jsonObject
        val d = AgentUiDescriptor(fallback = "Report")
        assertNull(d.withInlineTree(big))
        assertNotNull(d.withInlineTree(draft)).let {
            assertTrue(HomebaseProtocol.fitsHeaderContent(header(it)))
        }
    }

    @Test fun a_header_without_a_tree_has_no_inline_tree() {
        val content = assertIs<MessageContent.AgentUi>(MessageContentParser.parseForDisplay(219, header(AgentUiDescriptor(fallback = "x")), null))
        assertNull(content.inlineTree)
        assertEquals(false, header(AgentUiDescriptor(fallback = "x")).contains("tree"))
    }

    @Test fun a_deeply_nested_inline_tree_shows_the_fallback() {
        val deep = """{"schemaVersion":1,"fallback":"x","tree":""" + "[".repeat(5000) + "]".repeat(5000) + "}"
        val content = assertIs<MessageContent.AgentUi>(MessageContentParser.parse(219, deep))
        assertEquals("x", assertNotNull(content.descriptor).fallback)
        assertIs<AgentUiTreeResult.Invalid>(content.inlineTree)
    }

    @Test fun parse_without_display_leaves_the_inline_tree_undecoded() {
        val content = assertIs<MessageContent.AgentUi>(MessageContentParser.parse(219, """{"fallback":"Draft post","tree":[]}"""))
        assertNull(content.inlineTree)
        val d = assertNotNull(content.descriptor)
        assertEquals(Json.parseToJsonElement("[]"), d.tree)
        val shown = assertIs<MessageContent.AgentUi>(MessageContentParser.parseForDisplay(219, header(d), null))
        assertNull(assertNotNull(shown.descriptor).tree)
        assertIs<AgentUiTreeResult.Invalid>(shown.inlineTree)
    }

    @Test fun inline_card_is_ready_on_the_first_frame_and_never_fetches() = runTest {
        val fetches = mutableListOf<Uuid>()
        val loader = loader(fetches)
        val d = assertNotNull(AgentUiDescriptor(fallback = "Draft post").withInlineTree(draft))
        val m = message(header(d), images + treePayload)
        val content = m.messageContent as MessageContent.AgentUi
        loader.warm(listOf(m))
        advanceUntilIdle()
        assertIs<TreeLoad.Ready>(firstFrameTree(content, 0, m, loader))
        assertNull(loader.load(m))
        assertEquals(emptyList(), fetches)
    }

    @Test fun payload_card_warmed_on_arrival_is_ready_on_the_first_frame() = runTest {
        val fetches = mutableListOf<Uuid>()
        val loader = loader(fetches)
        val m = message(header(AgentUiDescriptor(fallback = "Draft post")), images + treePayload)
        val content = m.messageContent as MessageContent.AgentUi
        assertIs<TreeLoad.Loading>(firstFrameTree(content, 0, m, loader))
        loader.warm(listOf(m, m))
        advanceUntilIdle()
        assertIs<TreeLoad.Ready>(firstFrameTree(content, 0, m, loader))
        assertIs<AgentUiTreeResult.Valid>(loader.load(m))
        assertEquals(listOf(m.fileId), fetches)
    }

    @Test fun concurrent_loads_share_one_fetch() = runTest {
        val gate = CompletableDeferred<Unit>()
        var count = 0
        val loader = AgentUiTreeLoader(fetch = { _, _, _ -> count++; gate.await(); AgentUiFixtures.draftPostCardBytes() }, scope = this)
        val m = message(header(AgentUiDescriptor(fallback = "Draft post")), images + treePayload)
        val all = List(3) { async { loader.load(m) } }
        advanceUntilIdle()
        gate.complete(Unit)
        all.awaitAll().forEach { assertIs<AgentUiTreeResult.Valid>(it) }
        assertEquals(1, count)
    }

    @Test fun a_failed_fetch_is_retried_on_the_next_load() = runTest {
        val fetches = mutableListOf<Uuid>()
        var fail = true
        val loader = loader(fetches) { if (fail) null else AgentUiFixtures.draftPostCardBytes() }
        val m = message(header(AgentUiDescriptor(fallback = "Draft post")), images + treePayload)
        assertNull(loader.load(m))
        fail = false
        assertIs<AgentUiTreeResult.Valid>(loader.load(m))
        assertEquals(2, fetches.size)
    }

    @Test fun a_tree_warmed_after_a_failed_load_reaches_the_results_flow() = runTest {
        var fail = true
        var now = 0L
        val loader = AgentUiTreeLoader(
            fetch = { _, _, _ -> if (fail) null else AgentUiFixtures.draftPostCardBytes() },
            scope = this,
            nowMs = { now },
        )
        val m = message(header(AgentUiDescriptor(fallback = "Draft post")), images + treePayload)
        val seen = async { assertNotNull(loader.results(m)).first() }
        assertNull(loader.load(m))
        fail = false
        now = 31_000
        loader.warm(listOf(m))
        advanceUntilIdle()
        assertIs<AgentUiTreeResult.Valid>(seen.await())
    }

    @Test fun a_fetch_in_flight_when_the_session_ends_does_not_repopulate_the_cache() = runTest {
        val gate = CompletableDeferred<Unit>()
        val loader = AgentUiTreeLoader(fetch = { _, _, _ -> gate.await(); AgentUiFixtures.draftPostCardBytes() }, scope = this)
        val m = message(header(AgentUiDescriptor(fallback = "Draft post")), images + treePayload)
        loader.warm(listOf(m))
        advanceUntilIdle()
        loader.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(loader.cached(m))
    }

    @Test fun an_inline_tree_that_is_not_an_object_keeps_the_descriptor() {
        val content = assertIs<MessageContent.AgentUi>(
            MessageContentParser.parseForDisplay(219, """{"fallback":"Draft post","tree":[]}""", null)
        )
        assertNotNull(content.descriptor)
        assertIs<AgentUiTreeResult.Invalid>(content.inlineTree)
    }

    @Test fun an_edit_that_replaces_the_payload_refetches() = runTest {
        val fetches = mutableListOf<Uuid>()
        val loader = loader(fetches)
        val m = message(header(AgentUiDescriptor(fallback = "Draft post")), images + treePayload)
        loader.load(m)
        val edited = m.copy(payloads = (images + treePayload.copy(lastModified = 2, iv = Base64Bytes(ByteArray(16) { 2 }))).toImmutableList())
        assertNull(loader.cached(edited))
        loader.load(edited)
        assertEquals(2, fetches.size)
        assertNull(loader.cached(m))
    }

    @Test fun a_new_last_modified_alone_refetches_with_that_version() = runTest {
        val asked = mutableListOf<Long?>()
        var body = AgentUiFixtures.tree("""{"id":"root","type":"text","markdown":"Draft"}""")
        val loader = AgentUiTreeLoader(fetch = { _, _, lastModified -> asked += lastModified; body }, scope = this)
        val m = message(header(AgentUiDescriptor(fallback = "Draft post")), images + treePayload)
        fun AgentUiTreeResult?.text() = assertIs<AgentUiNode.Text>(assertIs<AgentUiTreeResult.Valid>(this).tree.root).markdown
        assertEquals("Draft", loader.load(m).text())

        body = AgentUiFixtures.tree("""{"id":"root","type":"text","markdown":"Posted"}""")
        val edited = m.copy(payloads = (images + treePayload.copy(lastModified = 2)).toImmutableList())
        assertNull(loader.cached(edited))
        assertEquals("Posted", loader.load(edited).text())
        assertEquals(listOf<Long?>(1, 2), asked)
    }

    @Test fun an_agent_card_image_opens_the_viewer_on_its_images_only() {
        val m = message(header(AgentUiDescriptor(fallback = "Draft post")), images + treePayload)
        val viewer = imageViewerFor(m, "Agent", "chat_web1")
        assertEquals(AgentUiFixtures.imageKeys.toList(), viewer.payloads.map { it.key })
        assertEquals("chat_web1", viewer.selectedPayloadKey)
        assertEquals("", viewer.content)
        assertEquals(m.fileId, viewer.fileId)
    }

    @Test fun warming_backs_off_after_a_failed_fetch() = runTest {
        val fetches = mutableListOf<Uuid>()
        var now = 0L
        val loader = AgentUiTreeLoader(fetch = { id, _, _ -> fetches += id; null }, scope = this, nowMs = { now })
        val m = message(header(AgentUiDescriptor(fallback = "Draft post")), images + treePayload)
        loader.warm(listOf(m))
        advanceUntilIdle()
        loader.warm(listOf(m))
        advanceUntilIdle()
        assertEquals(1, fetches.size)
        now = 31_000
        loader.warm(listOf(m))
        advanceUntilIdle()
        assertEquals(2, fetches.size)
        assertNull(loader.load(m))
        assertEquals(3, fetches.size)
    }

    @Test fun an_edit_from_inline_to_payload_loads_the_payload() = runTest {
        val fetches = mutableListOf<Uuid>()
        val loader = loader(fetches)
        val inline = message(header(assertNotNull(AgentUiDescriptor(fallback = "v1").withInlineTree(draft))), images)
        assertIs<TreeLoad.Ready>(firstFrameTree(inline.messageContent as MessageContent.AgentUi, 0, inline, loader))
        val edited = inline.copy(
            payloads = (images + treePayload).toImmutableList(),
            messageContent = MessageContentParser.parseForDisplay(219, header(AgentUiDescriptor(fallback = "v2", rev = 1)), images + treePayload),
        )
        assertIs<TreeLoad.Loading>(firstFrameTree(edited.messageContent as MessageContent.AgentUi, 1, edited, loader))
        assertIs<AgentUiTreeResult.Valid>(loader.load(edited))
        assertEquals(listOf(edited.fileId), fetches)
    }

    @Test fun a_card_with_neither_inline_tree_nor_payload_shows_its_fallback() = runTest {
        val loader = loader(mutableListOf())
        val m = message(header(AgentUiDescriptor(fallback = "x")), images)
        assertIs<TreeLoad.Failed>(firstFrameTree(m.messageContent as MessageContent.AgentUi, 0, m, loader))
    }

    @Test fun input_state_survives_save_and_restore() {
        val s = AgentUiInputState(mapOf("caption" to "Hi", "tone" to "warm"))
        s["caption"] = "Typed"
        val saved = with(AgentUiInputState.Saver) { SaverScope { true }.save(s) }!!
        val r = AgentUiInputState.Saver.restore(saved)!!
        assertEquals(mapOf("caption" to "Typed", "tone" to "warm"), r.snapshot())
        r.reconcile(mapOf("caption" to "Hi", "tone" to "cool"))
        assertEquals(mapOf("caption" to "Typed", "tone" to "cool"), r.snapshot())
    }
}
