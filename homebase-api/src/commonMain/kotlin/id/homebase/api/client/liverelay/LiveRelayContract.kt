package id.homebase.api.client.liverelay

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable

/**
 * Live Relay — generic, ephemeral, app-agnostic live data streaming among connected identities
 * (odin-core PR #1572). This file holds the chat-kmp client's contract for the **live location**
 * use case of that primitive.
 *
 * For this debug-flow build we use a single, fixed, well-known [LIVE_LOCATION_CHANNEL_KEY] meaning
 * "live location update". Both sender and receiver use this one constant; the receiver filters
 * inbound LiveRelay notifications on it. (A future UX may generate per-session channel keys.)
 *
 * The relay `blob` is opaque to the server (GPS today, anything tomorrow); the app owns its meaning
 * and encoding. Here it carries a base64-encoded [LiveLocationPoint] JSON.
 */
const val LIVE_LOCATION_CHANNEL_KEY = "7a1e9c40-1b2c-4d3e-9f50-0a1b2c3d4e5f"

/**
 * Compact live GPS point carried in the LiveRelay [blob]. Debug-grade — this is intentionally NOT
 * the durable `LocationTrackCodec`; it just needs to round-trip lat/lon plus a few extras for the
 * flow-confirmation build.
 */
@Serializable
data class LiveLocationPoint(
    val lat: Double,
    val lon: Double,
    val acc: Double? = null,
    val spd: Double? = null,
    val hdg: Double? = null,
    val ts: Long,
)

/** Encodes/decodes a [LiveLocationPoint] to the base64 string the LiveRelay `blob` field carries. */
object LiveLocationCodec {
    fun encode(point: LiveLocationPoint): String =
        Base64.encode(OdinSystemSerializer.serialize(point).encodeToByteArray())

    fun decode(blob: String): LiveLocationPoint? =
        runCatching {
            OdinSystemSerializer.deserialize<LiveLocationPoint>(Base64.decode(blob).decodeToString())
        }.getOrNull()
}
