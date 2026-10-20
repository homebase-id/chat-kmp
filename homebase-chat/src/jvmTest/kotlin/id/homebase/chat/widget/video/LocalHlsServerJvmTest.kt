package id.homebase.chat.widget.video

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class LocalHlsServerJvmTest {

    private val dir: File = Files.createTempDirectory("hls-loopback-test").toFile().also {
        File(it, "index.m3u8").writeText("#EXTM3U\n")
    }
    private val server: HttpServer = startLocalHlsServer(dir, totalSize = 4) { _, length -> ByteArray(length.toInt()) }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    @Test
    fun bindsLoopbackOnly() {
        assertTrue(server.address.address.isLoopbackAddress, "bound to ${server.address}")
    }

    @Test
    fun playlistUrlReachesTheServer() {
        val connection = URI(server.playlistUrl("index.m3u8")).toURL().openConnection() as HttpURLConnection
        try {
            assertEquals(200, connection.responseCode)
            assertEquals("#EXTM3U\n", connection.inputStream.readBytes().decodeToString())
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun refusesConnectionsOnNonLoopbackInterfaces() {
        val lanAddresses = NetworkInterface.networkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }
        assumeTrue("no non-loopback interface on this host", lanAddresses.isNotEmpty())
        for (address in lanAddresses) {
            assertFailsWith<IOException>("accepted on $address") {
                Socket().use { it.connect(InetSocketAddress(address, server.address.port), 1_000) }
            }
        }
    }
}
