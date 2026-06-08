package id.homebase.api.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

/**
 * Web (Js/browser fetch) cannot report upload progress at all and exposes no `SO_SNDBUF` lever,
 * so this is an honest no-op cap: [sendBufferBytes] is ignored and the client is identical to the
 * shared one. Real web upload progress would require an XMLHttpRequest path (deferred).
 */
actual fun createUploadHttpClient(sendBufferBytes: Int): HttpClient =
    HttpClient(Js) {
        applyOdinDefaults()
    }
