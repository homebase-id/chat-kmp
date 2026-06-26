package id.homebase.auth.login

import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get

/** Classification of the pre-login health ping against a candidate identity. */
sealed interface IdentityPingResult {
    /** HTTP 200 — a reachable Homebase identity; proceed with auth. */
    data object Ok : IdentityPingResult

    /**
     * The request never completed (offline, captive portal, DNS failure, connect/request
     * timeout, TLS error, connection refused) OR the server answered a 5xx. Either way it's
     * a "try again" condition that says nothing about whether the ID is a valid Homebase
     * identity, so the UI must not blame the ID. [detail] is the technical cause (exception
     * type + message, or the HTTP status) for the details toggle.
     */
    data class Unreachable(val detail: String) : IdentityPingResult

    /**
     * We reached a server over HTTPS and it answered a non-200, non-5xx status (404/403/…)
     * — a typo'd/wrong domain or a non-Homebase site. This is the genuine "are you sure it's
     * a Homebase ID?" case. [statusCode] is the HTTP status it answered with.
     */
    data class NotHomebase(val statusCode: Int) : IdentityPingResult
}

/**
 * Ping `https://<identity>/api/v2/health/ping` and classify the outcome so the login UI can
 * tell "couldn't reach you" apart from "that isn't a Homebase identity". The old code
 * collapsed both — plus every timeout/offline/TLS case — into a single accusatory
 * "are you sure it's a Homebase ID?" message.
 *
 * [httpClient] must have the `HttpTimeout` plugin installed (the production client does);
 * the timeouts mirror the previous inline values.
 */
internal suspend fun pingIdentity(httpClient: HttpClient, identity: OdinId): IdentityPingResult {
    return try {
        Logger.i(tag = "IdentityPing") { "Pinging https://$identity/api/v2/health/ping ..." }
        val response = httpClient.get("https://$identity/api/v2/health/ping") {
            timeout {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
            }
        }
        val code = response.status.value
        Logger.i(tag = "IdentityPing") { "Ping $identity -> $code" }
        when {
            code == 200 -> IdentityPingResult.Ok
            // 5xx means we DID reach the host (DNS + TLS + a server answered) — it's just
            // erroring, usually transiently (a real Homebase backend mid-restart, overloaded,
            // or a 502/503/504 from a proxy in front of it). That's "try again", not a verdict
            // that the ID isn't Homebase. Only a non-5xx non-200 (404/403/…) earns NotHomebase.
            code in 500..599 -> IdentityPingResult.Unreachable(
                "HTTP $code (server error) from https://$identity/api/v2/health/ping"
            )
            else -> IdentityPingResult.NotHomebase(code)
        }
    } catch (t: Throwable) {
        val detail = "${t::class.simpleName ?: "Error"}: ${t.message ?: "(no message)"}"
        Logger.e(tag = "IdentityPing") { "Ping failed for $identity: $detail" }
        IdentityPingResult.Unreachable(detail)
    }
}
