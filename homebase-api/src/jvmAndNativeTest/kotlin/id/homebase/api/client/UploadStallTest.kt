package id.homebase.api.client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Accepts one connection on 127.0.0.1, reads nothing from it and never answers, until closed. */
expect class StallingServer() : AutoCloseable {
    val port: Int
    override fun close()
}

private const val BODY_BYTES = 16 * 1024 * 1024

class UploadStallTest {

    @Test
    fun stalledUpload_onTheProductionClient_failsWithinTheStallTimeout() = runTest(timeout = 60.seconds) {
        StallingServer().use { server ->
            val provider = UploadTestProvider(HttpClientProvider.create(), uploadStallTimeout = 2.seconds)
            var lastSent = 0L
            val start = TimeSource.Monotonic.markNow()

            assertFailsWith<UploadStalledException> {
                provider.upload("http://127.0.0.1:${server.port}/api/v2/drives/x/files", BODY_BYTES) { sent, _ -> lastSent = sent }
            }

            val elapsed = start.elapsedNow()
            assertTrue(elapsed < 20.seconds, "took $elapsed")
            assertTrue(lastSent in 1 until BODY_BYTES, "body started then stopped: $lastSent")
        }
    }
}
