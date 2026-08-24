package id.homebase.api.client

import io.ktor.http.Headers
import kotlin.concurrent.Volatile

/**
 * The CDN base URL every Odin host stamps on every response — successes and errors alike — so any
 * call teaches us the edge that media can be routed through.
 *
 * One worker serves the fleet, so the value learned from the user's own host also addresses a
 * followed identity's. ponytail: if identities ever point at different workers this has to be read
 * from the author's host instead of shared globally.
 */
object CdnAdvertisement {
    private const val HEADER = "x-odin-cdn-payload"

    @Volatile
    var baseUrl: String? = null
        private set

    fun observe(headers: Headers) {
        if (baseUrl != null) return
        baseUrl = headers[HEADER]?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return
    }

    fun reset() {
        baseUrl = null
    }
}
