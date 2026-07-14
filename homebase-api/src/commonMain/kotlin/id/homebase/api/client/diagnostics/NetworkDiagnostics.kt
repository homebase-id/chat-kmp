package id.homebase.api.client.diagnostics

/** Outcome of a single layer in the [runNetworkDiagnostics] probe. */
enum class ProbeStatus { OK, FAIL, SKIPPED }

/** Which resolver produced the IP a ladder rung tried. */
enum class ResolutionSource { SystemDns, DoH, LastKnownIp }

/**
 * One timed layer of the connect probe (TCP / TLS / HTTP ping) for a given IP. [durationMs] is null
 * when the stage was skipped. [detail] is human-readable diagnostic text (HTTP status, error
 * message) — dynamic data, not a localizable label.
 */
data class ProbeStage(
    val name: String,
    val status: ProbeStatus,
    val durationMs: Long?,
    val detail: String,
)

/**
 * One rung of the resolution ladder: a [source] resolver was asked for [hostname]'s IP, and if it
 * produced one ([resolvedIp]) the connect [stages] (TCP → TLS → HTTP ping) were run against it.
 * [resolveStatus]/[resolveDetail]/[resolveMs] describe the resolution attempt itself.
 */
data class ResolutionRung(
    val source: ResolutionSource,
    val resolvedIp: String?,
    val resolveStatus: ProbeStatus,
    val resolveDetail: String,
    val resolveMs: Long?,
    val stages: List<ProbeStage>,
)

/**
 * Result of the layered connectivity probe against the owner server, used by the developer-menu
 * Network Status panel to tell *which* layer (and which resolution path) is broken.
 *
 * The probe walks a resolution ladder — **System DNS → DoH → last-known IP** — and for each rung
 * that yields an IP, connects with SNI = hostname (so TLS/vhost routing stay correct) and pings.
 * It stops at the first rung whose ping reaches the server; [rungs] holds every rung attempted, so
 * a broken-DNS-but-server-fine case shows System DNS failing and DoH succeeding in sequence.
 * [supported] is false on the non-Android v1 actuals.
 */
data class NetworkDiagnostics(
    val hostname: String,
    val rungs: List<ResolutionRung>,
    val captivePortalSuspected: Boolean,
    val supported: Boolean,
)

/**
 * Run the resolution ladder against [hostname] (the owner server): System DNS → DoH → the
 * [lastKnownIp] fallback (null if none captured yet). Each rung that resolves an IP gets a
 * TCP → TLS(SNI=hostname) → HTTP-ping probe; the ladder stops at the first rung that reaches the
 * server. Never throws — failures are reported per-rung/per-stage.
 *
 * v1 is implemented on Android only. Other platforms return [NetworkDiagnostics.supported] = false.
 */
expect suspend fun runNetworkDiagnostics(hostname: String, lastKnownIp: String?): NetworkDiagnostics

/** Shared result for the non-Android v1 actuals: a single SKIPPED rung flagged unsupported. */
internal fun unsupportedDiagnostics(hostname: String): NetworkDiagnostics = NetworkDiagnostics(
    hostname = hostname,
    rungs = listOf(
        ResolutionRung(
            source = ResolutionSource.SystemDns,
            resolvedIp = null,
            resolveStatus = ProbeStatus.SKIPPED,
            resolveDetail = "Network diagnostics is Android-only in v1",
            resolveMs = null,
            stages = emptyList(),
        ),
    ),
    captivePortalSuspected = false,
    supported = false,
)
