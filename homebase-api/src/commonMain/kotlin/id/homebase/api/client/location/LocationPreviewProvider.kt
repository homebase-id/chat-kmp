package id.homebase.api.client.location

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.coroutines.supervisedScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * DEV STUB: directly fetches an OSM-rendered static map tile + Nominatim reverse-geocoded address.
 *
 * Replace the body of [getLocationPreview] with a call to the user's identity host
 * (`GET /api/v2/preview/staticmap`) once the backend ships that endpoint. The function signature
 * and return type must stay identical so downstream code (`LocationPreviewPayloadBuilder`,
 * `MediaItem.kt` render dispatch, the receiver-side `LocationPreviewCard`) does not change.
 *
 * Privacy note: while this stub is in place, the *sender* contacts OSM directly. That's the leak
 * the backend swap is meant to close. Receivers are unaffected — they always render from the
 * encrypted drive payload, never from a third-party URL.
 */
class LocationPreviewProvider(
    private val httpClient: HttpClient,
) {
    /**
     * Always returns a usable [LocationPreview] when given valid coordinates. If the static-map
     * fetch fails (rate-limited, offline, OSM tile server down, etc.) we degrade to a
     * coordinates-only preview with `imageUrl = null` so the user can still send their location.
     * The receiver bubble renders a `LocationOn` icon placeholder when no map image is present.
     *
     * Reverse-geocode failures degrade further to a `lat, lon` text address. Either degradation
     * is silent at this layer; the caller decides whether to surface a snackbar.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun getLocationPreview(
        lat: Double,
        lon: Double,
        zoom: Int = 15,
        /**
         * Address the caller already resolved (e.g. the share-location screen's live pan
         * geocoding) — skips the Nominatim round-trip entirely. Blank/null ⇒ resolve here.
         */
        knownAddress: String? = null,
    ): LocationPreview {
        cache[CacheKey(lat, lon, zoom)]?.let { return it }

        val address = knownAddress?.takeIf { it.isNotBlank() } ?: try {
            reverseGeocode(lat, lon, zoom) ?: formatLatLon(lat, lon)
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = TAG) { "reverseGeocode threw — falling back to lat/lon" }
            formatLatLon(lat, lon)
        }

        val pngBytes = try {
            fetchStaticMap(lat, lon, zoom)
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = TAG) { "fetchStaticMap threw — sending coordinates only" }
            null
        }

        val dataUri = pngBytes?.let { "data:image/png;base64,${Base64.encode(it)}" }
        val preview = LocationPreview(
            lat = lat,
            lon = lon,
            address = address,
            imageUrl = dataUri,
            imageWidth = if (pngBytes != null) MAP_WIDTH else null,
            imageHeight = if (pngBytes != null) MAP_HEIGHT else null,
        )
        // Only cache successful image fetches — if the map service is temporarily down we want
        // the next attempt to retry instead of being stuck with a coords-only preview.
        if (pngBytes != null) cache[CacheKey(lat, lon, zoom)] = preview
        return preview
    }

    /**
     * Reverse-geocode a point to a display address (Nominatim `display_name`), or null when the
     * lookup fails or the point has no address. Cached by coordinates rounded to ~11 m so pan
     * jitter (the share-location screen re-resolving as the map settles) doesn't re-query; the
     * remote call is rate-limited to Nominatim's 1 req/s budget. Callers doing continuous lookups
     * (address-follows-pan) must ALSO debounce on their side — the rate limiter queues, it
     * doesn't shed.
     */
    suspend fun reverseGeocode(lat: Double, lon: Double, zoom: Int = 18): String? {
        val key = AddressKey(roundCoord(lat), roundCoord(lon), zoom)
        addressCacheMutex.withLock { addressCache[key] }?.let { return it }
        val resolved = reverseGeocodeRemote(lat, lon, zoom) ?: return null
        addressCacheMutex.withLock {
            addressCache[key] = resolved
            // Insertion-order eviction — good enough for a pan cache.
            while (addressCache.size > ADDRESS_CACHE_MAX) {
                addressCache.remove(addressCache.keys.first())
            }
        }
        return resolved
    }

    private suspend fun reverseGeocodeRemote(lat: Double, lon: Double, zoom: Int): String? {
        // Nominatim usage policy: max 1 req/sec, identifying User-Agent. We add a touch of
        // headroom (1.1s) so we never miss the budget if the wall clock is slightly skewed.
        nominatimMutex.withLock {
            val now = TimeSource.Monotonic.markNow()
            val sinceLast = lastNominatimCallAt?.elapsedNow()?.inWholeMilliseconds ?: Long.MAX_VALUE
            if (sinceLast < NOMINATIM_MIN_INTERVAL_MS) {
                delay((NOMINATIM_MIN_INTERVAL_MS - sinceLast).milliseconds)
            }
            lastNominatimCallAt = now
        }

        val url = "https://nominatim.openstreetmap.org/reverse?" +
            "format=jsonv2&lat=$lat&lon=$lon&zoom=$zoom&addressdetails=0"
        val response = httpClient.get(url) {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json")
        }
        if (!response.status.isSuccess()) {
            Logger.w(tag = TAG) { "reverseGeocode HTTP ${response.status.value} for $lat,$lon" }
            return null
        }
        val body = response.bodyAsText()
        val parsed = runCatching {
            Json.parseToJsonElement(body).jsonObject["display_name"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return parsed?.takeIf { it.isNotBlank() }
    }

    private suspend fun fetchStaticMap(lat: Double, lon: Double, zoom: Int): ByteArray? {
        // tile.openstreetmap.org is the canonical OSM tile server. We previously used
        // staticmap.openstreetmap.de but that community-run service has been shut down.
        // Single-tile fetch — accept the marker may not be perfectly centered, this is a
        // dev stub until the backend ships /api/v2/preview/staticmap.
        val (xTile, yTile) = latLonToTile(lat, lon, zoom)
        val url = "https://tile.openstreetmap.org/$zoom/$xTile/$yTile.png"
        Logger.d(tag = TAG) { "fetchStaticMap GET $url (lat=$lat lon=$lon zoom=$zoom)" }
        val response = httpClient.get(url) {
            header("User-Agent", USER_AGENT)
            header("Accept", "image/png,image/*")
        }
        if (!response.status.isSuccess()) {
            val snippet = runCatching { response.bodyAsText().take(200) }.getOrNull().orEmpty()
            Logger.w(tag = TAG) {
                "fetchStaticMap HTTP ${response.status.value} for $lat,$lon body=\"$snippet\""
            }
            return null
        }
        val bytes = response.readRawBytes()
        if (bytes.size < BLANK_TILE_THRESHOLD_BYTES) {
            // OSM serves a tiny uniform-color PNG (~100 bytes) for tiles with no rendered map
            // data — empty oceans, deserts with no roads, etc. Render-as-image would just be a
            // blank gray square, which looks broken. Treat as "no map" and let the caller's
            // coords-only fallback take over.
            Logger.w(tag = TAG) {
                "fetchStaticMap returned ${bytes.size} bytes (blank tile — no OSM data here)"
            }
            return null
        }
        Logger.d(tag = TAG) { "fetchStaticMap success ${bytes.size} bytes" }
        return bytes
    }

    /**
     * Fetch one raw OSM tile PNG for the Location history basemap. Same HTTP
     * path and User-Agent as the chat preview; LRU-cached and single-flighted.
     * The fetch runs on a provider-owned scope so a cancelled caller (the
     * canvas effect restarting on pan/zoom) can neither kill an in-flight
     * download other callers await nor strand its single-flight entry.
     * Returns null on failure — callers leave the background empty.
     *
     * No blank-tile size guard here (unlike [fetchStaticMap]): a tiny
     * uniform-color PNG is a legitimate basemap tile (open water, plain
     * terrain), not a broken preview image.
     */
    suspend fun getTilePng(zoom: Int, xTile: Int, yTile: Int): ByteArray? {
        val key = TileKey(zoom, xTile, yTile)
        val flight = tileCacheMutex.withLock {
            tileCache[key]?.let { return it }
            tileFlights.getOrPut(key) {
                tileScope.async {
                    val bytes = try {
                        fetchTile(zoom, xTile, yTile)
                    } catch (e: Exception) {
                        Logger.w(throwable = e, tag = TAG) { "getTilePng($zoom/$xTile/$yTile) threw" }
                        null
                    }
                    tileCacheMutex.withLock {
                        if (bytes != null) {
                            tileCache[key] = bytes
                            // Insertion-order eviction — good enough for a pan/zoom cache.
                            while (tileCache.size > TILE_CACHE_MAX) {
                                tileCache.remove(tileCache.keys.first())
                            }
                        }
                        tileFlights.remove(key)
                    }
                    bytes
                }
            }
        }
        return flight.await()
    }

    private suspend fun fetchTile(zoom: Int, xTile: Int, yTile: Int): ByteArray? {
        val url = "https://tile.openstreetmap.org/$zoom/$xTile/$yTile.png"
        val response = httpClient.get(url) {
            header("User-Agent", USER_AGENT)
            header("Accept", "image/png,image/*")
        }
        if (!response.status.isSuccess()) {
            Logger.w(tag = TAG) { "tile HTTP ${response.status.value} for $zoom/$xTile/$yTile" }
            return null
        }
        return response.readRawBytes()
    }

    /** Web Mercator lat/lon → tile X/Y at the given zoom. Standard Slippy Map formula. */
    private fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> =
        WebMercator.latLonToTile(lat, lon, zoom)

    private fun formatLatLon(lat: Double, lon: Double): String {
        val latStr = ((lat * 1e5).toLong() / 1e5).toString()
        val lonStr = ((lon * 1e5).toLong() / 1e5).toString()
        return "$latStr, $lonStr"
    }

    /** ~11 m grid (4 decimals) — close enough that one address serves the whole cell. */
    private fun roundCoord(v: Double): Double = (v * 1e4).toLong() / 1e4

    private data class CacheKey(val lat: Double, val lon: Double, val zoom: Int)

    private data class AddressKey(val lat: Double, val lon: Double, val zoom: Int)

    private data class TileKey(val zoom: Int, val x: Int, val y: Int)

    private companion object {
        private const val TAG = "LocationPreviewProvider"
        // OSM tiles are 256x256. Single-tile fetch matches that natively.
        private const val MAP_WIDTH = 256
        private const val MAP_HEIGHT = 256

        // OSM "no data" tiles are tiny uniform-color PNGs (~100 bytes). Real rendered tiles
        // with even minimal content are >1KB. 500 bytes is a safe cutoff.
        private const val BLANK_TILE_THRESHOLD_BYTES = 500
        private const val NOMINATIM_MIN_INTERVAL_MS = 1100L
        private const val USER_AGENT = "HomebaseChat/dev (+https://homebase.id)"

        private val cache = mutableMapOf<CacheKey, LocationPreview>()
        private val nominatimMutex = Mutex()
        private var lastNominatimCallAt: TimeSource.Monotonic.ValueTimeMark? = null

        // Resolved-address cache for pan-follows-address lookups (share-location screen).
        private const val ADDRESS_CACHE_MAX = 128
        private val addressCache = LinkedHashMap<AddressKey, String>()
        private val addressCacheMutex = Mutex()

        // Raw-tile cache for the Location history basemap (~64 tiles ≈ 1-2MB).
        private const val TILE_CACHE_MAX = 64
        private val tileCache = LinkedHashMap<TileKey, ByteArray>()
        private val tileFlights = mutableMapOf<TileKey, Deferred<ByteArray?>>()
        private val tileCacheMutex = Mutex()

        // Owns tile downloads so they survive caller cancellation (see getTilePng).
        private val tileScope = supervisedScope("OsmTileFetcher", ioDispatcher)
    }
}
