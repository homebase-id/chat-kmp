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
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun getLocationPreview(
        lat: Double,
        lon: Double,
        zoom: Int = 15,
    ): LocationPreview? {
        cache[CacheKey(lat, lon, zoom)]?.let { return it }

        return try {
            val address = reverseGeocode(lat, lon, zoom) ?: formatLatLon(lat, lon)
            val pngBytes = fetchStaticMap(lat, lon, zoom) ?: return null

            val dataUri = "data:image/png;base64,${Base64.encode(pngBytes)}"
            val preview = LocationPreview(
                lat = lat,
                lon = lon,
                address = address,
                imageUrl = dataUri,
                imageWidth = MAP_WIDTH,
                imageHeight = MAP_HEIGHT,
            ).also { cache[CacheKey(lat, lon, zoom)] = it }
            preview
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = TAG) { "getLocationPreview failed for $lat,$lon" }
            null
        }
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
        if (!response.status.isSuccess()) return null
        val body = response.bodyAsText()
        val parsed = runCatching {
            Json.parseToJsonElement(body).jsonObject["display_name"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return parsed?.takeIf { it.isNotBlank() }
    }

    private suspend fun fetchStaticMap(lat: Double, lon: Double, zoom: Int): ByteArray? {
        val url = "https://staticmap.openstreetmap.de/staticmap.php?" +
            "center=$lat,$lon&zoom=$zoom&size=${MAP_WIDTH}x$MAP_HEIGHT&markers=$lat,$lon,red-pushpin"
        val response = httpClient.get(url) {
            header("User-Agent", USER_AGENT)
            header("Accept", "image/png,image/*")
        }
        if (!response.status.isSuccess()) return null
        return response.readRawBytes()
    }

    private fun formatLatLon(lat: Double, lon: Double): String {
        val latStr = ((lat * 1e5).toLong() / 1e5).toString()
        val lonStr = ((lon * 1e5).toLong() / 1e5).toString()
        return "$latStr, $lonStr"
    }

    private data class CacheKey(val lat: Double, val lon: Double, val zoom: Int)

    private companion object {
        private const val TAG = "LocationPreviewProvider"
        private const val MAP_WIDTH = 600
        private const val MAP_HEIGHT = 400
        private const val NOMINATIM_MIN_INTERVAL_MS = 1100L
        private const val USER_AGENT = "HomebaseChat/dev (+https://homebase.id)"

        private val cache = mutableMapOf<CacheKey, LocationPreview>()
        private val nominatimMutex = Mutex()
        private var lastNominatimCallAt: TimeSource.Monotonic.ValueTimeMark? = null
    }
}
