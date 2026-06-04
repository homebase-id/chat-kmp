package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Pins the `SlowDbRead` triple split (`queueWait` / `sql` / `mapper`).
 *
 * Background: before this split, `sql=` was actually "wall-clock spent inside
 * `driver.executeQuery`", which silently bundled mapper CPU — for QueryBatch
 * that's per-row `OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)`,
 * which can be the dominant cost. A multi-second `SlowDbRead sql=…` then
 * misleadingly pointed at the query plan when the real culprit was
 * deserialization. The cursor's `next()` is the genuine "row pull" call, so we
 * time it separately and report the residual as mapper CPU.
 *
 * These tests deliberately make the mapper sleep between `next()` calls so
 * `mapper=` accumulates predictably, and assert (a) the mapper time is at
 * least the sleep total and (b) it never exceeds the legacy total. A
 * no-work mapper test pins the other direction: real-SQL queries against the
 * in-memory driver report near-zero mapper time.
 */
class SlowDbReadTimingSplitTest {

    private fun newDbm(): DatabaseManager {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return DatabaseManager({ driver })
    }

    @Test
    fun mapperSleep_isAttributedToMapper_notSql() = runBlocking {
        val dbm = newDbm()
        val rows = 5
        // Seed rows so the cursor has something to iterate.
        repeat(rows) { i ->
            dbm.withWriteValue { db ->
                db.keyValueQueries.upsertValue(
                    Uuid.fromLongs(0L, i.toLong()),
                    byteArrayOf(i.toByte()),
                )
            }
        }

        val sleepPerRowMs = 30L
        dbm.executeReadQuery(
            identifier = null,
            sql = "SELECT key FROM KeyValue",
            mapper = { cursor ->
                while (cursor.next().value) {
                    // Mapper-CPU stand-in: this is exactly the window between
                    // `next()` calls that the TimingCursor classifies as
                    // mapper time rather than SQL. Production mappers (e.g.
                    // QueryBatch) do JSON deserialization here.
                    Thread.sleep(sleepPerRowMs)
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )

        val timing = assertNotNull(dbm.lastReadTiming, "no read timing recorded")
        val expectedMapperFloorMs = (sleepPerRowMs * rows) - 20 // small slack for jitter
        assertTrue(
            timing.mapperMs >= expectedMapperFloorMs,
            "mapper time should reflect sleep total; expected ≥${expectedMapperFloorMs}ms, " +
                "got mapperMs=${timing.mapperMs} sqlMs=${timing.sqlMs}",
        )
        assertTrue(
            timing.mapperMs <= timing.sqlMs,
            "mapper portion cannot exceed the total wall-clock spent in driver.executeQuery; " +
                "mapperMs=${timing.mapperMs} sqlMs=${timing.sqlMs}",
        )
        // Real-SQL = sqlMs - mapperMs should be tiny for an in-memory SELECT
        // — proves the split is doing its job, not just reporting `mapper ≈ sql`.
        val realSqlMs = timing.sqlMs - timing.mapperMs
        assertTrue(
            realSqlMs < 50,
            "the real-SQL residual should be small for an in-memory query; was ${realSqlMs}ms " +
                "(sqlMs=${timing.sqlMs} mapperMs=${timing.mapperMs})",
        )
    }

    @Test
    fun noWorkMapper_reportsNegligibleMapperTime() = runBlocking {
        val dbm = newDbm()
        dbm.withWriteValue { db ->
            db.keyValueQueries.upsertValue(Uuid.fromLongs(0L, 1L), byteArrayOf(1))
        }

        dbm.executeReadQuery(
            identifier = null,
            sql = "SELECT key FROM KeyValue",
            mapper = { cursor ->
                while (cursor.next().value) { /* no work */ }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )

        val timing = assertNotNull(dbm.lastReadTiming, "no read timing recorded")
        // Mapper time is measured in ms; sub-ms loop overhead rounds to 0 or 1.
        // Anything larger than a few ms means our accounting is wrong.
        assertTrue(
            timing.mapperMs <= 5,
            "no-work mapper should report near-zero mapper time; was ${timing.mapperMs}ms",
        )
    }

    @Test
    fun readValue_doesNotPopulateMapperSplit() = runBlocking {
        // The split lives only on the raw-SQL path; `readValue` (used by
        // SQLDelight typed queries) intentionally leaves mapperMs=0 because the
        // SQLDelight-generated row mapper is a thin code-gen constructor, not
        // a JSON deserialization, so splitting it would just be noise. Pin
        // that contract here so a future refactor doesn't quietly start
        // attributing typed-query mapper CPU to the wrong bucket.
        val dbm = newDbm()
        dbm.withWriteValue { db ->
            db.keyValueQueries.upsertValue(Uuid.fromLongs(0L, 1L), byteArrayOf(1))
        }

        dbm.readValue("test-typed-read") {
            // No-op block — the contract under test is that readValue leaves
            // mapperMs=0 regardless of what the block does.
        }

        val timing = assertNotNull(dbm.lastReadTiming, "no read timing recorded")
        assertTrue(
            timing.mapperMs == 0L,
            "readValue must leave mapperMs=0 (the split is opt-in for executeReadQuery); " +
                "was ${timing.mapperMs}",
        )
    }
}
