package id.homebase.chat.services.livelocation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.common.OdinId
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.core.location.tracking.DeviceSensors
import id.homebase.core.location.tracking.LocationPointStore
import id.homebase.core.location.tracking.RawLocationPoint
import id.homebase.core.location.tracking.StepSample
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the relay half of the #823 matrix: [LiveLocationShareService.relayLatest] relays to live
 * recipients, no-ops when nothing is live, honours the 3 s throttle, and drops the GPS hold when the
 * last share expires. Uses the injected `relay` seam (no HttpClient) + a controllable clock.
 */
class LiveLocationShareServiceTest {

    private fun point(t: Long) =
        RawLocationPoint(t = t, lat = 52.0, lon = 13.0, acc = 10.0, src = "gps", fg = true)

    private class NoSensors : DeviceSensors {
        override suspend fun batteryPercent(): Int? = null
        override suspend fun stepsSince(prevPointTimeMs: Long?, lastCumulative: Long?) = StepSample(null, null)
        override fun isPowerSaveMode(): Boolean = false
    }

    private class Harness {
        val relayed = mutableListOf<List<String>>()
        var holdChanges = 0
    }

    private fun runShareTest(
        clock: () -> Long,
        body: suspend (LiveLocationShareService, Harness) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val db = DatabaseManager(
            {
                val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
                OdinDatabase.Schema.create(driver)
                driver
            },
            dispatcher = dispatcher,
            readDispatcher = dispatcher,
        )
        try {
            val h = Harness()
            val service = LiveLocationShareService(
                relay = { _, recipients, _ -> h.relayed += recipients; true },
                locationPointStore = LocationPointStore(db, NoSensors()),
                databaseManager = db,
                onLiveShareChanged = { h.holdChanges++ },
                nowMs = clock,
            )
            body(service, h)
        } finally {
            db.close()
        }
    }

    @Test
    fun relaysToLiveRecipient() {
        var now = 5_000L // > 3 s so the first relay clears the throttle (lastSentMs starts 0)
        runShareTest({ now }) { service, h ->
            service.start(listOf(OdinId("alice.com")), endTimeMs = now + 3_600_000L)
            service.relayLatest(point(t = now))
            assertEquals(listOf(listOf("alice.com")), h.relayed)
        }
    }

    @Test
    fun doesNotRelayWhenNoShare() {
        var now = 5_000L
        runShareTest({ now }) { service, h ->
            service.relayLatest(point(t = now))
            assertTrue(h.relayed.isEmpty())
        }
    }

    @Test
    fun throttlesWithinThreeSeconds() {
        var now = 5_000L
        runShareTest({ now }) { service, h ->
            service.start(listOf(OdinId("alice.com")), endTimeMs = now + 3_600_000L)
            service.relayLatest(point(t = now))          // sent (now=5000)
            now = 6_000L
            service.relayLatest(point(t = now))          // throttled (1 s < 3 s)
            assertEquals(1, h.relayed.size)
            now = 9_000L
            service.relayLatest(point(t = now))          // sent (4 s >= 3 s)
            assertEquals(2, h.relayed.size)
        }
    }

    @Test
    fun expiredShareDropsHoldAndDoesNotRelay() {
        var now = 5_000L
        runShareTest({ now }) { service, h ->
            service.start(listOf(OdinId("alice.com")), endTimeMs = 10_000L)
            val holdChangesAfterStart = h.holdChanges
            now = 20_000L // past the share's end time
            service.relayLatest(point(t = now))
            assertTrue(h.relayed.isEmpty())
            // The roster emptied on this fix → coordinator told to re-evaluate (drop the GPS hold).
            assertTrue(h.holdChanges > holdChangesAfterStart)
        }
    }
}
