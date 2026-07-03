package id.homebase.core.vault

import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.uuid.Uuid

class VaultPreferences(
    private val databaseManager: DatabaseManager,
    private val clock: Clock = Clock.System,
) {

    private val keyValue get() = databaseManager.keyValue

    private val _iconVisible = MutableStateFlow(readBoolean(ICON_VISIBLE_KEY, default = true))
    val iconVisible: StateFlow<Boolean> = _iconVisible.asStateFlow()

    private val _biometricsEnabled = MutableStateFlow(readBoolean(BIOMETRICS_KEY, default = true))
    val biometricsEnabled: StateFlow<Boolean> = _biometricsEnabled.asStateFlow()

    // In-memory biometric session tracking — not persisted, resets on app restart.
    // All reads/writes are confined to the main thread (lifecycle callbacks, composable effects).
    private var lastAuthTimeMs: Long = 0L
    private var lastBackgroundTimeMs: Long = 0L
    private var lastActionTimeMs: Long = 0L

    var isVaultScreenActive: Boolean = false
        private set

    fun setVaultScreenActive(active: Boolean) {
        isVaultScreenActive = active
    }

    fun reset() {
        _iconVisible.value = readBoolean(ICON_VISIBLE_KEY, default = true)
        _biometricsEnabled.value = readBoolean(BIOMETRICS_KEY, default = true)
        lastAuthTimeMs = 0L
        lastBackgroundTimeMs = 0L
        lastActionTimeMs = 0L
        isVaultScreenActive = false
    }

    fun recordAuthSuccess() {
        val now = clock.now().toEpochMilliseconds()
        lastAuthTimeMs = now
        lastActionTimeMs = now
    }

    fun recordAppBackgrounded() {
        lastBackgroundTimeMs = clock.now().toEpochMilliseconds()
    }

    fun recordUserAction() {
        lastActionTimeMs = clock.now().toEpochMilliseconds()
    }

    fun isAuthSessionValid(): Boolean {
        if (lastAuthTimeMs == 0L) return false
        val now = clock.now().toEpochMilliseconds()
        // (B) Genuine app-background re-lock — always applies, even mid-session.
        if (lastBackgroundTimeMs > lastAuthTimeMs &&
            now - lastBackgroundTimeMs > BACKGROUND_THRESHOLD_MS
        ) return false
        // (A) Idle auto-lock — suppressed while the user is actively inside the Vault
        // (any sub-screen). Uninterrupted foreground work never idle-locks; a real
        // background still re-locks via (B) above.
        if (!isVaultScreenActive && now - lastActionTimeMs > AUTH_SESSION_DURATION_MS) return false
        return true
    }

    suspend fun setIconVisible(value: Boolean) {
        if (_iconVisible.value == value) return
        keyValue.upsertValue(ICON_VISIBLE_KEY, encode(value))
        _iconVisible.value = value
    }

    suspend fun setBiometricsEnabled(value: Boolean) {
        if (_biometricsEnabled.value == value) return
        keyValue.upsertValue(BIOMETRICS_KEY, encode(value))
        _biometricsEnabled.value = value
    }

    private fun readBoolean(key: Uuid, default: Boolean): Boolean {
        // Bootstrap-only sync read — seeds the StateFlow at construction. See
        // KeyValueWrapper.selectByKeyBootstrapSync for the rationale (commonMain
        // has no runBlocking on wasmJs).
        val bytes: ByteArray = runCatching {
            keyValue.selectByKeyBootstrapSync(key) { _, data -> data }
        }.getOrNull() ?: return default
        return if (bytes.isEmpty()) default else bytes[0].toInt() != 0
    }

    private fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    companion object {
        val ICON_VISIBLE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0102")
        val BIOMETRICS_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0103")

        private const val AUTH_SESSION_DURATION_MS = 5 * 60 * 1000L  // 5 minutes
        private const val BACKGROUND_THRESHOLD_MS = 30 * 1000L       // 30 seconds
    }
}
