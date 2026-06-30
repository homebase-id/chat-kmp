package id.homebase.api.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android uses the OkHttp engine, which validates TLS against the OS trust store.
 * Android keeps its CA set current via Play system updates, so no extra roots are
 * needed here — we just name the engine and apply the shared config.
 */
internal actual fun createPlatformHttpClient(
    block: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(OkHttp) { block() }
