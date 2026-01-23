package id.homebase.homebasekmppoc.prototype.lib.base

import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

object HttpClientProvider {
    fun create(): HttpClient {
        return HttpClient {
            install(ContentNegotiation) {
                json(OdinSystemSerializer.json)
            }
        }
    }
}
