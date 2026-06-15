package id.homebase.core.lists

import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/**
 * Local, per-identity state for the Lists add-on. Lives in `keyValue`, so it is wiped on
 * logout; [reset] re-seeds the in-memory StateFlows from the (now-empty) DB on the next
 * login. Modeled on [id.homebase.core.location.LocationPreferences].
 */
class ListsPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    // Whether the user has activated Lists (mounted the drive via extend-permissions).
    private val _activated = MutableStateFlow(readBoolean(ACTIVATED_KEY, default = false))
    val activated: StateFlow<Boolean> = _activated.asStateFlow()

    // Hidden by default — "soft launch": the bottom-nav icon only appears after the user
    // opts in via Settings → Lists → "Show Lists icon in bottom bar" (persists true,
    // overriding this default). The Home-screen tile and the Settings entry remain the
    // discoverable entry points either way.
    private val _iconVisible = MutableStateFlow(readBoolean(ICON_VISIBLE_KEY, default = false))
    val iconVisible: StateFlow<Boolean> = _iconVisible.asStateFlow()

    suspend fun setActivated(value: Boolean) {
        if (_activated.value == value) return
        keyValue.upsertValue(ACTIVATED_KEY, encode(value))
        _activated.value = value
    }

    suspend fun setIconVisible(value: Boolean) {
        if (_iconVisible.value == value) return
        keyValue.upsertValue(ICON_VISIBLE_KEY, encode(value))
        _iconVisible.value = value
    }

    /**
     * Re-seed in-memory state from the DB for a clean login. Called from
     * `onPostAuthenticated` in `AppModule.kt`; after a logout wipe the reads return defaults.
     */
    fun reset() {
        _activated.value = readBoolean(ACTIVATED_KEY, default = false)
        _iconVisible.value = readBoolean(ICON_VISIBLE_KEY, default = false)
    }

    private fun readBoolean(key: Uuid, default: Boolean): Boolean {
        // Bootstrap-only sync read — seeds the StateFlow at construction. See
        // KeyValueWrapper.selectByKeyBootstrapSync for the rationale (commonMain has no
        // runBlocking on wasmJs).
        val bytes: ByteArray = runCatching {
            keyValue.selectByKeyBootstrapSync(key) { _, data -> data }
        }.getOrNull() ?: return default
        return if (bytes.isEmpty()) default else bytes[0].toInt() != 0
    }

    private fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    companion object {
        // Stable namespace for Lists. Vault owns 0a01xx, Moments 0a02xx, Location 0a03xx;
        // Lists is the next free slot (0a04xx).
        val ACTIVATED_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0401")
        val ICON_VISIBLE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0402")
    }
}
