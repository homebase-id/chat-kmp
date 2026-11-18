package id.homebase.api.client

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

actual class StallingServer actual constructor() : AutoCloseable {
    private val server = ServerSocket().apply {
        receiveBufferSize = 16 * 1024
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    }
    @Volatile private var accepted: Socket? = null

    init {
        thread(isDaemon = true) { runCatching { accepted = server.accept() } }
    }

    actual val port: Int get() = server.localPort

    actual override fun close() {
        accepted?.close()
        server.close()
    }
}
