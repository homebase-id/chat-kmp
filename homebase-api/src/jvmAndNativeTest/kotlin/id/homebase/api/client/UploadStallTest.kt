package id.homebase.api.client

import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Accepts one connection on 127.0.0.1, reads nothing from it and never answers, until closed. */
expect class StallingServer() : AutoCloseable {
    val port: Int
    override fun close()
}

private const val BODY_BYTES = 16 * 1024 * 1024

private fun body(bytes: Int = BODY_BYTES) = MultiPartFormDataContent(formData { append("payload", ByteArray(bytes)) })

internal class UploadTestProvider(client: HttpClient, override val uploadStallTimeout: Duration) :
    OdinApiProviderBase(client, CredentialsManager()) {
    suspend fun upload(url: String, bytes: Int = BODY_BYTES, onProgress: UploadProgress? = null) =
        plainPostMultipart(url, "token", body(bytes), onProgress)
}

class UploadStallTest {

    @Test
    fun stalledUpload_onTheProductionClient_failsWithinTheStallTimeout() = runTest(timeout = 60.seconds) {
        StallingServer().use { server ->
            val provider = UploadTestProvider(HttpClientProvider.create(), uploadStallTimeout = 2.seconds)
            var lastSent = 0L
            val start = TimeSource.Monotonic.markNow()

            val e = assertFailsWith<UploadStalledException> {
                provider.upload("http://127.0.0.1:${server.port}/api/v2/drives/x/files") { sent, _ -> lastSent = sent }
            }

            val elapsed = start.elapsedNow()
            println("UploadStallTest: stalled after $elapsed, ${e.message}")
            assertTrue(elapsed < 20.seconds, "took $elapsed")
            assertTrue(lastSent in 1 until BODY_BYTES, "body started then stopped: $lastSent")
        }
    }

    // Records what this platform's engine does on its own when the body stops moving.
    @Test
    fun engineSocketTimeout_onAStalledBody() = runTest(timeout = 60.seconds) {
        StallingServer().use { server ->
            val client = createPlatformHttpClient {
                install(HttpTimeout) {
                    socketTimeoutMillis = 2_000
                    requestTimeoutMillis = 20_000
                }
            }
            var lastSent = 0L
            val start = TimeSource.Monotonic.markNow()

            val e = runCatching {
                client.post("http://127.0.0.1:${server.port}/") {
                    setBody(body())
                    onUpload { sent, _ -> lastSent = sent }
                }
            }.exceptionOrNull()

            println("UploadStallTest: engine ended a stalled body after ${start.elapsedNow()} with ${e?.let { it::class.simpleName + ": " + it.message }}, sent $lastSent")
            assertTrue(e != null)
        }
    }
}
