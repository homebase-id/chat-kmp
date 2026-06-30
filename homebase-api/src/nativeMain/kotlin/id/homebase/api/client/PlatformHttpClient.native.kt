package id.homebase.api.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

/**
 * iOS/macOS native uses the Darwin engine, which validates TLS against the system
 * trust store (kept current by the OS). No extra roots needed — name the engine
 * and apply the shared config.
 */
internal actual fun createPlatformHttpClient(
    block: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(Darwin) { block() }
