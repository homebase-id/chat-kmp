package id.homebase.api.client

import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

object HttpClientProvider {
    fun create(): HttpClient {
        return HttpClient {
            install(ContentNegotiation) {
                json(OdinSystemSerializer.json)
            }

            install(ContentEncoding) {
                gzip(quality = 1.0f)
                deflate(quality = 0.9f)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 5 * 60_000   // 5 minutes
                connectTimeoutMillis = 30_000        // 30 seconds
                socketTimeoutMillis = 5 * 60_000     // 5 minutes
            }
        }
    }
}
