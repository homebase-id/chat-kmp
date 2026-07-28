package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Resolves where "Report" should send the user for a given author.
 *
 * Each identity publishes its own abuse-reporting endpoint at the unauthenticated
 * `https://{odinId}/config/reporting` (shape `{"url": "..."}`), so a report lands with whoever
 * hosts the author rather than with us. Mirrors dotyoucore-js `useManageSocialFeed`'s
 * `getContentReportUrl`, including its fallback: any failure (offline, 404, malformed body,
 * identity that never configured one) resolves to [DEFAULT_REPORT_URL].
 */
class ReportingUrlProvider(private val httpClient: HttpClient) {

    @Serializable
    private data class ReportingConfig(val url: String? = null)

    suspend fun reportUrlFor(odinId: OdinId): String =
        runCatching {
            val body = httpClient.get("https://${odinId.domainName}/config/reporting").bodyAsText()
            json.decodeFromString<ReportingConfig>(body).url?.takeIf { it.isNotBlank() }
        }
            .onFailure { Logger.i(tag = TAG) { "no reporting config for $odinId: ${it.message}" } }
            .getOrNull()
            ?: DEFAULT_REPORT_URL

    companion object {
        private const val TAG = "ReportingUrl"

        /** The web client's fallback (`useManageSocialFeed.ts`) — keep the two in step. */
        const val DEFAULT_REPORT_URL = "https://ravenhosting.cloud/report"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
