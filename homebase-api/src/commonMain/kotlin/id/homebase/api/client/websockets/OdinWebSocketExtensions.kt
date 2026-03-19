package id.homebase.api.client.websockets

import io.ktor.client.HttpClientConfig

expect fun HttpClientConfig<*>.installWebSockets()
