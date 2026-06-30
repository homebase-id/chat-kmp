package id.homebase.api.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * Builds an [HttpClient] on the platform's native engine, applying the shared
 * [block] configuration. Centralising engine selection here (rather than relying
 * on Ktor's classpath auto-resolution at each `HttpClient { }` call site) gives
 * each platform a single place to attach engine-specific tweaks.
 *
 * The JVM/Desktop actual uses this to install a TLS trust manager that augments
 * the runtime's default trust store with CA roots the bundled JRE may not carry
 * (see `PlatformHttpClient.jvm.kt`). Android/iOS/Web defer entirely to the OS
 * trust store, so their actuals just name the engine and apply [block] verbatim.
 */
internal expect fun createPlatformHttpClient(
    block: HttpClientConfig<*>.() -> Unit = {},
): HttpClient
