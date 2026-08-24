package id.homebase.api.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the #1109 background-observability line shapes. Downstream log tooling greps these, so the
 * exact formats are a contract, not an implementation detail.
 */
class BgTraceFormatTest {

    @Test
    fun transitionForegroundToBackgroundWithProfile() {
        assertEquals(
            "transition fg->bg windowMs=123456 profile=HistoryBackground",
            BgTrace.transition(toForeground = false, windowMs = 123456, profile = "HistoryBackground"),
        )
    }

    @Test
    fun transitionBackgroundToForegroundWithProfile() {
        assertEquals(
            "transition bg->fg windowMs=5000 profile=LiveForeground",
            BgTrace.transition(toForeground = true, windowMs = 5000, profile = "LiveForeground"),
        )
    }

    @Test
    fun transitionOmitsProfileWhenNull() {
        assertEquals(
            "transition fg->bg windowMs=42",
            BgTrace.transition(toForeground = false, windowMs = 42, profile = null),
        )
    }

    @Test
    fun wsConnectForeground() {
        assertEquals(
            "ws-connect state=fg url=wss://example.homebase.id/api/v2/notify/ws-token",
            BgTrace.wsConnect(foreground = true, url = "wss://example.homebase.id/api/v2/notify/ws-token"),
        )
    }

    @Test
    fun wsConnectBackgroundIsTheRedFlagShape() {
        // The greppable red flag #1108 targets: a background WS connect attempt.
        assertEquals(
            "ws-connect state=bg url=wss://example.homebase.id/api/v2/notify/ws-token",
            BgTrace.wsConnect(foreground = false, url = "wss://example.homebase.id/api/v2/notify/ws-token"),
        )
    }

    @Test
    fun wakeCauseLine() {
        assertEquals("wake cause=fcm priority=high", BgTrace.wake("fcm", "priority=high"))
        assertEquals("wake cause=location-delivery points=3", BgTrace.wake("location-delivery", "points=3"))
    }
}
