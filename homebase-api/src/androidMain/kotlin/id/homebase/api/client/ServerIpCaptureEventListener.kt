package id.homebase.api.client

import id.homebase.api.client.diagnostics.ServerIpCaptureRegistry
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Captures the owner server's IP on each validated TLS connect. `connectEnd` fires only after the
 * full connection — including the TLS handshake and cert validation — succeeds (failures go to
 * `connectFailed`), so the address here provably reached the real server. One instance, attached in
 * [createPlatformHttpClient], covers both the REST client and the notify WebSocket (both build
 * through that actual). Forwards to the process-global [ServerIpCaptureRegistry], which gates on the
 * owner host and does the persistence off-thread.
 */
internal class ServerIpCaptureEventListener : EventListener() {
    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        val host = call.request().url.host
        val ip = inetSocketAddress.address?.hostAddress ?: return
        ServerIpCaptureRegistry.capture?.onValidatedConnect(host, ip)
    }
}
