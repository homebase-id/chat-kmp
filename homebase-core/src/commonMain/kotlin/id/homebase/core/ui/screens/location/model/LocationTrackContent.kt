package id.homebase.core.ui.screens.location.model

import id.homebase.api.crypto.Md5
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.BufferedLocationPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.uuid.Uuid

/** Reserve with the team — see ADDING_TYPED_MESSAGE_KIND.md for the registry habit. */
const val LOCATION_TRACK_FILE_TYPE = 5610

/** Payload key for the full-resolution overflow payload; matches ^[a-z0-9_]{8,10}$. */
const val LOCATION_POINTS_PAYLOAD_KEY = "loc_points"

const val LOCATION_TRACK_CONTENT_VERSION = 1

/**
 * Plaintext budget for the header trace. appData.content is validated against
 * HomebaseProtocol.MaxHeaderContentBytes (7000) AFTER encryption, which is
 * base64(AES-CBC(plaintext)) ≈ ceil((n + pad) / 3) * 4 bytes. 5200 plaintext
 * bytes encrypt to ~6960 — just under the cap. ≈150 points per hour file.
 */
const val LOCATION_HEADER_PLAINTEXT_BUDGET = 5200

/** Deterministic per-device per-UTC-hour file identity. */
fun locationHourFileUid(deviceId: Uuid, hourStartMs: Long): Uuid =
    Md5.toGuidId("loc-track:$deviceId:$hourStartMs")

/**
 * Per-device profile file (fileType [LOCATION_DEVICE_FILE_TYPE]): gives the
 * anonymous tracking deviceId a human-readable identity for Find device and
 * the dashboard's device list. Written once by each device that actually
 * tracks — viewer devices (desktop/web) never create one. Auto-only naming v1.
 */
const val LOCATION_DEVICE_FILE_TYPE = 5611

fun locationDeviceFileUid(deviceId: Uuid): Uuid = Md5.toGuidId("loc-device:$deviceId")

@Serializable
data class LocationDeviceProfile(
    val v: Int = 1,
    val deviceId: String,
    val name: String,
    /** "android" | "ios" (trackers); desktop/web never write profiles. */
    val platform: String,
) {
    companion object {
        fun decode(json: String): LocationDeviceProfile? = runCatching {
            OdinSystemSerializer.deserialize<LocationDeviceProfile>(json)
        }.getOrNull()
    }
}

fun hourStartMs(epochMs: Long): Long = (epochMs / HOUR_MS) * HOUR_MS

const val HOUR_MS = 3_600_000L

/** Decoded hour-file content (header trace or payload). */
data class LocationTrackHour(
    val deviceId: Uuid,
    val hourStartMs: Long,
    val points: List<BufferedLocationPoint>,
    /** Raw captured count for the hour; > points.size when the header trace was thinned. */
    val fullCount: Int,
    /** Battery % at the hour's first point (header only); null for payload/older files. */
    val battery: Int? = null,
) {
    /** True when a full-resolution payload rides alongside the header trace. */
    val hasOverflowPayload: Boolean get() = fullCount > points.size
}

/**
 * Compact positional encoding of the header trace:
 * ```
 * { "v":1, "deviceId":"…", "hourStart":…, "count":n, "full":N?, "t0":ms, "bat":pct?,
 *   "bbox":[minLatE5,minLonE5,maxLatE5,maxLonE5],
 *   "pts":[[dtSec,latE5,lonE5,accM,spdE1,hdgDeg,flags,steps?],…] }
 * ```
 * lat/lon as integer 1e-5 degrees (≈1.1 m — below GPS accuracy), dt as whole
 * seconds from t0, speed as 0.1 m/s, heading as whole degrees; flags bit0 = fg,
 * bits1+ = source index; steps = pedometer delta since the previous point (or
 * null); bat = battery % at the hour's first point. Integers keep the JSON
 * dense. Trailing optional fields are appended, so older 7-element points and
 * `bat`-less summaries still decode.
 */
object LocationTrackCodec {

    private val sources = listOf("gps", "net", "fused", "slc")

    /**
     * Encode the header trace for an hour, thinning uniformly by time until the
     * plaintext fits [budgetBytes]. Returns the JSON and the points actually
     * stored (callers attach the overflow payload when storedCount < points.size).
     */
    fun encodeHeader(
        deviceId: Uuid,
        hourStartMs: Long,
        points: List<BufferedLocationPoint>,
        budgetBytes: Int = LOCATION_HEADER_PLAINTEXT_BUDGET,
    ): Pair<String, List<BufferedLocationPoint>> {
        require(points.isNotEmpty()) { "encodeHeader needs at least one point" }
        var stored = points
        var json = encodeHeaderExact(deviceId, hourStartMs, stored, fullCount = points.size)
        while (json.encodeToByteArray().size > budgetBytes && stored.size > 2) {
            val ratio = budgetBytes.toDouble() / json.encodeToByteArray().size
            val target = max(2, (stored.size * ratio * 0.95).toInt())
                .coerceAtMost(stored.size - 1)
            stored = thinUniform(stored, target)
            json = encodeHeaderExact(deviceId, hourStartMs, stored, fullCount = points.size)
        }
        return json to stored
    }

    private fun encodeHeaderExact(
        deviceId: Uuid,
        hourStartMs: Long,
        stored: List<BufferedLocationPoint>,
        fullCount: Int,
    ): String {
        val t0 = stored.first().t
        val obj = buildJsonObject {
            put("v", LOCATION_TRACK_CONTENT_VERSION)
            put("deviceId", deviceId.toString())
            put("hourStart", hourStartMs)
            put("count", stored.size)
            if (fullCount > stored.size) put("full", fullCount)
            put("t0", t0)
            // Battery for the hour = the first point's reading (chaining hour
            // files reconstructs the curve). thinUniform always keeps point 0.
            stored.first().bat?.let { put("bat", it) }
            put("bbox", buildJsonArray {
                add(JsonPrimitive(stored.minOf { it.lat.toE5() }))
                add(JsonPrimitive(stored.minOf { it.lon.toE5() }))
                add(JsonPrimitive(stored.maxOf { it.lat.toE5() }))
                add(JsonPrimitive(stored.maxOf { it.lon.toE5() }))
            })
            put("pts", buildJsonArray {
                for (p in stored) {
                    add(buildJsonArray {
                        add(JsonPrimitive(((p.t - t0) / 1000).toInt()))
                        add(JsonPrimitive(p.lat.toE5()))
                        add(JsonPrimitive(p.lon.toE5()))
                        add(p.acc?.let { JsonPrimitive(it.roundToInt()) } ?: JsonNull)
                        add(p.spd?.let { JsonPrimitive((it * 10).roundToInt()) } ?: JsonNull)
                        add(p.hdg?.let { JsonPrimitive(it.roundToInt()) } ?: JsonNull)
                        add(JsonPrimitive(flagsOf(p)))
                        add(p.steps?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            })
        }
        return obj.toString()
    }

    fun decodeHeader(json: String): LocationTrackHour? = runCatching {
        val obj = Json.parseToJsonElement(json).jsonObject
        val deviceId = Uuid.parse(obj.getValue("deviceId").jsonPrimitive.content)
        val hourStart = obj.getValue("hourStart").jsonPrimitive.long
        val t0 = obj.getValue("t0").jsonPrimitive.long
        val pts = obj.getValue("pts").jsonArray.map { el ->
            val a = el.jsonArray
            val flags = a[6].jsonPrimitive.intOrNull ?: 0
            BufferedLocationPoint(
                t = t0 + (a[0].jsonPrimitive.long * 1000),
                lat = a[1].jsonPrimitive.long.fromE5(),
                lon = a[2].jsonPrimitive.long.fromE5(),
                acc = a[3].jsonPrimitive.intOrNull?.toDouble(),
                spd = a[4].jsonPrimitive.intOrNull?.let { it / 10.0 },
                hdg = a[5].jsonPrimitive.intOrNull?.toDouble(),
                src = sources.getOrElse(flags shr 1) { "gps" },
                fg = flags and 1 == 1,
                // Index 7 appended later — null on older 7-element points.
                steps = a.getOrNull(7)?.jsonPrimitive?.intOrNull,
            )
        }
        LocationTrackHour(
            deviceId = deviceId,
            hourStartMs = hourStart,
            points = pts,
            fullCount = obj["full"]?.jsonPrimitive?.intOrNull ?: pts.size,
            battery = obj["bat"]?.jsonPrimitive?.intOrNull,
        )
    }.getOrNull()

    /**
     * Full-resolution payload for overflow hours — same positional shape, but
     * absolute ms timestamps and unrounded doubles, plus alt/altAcc:
     * `[t,lat,lon,acc,alt,altAcc,spd,hdg,flags,steps?]`.
     */
    fun encodePayload(deviceId: Uuid, hourStartMs: Long, points: List<BufferedLocationPoint>): String {
        val obj = buildJsonObject {
            put("v", LOCATION_TRACK_CONTENT_VERSION)
            put("deviceId", deviceId.toString())
            put("hourStart", hourStartMs)
            put("points", buildJsonArray {
                for (p in points) {
                    add(buildJsonArray {
                        add(JsonPrimitive(p.t))
                        add(JsonPrimitive(p.lat))
                        add(JsonPrimitive(p.lon))
                        add(p.acc?.let { JsonPrimitive(it) } ?: JsonNull)
                        add(p.alt?.let { JsonPrimitive(it) } ?: JsonNull)
                        add(p.altAcc?.let { JsonPrimitive(it) } ?: JsonNull)
                        add(p.spd?.let { JsonPrimitive(it) } ?: JsonNull)
                        add(p.hdg?.let { JsonPrimitive(it) } ?: JsonNull)
                        add(JsonPrimitive(flagsOf(p)))
                        add(p.steps?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            })
        }
        return obj.toString()
    }

    fun decodePayload(json: String): LocationTrackHour? = runCatching {
        val obj = Json.parseToJsonElement(json).jsonObject
        val pts = obj.getValue("points").jsonArray.map { el ->
            val a = el.jsonArray
            val flags = a[8].jsonPrimitive.intOrNull ?: 0
            BufferedLocationPoint(
                t = a[0].jsonPrimitive.long,
                lat = a[1].jsonPrimitive.content.toDouble(),
                lon = a[2].jsonPrimitive.content.toDouble(),
                acc = a[3].jsonPrimitive.contentOrNullDouble(),
                alt = a[4].jsonPrimitive.contentOrNullDouble(),
                altAcc = a[5].jsonPrimitive.contentOrNullDouble(),
                spd = a[6].jsonPrimitive.contentOrNullDouble(),
                hdg = a[7].jsonPrimitive.contentOrNullDouble(),
                src = sources.getOrElse(flags shr 1) { "gps" },
                fg = flags and 1 == 1,
                steps = a.getOrNull(9)?.jsonPrimitive?.intOrNull,
            )
        }
        LocationTrackHour(
            deviceId = Uuid.parse(obj.getValue("deviceId").jsonPrimitive.content),
            hourStartMs = obj.getValue("hourStart").jsonPrimitive.long,
            points = pts,
            fullCount = pts.size,
        )
    }.getOrNull()

    /** Uniform sample of [target] points preserving the first and last. */
    fun thinUniform(points: List<BufferedLocationPoint>, target: Int): List<BufferedLocationPoint> {
        if (target >= points.size) return points
        if (target <= 2) return listOf(points.first(), points.last())
        val n = points.size
        return List(target) { i ->
            points[((i.toLong() * (n - 1)) / (target - 1)).toInt()]
        }
    }

    private fun flagsOf(p: BufferedLocationPoint): Int {
        val srcIndex = sources.indexOf(p.src).coerceAtLeast(0)
        return (srcIndex shl 1) or (if (p.fg) 1 else 0)
    }

    private fun Double.toE5(): Long = (this * 1e5).roundToLong()
    private fun Long.fromE5(): Double = this / 1e5

    private fun JsonPrimitive.contentOrNullDouble(): Double? =
        if (this is JsonNull) null else content.toDoubleOrNull()
}
