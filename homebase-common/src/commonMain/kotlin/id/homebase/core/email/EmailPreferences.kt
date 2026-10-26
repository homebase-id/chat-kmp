package id.homebase.core.email

import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.ui.navigation.BottomBarDefaultsMigration
import id.homebase.core.vault.BiometricSession
import kotlinx.coroutines.CoroutineScope
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
 * The biometric session is the same [BiometricSession] Vault uses: this app holds the identity's
 * mail keys and app passwords, the same class of secret the Vault gate exists for.
 */
class EmailPreferences(
    private val databaseManager: DatabaseManager,
    private val clock: Clock = Clock.System,
    private val migrationScope: CoroutineScope? = null,
) {

    private val keyValue get() = databaseManager.keyValue

    private val _iconVisible = MutableStateFlow(readIconVisible())
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

    private val session = BiometricSession(clock, BACKGROUND_THRESHOLD_MS)

    val isEmailScreenActive: Boolean get() = session.isScreenActive

    fun setEmailScreenActive(active: Boolean) {
        // Otherwise leaving a still-locked screen would stamp a fresh action and revive the session.
        if (!session.isScreenActive && active) session.expireIfIdle()
        if (session.isScreenActive && !active) session.recordUserAction()
        session.setScreenActive(active)
    }

    fun requiresUnlock(): Boolean = _biometricsEnabled.value && !isAuthSessionValid()

    fun reset() {
        _iconVisible.value = readIconVisible()
        _biometricsEnabled.value = readBoolean(BIOMETRICS_KEY, default = true)
        _serverSupportsMail.value = readBooleanOrNull(SERVER_SUPPORTS_MAIL_KEY)
        session.reset()
    }

    fun recordAuthSuccess() = session.recordAuthSuccess()

    fun recordAppBackgrounded() = session.recordAppBackgrounded()

    fun recordAppForegrounded() = session.recordAppForegrounded()

    fun recordUserAction() = session.recordUserAction()

    fun isAuthSessionValid(): Boolean = session.isAuthSessionValid()

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

    private fun readIconVisible(): Boolean =
        BottomBarDefaultsMigration.resolveIconVisible(databaseManager, ICON_VISIBLE_KEY, migrationScope)

    private fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    companion object {
        // Namespace 0a07xx. 0a01 Vault, 0a02 Moments, 0a03 Location, 0a04 Contact Book,
        // 0a05 ServerIpStore, 0a06 EventReminder. 0a0701 is the retired ACTIVATED slot —
        // activation is the drive's mount state — and 0a0704 the retired mail-client choice —
        // Thunderbird is the only client we support — so both stay unused.
        val ICON_VISIBLE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0702")
        val BIOMETRICS_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0703")
        val SERVER_SUPPORTS_MAIL_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0705")

        // Deliberately longer than Vault's 30s: setting up a mail client means constant app switching.
        private const val BACKGROUND_THRESHOLD_MS = 5 * 60 * 1000L
    }
}
