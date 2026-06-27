package id.homebase.auth.login

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// NOTE: kept byte-for-byte identical to CertificateProbe.android.kt — Android and desktop both
// use javax.net.ssl, but the KMP hierarchy gives them no shared parent source set, so the
// implementation is duplicated. Change both together.
internal actual suspend fun probeCertificateInfo(host: String): String? =
    withContext(Dispatchers.IO) {
        withTimeoutOrNull(8_000) {
            runCatching {
                // Platform default trust manager. We DO NOT trust-all — we still validate; we
                // only record the presented chain in the callback (which runs before any
                // rejection), so an untrusted intercepting cert is captured AND still rejected.
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)
                val defaultTm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()

                var presented: Array<X509Certificate>? = null
                val capturingTm = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                        presented = chain
                        defaultTm.checkServerTrusted(chain, authType) // still validates; may throw
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTm.acceptedIssuers
                }

                val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capturingTm), null) }
                (ctx.socketFactory.createSocket() as SSLSocket).use { socket ->
                    socket.sslParameters = socket.sslParameters.apply {
                        serverNames = listOf(SNIHostName(host))
                    }
                    socket.connect(InetSocketAddress(host, 443), 6_000)
                    socket.soTimeout = 6_000
                    // Triggers checkServerTrusted (records the chain), then may throw on an
                    // untrusted cert — exactly the case we want to report. No app data is sent.
                    runCatching { socket.startHandshake() }
                }

                val chain = presented
                if (chain.isNullOrEmpty()) {
                    null
                } else {
                    val leaf = chain.first()
                    // The roots THIS device trusts (the system store the app uses) — a local
                    // query, no network. The chain anchors on the issuer of its top cert (or the
                    // top cert itself, if the server sent the self-signed root). "MISSING" means
                    // the device's trust store lacks the needed root — and we name it: e.g.
                    // [ISRG Root X2] for a genuine Let's Encrypt cert an old/OEM Android doesn't
                    // trust (fix is ours: broaden the chain / bundle the root), vs an
                    // interceptor's own CA like [AdGuard Personal CA] (fix is theirs: turn it off).
                    val trustedSubjects = defaultTm.acceptedIssuers.asSequence()
                        .map { it.subjectX500Principal.name }
                        .toSet()
                    val top = chain.last()
                    val anchorDN = top.issuerX500Principal.name
                    val anchored = anchorDN in trustedSubjects ||
                        top.subjectX500Principal.name in trustedSubjects
                    buildString {
                        append("certificate presented by ").append(host)
                        append(" was issued by [").append(leaf.issuerX500Principal.name).append("]")
                        append("; chain anchors to [").append(anchorDN).append("] — ")
                        append(if (anchored) "present" else "MISSING")
                        append(" in this device's ").append(trustedSubjects.size).append(" trusted roots")
                    }
                }
            }.onFailure {
                Logger.w(tag = "CertificateProbe") { "probe failed for $host: ${it.message}" }
            }.getOrNull()
        }
    }
