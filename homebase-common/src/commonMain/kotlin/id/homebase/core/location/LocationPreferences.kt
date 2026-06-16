package id.homebase.core.location

import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

class LocationPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    // Activation is no longer a local preference flag — it's derived from the mounted
    // drive (see LocationViewModel.isActivated), which reflects the cross-device
    // DriveRegistry. The old 0a0301 key is abandoned (no migration needed).

    // Hidden by default — "soft launch": the bottom-nav icon only appears after the
    // user opts in via Settings → Location → "Show Location icon in bottom bar"
    // (persists true, overriding this default). The Home-screen tile and the
    // Settings entry remain the discoverable entry points either way.
    private val _iconVisible = MutableStateFlow(readBoolean(ICON_VISIBLE_KEY, default = false))
    val iconVisible: StateFlow<Boolean> = _iconVisible.asStateFlow()

    // Master tracking switch. Lives in keyValue, so it is wiped on logout —
    // tracking deliberately stops when the identity signs out.
    private val _trackingEnabled = MutableStateFlow(readBoolean(TRACKING_ENABLED_KEY, default = false))
    val trackingEnabled: StateFlow<Boolean> = _trackingEnabled.asStateFlow()

    // History basemap opt-in. Default OFF: the trace renders fully offline;
    // turning this on fetches OpenStreetMap tiles, revealing the viewed area
    // to the tile server (disclosed next to the toggle).
    private val _showMapTiles = MutableStateFlow(readBoolean(SHOW_MAP_TILES_KEY, default = false))
    val showMapTiles: StateFlow<Boolean> = _showMapTiles.asStateFlow()

    // Google Play "prominent disclosure" consent: the user accepted the
    // location-collection dialog. Gates the first permission request and the
    // tracking switch; shown once, persisted.
    private val _disclosureAccepted = MutableStateFlow(readBoolean(DISCLOSURE_ACCEPTED_KEY, default = false))
    val disclosureAccepted: StateFlow<Boolean> = _disclosureAccepted.asStateFlow()

    suspend fun setIconVisible(value: Boolean) {
        if (_iconVisible.value == value) return
        keyValue.upsertValue(ICON_VISIBLE_KEY, encode(value))
        _iconVisible.value = value
    }

    suspend fun setTrackingEnabled(value: Boolean) {
        if (_trackingEnabled.value == value) return
        keyValue.upsertValue(TRACKING_ENABLED_KEY, encode(value))
        _trackingEnabled.value = value
    }

    suspend fun setShowMapTiles(value: Boolean) {
        if (_showMapTiles.value == value) return
        keyValue.upsertValue(SHOW_MAP_TILES_KEY, encode(value))
        _showMapTiles.value = value
    }

    suspend fun setDisclosureAccepted(value: Boolean) {
        if (_disclosureAccepted.value == value) return
        keyValue.upsertValue(DISCLOSURE_ACCEPTED_KEY, encode(value))
        _disclosureAccepted.value = value
    }

    /**
     * Re-seed in-memory state from the DB for a clean login. Called from
     * `onPostAuthenticated` in `AppModule.kt`; after a logout wipe the reads
     * return defaults.
     */
    fun reset() {
        _iconVisible.value = readBoolean(ICON_VISIBLE_KEY, default = false)
        _trackingEnabled.value = readBoolean(TRACKING_ENABLED_KEY, default = false)
        _showMapTiles.value = readBoolean(SHOW_MAP_TILES_KEY, default = false)
        _disclosureAccepted.value = readBoolean(DISCLOSURE_ACCEPTED_KEY, default = false)
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
        // Stable namespace for Location. Vault owns 0a01xx, Moments 0a02xx;
        // Location is the next free slot. 0a0301 (former ACTIVATED_KEY) is retired —
        // activation now derives from the drive.
        val ICON_VISIBLE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0302")
        val TRACKING_ENABLED_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0303")
        val SHOW_MAP_TILES_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0304")
        val DISCLOSURE_ACCEPTED_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0305")
    }
}
