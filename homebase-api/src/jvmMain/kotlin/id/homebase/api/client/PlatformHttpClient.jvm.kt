package id.homebase.api.client

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionPool
import okhttp3.Dispatcher

/**
 * Not CIO: it reuses a connection only for pipelined GET/HEAD without custom timeouts.
 * The JetBrains Runtime's `cacerts` lacks ISRG Root X2, so the engine also trusts [ExtraTrustRoots].
 */
internal actual fun createPlatformHttpClient(
    block: HttpClientConfig<*>.() -> Unit,
): HttpClient = desktopHttpClient(HomebaseTrustManager.augmented, block)

internal fun desktopHttpClient(
    trustManager: X509TrustManager?,
    block: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(OkHttp) {
    block()
    engine {
        // Ktor's OkHttp clients share one pool by default, and closing any client evicts it.
        val pool = ConnectionPool()
        config {
            connectionPool(pool)
            eventListener(ServerIpCaptureEventListener())
            dispatcher(Dispatcher().apply { maxRequests = MAX_REQUESTS_PER_HOST; maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
        }
        if (trustManager != null) {
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
            config { sslSocketFactory(tls.socketFactory, trustManager) }
        }
    }
}

internal object HomebaseTrustManager {
    /**
     * An [X509TrustManager] trusting the JRE default anchors plus [ExtraTrustRoots],
     * or `null` if it could not be built (in which case the engine keeps the JRE default and we
     * log — we never want a trust-store hiccup to silently disable TLS validation).
     */
    val augmented: X509TrustManager? by lazy { runCatching { build() }.getOrElse { it.log(); null } }

    /** The JRE's default trust anchors, exposed so tests can assert we ADD to (never replace) them. */
    internal fun jreDefaults(): X509TrustManager = defaultTrustManager()

    private fun build(): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

        // Seed with the JRE's default trust anchors so we ADD to, never replace, them.
        val defaults = defaultTrustManager()
        defaults.acceptedIssuers.forEachIndexed { i, cert -> ks.setCertificateEntry("default-$i", cert) }

        // Add the bundled roots the JRE may be missing.
        val cf = CertificateFactory.getInstance("X.509")
        ExtraTrustRoots.pems.forEachIndexed { i, pem ->
            val cert = pem.byteInputStream().use { cf.generateCertificate(it) }
            ks.setCertificateEntry("homebase-extra-$i", cert)
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        Logger.i(tag = "TLS") {
            "Trust manager augmented: ${defaults.acceptedIssuers.size} default + " +
                "${ExtraTrustRoots.pems.size} bundled root(s) = ${tm.acceptedIssuers.size} anchors"
        }
        return tm
    }

    private fun defaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun Throwable.log() =
        Logger.w(tag = "TLS", throwable = this) { "Failed to build augmented trust manager; using the JRE default" }
}

/**
 * CA roots bundled with the app because the launch JRE's `cacerts` may not carry
 * them. Add a root here (PEM) when an in-use identity's chain anchors at a CA the
 * JetBrains Runtime is missing.
 *
 * Targeted-but-extensible: bundling specific roots fixes today's failure
 * deterministically without trusting anything extra. If a future Let's Encrypt
 * root rotation reintroduces the symptom, append its PEM rather than reaching for
 * a broader (and riskier) "trust the OS keychain" scheme.
 */
internal object ExtraTrustRoots {
    // ISRG Root X2 (Let's Encrypt ECDSA root, valid 2020-09-04 .. 2040-09-17).
    // SHA-256: 69:72:9B:8E:15:A8:6E:FC:17:7A:57:AF:B7:17:1D:FC:64:AD:D2:8C:2F:CA:8C:F1:50:7E:34:45:3C:CB:14:70
    private const val ISRG_ROOT_X2 = """-----BEGIN CERTIFICATE-----
MIICGzCCAaGgAwIBAgIQQdKd0XLq7qeAwSxs6S+HUjAKBggqhkjOPQQDAzBPMQsw
CQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFyY2gg
R3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBYMjAeFw0yMDA5MDQwMDAwMDBaFw00
MDA5MTcxNjAwMDBaME8xCzAJBgNVBAYTAlVTMSkwJwYDVQQKEyBJbnRlcm5ldCBT
ZWN1cml0eSBSZXNlYXJjaCBHcm91cDEVMBMGA1UEAxMMSVNSRyBSb290IFgyMHYw
EAYHKoZIzj0CAQYFK4EEACIDYgAEzZvVn4CDCuwJSvMWSj5cz3es3mcFDR0HttwW
+1qLFNvicWDEukWVEYmO6gbf9yoWHKS5xcUy4APgHoIYOIvXRdgKam7mAHf7AlF9
ItgKbppbd9/w+kHsOdx1ymgHDB/qo0IwQDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0T
AQH/BAUwAwEB/zAdBgNVHQ4EFgQUfEKWrt5LSDv6kviejM9ti6lyN5UwCgYIKoZI
zj0EAwMDaAAwZQIwe3lORlCEwkSHRhtFcP9Ymd70/aTSVaYgLXTWNLxBo1BfASdW
tL4ndQavEi51mI38AjEAi/V3bNTIZargCyzuFJ0nN6T5U6VR5CmD1/iQMVtCnwr1
/q4AaOeMSQ+2b1tbFfLn
-----END CERTIFICATE-----"""

    val pems: List<String> = listOf(ISRG_ROOT_X2)
}
