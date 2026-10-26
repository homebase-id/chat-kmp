package id.homebase.core.vault

import kotlin.time.Clock

// In-memory, not persisted, main-thread only (lifecycle callbacks, composable effects).
class BiometricSession(
    private val clock: Clock,
    private val backgroundThresholdMs: Long,
) {
    private var lastAuthTimeMs: Long = 0L
    private var lastBackgroundTimeMs: Long = 0L
    private var lastActionTimeMs: Long = 0L

    var isScreenActive: Boolean = false
        private set

    fun setScreenActive(active: Boolean) {
        isScreenActive = active
    }

    fun reset() {
        lastAuthTimeMs = 0L
        lastBackgroundTimeMs = 0L
        lastActionTimeMs = 0L
        isScreenActive = false
    }

    fun recordAuthSuccess() {
        val now = clock.now().toEpochMilliseconds()
        lastAuthTimeMs = now
        lastActionTimeMs = now
    }

    fun recordAppBackgrounded() {
        val now = clock.now().toEpochMilliseconds()
        // The biometric prompt itself stops the Activity briefly around a successful unlock on
        // some devices (e.g. MIUI); that churn is not a genuine app-background.
        if (lastAuthTimeMs != 0L && now - lastAuthTimeMs in 0..POST_AUTH_SETTLE_MS) return
        lastBackgroundTimeMs = now
    }

    fun recordAppForegrounded() {
        val now = clock.now().toEpochMilliseconds()
        if (lastBackgroundTimeMs != 0L && now - lastBackgroundTimeMs <= backgroundThresholdMs) {
            lastBackgroundTimeMs = 0L
        }
    }

    fun recordUserAction() {
        lastActionTimeMs = clock.now().toEpochMilliseconds()
    }

    fun expireIfIdle() {
        if (clock.now().toEpochMilliseconds() - lastActionTimeMs > AUTH_SESSION_DURATION_MS) {
            lastAuthTimeMs = 0L
        }
    }

    fun isAuthSessionValid(): Boolean {
        if (lastAuthTimeMs == 0L) return false
        val now = clock.now().toEpochMilliseconds()
        // Genuine app-background re-lock always applies, even mid-session.
        if (lastBackgroundTimeMs > lastAuthTimeMs && now - lastBackgroundTimeMs > backgroundThresholdMs) {
            return false
        }
        // Idle auto-lock is suppressed while the user is inside the gated flow.
        if (!isScreenActive && now - lastActionTimeMs > AUTH_SESSION_DURATION_MS) return false
        return true
    }

    private companion object {
        const val AUTH_SESSION_DURATION_MS = 5 * 60 * 1000L
        const val POST_AUTH_SETTLE_MS = 2 * 1000L
    }
}
