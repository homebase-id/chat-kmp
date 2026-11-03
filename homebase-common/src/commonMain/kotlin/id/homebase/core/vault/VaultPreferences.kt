package id.homebase.core.vault

import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.ui.navigation.BottomBarDefaultsMigration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.uuid.Uuid

class VaultPreferences(
    private val databaseManager: DatabaseManager,
    private val clock: Clock = Clock.System,
    private val migrationScope: CoroutineScope? = null,
) {

    private val keyValue get() = databaseManager.keyValue

    private val _iconVisible = MutableStateFlow(readIconVisible())
    val iconVisible: StateFlow<Boolean> = _iconVisible.asStateFlow()

    private val _biometricsEnabled = MutableStateFlow(readBoolean(BIOMETRICS_KEY, default = true))
    val biometricsEnabled: StateFlow<Boolean> = _biometricsEnabled.asStateFlow()

    private val session = BiometricSession(clock, BACKGROUND_THRESHOLD_MS)

    val isVaultScreenActive: Boolean get() = session.isScreenActive

    // True while a system picker/camera is presented from the Vault. Leaving to a picker
    // stops the Activity, which must NOT count as a genuine app-background (it would re-lock
    // on return). Set true before launching, cleared when the picker is handled.
    var isPickerActive: Boolean = false
        private set

    fun setVaultScreenActive(active: Boolean) {
        session.setScreenActive(active)
    }

    fun setPickerActive(active: Boolean) {
        isPickerActive = active
    }

    fun reset() {
        _iconVisible.value = readIconVisible()
        _biometricsEnabled.value = readBoolean(BIOMETRICS_KEY, default = true)
        session.reset()
        isPickerActive = false
    }

    fun recordAuthSuccess() = session.recordAuthSuccess()

    fun recordAppBackgrounded() = session.recordAppBackgrounded()

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

    private fun readBoolean(key: Uuid, default: Boolean): Boolean {
        // Bootstrap-only sync read — seeds the StateFlow at construction. See
        // KeyValueWrapper.selectByKeyBootstrapSync for the rationale (commonMain
        // has no runBlocking on wasmJs).
        val bytes: ByteArray = runCatching {
            keyValue.selectByKeyBootstrapSync(key) { _, data -> data }
        }.getOrNull() ?: return default
        return if (bytes.isEmpty()) default else bytes[0].toInt() != 0
    }

    private fun readIconVisible(): Boolean =
        BottomBarDefaultsMigration.resolveIconVisible(databaseManager, ICON_VISIBLE_KEY, migrationScope)

    private fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    companion object {
        val ICON_VISIBLE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0102")
        val BIOMETRICS_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0103")

        private const val BACKGROUND_THRESHOLD_MS = 30 * 1000L
    }
}
