package id.homebase.core.email

import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Device-local flags for the Email setup add-on, plus its biometric session clock.
 *
 * There is deliberately no `activated` flag: activation is the email drive's mount state, read
 * through `OptionalDriveActivation`. A local flag diverges across devices — see
 * ADDING_ADDON_APPS.md.
 *
 * The biometric session mirrors [id.homebase.core.vault.VaultPreferences] because this app holds
 * the identity's mail keys and app passwords, which is the same class of secret the Vault gate
 * exists for. (Both copies are a candidate for extraction — see the follow-up in the plan.)
 */
class EmailPreferences(
    private val databaseManager: DatabaseManager,
    private val clock: Clock = Clock.System,
) {

    private val keyValue get() = databaseManager.keyValue

    private val _iconVisible = MutableStateFlow(readBoolean(ICON_VISIBLE_KEY, default = true))
    val iconVisible: StateFlow<Boolean> = _iconVisible.asStateFlow()

    private val _biometricsEnabled = MutableStateFlow(readBoolean(BIOMETRICS_KEY, default = true))
    val biometricsEnabled: StateFlow<Boolean> = _biometricsEnabled.asStateFlow()

    /**
     * The last answer this host gave to "do you run mail at all" — null until one has been heard.
     *
     * Cached so the toolbar can decide on the first frame. The status call itself runs on every
     * launch regardless (EmailViewModel asks as soon as credentials exist), so this saves no
     * request; what it saves is an icon that pops in a second late, or worse, pops in and then
     * vanishes on a host without mail.
     */
    private val _serverSupportsMail = MutableStateFlow(readBooleanOrNull(SERVER_SUPPORTS_MAIL_KEY))
    val serverSupportsMail: StateFlow<Boolean?> = _serverSupportsMail.asStateFlow()

    // In-memory biometric session tracking — not persisted, resets on app restart.
    private var lastAuthTimeMs: Long = 0L
    private var lastBackgroundTimeMs: Long = 0L
    private var lastActionTimeMs: Long = 0L

    var isEmailScreenActive: Boolean = false
        private set

    fun setEmailScreenActive(active: Boolean) {
        isEmailScreenActive = active
    }

    fun reset() {
        _iconVisible.value = readBoolean(ICON_VISIBLE_KEY, default = true)
        _biometricsEnabled.value = readBoolean(BIOMETRICS_KEY, default = true)
        _serverSupportsMail.value = readBooleanOrNull(SERVER_SUPPORTS_MAIL_KEY)
        lastAuthTimeMs = 0L
        lastBackgroundTimeMs = 0L
        lastActionTimeMs = 0L
        isEmailScreenActive = false
    }

    fun recordAuthSuccess() {
        val now = clock.now().toEpochMilliseconds()
        lastAuthTimeMs = now
        lastActionTimeMs = now
    }

    fun recordAppBackgrounded() {
        val now = clock.now().toEpochMilliseconds()
        // The biometric prompt itself stops the Activity briefly around a successful unlock on
        // some devices; that churn is not a genuine app-background.
        if (lastAuthTimeMs != 0L && now - lastAuthTimeMs in 0..POST_AUTH_SETTLE_MS) return
        lastBackgroundTimeMs = now
    }

    fun recordUserAction() {
        lastActionTimeMs = clock.now().toEpochMilliseconds()
    }

    fun isAuthSessionValid(): Boolean {
        if (lastAuthTimeMs == 0L) return false
        val now = clock.now().toEpochMilliseconds()
        if (lastBackgroundTimeMs > lastAuthTimeMs &&
            now - lastBackgroundTimeMs > BACKGROUND_THRESHOLD_MS
        ) return false
        if (!isEmailScreenActive && now - lastActionTimeMs > AUTH_SESSION_DURATION_MS) return false
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

    suspend fun setServerSupportsMail(value: Boolean) {
        if (_serverSupportsMail.value == value) return
        keyValue.upsertValue(SERVER_SUPPORTS_MAIL_KEY, encode(value))
        _serverSupportsMail.value = value
    }

    /** Null means "never asked", which is not the same answer as "no". */
    private fun readBooleanOrNull(key: Uuid): Boolean? {
        val bytes: ByteArray = runCatching {
            keyValue.selectByKeyBootstrapSync(key) { _, data -> data }
        }.getOrNull() ?: return null
        return if (bytes.isEmpty()) null else bytes[0].toInt() != 0
    }

    private fun readBoolean(key: Uuid, default: Boolean): Boolean {
        // Bootstrap-only sync read — seeds the StateFlow at construction. See
        // KeyValueWrapper.selectByKeyBootstrapSync (commonMain has no runBlocking on wasmJs).
        val bytes: ByteArray = runCatching {
            keyValue.selectByKeyBootstrapSync(key) { _, data -> data }
        }.getOrNull() ?: return default
        return if (bytes.isEmpty()) default else bytes[0].toInt() != 0
    }

    private fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    companion object {
        // Namespace 0a07xx. 0a01 Vault, 0a02 Moments, 0a03 Location, 0a04 Contact Book,
        // 0a05 ServerIpStore, 0a06 EventReminder. 0a0701 is the retired ACTIVATED slot —
        // activation is the drive's mount state — and 0a0704 the retired mail-client choice —
        // Thunderbird is the only client we support — so both stay unused.
        val ICON_VISIBLE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0702")
        val BIOMETRICS_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0703")
        val SERVER_SUPPORTS_MAIL_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0705")

        private const val AUTH_SESSION_DURATION_MS = 5 * 60 * 1000L  // 5 minutes
        private const val BACKGROUND_THRESHOLD_MS = 30 * 1000L       // 30 seconds
        private const val POST_AUTH_SETTLE_MS = 2 * 1000L
    }
}
