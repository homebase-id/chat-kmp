package id.homebase.api.client

import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpResponseData
import io.ktor.utils.io.ByteReadChannel
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

internal class UploadTestProvider(client: HttpClient, override val uploadStallTimeout: Duration) :
    OdinApiProviderBase(client, CredentialsManager()) {
    suspend fun upload(url: String, bytes: Int, onProgress: UploadProgress? = null) = plainPostMultipart(
        url, "token", MultiPartFormDataContent(formData { append("payload", ByteArray(bytes)) }), onProgress,
    )
}

class IdleTimeoutTest {

    // The body is larger than the 1 MiB Ktor buffers between the progress listener and the engine.
    private suspend fun upload(handler: suspend MockRequestHandleScope.(ByteReadChannel) -> HttpResponseData) =
        UploadTestProvider(
            HttpClient(MockEngine { handler((it.body as OutgoingContent.ReadChannelContent).readFrom()) }),
            uploadStallTimeout = 300.milliseconds,
        ).upload("https://example.test/api/v2/drives/x/files", bytes = 8 * 1024 * 1024)

    @Test
    fun uploadWhoseBodyStopsMoving_failsAsStalled() = runTest(timeout = 30.seconds) {
        val e = assertFailsWith<UploadStalledException> { upload { body -> body.discard(64 * 1024); awaitCancellation() } }
        assertEquals(300.milliseconds, e.idle)
    }

    @Test
    fun uploadThatFinishes_isNotTimedOut() = runTest(timeout = 30.seconds) {
        assertEquals(200, upload { body -> body.discard(); respond("{}", HttpStatusCode.OK) }.status)
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
