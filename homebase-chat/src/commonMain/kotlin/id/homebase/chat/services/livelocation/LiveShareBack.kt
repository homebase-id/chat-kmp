package id.homebase.chat.services.livelocation

import id.homebase.chat.services.builder.LocationPreviewDescriptor
import id.homebase.core.location.liveShareEndTimeMs
import id.homebase.core.location.tracking.GpsFixResult

/** Outcome of a share-back attempt — the caller maps these to user feedback. */
enum class ShareBackResult {
    /** Own live message sent and relay started. */
    Sent,

    /** No usable window: mirroring a share that already ended (or a message with none). */
    Expired,

    /** GPS fix unavailable — nothing was sent. */
    NoFix,
}

/**
 * Share your live location back from someone ELSE's location bubble (#966). Their message is
 * never touched — this sends a NEW lightweight live location message of our own (header
 * descriptor only, no static-map payload, live already set at creation) and starts the relay.
 *
 * [durationMs] null = mirror [senderLiveShareUntilMs], the sender's absolute end-time synced in
 * their header descriptor — a single tap on a LIVE bubble shares back for exactly the sender's
 * remaining window so both shares end together. Non-null = picked from the duration menu on a
 * received static (fresh) bubble. Either path may carry the [LIVE_SHARE_INDEFINITE] sentinel
 * (an indefinite pick, or mirroring a sender's indefinite share) — it passes through unchanged.
 *
 * [send] receives the ready descriptor; [startRelay] receives the SAME absolute end-time the
 * descriptor carries — the {recipient, endTime} pair is the roster's stop key, so the two halves
 * must never diverge. The relay only starts after a successful send.
 */
suspend fun shareLiveLocationBack(
    durationMs: Long?,
    senderLiveShareUntilMs: Long?,
    nowMs: Long,
    getFix: suspend () -> GpsFixResult,
    send: suspend (LocationPreviewDescriptor) -> Unit,
    startRelay: suspend (untilMs: Long) -> Unit,
): ShareBackResult {
    val untilMs = durationMs?.let { liveShareEndTimeMs(nowMs, it) } ?: senderLiveShareUntilMs
    if (untilMs == null || untilMs <= nowMs) return ShareBackResult.Expired
    val fix = getFix()
    if (fix !is GpsFixResult.Success) return ShareBackResult.NoFix
    send(
        LocationPreviewDescriptor(
            lat = fix.point.lat,
            lon = fix.point.lon,
            address = "",
            hasImage = false,
            imageWidth = null,
            imageHeight = null,
            liveShareUntilMs = untilMs,
        )
    )
    startRelay(untilMs)
    return ShareBackResult.Sent
}
