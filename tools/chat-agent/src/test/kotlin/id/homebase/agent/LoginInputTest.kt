package id.homebase.agent

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class LoginInputTest {
    private val q = "identity=me.homebase.id&public_key=PK%2Bx&salt=S1&state=abc"
    private val url = "http://localhost:5555/authorization-code-callback?$q"

    private fun ok(input: String) = (parseCallbackInput(input, "abc") as CallbackParse.Ok).params
    private fun bad(input: String) = (parseCallbackInput(input, "abc") as CallbackParse.Bad).message

    @Test fun fullUrl() {
        val p = ok("  $url \n")
        assertEquals("me.homebase.id", p.identity)
        assertEquals("PK+x", p.publicKey)
        assertEquals("S1", p.salt)
    }

    @Test fun queryOnlyAndQuotedAndFragment() {
        ok(q)
        ok("?$q")
        ok("\"$url#frag\"")
    }

    @Test fun wrongState() = assertTrue(bad(url.replace("state=abc", "state=zzz")).contains("state"))

    @Test fun missingParams() = assertTrue(bad("http://localhost:1/x?identity=a&state=abc").contains("public_key"))

    @Test fun garbage() {
        assertTrue(bad("hello world").contains("not a callback URL"))
        assertTrue(bad("http://localhost:1/x").contains("no query"))
        assertTrue(bad("   ").contains("empty"))
    }

    @Test fun serverError() = assertTrue(bad("http://localhost:1/x?error=access_denied&state=abc").contains("access_denied"))

    @Test fun eofEndsPumpWithoutCrash() {
        val got = mutableListOf<String>()
        pumpLines(StringReader("one\n\ntwo").buffered()) { got += it }
        assertEquals(listOf("one", "two"), got)
    }

    @Test fun pasteRetryThenHttpWins() = runBlocking<Unit> {
        val inputs = Channel<String>(Channel.UNLIMITED)
        val errors = mutableListOf<String>()
        inputs.trySend("garbage")
        inputs.trySend(url.replace("state=abc", "state=x"))
        val job = launch { }
        job.join()
        launch { inputs.send(url) }
        val p = withTimeout(5000) { awaitCallback(inputs, "abc", onError = { errors += it }) }
        assertEquals(2, errors.size)
        assertEquals("S1", p.salt)
    }

    @Test fun firstValidOfCallbackAndPasteWins() = runBlocking<Unit> {
        val inputs = Channel<String>(Channel.UNLIMITED)
        inputs.trySend(url)
        inputs.trySend(url.replace("salt=S1", "salt=S2"))
        assertEquals("S1", withTimeout(5000) { awaitCallback(inputs, "abc", onError = { }) }.salt)
    }

    @Test fun identityMismatchRejectedMatchCaseInsensitive() {
        val m = (parseCallbackInput(url, "abc", "other.homebase.id") as CallbackParse.Bad).message
        assertTrue(m.contains("approved as me.homebase.id") && m.contains("other.homebase.id"))
        assertTrue(parseCallbackInput(url, "abc", "ME.Homebase.ID") is CallbackParse.Ok)
    }

    @Test fun openerSelection() {
        assertEquals("open", browserOpener("Mac OS X", emptyMap(), false))
        assertNull(browserOpener("Mac OS X", emptyMap(), true))
        assertNull(browserOpener("Linux", emptyMap(), false))
        assertNull(browserOpener("Linux", mapOf("DISPLAY" to ""), false))
        assertEquals("xdg-open", browserOpener("Linux", mapOf("DISPLAY" to ":0"), false))
        assertEquals("xdg-open", browserOpener("Linux", mapOf("WAYLAND_DISPLAY" to "wayland-0"), false))
        assertNull(browserOpener("Linux", mapOf("DISPLAY" to ":0"), true))
        assertNull(browserOpener("Windows 11", mapOf("DISPLAY" to ":0"), false))
    }
}
