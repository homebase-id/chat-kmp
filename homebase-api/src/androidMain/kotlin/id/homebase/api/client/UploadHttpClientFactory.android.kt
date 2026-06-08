package id.homebase.api.client

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Delegating [SocketFactory] that caps each socket's `SO_SNDBUF` to [sendBufferBytes].
 *
 * OkHttp opens connections via the no-arg [createSocket] (an *unconnected* socket it then
 * connects), so setting the send buffer there applies **pre-connect** — required to influence
 * TCP window-scaling negotiation, and it disables Linux send-buffer autotuning for that socket
 * (which is exactly what forces the backpressure that makes Ktor's `onUpload` track the wire).
 * The connected overloads are delegated and set defensively even though OkHttp doesn't use them.
 *
 * `setSendBufferSize` is a hint the kernel clamps to `net.core.wmem_max` (>= 4 MiB on Android),
 * so the [UploadHttpClientPool] range (64 KiB..2 MiB) is honored.
 */
private class SndBufSocketFactory(
    private val sendBufferBytes: Int,
    private val delegate: SocketFactory = SocketFactory.getDefault(),
) : SocketFactory() {

    private fun Socket.capped(): Socket = apply {
        // Log requested vs. actually-applied SO_SNDBUF so we can confirm the cap took effect.
        // Linux typically reports back ~2x the requested value (kernel bookkeeping); what matters
        // is that it's near our request and NOT the multi-MB autotuned default. If onUpload still
        // reaches 100% fast while this is small, the bytes genuinely left the wire fast → the
        // remaining wait is server-side.
        val applied = runCatching {
            sendBufferSize = sendBufferBytes
            sendBufferSize
        }.getOrElse { -1 }
        Logger.i(tag = "UploadSndBuf") {
            "createSocket: requested=$sendBufferBytes appliedSendBuffer=$applied"
        }
    }

    override fun createSocket(): Socket = delegate.createSocket().capped()

    override fun createSocket(host: String?, port: Int): Socket =
        delegate.createSocket(host, port).capped()

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).capped()

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        delegate.createSocket(host, port).capped()

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).capped()
}

actual fun createUploadHttpClient(sendBufferBytes: Int): HttpClient =
    HttpClient(OkHttp) {
        applyOdinDefaults()
        engine {
            config {
                socketFactory(SndBufSocketFactory(sendBufferBytes))
            }
        }
    }
