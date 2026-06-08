package id.homebase.api.coroutines

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import id.homebase.api.client.HttpBreadcrumbPlugin
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What these tests prove, in plain terms:
 *
 *  - A background coroutine that fails with the exact error that crashed the app
 *    in the wild (`UnknownHostException`, a dropped-network DNS failure) is now
 *    CONTAINED by [supervisedScope]'s fallback handler instead of killing the
 *    process — and its sibling coroutines keep running.
 *  - When it's contained, the failure is LOGGED with the scope name and the
 *    original throwable, which is the attribution Crashlytics relies on (the
 *    async stack of a suspended network call has no app frames of its own).
 *  - The per-request HTTP breadcrumb names the host + path but NEVER the query
 *    string (which can carry the shared secret / tokens).
 *
 * The only part of the crash-handling change NOT covered here is the Android
 * `ServiceLoader` global handler + R8 packaging — that only proves out in a real
 * APK and was verified by inspecting the merged `base.jar`.
 */
class CoroutineExceptionHandlersTest {

    private val logCollector = CollectingLogWriter()

    @BeforeTest
    fun setUp() {
        Logger.setLogWriters(listOf(logCollector))
    }

    @AfterTest
    fun tearDown() {
        // Restore Kermit's global writer — this is process-wide shared state.
        Logger.setLogWriters(listOf(platformLogWriter()))
    }

    @Test
    fun aThrowingChildIsContained_andSiblingsKeepRunning() = runTest {
        val scope = supervisedScope("test-sync", StandardTestDispatcher(testScheduler))

        var siblingCompleted = false
        // One child blows up with the real-world crash.
        scope.launch { throw UnknownHostException("shelly.silberberg.dk") }
        // A sibling on the SAME scope must still run to completion — proving the
        // failure was isolated, not propagated to tear the scope (and process) down.
        scope.launch { siblingCompleted = true }

        advanceUntilIdle()

        assertTrue(siblingCompleted, "a sibling must survive another child's crash")
        assertTrue(scope.isActive, "the scope must stay alive after an isolated child failure")
    }

    @Test
    fun theFallbackHandlerLogsTheThrowableWithScopeName_forCrashlyticsAttribution() = runTest {
        val scope = supervisedScope("drive-sync", StandardTestDispatcher(testScheduler))

        scope.launch { throw UnknownHostException("shelly.silberberg.dk") }
        advanceUntilIdle()

        val errors = logCollector.entries.filter {
            it.severity == Severity.Error && it.tag == COROUTINE_CRASH_TAG
        }
        assertEquals(1, errors.size, "exactly one error should be logged for the one failure")
        val entry = errors.single()
        // The scope label is the attribution we depend on in the crash report.
        assertTrue(
            entry.message.contains("drive-sync"),
            "the log must name the offending scope; was: ${entry.message}",
        )
        // The throwable is attached so CrashlyticsLogWriter records it as a non-fatal
        // (with a stack) rather than it vanishing into the text log.
        assertTrue(
            entry.throwable is UnknownHostException,
            "the original throwable must be attached; was: ${entry.throwable}",
        )
    }

    @Test
    fun httpBreadcrumbLogsMethodHostAndPath_butNeverTheQueryString() = runTest {
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK) }) {
            install(HttpBreadcrumbPlugin)
        }

        client.get("https://shelly.silberberg.dk/api/v2/drive/query?ss=SUPERSECRET&token=PII")

        val breadcrumbs = logCollector.entries.filter { it.tag == "HttpIO" }.map { it.message }
        assertEquals(1, breadcrumbs.size, "one breadcrumb per request")
        val line = breadcrumbs.single()
        assertTrue(line.contains("GET"), "method should be present; was: $line")
        assertTrue(
            line.contains("shelly.silberberg.dk/api/v2/drive/query"),
            "host + path should be present; was: $line",
        )
        assertFalse(
            line.contains("SUPERSECRET"),
            "the query string can carry the shared secret and MUST be stripped; was: $line",
        )
        assertFalse(line.contains("token"), "no query params in the breadcrumb; was: $line")
    }
}

/** Kermit [LogWriter] that captures entries for assertions. */
private class CollectingLogWriter : LogWriter() {
    data class Entry(val severity: Severity, val tag: String, val message: String, val throwable: Throwable?)

    val entries: MutableList<Entry> = mutableListOf()

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        entries += Entry(severity, tag, message, throwable)
    }
}
