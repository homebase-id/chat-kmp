package id.homebase.core.contactbook

import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/**
 * Device-local runtime flags for the Contact Book add-on, backed by the
 * encrypted key/value store. Modeled on
 * [id.homebase.core.vault.VaultPreferences] but without an `activated` flag —
 * the Contacts drive is mandatory (always mounted/synced), so there is no
 * extend-permissions / drive-activation step. [onboardingComplete] gates the
 * first-run intro screen; [iconVisible] toggles the bottom-bar icon. The add-on
 * is **dark-launched** — [iconVisible] defaults to `false`, so the Contacts icon
 * stays off the bottom bar until the user enables it in Settings.
 *
 * UUID namespace: `0a04xx` (claimed by Contact Book — see ADDING_ADDON_APPS.md).
 */
class ContactBookPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    private val _onboardingComplete = MutableStateFlow(readBoolean(ONBOARDING_KEY, default = false))
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()

    private val _iconVisible = MutableStateFlow(readBoolean(ICON_VISIBLE_KEY, default = false))
    val iconVisible: StateFlow<Boolean> = _iconVisible.asStateFlow()

    fun reset() {
        _onboardingComplete.value = readBoolean(ONBOARDING_KEY, default = false)
        _iconVisible.value = readBoolean(ICON_VISIBLE_KEY, default = false)
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
        // 0a0403 was the (removed) biometrics flag — left unused, not reassigned.
    }
}
