package id.homebase.api.client.diagnostics

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * Process-global bridge so the platform OkHttp `EventListener` — built inside
 * `createPlatformHttpClient`, which takes no DI params — can reach the [ServerIpCapture] singleton.
 * Same global-mutable engine seam as `HomebaseTrustManager.augmented` in the JVM HTTP client actual.
 * Set once in [ServerIpCapture]'s init; read (nullable) from the listener callback at connect time.
 */
object ServerIpCaptureRegistry {
    @Volatile
    var capture: ServerIpCapture? = null
}

/**
 * Records the owner server's IP whenever the real app completes a **validated TLS connection** to
 * it (fed by the Android OkHttp `EventListener` on `connectEnd`, which fires only on a
 * cert-validated success). Because SNI = hostname and the cert is validated against it, the
 * captured IP is provably the real owner server — so it's safe to reuse later as a
 * last-known-good fallback (see [ServerIpStore]). Only the owner host is persisted; connections to
 * peers/CDNs are ignored.
 */
class ServerIpCapture(
    private val store: ServerIpStore,
    private val credentialsManager: CredentialsManager,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    init {
        ServerIpCaptureRegistry.capture = this
    }

    /**
     * Called from the transport `EventListener` on a validated connect. Synchronous and cheap;
     * the actual KV write is a suspend call dispatched onto [scope]. No-op for any host that isn't
     * the current owner server.
     */
    fun onValidatedConnect(host: String, ip: String) {
        val owner = credentialsManager.credentialsFlow.value?.domain?.domainName ?: return
        if (!host.equals(owner, ignoreCase = true)) return
        scope.launch {
            runCatching { store.setLastKnownIp(owner, ip, nowMs()) }
                .onFailure { Logger.w(tag = "ServerIpCapture") { "persist failed: ${it.message}" } }
        }
    }
}
