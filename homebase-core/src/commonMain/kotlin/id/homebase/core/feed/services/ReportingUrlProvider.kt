package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Each identity publishes its own abuse-reporting endpoint at the unauthenticated
// `https://{odinId}/config/reporting`, so a report lands with whoever hosts the author rather than with us.
// Any failure, or a published value that isn't an absolute https URL (a hosting cluster can publish a
// placeholder), resolves to [DEFAULT_REPORT_URL].
class ReportingUrlProvider(private val httpClient: HttpClient) {

    @Serializable
    private data class ReportingConfig(val url: String? = null)

    suspend fun reportUrlFor(odinId: OdinId): String =
        runCatching {
            val body = httpClient.get("https://${odinId.domainName}/config/reporting").bodyAsText()
            json.decodeFromString<ReportingConfig>(body).url?.takeIf(::isAbsoluteHttps)
        }
            .onFailure { Logger.i(tag = TAG) { "no reporting config for $odinId: ${it.message}" } }
            .getOrNull()
            ?: DEFAULT_REPORT_URL

    private fun isAbsoluteHttps(url: String): Boolean =
        runCatching { Url(url) }.getOrNull()?.let { it.protocol == URLProtocol.HTTPS && it.host.isNotEmpty() } == true

    companion object {
        private const val TAG = "ReportingUrl"

        /** The web client's fallback — keep the two in step. */
        const val DEFAULT_REPORT_URL = "https://ravenhosting.cloud/report"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
