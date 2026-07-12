package id.homebase.api.client.diagnostics

import co.touchlab.kermit.Logger
import id.homebase.api.youauth.PingResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

private val pingJson = Json { ignoreUnknownKeys = true }

/**
 * Android probe. DNS/TCP/TLS use javax (like [id.homebase.auth.login.probeCertificateInfo]); the
 * HTTP ping uses a throwaway OkHttp client with a custom [Dns] that pins the chosen IP for the
 * hostname — so SNI + `Host` stay the hostname and the cert validates, exactly as the app's real
 * client would. The app's production client is untouched.
 */
actual suspend fun runNetworkDiagnostics(
    hostname: String,
    lastKnownIp: String?,
): NetworkDiagnostics = withContext(Dispatchers.IO) {
    val stages = mutableListOf<ProbeStage>()
    var capturedIp: String? = null
    var usedFallbackIp: String? = null
    var captivePortal = false

    // Stage 1 — DNS resolve
    val dnsStart = System.nanoTime()
    val resolved: List<String> = runCatching {
        InetAddress.getAllByName(hostname).mapNotNull { it.hostAddress }
    }.getOrElse { emptyList() }
    val dnsMs = elapsedMs(dnsStart)
    if (resolved.isNotEmpty()) {
        capturedIp = resolved.first()
        stages += ProbeStage("DNS resolve", ProbeStatus.OK, dnsMs, "$hostname → ${resolved.joinToString()}")
    } else {
        stages += ProbeStage("DNS resolve", ProbeStatus.FAIL, dnsMs, "No address associated with $hostname")
    }

    // Stage 2 — DNS-override fallback (only when live DNS failed)
    val targetIp: String? = resolved.firstOrNull() ?: lastKnownIp
    if (resolved.isEmpty()) {
        if (lastKnownIp != null) {
            usedFallbackIp = lastKnownIp
            stages += ProbeStage("DNS fallback", ProbeStatus.OK, null, "Reusing last-known IP $lastKnownIp")
        } else {
            stages += ProbeStage("DNS fallback", ProbeStatus.SKIPPED, null, "No last-known IP stored yet")
        }
    }

    if (targetIp == null) {
        stages += ProbeStage("TCP connect", ProbeStatus.SKIPPED, null, "No IP to connect to")
        stages += ProbeStage("TLS handshake", ProbeStatus.SKIPPED, null, "No IP to connect to")
        stages += ProbeStage("HTTP ping", ProbeStatus.SKIPPED, null, "No IP to connect to")
        return@withContext NetworkDiagnostics(hostname, stages, capturedIp, usedFallbackIp, captivePortal, supported = true)
    }

    // Stages 3 & 4 — TCP connect + TLS handshake (SNI = hostname, cert verified against hostname)
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
            return@withContext NetworkDiagnostics(hostname, stages, capturedIp, usedFallbackIp, captivePortal, supported = true)
        }

        val tlsStart = System.nanoTime()
        try {
            // Layer TLS over the already-connected socket, verifying the cert against `hostname`
            // (not the raw IP): autoClose=true, endpointIdentificationAlgorithm=HTTPS, SNI=hostname.
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            tls = (factory.createSocket(plain, hostname, HTTPS_PORT, true) as SSLSocket).apply {
                sslParameters = sslParameters.apply {
                    serverNames = listOf(SNIHostName(hostname))
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                soTimeout = TIMEOUT_MS
            }
            tls.startHandshake()
            val protocol = tls.session.protocol
            stages += ProbeStage("TLS handshake", ProbeStatus.OK, elapsedMs(tlsStart), "$protocol, SNI=$hostname")
        } catch (t: Throwable) {
            stages += ProbeStage("TLS handshake", ProbeStatus.FAIL, elapsedMs(tlsStart), describe(t))
            // Continue to the HTTP stage anyway — it uses its own client and may surface a
            // different (e.g. captive-portal) signal, but usually it will fail the same way.
        }
    } finally {
        runCatching { tls?.close() }
        runCatching { plain?.close() }
    }

    // Stage 5 — HTTP ping (SNI-preserving via custom Dns; redirects NOT followed so a captive
    // portal shows up as a 3xx instead of being silently chased).
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
    try {
        val request = Request.Builder()
            .url("https://$hostname/api/v2/health/ping")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { resp ->
            val code = resp.code
            val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
            val identity = runCatching { pingJson.decodeFromString<PingResponse>(body).identity }.getOrNull()
            val httpMs = elapsedMs(httpStart)
            when {
                code == 200 && !identity.isNullOrBlank() ->
                    stages += ProbeStage("HTTP ping", ProbeStatus.OK, httpMs, "HTTP 200, identity=$identity")
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
        Logger.w(tag = TAG) { "HTTP ping failed for $hostname: ${t.message}" }
        stages += ProbeStage("HTTP ping", ProbeStatus.FAIL, elapsedMs(httpStart), describe(t))
    } finally {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    NetworkDiagnostics(hostname, stages, capturedIp, usedFallbackIp, captivePortal, supported = true)
}

private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

private fun describe(t: Throwable): String = "${t::class.simpleName ?: "Error"}: ${t.message ?: "(no message)"}"
