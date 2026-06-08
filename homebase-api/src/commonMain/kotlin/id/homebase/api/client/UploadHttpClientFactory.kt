package id.homebase.api.client

import io.ktor.client.HttpClient

/**
 * Builds an HTTP client whose TCP socket send buffer (`SO_SNDBUF`) is capped to
 * [sendBufferBytes], used only for the multipart drive-upload path.
 *
 * ## Why
 * A curated image often fits entirely inside the OS TCP send buffer (Android `tcp_wmem`
 * autotunes to several MB), so Ktor's `onUpload` — which counts bytes *accepted into the
 * socket*, not bytes on the wire — reports 100% the instant the kernel swallows the payload.
 * The upload then drains over the wire with no further progress, surfacing as a long
 * "Finalizing…" spinner. Capping `SO_SNDBUF` below the payload forces TCP backpressure so
 * `onUpload` tracks the wire and the percentage climbs honestly. See
 * [id.homebase.api.client.UploadHttpClientPool] for the per-payload sizing.
 *
 * ## Platform support (honest)
 * - **Android (OkHttp):** real — a delegating `SocketFactory` sets `setSendBufferSize` pre-connect.
 * - **iOS (Darwin), Desktop (CIO), Web (Js):** no-op cap. NSURLSession/CIO/fetch expose no
 *   `SO_SNDBUF` lever, so these return a client identical to the shared one; [sendBufferBytes]
 *   is ignored. (Darwin's `didSendBodyData` is often already more wire-accurate.)
 */
expect fun createUploadHttpClient(sendBufferBytes: Int): HttpClient
