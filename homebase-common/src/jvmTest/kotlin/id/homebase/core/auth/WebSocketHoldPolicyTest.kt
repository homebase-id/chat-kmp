package id.homebase.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the #1108 background-WS policy: suppress the notify WS only when backgrounded on a
 * push-capable platform (Android/iOS). Foreground always keeps it; a platform with no push fallback
 * (Desktop/Web) always keeps it. Also covers the `wsHoldDecision` control flow and — the bug this
 * change nearly shipped — that parking the WS marks the connection offline (so the FCM→HTTP
 * background sync engages instead of being skipped as "WS online").
 */
class WebSocketHoldPolicyTest {

    @Test
    fun foregroundAlwaysKeepsTheWs() {
        assertTrue(shouldKeepWebSocketConnected(foreground = true, backgroundSyncViaPush = true))
        assertTrue(shouldKeepWebSocketConnected(foreground = true, backgroundSyncViaPush = false))
    }

    @Test
    fun backgroundedOnPushCapablePlatformSuppressesTheWs() {
        // The one case that changes: Android/iOS in the background rely on FCM/APNs + HTTP.
        assertFalse(shouldKeepWebSocketConnected(foreground = false, backgroundSyncViaPush = true))
    }

    @Test
    fun backgroundedWithoutPushFallbackKeepsTheWs() {
        // Desktop/Web have no push path, so the WS must stay up even when the window loses focus.
        assertTrue(shouldKeepWebSocketConnected(foreground = false, backgroundSyncViaPush = false))
    }

    /* ---- wsHoldDecision control flow ---- */

    @Test
    fun backgroundedOnPushPlatformWithLiveWsParks() {
        // The core #1108 behavior: an already-connected WS is closed when we go background.
        assertEquals(
            WsHoldAction.PARK,
            wsHoldDecision(foreground = false, backgroundSyncViaPush = true, wsPresent = true, authResolved = true),
        )
    }

    @Test
    fun foregroundingWithNoWsReconnectsOnceAuthResolved() {
        assertEquals(
            WsHoldAction.CONNECT,
            wsHoldDecision(foreground = true, backgroundSyncViaPush = true, wsPresent = false, authResolved = true),
        )
    }

    @Test
    fun doesNotConnectBeforeAuthResolves() {
        // The Authenticated branch owns the initial connect; applyWsHold must not race it in.
        assertEquals(
            WsHoldAction.NONE,
            wsHoldDecision(foreground = true, backgroundSyncViaPush = true, wsPresent = false, authResolved = false),
        )
    }

    @Test
    fun doesNotParkAWsThatIsAlreadyGone() {
        assertEquals(
            WsHoldAction.NONE,
            wsHoldDecision(foreground = false, backgroundSyncViaPush = true, wsPresent = false, authResolved = true),
        )
    }

    @Test
    fun desktopBackgroundedNeverParksEvenWithLiveWs() {
        // No push fallback → keep the WS; a present client stays (NONE, not PARK).
        assertEquals(
            WsHoldAction.NONE,
            wsHoldDecision(foreground = false, backgroundSyncViaPush = false, wsPresent = true, authResolved = true),
        )
    }

    @Test
    fun alreadyConnectedForegroundIsANoOp() {
        assertEquals(
            WsHoldAction.NONE,
            wsHoldDecision(foreground = true, backgroundSyncViaPush = true, wsPresent = true, authResolved = true),
        )
    }

    /* ---- the bug: parking must mark us offline ---- */

    @Test
    fun parkingTheWsMarksTheConnectionOffline() {
        // Regression guard for the bug found during implementation: OdinWebSocketClient.close() never
        // fires onDisconnected, so applyWsHold must flip the state offline itself. If it didn't,
        // isOnline (= connectionState.isConnected) would stay true and BackgroundSyncOrchestrator
        // would skip the FCM→HTTP background sync ("WS online — skipping").
        val connected = AuthConnectionState(isConnecting = false, isConnected = true)
        val parked = connectionStateAfterWsPark(connected)
        assertFalse(parked.isConnected, "a parked WS must report disconnected so background HTTP sync runs")
        assertFalse(parked.isConnecting, "a parked WS is not mid-connect")
    }
}
