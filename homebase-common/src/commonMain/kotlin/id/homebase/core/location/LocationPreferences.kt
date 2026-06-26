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

    // Master location-HISTORY switch: when on, captured fixes are persisted as the user's
    // location history (DB → hour files → dashboard/history). It does NOT gate whether GPS can
    // run — a live share or a transient consumer (e.g. the live map) can acquire GPS without it
    // (see LocationTrackingCoordinator/GpsDemand). Lives in keyValue, so it is wiped on logout —
    // history deliberately stops when the identity signs out.
    private val _allowLocationHistory = MutableStateFlow(readBoolean(ALLOW_LOCATION_HISTORY_KEY, default = false))
    val allowLocationHistory: StateFlow<Boolean> = _allowLocationHistory.asStateFlow()

    // Basemap selection. Default OpenStreetMap: traces render over OSM tiles,
    // revealing the viewed area to the tile server (disclosed next to the
    // selector). None keeps the fully-offline Canvas trace. Persisted by enum
    // name so future entries (Google/Apple) can't corrupt a stored choice.
    private val _mapProvider = MutableStateFlow(readMapProvider())
    val mapProvider: StateFlow<LocationMapProvider> = _mapProvider.asStateFlow()

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

    suspend fun setAllowLocationHistory(value: Boolean) {
        if (_allowLocationHistory.value == value) return
        keyValue.upsertValue(ALLOW_LOCATION_HISTORY_KEY, encode(value))
        _allowLocationHistory.value = value
    }

    suspend fun setMapProvider(value: LocationMapProvider) {
        if (_mapProvider.value == value) return
        keyValue.upsertValue(MAP_PROVIDER_KEY, value.name.encodeToByteArray())
        _mapProvider.value = value
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
        _allowLocationHistory.value = readBoolean(ALLOW_LOCATION_HISTORY_KEY, default = false)
        _mapProvider.value = readMapProvider()
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

    private fun readMapProvider(): LocationMapProvider {
        val bytes: ByteArray = runCatching {
            keyValue.selectByKeyBootstrapSync(MAP_PROVIDER_KEY) { _, data -> data }
        }.getOrNull() ?: return LocationMapProvider.DEFAULT
        return if (bytes.isEmpty()) LocationMapProvider.DEFAULT
        else LocationMapProvider.fromName(bytes.decodeToString())
    }

    private fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    companion object {
        // Stable namespace for Location. Vault owns 0a01xx, Moments 0a02xx;
        // Location is the next free slot. 0a0301 (former ACTIVATED_KEY) is retired —
        // activation now derives from the drive.
        val ICON_VISIBLE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0302")
        // 0a0303 (formerly TRACKING_ENABLED_KEY) — same slot, renamed to match
        // allowLocationHistory; persisted values carry over, no migration.
        val ALLOW_LOCATION_HISTORY_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0303")
        // 0a0304 (former SHOW_MAP_TILES_KEY, a boolean) is retired — the map
        // choice is now an enum under 0a0306 (default OpenStreetMap). No migration.
        val DISCLOSURE_ACCEPTED_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0305")
        val MAP_PROVIDER_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0306")
    }
}
