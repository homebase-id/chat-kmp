package id.homebase.core.location.tracking

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the routing seam (#835). The router reads exactly one flag — history — and **always**
 * delegates relay to the share service (which self-gates on its own roster). So the matrix the
 * router owns is the persist gate (#823): history on ⇒ persist; history off ⇒ no persist. In every
 * accepted case it publishes last-known and delegates relay; a fully-dropped batch routes nothing.
 */
class LocationFixRouterTest {

    private fun point(t: Long, lon: Double = 13.0) =
        RawLocationPoint(t = t, lat = 52.0, lon = lon, acc = 10.0, src = "gps", fg = true)

    private class NoSensors : DeviceSensors {
        override suspend fun batteryPercent(): Int? = null
        override suspend fun stepsSince(prevPointTimeMs: Long?, lastCumulative: Long?) =
            StepSample(null, null)
        override fun isPowerSaveMode(): Boolean = false
    }

    private class Captured {
        val persisted = mutableListOf<List<RawLocationPoint>>()
        val relayed = mutableListOf<RawLocationPoint>()
    }

    private fun runRouterTest(
        allowHistory: Boolean,
        body: suspend (LocationFixRouter, LocationPointStore, Captured) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val db = DatabaseManager(
            { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) },
            dispatcher = dispatcher,
            readDispatcher = dispatcher,
        )
        try {
            val store = LocationPointStore(db, NoSensors())
            val cap = Captured()
            val router = LocationFixRouter(
                store = store,
                allowHistory = { allowHistory },
                persistAsHistory = { cap.persisted += it },
                relayLatest = { cap.relayed += it },
            )
            body(router, store, cap)
        } finally {
            db.close()
        }
    }

    @Test
    fun historyOn_persistsRelaysAndPublishes() = runRouterTest(allowHistory = true) { router, store, cap ->
        router.submit(listOf(point(t = 1_000)))
        assertEquals(1, cap.persisted.size)
        assertEquals(1, cap.relayed.size)
        assertEquals(1_000, store.lastPoint.value?.t)
    }

    @Test
    fun historyOff_relaysAndPublishesButDoesNotPersist() = runRouterTest(allowHistory = false) { router, store, cap ->
        // #823: a live share holds GPS on with history off — relay, but never record history.
        router.submit(listOf(point(t = 1_000)))
        assertTrue(cap.persisted.isEmpty())
        assertEquals(1, cap.relayed.size)
        assertEquals(1_000, store.lastPoint.value?.t)
    }

    @Test
    fun relayReceivesTheLatestAcceptedFix() = runRouterTest(allowHistory = true) { router, _, cap ->
        // Two far-apart points in one batch → both accepted; relay gets the newest.
        router.submit(listOf(point(t = 1_000), point(t = 60_000, lon = 13.01)))
        assertEquals(60_000, cap.relayed.single().t)
        assertEquals(listOf(1_000L, 60_000L), cap.persisted.single().map { it.t })
    }

    @Test
    fun droppedBatchRoutesNothing() = runRouterTest(allowHistory = true) { router, store, cap ->
        store.publishLastKnown(point(t = 60_000, lon = 14.0))
        // Older than last-known → dedup drops it → no persist, no relay, last-known unchanged.
        router.submit(listOf(point(t = 30_000)))
        assertTrue(cap.persisted.isEmpty())
        assertTrue(cap.relayed.isEmpty())
        assertEquals(60_000, store.lastPoint.value?.t)
    }

    @Test
    fun emptyInputRoutesNothing() = runRouterTest(allowHistory = true) { router, _, cap ->
        router.submit(emptyList())
        assertTrue(cap.persisted.isEmpty())
        assertTrue(cap.relayed.isEmpty())
    }
}
