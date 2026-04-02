package id.homebase.api.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

actual fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient {
    return HttpClient { block() }
}
