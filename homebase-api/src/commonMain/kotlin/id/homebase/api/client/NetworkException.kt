package id.homebase.api.client

import kotlinx.io.IOException

/**
 * Transport-level failure reaching the server — connect/socket/request timeout,
 * connection refused, DNS failure, or a dropped socket mid-read. Distinct from
 * [OdinApiException]'s HTTP-status subclasses ([NotFoundException], … ), which
 * mean the server *did* respond. [status] is `0` to signal "no HTTP response".
 *
 * The raw Ktor/transport exception is preserved as [cause] so a crash report or
 * log carries the real stack. Produced centrally by [OdinApiProviderBase.request]
 * / [OdinApiProviderBase.requestBytes] so every REST call surfaces connectivity
 * failures as one catchable, typed exception instead of a raw `IOException`.
 */
class NetworkException(
    override val cause: Throwable,
) : OdinApiException(
    status = 0,
    message = "Network failure: ${cause.message ?: cause::class.simpleName ?: "unknown"}",
)

/**
 * True when [this] (or anything in its [Throwable.cause] chain) is a transient
 * connectivity failure: a [NetworkException] from the API layer, or a raw
 * [IOException] from a network path that bypasses it (e.g. the WebSocket).
 *
 * Used by the platform uncaught-exception handlers to keep a dropped connection
 * from killing the process — a transient network error that leaks to a scope
 * without its own `CoroutineExceptionHandler` is recorded as a non-fatal rather
 * than crashing. Non-network failures return `false` and stay fatal. Walks the
 * cause chain defensively (guards against a cyclic chain).
 */
fun Throwable.isTransientNetworkFailure(): Boolean {
    val seen = HashSet<Throwable>()
    var cur: Throwable? = this
    while (cur != null && seen.add(cur)) {
        if (cur is NetworkException || cur is IOException || cur.isConnectTimeNetworkType()) return true
        cur = cur.cause
    }
    return false
}

/**
 * True when [this] is an engine connect-time failure that is NOT an [IOException] and thus
 * bypasses the IOException-shaped catch/classify paths. Ktor CIO (Desktop/JVM) throws
 * `java.nio.channels.UnresolvedAddressException` (an `IllegalArgumentException`, not an
 * `IOException`) on DNS failure / no network — the Desktop variant of the OkHttp
 * `UnknownHostException` already covered above.
 *
 * Matched by qualified class name because this code is in `commonMain`, which cannot
 * reference the JVM-only type. Mirrors [isMlKitTeardownFailure]'s class-name approach. The
 * match is narrow (the specific class name, not its `IllegalArgumentException` supertype) to
 * avoid masking unrelated bugs.
 */
private fun Throwable.isConnectTimeNetworkType(): Boolean {
    val name = this::class.qualifiedName?.lowercase() ?: return false
    return "unresolvedaddressexception" in name
}
