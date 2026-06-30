package id.homebase.api.client

import co.touchlab.kermit.Logger
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * Emits one Kermit Info line per outbound request (host + path only — the query
 * string is omitted because it can carry tokens/IDs). The installed
 * `CrashlyticsLogWriter` mirrors Info logs as Crashlytics breadcrumbs, so the
 * last few HTTP requests appear in the "Logs" section of any crash report. This
 * is the attribution we lacked for the `UnknownHostException` crash: a suspended
 * network call's stack has no app frames, so without this breadcrumb the report
 * could not say which endpoint/host was in flight.
 */
internal val HttpBreadcrumbPlugin = createClientPlugin("HttpBreadcrumb") {
    onRequest { request, _ ->
        Logger.i(tag = "HttpIO") {
            "→ ${request.method.value} ${request.url.host}${request.url.encodedPathSegments.joinToString("/")}"
        }
    }
}

object HttpClientProvider {
    fun create(): HttpClient {
        return createPlatformHttpClient {
            install(HttpBreadcrumbPlugin)

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
