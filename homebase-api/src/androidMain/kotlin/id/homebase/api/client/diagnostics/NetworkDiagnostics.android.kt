package id.homebase.api.client.diagnostics

import co.touchlab.kermit.Logger
import id.homebase.api.youauth.PingResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private const val TAG = "NetDiag"
private const val TIMEOUT_MS = 8_000
private const val HTTPS_PORT = 443

private val diagJson = Json { ignoreUnknownKeys = true }

/** Cloudflare DoH JSON response (`{"Answer":[{"data":"1.2.3.4","type":1}]}`). type 1 = A record. */
@Serializable
private data class DohResponse(@SerialName("Answer") val answer: List<DohAnswer> = emptyList())

@Serializable
private data class DohAnswer(val data: String = "", val type: Int = 0)

private data class ResolveOutcome(val ip: String?, val status: ProbeStatus, val detail: String, val ms: Long?)

private data class ConnResult(val stages: List<ProbeStage>, val pingOk: Boolean, val captivePortal: Boolean)

/**
 * Android probe. Walks the resolution ladder System DNS → DoH → last-known IP; each rung that
 * yields an IP is connected with SNI = hostname (cert verified against the hostname) and pinged.
 * The DoH rung hits Cloudflare `1.1.1.1` by IP literal, so it works even when system DNS is broken.
 * Stops at the first rung whose ping reaches the server. The app's production client is untouched.
 */
actual suspend fun runNetworkDiagnostics(
    hostname: String,
    lastKnownIp: String?,
): NetworkDiagnostics = withContext(Dispatchers.IO) {
    val rungs = mutableListOf<ResolutionRung>()
    var captivePortal = false

    val ladder: List<Pair<ResolutionSource, suspend () -> ResolveOutcome>> = listOf(
        ResolutionSource.SystemDns to { resolveSystemDns(hostname) },
        ResolutionSource.DoH to { resolveDoH(hostname) },
        ResolutionSource.LastKnownIp to { resolveLastKnown(lastKnownIp) },
    )

    for ((source, resolver) in ladder) {
        val r = resolver()
        val conn = if (r.ip != null) probeConnection(hostname, r.ip) else ConnResult(emptyList(), false, false)
        if (conn.captivePortal) captivePortal = true
        rungs += ResolutionRung(source, r.ip, r.status, r.detail, r.ms, conn.stages)
        if (conn.pingOk) break
    }

    NetworkDiagnostics(hostname, rungs, captivePortal, supported = true)
}

/** Rung 1 — the OS resolver. */
private fun resolveSystemDns(hostname: String): ResolveOutcome {
    val t0 = System.nanoTime()
    val ips = runCatching { InetAddress.getAllByName(hostname).mapNotNull { it.hostAddress } }.getOrElse { emptyList() }
    val ms = elapsedMs(t0)
    return if (ips.isNotEmpty()) {
        ResolveOutcome(ips.first(), ProbeStatus.OK, "$hostname → ${ips.joinToString()}", ms)
    } else {
        ResolveOutcome(null, ProbeStatus.FAIL, "No address associated with $hostname", ms)
    }
}

/**
 * Rung 2 — DNS-over-HTTPS via Cloudflare, hit by IP literal (`https://1.1.1.1/dns-query`, JSON API)
 * so it never uses system DNS. TLS validates against Cloudflare's IP-SAN cert, so this resolves
 * even when the local resolver is broken/hijacked.
 */
private fun resolveDoH(hostname: String): ResolveOutcome {
    val t0 = System.nanoTime()
    val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout((TIMEOUT_MS * 2).toLong(), TimeUnit.MILLISECONDS)
        .build()
    return try {
        val request = Request.Builder()
            .url("https://1.1.1.1/dns-query?name=$hostname&type=A")
            .header("accept", "application/dns-json")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
            val ips = runCatching {
                diagJson.decodeFromString<DohResponse>(body).answer.filter { it.type == 1 }.map { it.data }
            }.getOrDefault(emptyList())
            val ms = elapsedMs(t0)
            if (resp.code == 200 && ips.isNotEmpty()) {
                ResolveOutcome(ips.first(), ProbeStatus.OK, "1.1.1.1 → ${ips.joinToString()}", ms)
            } else {
                ResolveOutcome(null, ProbeStatus.FAIL, "DoH HTTP ${resp.code}, ${ips.size} A record(s)", ms)
            }
        }
    } catch (t: Throwable) {
        Logger.w(tag = TAG) { "DoH failed for $hostname: ${t.message}" }
        ResolveOutcome(null, ProbeStatus.FAIL, describe(t), elapsedMs(t0))
    } finally {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }
}

/** Rung 3 — the production-captured last-known-good IP (null if the app hasn't connected yet). */
private fun resolveLastKnown(lastKnownIp: String?): ResolveOutcome =
    if (lastKnownIp != null) {
        ResolveOutcome(lastKnownIp, ProbeStatus.OK, "Stored IP $lastKnownIp", null)
    } else {
        ResolveOutcome(null, ProbeStatus.SKIPPED, "No last-known IP stored yet", null)
    }

/**
 * Connect to [targetIp]:443 and run TCP → TLS(SNI=[hostname]) → HTTP ping. The cert is verified
 * against the hostname (not the IP), and the HTTP ping pins [targetIp] via a custom OkHttp [Dns]
 * while keeping the URL/host = [hostname] — so SNI + Host stay the hostname and the cert validates,
 * exactly as the real client would.
 */
private fun probeConnection(hostname: String, targetIp: String): ConnResult {
    val stages = mutableListOf<ProbeStage>()

    // Stages 1 & 2 — TCP connect + TLS handshake.
    var plain: Socket? = null
    var tls: SSLSocket? = null
    try {
        plain = Socket()
        val tcpStart = System.nanoTime()
        try {
            plain.connect(InetSocketAddress(InetAddress.getByName(targetIp), HTTPS_PORT), TIMEOUT_MS)
            stages += ProbeStage("TCP connect", ProbeStatus.OK, elapsedMs(tcpStart), "$targetIp:$HTTPS_PORT")
        } catch (t: Throwable) {
            stages += ProbeStage("TCP connect", ProbeStatus.FAIL, elapsedMs(tcpStart), describe(t))
            stages += ProbeStage("TLS handshake", ProbeStatus.SKIPPED, null, "TCP did not connect")
            stages += ProbeStage("HTTP ping", ProbeStatus.SKIPPED, null, "TCP did not connect")
            return ConnResult(stages, pingOk = false, captivePortal = false)
        }

        val tlsStart = System.nanoTime()
        try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            tls = (factory.createSocket(plain, hostname, HTTPS_PORT, true) as SSLSocket).apply {
                sslParameters = sslParameters.apply {
                    serverNames = listOf(SNIHostName(hostname))
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                soTimeout = TIMEOUT_MS
            }
            tls.startHandshake()
            stages += ProbeStage("TLS handshake", ProbeStatus.OK, elapsedMs(tlsStart), "${tls.session.protocol}, SNI=$hostname")
        } catch (t: Throwable) {
            stages += ProbeStage("TLS handshake", ProbeStatus.FAIL, elapsedMs(tlsStart), describe(t))
        }
    } finally {
        runCatching { tls?.close() }
        runCatching { plain?.close() }
    }

    // Stage 3 — HTTP ping (SNI-preserving via custom Dns; redirects not followed so a captive
    // portal shows as a 3xx rather than being chased).
    val client = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(h: String): List<InetAddress> =
                if (h.equals(hostname, ignoreCase = true)) listOf(InetAddress.getByName(targetIp))
                else Dns.SYSTEM.lookup(h)
        })
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout((TIMEOUT_MS * 2).toLong(), TimeUnit.MILLISECONDS)
        .build()
    val httpStart = System.nanoTime()
    var pingOk = false
    var captivePortal = false
    try {
        val request = Request.Builder()
            .url("https://$hostname/api/v2/health/ping")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { resp ->
            val code = resp.code
            val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
            val identity = runCatching { diagJson.decodeFromString<PingResponse>(body).identity }.getOrNull()
            val httpMs = elapsedMs(httpStart)
            when {
                code == 200 && !identity.isNullOrBlank() -> {
                    pingOk = true
                    stages += ProbeStage("HTTP ping", ProbeStatus.OK, httpMs, "HTTP 200, identity=$identity")
                }
                code == 200 -> {
                    captivePortal = true
                    stages += ProbeStage("HTTP ping", ProbeStatus.FAIL, httpMs, "HTTP 200 but no server identity — captive portal likely")
                }
                code in 300..399 -> {
                    captivePortal = true
                    val loc = resp.header("Location")
                    stages += ProbeStage("HTTP ping", ProbeStatus.FAIL, httpMs, "HTTP $code redirect${if (loc != null) " → $loc" else ""} — captive portal likely")
                }
                else -> stages += ProbeStage("HTTP ping", ProbeStatus.FAIL, httpMs, "HTTP $code")
            }
        }
    } catch (t: Throwable) {
        Logger.w(tag = TAG) { "HTTP ping failed for $hostname@$targetIp: ${t.message}" }
        stages += ProbeStage("HTTP ping", ProbeStatus.FAIL, elapsedMs(httpStart), describe(t))
    } finally {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    return ConnResult(stages, pingOk, captivePortal)
}

private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

private fun describe(t: Throwable): String = "${t::class.simpleName ?: "Error"}: ${t.message ?: "(no message)"}"
