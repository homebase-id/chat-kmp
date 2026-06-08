package id.homebase.api.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * iOS (Darwin/NSURLSession) exposes no `SO_SNDBUF` lever, so this is an honest no-op cap:
 * [sendBufferBytes] is ignored and the client is identical to the shared one. Darwin's
 * `onUpload` (driven by `didSendBodyData`) is often already more wire-accurate than OkHttp's
 * socket-fill count; measure before pursuing any iOS-specific work.
 */
actual fun createUploadHttpClient(sendBufferBytes: Int): HttpClient =
    HttpClient(Darwin) {
        applyOdinDefaults()
    }
