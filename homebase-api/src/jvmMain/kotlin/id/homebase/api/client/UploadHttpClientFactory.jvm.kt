package id.homebase.api.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/**
 * Desktop (CIO) exposes no supported socket send-buffer config, so this is an honest no-op cap:
 * [sendBufferBytes] is ignored and the client is identical to the shared one. Revisit only if
 * desktop upload-progress honesty becomes a priority.
 */
actual fun createUploadHttpClient(sendBufferBytes: Int): HttpClient =
    HttpClient(CIO) {
        applyOdinDefaults()
    }
