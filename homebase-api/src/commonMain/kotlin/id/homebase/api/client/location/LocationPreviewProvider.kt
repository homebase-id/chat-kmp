package id.homebase.api.client.location

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan
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
    ): LocationPreview {
        cache[CacheKey(lat, lon, zoom)]?.let { return it }

        val address = try {
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

    private suspend fun reverseGeocode(lat: Double, lon: Double, zoom: Int): String? {
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
        Logger.d(tag = TAG) { "fetchStaticMap success ${bytes.size} bytes" }
        return bytes
    }

    /** Web Mercator lat/lon → tile X/Y at the given zoom. Standard Slippy Map formula. */
    private fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n = 1 shl zoom
        val xTile = ((lon + 180.0) / 360.0 * n).toInt()
        val latRad = lat * PI / 180.0
        val yTile = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
        return xTile to yTile
    }

    private fun formatLatLon(lat: Double, lon: Double): String {
        val latStr = ((lat * 1e5).toLong() / 1e5).toString()
        val lonStr = ((lon * 1e5).toLong() / 1e5).toString()
        return "$latStr, $lonStr"
    }

    private data class CacheKey(val lat: Double, val lon: Double, val zoom: Int)

    private companion object {
        private const val TAG = "LocationPreviewProvider"
        // OSM tiles are 256x256. Single-tile fetch matches that natively.
        private const val MAP_WIDTH = 256
        private const val MAP_HEIGHT = 256
        private const val NOMINATIM_MIN_INTERVAL_MS = 1100L
        private const val USER_AGENT = "HomebaseChat/dev (+https://homebase.id)"

        private val cache = mutableMapOf<CacheKey, LocationPreview>()
        private val nominatimMutex = Mutex()
        private var lastNominatimCallAt: TimeSource.Monotonic.ValueTimeMark? = null
    }
}
