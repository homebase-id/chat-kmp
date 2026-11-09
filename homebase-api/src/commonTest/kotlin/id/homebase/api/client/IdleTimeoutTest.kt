package id.homebase.api.client

import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.discard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class StallProbe : Exception()

private class MockUploadProvider(client: HttpClient) : OdinApiProviderBase(client, CredentialsManager()) {
    override val uploadStallTimeout: Duration = 300.milliseconds
    suspend fun upload() = plainPostMultipart(
        "https://example.test/api/v2/drives/x/files",
        "token",
        MultiPartFormDataContent(formData { append("payload", ByteArray(8 * 1024 * 1024)) }),
    )
}

class IdleTimeoutTest {

    // The body is larger than the 1 MiB Ktor buffers between the progress listener and the engine.

    private fun engine(readBytes: Long?) = HttpClient(MockEngine { request ->
        val body = (request.body as OutgoingContent.ReadChannelContent).readFrom()
        if (readBytes == null) {
            body.discard()
            respond("{}", HttpStatusCode.OK)
        } else {
            body.discard(readBytes)
            awaitCancellation()
        }
    })

    @Test
    fun uploadWhoseBodyStopsMoving_failsAsStalled() = runTest(timeout = 30.seconds) {
        val e = assertFailsWith<UploadStalledException> { MockUploadProvider(engine(readBytes = 64 * 1024)).upload() }
        assertEquals(300.milliseconds, e.idle)
    }

    @Test
    fun uploadThatFinishes_isNotTimedOut() = runTest(timeout = 30.seconds) {
        assertEquals(200, MockUploadProvider(engine(readBytes = null)).upload().status)
    }

    @Test
    fun waitAfterDone_isNotTimed() = runTest(timeout = 30.seconds) {
        val result = withIdleTimeout(100.milliseconds, { StallProbe() }) { progress ->
            progress(false)
            progress(true)
            withContext(Dispatchers.Default) { delay(400) }
            "answered"
        }
        assertEquals("answered", result)
    }

    @Test
    fun armedAndSilent_fails() = runTest(timeout = 30.seconds) {
        assertFailsWith<StallProbe> {
            withIdleTimeout(100.milliseconds, { StallProbe() }) { progress ->
                progress(false)
                awaitCancellation()
            }
        }
    }
}
