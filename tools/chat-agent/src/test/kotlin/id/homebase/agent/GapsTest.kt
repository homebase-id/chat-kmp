package id.homebase.agent

import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import id.homebase.api.client.HttpClientProvider
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class GapsTest {
    private val author = OdinId("alice.example.com")
    private val thumb = EmbeddedThumb(10, 10, "image/jpeg", "abc")

    private fun parent(
        text: String = "hello",
        payloads: List<PayloadDescriptor>? = null,
        thumbnail: EmbeddedThumb? = null,
        dataType: Int? = null,
        rawContent: String? = null,
    ) = ChatMsg(Uuid.random(), Uuid.random(), author, text, 1L, thumbnail, payloads, dataType, rawContent)

    @Test
    fun mediaParentCarriesThumbnail() {
        val p = parent(payloads = listOf(PayloadDescriptor(key = "pfl0000000", contentType = "image/jpeg")), thumbnail = thumb).toReplyPreview()
        assertEquals(thumb, p.previewThumbnail)
        assertNull(p.context)
    }

    @Test
    fun linkPreviewPayloadDoesNotCarryThumbnail() {
        val p = parent(payloads = listOf(PayloadDescriptor(key = ChatProtocol.PAYLOAD_KEY_LINKS, contentType = "image/jpeg")), thumbnail = thumb).toReplyPreview()
        assertNull(p.previewThumbnail)
    }

    @Test
    fun eventParentCarriesContext() {
        val json = """{"title":"Party","startUtcMs":1790000000000,"timezone":"UTC"}"""
        val p = parent(text = "Party", dataType = ChatProtocol.ChatEventMessageDataType, rawContent = json).toReplyPreview()
        assertNotNull(p.context)
        assertTrue(p.context.toString().contains("1790000000000"))
        assertNull(p.previewThumbnail)
    }

    @Test
    fun textParentUnchanged() {
        val p = parent(text = "\n  hello  ", thumbnail = thumb).toReplyPreview()
        assertEquals("hello", p.message)
        assertEquals("alice.example.com", p.authorOdinId)
        assertNull(p.previewThumbnail)
        assertNull(p.context)
    }

    @Test
    fun slowLineOnlyAboveThresholdAndNamesPhases() = runBlocking {
        var clock = 0L
        val t = PollTimings { clock }
        t.time("query") { clock += 100 }
        assertNull(t.slowLine())
        t.time("brain") { clock += 6_000 }
        t.time("query") { clock += 50 }
        assertEquals("poll slow: query=150ms brain=6000ms", t.slowLine())
        t.reset()
        assertNull(t.slowLine())
    }

    @Test
    fun requestTimeoutSurfacesAsException() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { Thread.sleep(3_000); it.sendResponseHeaders(200, -1); it.close() }
            start()
        }
        try {
            val client = HttpClientProvider.create().withRequestTimeout(300)
            assertFailsWith<HttpRequestTimeoutException> { client.get("http://127.0.0.1:${server.address.port}/") }
            client.close()
        } finally {
            server.stop(0)
        }
    }
}
