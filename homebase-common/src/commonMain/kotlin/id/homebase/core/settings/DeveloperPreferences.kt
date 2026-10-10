package id.homebase.core.settings

import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/**
 * Flags for features that are built but not yet offered to users — set from the developer menu,
 * which is the only place they appear.
 *
 * Stored in keyValue like every other preference, so they are wiped on logout. That is the right
 * default for a dark launch: signing out returns the app to what a real user would see.
 */
class DeveloperPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    private val _profileCardEnabled = MutableStateFlow(readBoolean(PROFILE_CARD_KEY, default = false))
    val profileCardEnabled: StateFlow<Boolean> = _profileCardEnabled.asStateFlow()

    suspend fun setProfileCardEnabled(value: Boolean) {
        if (_profileCardEnabled.value == value) return
        keyValue.upsertValue(PROFILE_CARD_KEY, encode(value))
        _profileCardEnabled.value = value
    }

    fun reload() {
        _profileCardEnabled.value = readBoolean(PROFILE_CARD_KEY, default = false)
    }

    private fun readBoolean(key: Uuid, default: Boolean): Boolean {
        // Bootstrap-only sync read, seeding the StateFlow at construction — same rationale as
        // LocationPreferences: commonMain has no runBlocking on wasmJs.
        val bytes: ByteArray = runCatching {
            keyValue.selectByKeyBootstrapSync(key) { _, data -> data }
        }.getOrNull() ?: return default
        return if (bytes.isEmpty()) default else bytes[0].toInt() != 0
    }

    private fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    companion object {
        // 0a09xx — developer flags. Vault owns 0a01xx, Moments 0a02xx, Location 0a03xx,
        // 0a04xx and 0a07xx are taken, and DiceRollPreferences writes 0a0801-0a0803.
        val PROFILE_CARD_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0901")
    }
}
