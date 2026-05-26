package id.homebase.api.sync.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.common.OdinId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Regression guard for PR C ("read-lane coverage audit"): every wrapper read
 * now routes through `DatabaseManager.readValue("<label>") { … }` so it shows
 * up in `SlowDbRead` logs and queues on the read lane instead of bypassing
 * both lanes. Pre-PR the reads in these wrappers were synchronous `fun`s
 * calling `delegate.x().executeAs*()` directly, which left them invisible to
 * the diagnostic + queue-wait split.
 *
 * The contract: a read that's gone through the lane updates
 * `DatabaseManager.lastReadTiming` (only `executeReadQuery` / `readValue`
 * populate that cell — writes don't, raw `executeAs*` outside the lane
 * doesn't). So the cheapest pin is: call the wrapper, assert `lastReadTiming`
 * is now non-null.
 *
 * The companion pin — that `selectByKeyBootstrapSync` deliberately does NOT
 * route (it's the documented escape hatch for class-constructor reads where
 * `runBlocking` is unavailable on wasmJs) — also lives here.
 */
class WrapperReadsRouteThroughLaneTest {

    private fun newDbm(): DatabaseManager =
        DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })

    @Test
    fun outboxReads_routeThroughLane() {
        runBlocking {
            val dbm = newDbm()
            assertNull(dbm.lastReadTiming, "fresh DBM should have no read timing")

            dbm.outbox.count()
            assertNotNull(
                dbm.lastReadTiming,
                "outbox.count() must route through readValue (populates lastReadTiming)",
            )

            dbm.outbox.nextScheduled()
            assertNotNull(dbm.lastReadTiming, "outbox.nextScheduled() must route through readValue")

            dbm.outbox.selectCheckedOut(0L)
            assertNotNull(dbm.lastReadTiming, "outbox.selectCheckedOut() must route through readValue")
        }
    }

    @Test
    fun keyValueSuspendRead_routesThroughLane() {
        runBlocking {
            val dbm = newDbm()
            val key = Uuid.fromLongs(0L, 1L)
            dbm.keyValue.upsertValue(key, byteArrayOf(1))
            // Baseline: only writes have happened, so lastReadTiming must still
            // be null (writes don't populate it). This makes the post-read
            // assertion meaningful — anything non-null after must have come
            // from the read we're about to do.
            assertNull(
                dbm.lastReadTiming,
                "writes should not populate lastReadTiming (baseline)",
            )

            val v = dbm.keyValue.selectByKey(key)
            assertNotNull(v)
            assertNotNull(
                dbm.lastReadTiming,
                "keyValue.selectByKey (suspend) must route through readValue",
            )
        }
    }

    @Test
    fun keyValueBootstrapSync_doesNotRouteThroughLane() {
        // Escape-hatch contract: the bootstrap-sync variant is the documented
        // exception. It runs synchronously on the caller's thread for
        // class-constructor reads (Vault/Moments/DiceRoll/CursorStorage init)
        // where `runBlocking` isn't available on wasmJs. Pin that it stays an
        // escape hatch — if someone "accidentally" routes it through readValue
        // in the future, the bootstrap callers would deadlock on Main from a
        // ViewModel constructor.
        runBlocking {
            val dbm = newDbm()
            val key = Uuid.fromLongs(0L, 1L)
            dbm.keyValue.upsertValue(key, byteArrayOf(1))
            assertNull(
                dbm.lastReadTiming,
                "writes should not populate lastReadTiming (baseline)",
            )

            val v = dbm.keyValue.selectByKeyBootstrapSync(key)
            assertNotNull(v, "bootstrap-sync read must still return the value")
            assertNull(
                dbm.lastReadTiming,
                "selectByKeyBootstrapSync must NOT route through readValue — it's the documented " +
                    "escape hatch for class-constructor reads. If this test fails, the bootstrap " +
                    "callers (VaultPreferences/MomentsPreferences/DiceRollPreferences/CursorStorage) " +
                    "may now deadlock on Main.",
            )
        }
    }

    @Test
    fun chatReadCountReads_routeThroughLane() {
        runBlocking {
            val dbm = newDbm()
            val identityId = Uuid.fromLongs(0L, 1L)
            val groupId = Uuid.fromLongs(0L, 2L)
            val selfDomain = OdinId("self.test")

            dbm.chatReadCount.selectLastReadTimeMs(groupId)
            assertNotNull(
                dbm.lastReadTiming,
                "chatReadCount.selectLastReadTimeMs must route through readValue",
            )

            dbm.chatReadCount.selectUnreadCountForConversation(identityId, groupId, selfDomain)
            assertNotNull(
                dbm.lastReadTiming,
                "chatReadCount.selectUnreadCountForConversation must route through readValue",
            )

            dbm.chatReadCount.selectOrphanedAtRestConversations(identityId, selfDomain.domainName)
            assertNotNull(
                dbm.lastReadTiming,
                "chatReadCount.selectOrphanedAtRestConversations must route through readValue",
            )
        }
    }

    @Test
    fun connectionCacheReads_routeThroughLane() {
        runBlocking {
            val dbm = newDbm()
            val identityId = Uuid.fromLongs(0L, 1L)

            dbm.connectionCache.selectByIdentity(identityId)
            assertNotNull(
                dbm.lastReadTiming,
                "connectionCache.selectByIdentity must route through readValue",
            )

            dbm.connectionCache.selectByIdentityAndStatus(identityId, "connected")
            assertNotNull(
                dbm.lastReadTiming,
                "connectionCache.selectByIdentityAndStatus must route through readValue",
            )
        }
    }

    @Test
    fun bootstrapSyncRoundTrip_isEquivalentToSuspendVariant() {
        // Beyond "doesn't route through the lane", the bootstrap-sync variant
        // must still produce the same row data as the suspend variant — they're
        // two windows onto the same row. Catches typos / wrong delegate calls
        // in the escape hatch.
        runBlocking {
            val dbm = newDbm()
            val key = Uuid.fromLongs(0L, 1L)
            val payload = byteArrayOf(0x42, 0x43)
            dbm.keyValue.upsertValue(key, payload)

            val viaSuspend = dbm.keyValue.selectByKey(key)
            val viaSync = dbm.keyValue.selectByKeyBootstrapSync(key)
            assertNotNull(viaSuspend)
            assertNotNull(viaSync)
            assertEquals(viaSuspend.key, viaSync.key)
            assertEquals(viaSuspend.data_.toList(), viaSync.data_.toList())
        }
    }
}
