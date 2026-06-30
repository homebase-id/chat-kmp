package id.homebase.api.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js

/**
 * Web uses the JS engine, which delegates to the browser's fetch/WebSocket stack
 * and therefore the browser's own trust store. No extra roots needed — name the
 * engine and apply the shared config.
 */
internal actual fun createPlatformHttpClient(
    block: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(Js) { block() }
