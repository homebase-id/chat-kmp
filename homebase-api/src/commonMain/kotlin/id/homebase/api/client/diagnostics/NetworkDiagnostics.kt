package id.homebase.api.client.diagnostics

/** Outcome of a single layer in the [runNetworkDiagnostics] probe. */
enum class ProbeStatus { OK, FAIL, SKIPPED }

/**
 * One timed layer of the network probe (DNS / TCP / TLS / HTTP ping). [durationMs] is null when the
 * stage was skipped. [detail] is human-readable diagnostic text (resolved IP, HTTP status, error
 * message) — it is dynamic data, not a localizable label.
 */
data class ProbeStage(
    val name: String,
    val status: ProbeStatus,
    val durationMs: Long?,
    val detail: String,
)

/**
 * Result of a layered connectivity probe against the owner server, used by the developer-menu
 * Network Status panel to tell *which* layer is broken (DNS vs route vs TLS vs the server) rather
 * than a single online/offline bit.
 *
 * [capturedIp] is the address the live DNS stage resolved (null when DNS failed) — the caller
 * persists it so a later run can fall back to it. [usedFallbackIp] is non-null when live DNS failed
 * and the probe reused a previously-persisted last-known IP (SNI is still the hostname, so TLS/vhost
 * routing stay correct). [supported] is false on the non-Android v1 actuals, which return a single
 * SKIPPED stage.
 */
data class NetworkDiagnostics(
    val hostname: String,
    val stages: List<ProbeStage>,
    val capturedIp: String?,
    val usedFallbackIp: String?,
    val captivePortalSuspected: Boolean,
    val supported: Boolean,
)

/**
 * Run a layered DNS → TCP → TLS → HTTP-ping probe against [hostname] (the owner server), timing each
 * layer independently. When live DNS resolution fails and [lastKnownIp] is non-null, the probe
 * reuses that IP for the TCP/TLS/HTTP layers **while keeping SNI = hostname** (so the server's cert
 * validates and multi-tenant routing still works). Never throws — failures are reported per-stage.
 *
 * v1 is implemented on Android only (the app's OkHttp/javax stack). Other platforms return
 * [NetworkDiagnostics.supported] = false.
 */
expect suspend fun runNetworkDiagnostics(hostname: String, lastKnownIp: String?): NetworkDiagnostics

/** Shared result for the non-Android v1 actuals: a single SKIPPED stage flagged unsupported. */
internal fun unsupportedDiagnostics(hostname: String): NetworkDiagnostics = NetworkDiagnostics(
    hostname = hostname,
    stages = listOf(ProbeStage("Platform", ProbeStatus.SKIPPED, null, "Network diagnostics is Android-only in v1")),
    capturedIp = null,
    usedFallbackIp = null,
    captivePortalSuspected = false,
    supported = false,
)
