package id.homebase.api.client

import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class SlowUploadTest {

    @Test
    fun slowButProgressingUpload_isNotFailed() = runTest(timeout = 120.seconds) {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                var left = Regex("(?i)content-length: (\\d+)").find(head)!!.groupValues[1].toLong()
                while (left > 0) {
                    left -= input.readNBytes(minOf(64 * 1024L, left).toInt()).size
                    Thread.sleep(50)
                }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                    flush()
                }
            }
        }

        server.use {
            val provider = UploadTestProvider(HttpClientProvider.create(), uploadStallTimeout = 3.seconds)
            val start = TimeSource.Monotonic.markNow()
            var lastProgressAt = start.elapsedNow()

            val response = provider.upload("http://127.0.0.1:${server.localPort}/api/v2/drives/x/files", bytes = 16 * 1024 * 1024) { _, _ ->
                lastProgressAt = start.elapsedNow()
            }

            assertEquals(200, response.status)
            // Socket buffers must not have swallowed the body up front, or this proved nothing about the gaps.
            assertTrue(lastProgressAt > 4.seconds, "body was all handed over after $lastProgressAt")
        }
    }
}
