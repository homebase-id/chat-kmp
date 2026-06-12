package id.homebase.core.contactbook

import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Device-local runtime flags for the Contact Book add-on, backed by the
 * encrypted key/value store. Modeled on
 * [id.homebase.core.vault.VaultPreferences] but without an `activated` flag —
 * the Contacts drive is mandatory (always mounted/synced), so there is no
 * extend-permissions / drive-activation step. [onboardingComplete] gates the
 * first-run intro screen; [iconVisible] toggles the bottom-bar icon;
 * [biometricsEnabled] gates the screen behind device biometrics.
 *
 * UUID namespace: `0a04xx` (claimed by Contact Book — see ADDING_ADDON_APPS.md).
 */
class ContactBookPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    private val _onboardingComplete = MutableStateFlow(readBoolean(ONBOARDING_KEY, default = false))
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()

    private val _iconVisible = MutableStateFlow(readBoolean(ICON_VISIBLE_KEY, default = true))
    val iconVisible: StateFlow<Boolean> = _iconVisible.asStateFlow()

    // Biometrics default OFF (per the v1 decision): contacts are a frequently-used
    // screen, so the lock is opt-in via Settings rather than on by default.
    private val _biometricsEnabled = MutableStateFlow(readBoolean(BIOMETRICS_KEY, default = false))
    val biometricsEnabled: StateFlow<Boolean> = _biometricsEnabled.asStateFlow()

    // In-memory biometric session tracking — not persisted, resets on app restart.
    private var lastAuthTimeMs: Long = 0L
    private var lastBackgroundTimeMs: Long = 0L
    private var lastActionTimeMs: Long = 0L

    fun reset() {
        _onboardingComplete.value = readBoolean(ONBOARDING_KEY, default = false)
        _iconVisible.value = readBoolean(ICON_VISIBLE_KEY, default = true)
        _biometricsEnabled.value = readBoolean(BIOMETRICS_KEY, default = false)
        lastAuthTimeMs = 0L
        lastBackgroundTimeMs = 0L
        lastActionTimeMs = 0L
    }

    fun recordAuthSuccess() {
        val now = Clock.System.now().toEpochMilliseconds()
        lastAuthTimeMs = now
        lastActionTimeMs = now
    }

    fun recordAppBackgrounded() {
        lastBackgroundTimeMs = Clock.System.now().toEpochMilliseconds()
    }

    fun recordUserAction() {
        lastActionTimeMs = Clock.System.now().toEpochMilliseconds()
    }

    fun isAuthSessionValid(): Boolean {
        if (lastAuthTimeMs == 0L) return false
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - lastActionTimeMs > AUTH_SESSION_DURATION_MS) return false
        if (lastBackgroundTimeMs > lastAuthTimeMs &&
            now - lastBackgroundTimeMs > BACKGROUND_THRESHOLD_MS
        ) return false
        return true
    }

    suspend fun setOnboardingComplete(value: Boolean) {
        if (_onboardingComplete.value == value) return
        keyValue.upsertValue(ONBOARDING_KEY, encode(value))
        _onboardingComplete.value = value
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
        val bytes: ByteArray = runCatching {
            keyValue.selectByKeyBootstrapSync(key) { _, data -> data }
        }.getOrNull() ?: return default
        return if (bytes.isEmpty()) default else bytes[0].toInt() != 0
    }

    private fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    companion object {
        val ONBOARDING_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0401")
        val ICON_VISIBLE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0402")
        val BIOMETRICS_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0403")

        private const val AUTH_SESSION_DURATION_MS = 5 * 60 * 1000L  // 5 minutes
        private const val BACKGROUND_THRESHOLD_MS = 30 * 1000L       // 30 seconds
    }
}
