package id.homebase.chat.services.livelocation

import id.homebase.chat.services.builder.LocationPreviewDescriptor
import id.homebase.core.location.tracking.GpsFixResult
import id.homebase.core.location.tracking.RawLocationPoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the #966 share-back contract: the recipient's reciprocal share sends a NEW lightweight
 * message (never touching the sender's), mirrors the sender's absolute end-time on a single tap,
 * refuses to send without a GPS fix or a usable window, and hands the relay the SAME end-time the
 * descriptor carries (the roster's stop key).
 */
class LiveShareBackTest {

    private val fix = GpsFixResult.Success(
        RawLocationPoint(t = 1_000L, lat = 52.5, lon = 13.4, acc = 10.0, src = "gps", fg = true)
    )

    private class Harness {
        val sent = mutableListOf<LocationPreviewDescriptor>()
        val relayStarts = mutableListOf<Long>()
    }

    private suspend fun run(
        durationMs: Long?,
        senderUntilMs: Long?,
        nowMs: Long,
        fixResult: GpsFixResult,
        h: Harness,
    ): ShareBackResult = shareLiveLocationBack(
        durationMs = durationMs,
        senderLiveShareUntilMs = senderUntilMs,
        nowMs = nowMs,
        getFix = { fixResult },
        send = { h.sent += it },
        startRelay = { h.relayStarts += it },
    )

    @Test
    fun mirrorUsesSenderAbsoluteEndTimeNotNowPlusDuration() = runTest {
        val h = Harness()
        val senderUntil = 90_000L
        val result = run(durationMs = null, senderUntilMs = senderUntil, nowMs = 10_000L, fixResult = fix, h = h)
        assertEquals(ShareBackResult.Sent, result)
        assertEquals(senderUntil, h.sent.single().liveShareUntilMs)
        assertEquals(listOf(senderUntil), h.relayStarts)
    }

    @Test
    fun explicitDurationCountsFromNow() = runTest {
        val h = Harness()
        val result = run(durationMs = 15 * 60_000L, senderUntilMs = null, nowMs = 10_000L, fixResult = fix, h = h)
        assertEquals(ShareBackResult.Sent, result)
        assertEquals(10_000L + 15 * 60_000L, h.sent.single().liveShareUntilMs)
    }

    @Test
    fun descriptorAndRelayShareTheSameEndTime() = runTest {
        val h = Harness()
        run(durationMs = 60_000L, senderUntilMs = null, nowMs = 5_000L, fixResult = fix, h = h)
        assertEquals(h.sent.single().liveShareUntilMs, h.relayStarts.single())
    }

    @Test
    fun sentMessageIsLightweightAndOwn() = runTest {
        val h = Harness()
        run(durationMs = null, senderUntilMs = 90_000L, nowMs = 10_000L, fixResult = fix, h = h)
        val d = h.sent.single()
        assertEquals(52.5, d.lat)
        assertEquals(13.4, d.lon)
        assertEquals(false, d.hasImage)
        assertNull(d.imageWidth)
        assertNull(d.imageHeight)
        assertNull(d.caption)
        assertEquals("", d.address)
    }

    @Test
    fun mirroringAnEndedShareSendsNothing() = runTest {
        val h = Harness()
        val result = run(durationMs = null, senderUntilMs = 9_000L, nowMs = 10_000L, fixResult = fix, h = h)
        assertEquals(ShareBackResult.Expired, result)
        assertTrue(h.sent.isEmpty())
        assertTrue(h.relayStarts.isEmpty())
    }

    @Test
    fun mirroringAMessageWithoutAWindowSendsNothing() = runTest {
        val h = Harness()
        val result = run(durationMs = null, senderUntilMs = null, nowMs = 10_000L, fixResult = fix, h = h)
        assertEquals(ShareBackResult.Expired, result)
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun noGpsFixSendsNothing() = runTest {
        for (failure in listOf(GpsFixResult.PermissionDenied, GpsFixResult.Unavailable, GpsFixResult.Timeout)) {
            val h = Harness()
            val result = run(durationMs = 60_000L, senderUntilMs = null, nowMs = 10_000L, fixResult = failure, h = h)
            assertEquals(ShareBackResult.NoFix, result)
            assertTrue(h.sent.isEmpty())
            assertTrue(h.relayStarts.isEmpty())
        }
    }
}
