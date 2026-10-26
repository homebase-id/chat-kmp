package id.homebase.api.sync.database.bench

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.drives.FileStateFilter
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.common.OdinId
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.QueryBatch
import id.homebase.api.sync.database.SqlCipherRawKey
import id.homebase.api.sync.database.cipherUrl
import id.homebase.api.sync.database.deleteDatabaseFiles
import id.homebase.api.sync.database.openSqliteDriver
import id.homebase.api.sync.database.tuningProperties
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Opt-in local benchmark for the chat database on the desktop driver. Skipped unless
 * `DB_BENCH=1`. See OPTIMIZE.md for how to run it and where results are recorded.
 */
class DbPerfBench {

    private val fixture = ChatDbFixture()
    private val dbFile = File(ChatDbFixture.benchDir(), fixture.shape.fileName)
    private val report = StringBuilder()
    private val recorded = LinkedHashMap<String, Recorded>()
    private var recordLabel: String? = null

    @Test
    fun bench() = runBlocking {
        assumeTrue("Set DB_BENCH=1 to run the database benchmark", System.getenv("DB_BENCH") == "1")
        val iterations = System.getenv("DB_BENCH_ITERATIONS")?.toInt() ?: 30

        ensureFixture()
        line("# DB bench ${Instant.now()}  commit=${gitCommit()}")
        line("driver=${if (legacyDriver) "legacy JdbcSqliteDriver" else "pooled"} db=${dbFile.name} size=${dbFile.length() / 1_048_576} MB, iterations=$iterations (median / p95 / min, ms)")
        line("")

        timed("open DatabaseManager (schema create + pragma audit)", iterations = 5) {
            newManager().close()
        }

        newManager().use { dbm ->
            line(shapeSummary(dbm))
            line("")
            val qb = QueryBatch(fixture.identityId)
            val largest = 0
            val median = fixture.conversationIds.size / 8
            val small = fixture.conversationIds.size - 10
            for ((label, c) in listOf("largest" to largest, "median" to median, "small" to small)) {
                val id = fixture.conversationIds[c]
                suspend fun page(cursor: QueryBatchCursor?) = qb.queryBatchAsync(
                    dbm = dbm, driveId = fixture.chatDrive, noOfItems = 75, cursor = cursor,
                    sortOrder = QueryBatchSortOrder.NewestFirst, sortField = QueryBatchSortField.UserDate,
                    fileSystemType = 0, fileState = FileStateFilter.All,
                    filetypesAnyOf = listOf(7878), groupIdAnyOf = listOf(id),
                )
                var olderCursor: QueryBatchCursor? = null
                timed("open conversation, first page of 75 ($label, ${fixture.messageCounts[c]} msgs)", iterations) {
                    olderCursor = page(null).cursor
                }
                timed("scroll up, next page of 75 ($label)", iterations) {
                    page(olderCursor)
                }
            }

            timed("conversation list + last message", iterations) {
                dbm.chatReadCount.selectAllConversationPlusLastMessage(fixture.identityId)
            }
            timed("unread counts", iterations) {
                dbm.chatReadCount.selectAllUnreadCount(fixture.identityId, OdinId(fixture.self))
            }
            timed("orphaned-at-rest scan (cold start)", iterations = 5) {
                dbm.chatReadCount.selectOrphanedAtRestConversations(fixture.identityId, fixture.self)
            }

            // A fresh seed per run, so every upsert inserts a new row. The rows stay in the fixture;
            // a few hundred extra on 57K don't move the read numbers.
            val incoming = ChatDbFixture(fixture.shape.copy(seed = System.nanoTime()))
            val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
            var nextUserDate = System.currentTimeMillis()
            timed("incoming message insert (1 row, largest conversation)", iterations) {
                val msg = incoming.newIncomingMessage(fixture.conversationIds[largest], nextUserDate++)
                processor.performBaseUpsert(fixture.identityId, fixture.chatDrive, listOf(msg), cursor = null)
            }

            line("")
            line("## Query plans")
            explainAll(dbm.driver)
        }

        val out = File(ChatDbFixture.benchDir(), "results-${Instant.now().toString().replace(':', '-')}.md")
        out.writeText(report.toString())
        println(report)
        println("Bench report written to ${out.absolutePath}")
    }

    private suspend fun ensureFixture() {
        if (dbFile.exists()) return
        val tmp = File(dbFile.path + ".tmp")
        deleteDatabaseFiles(tmp.path)
        val start = TimeSource.Monotonic.markNow()
        DatabaseManager({ driverFor(tmp) }).use { fixture.populate(it) }
        println("Generated ${tmp.name} in ${start.elapsedNow()}")
        check(tmp.renameTo(dbFile)) { "could not move fixture into place" }
    }

    // Production desktop's driver and URL (raw-key SQLCipher, shared pragmas).
    // DB_BENCH_DRIVER=legacy runs SQLDelight's connection-per-statement driver for comparison.
    private fun driverFor(file: File): SqlDriver {
        val url = cipherUrl(file, SqlCipherRawKey.format(KEY_HEX))
        return if (legacyDriver) JdbcSqliteDriver(url, tuningProperties()) else openSqliteDriver(url)
    }

    private val legacyDriver = System.getenv("DB_BENCH_DRIVER") == "legacy"

    private fun newManager() = DatabaseManager({ RecordingDriver(driverFor(dbFile)) })

    private suspend fun timed(label: String, iterations: Int, block: suspend () -> Unit) {
        recordLabel = label
        block()
        recordLabel = null
        repeat(WARMUP) { block() }
        val samples = List(iterations) {
            val mark = TimeSource.Monotonic.markNow()
            block()
            mark.elapsedNow()
        }.sorted()
        line(
            "%-70s %8s %8s %8s".format(
                label, samples.ms(samples.size / 2), samples.ms((samples.size * 95 / 100).coerceAtMost(samples.size - 1)), samples.ms(0),
            ),
        )
    }

    private fun List<Duration>.ms(i: Int) = "%.2f".format(Locale.ROOT, this[i].inWholeMicroseconds / 1000.0)

    private fun shapeSummary(dbm: DatabaseManager): String {
        val d = dbm.driver
        val rows = d.scalar("SELECT count(*) FROM DriveMainIndex")
        val avgHeader = d.scalar("SELECT avg(length(jsonHeader)) FROM DriveMainIndex")
        val pages = d.scalar("PRAGMA page_count")
        val pageSize = d.scalar("PRAGMA page_size")
        val indexes = d.scalar("SELECT count(*) FROM sqlite_master WHERE type='index' AND tbl_name='DriveMainIndex'")
        return "rows=$rows avgJsonHeader=${avgHeader}B pages=$pages pageSize=$pageSize driveMainIndexIndexes=$indexes " +
            "biggestConversations=${fixture.messageCounts.take(5)}"
    }

    private fun explainAll(driver: SqlDriver) {
        for ((label, r) in recorded) {
            line("")
            line("### $label")
            line("```sql")
            line(r.sql.trim())
            line("```")
            val plan = driver.executeQuery(null, "EXPLAIN QUERY PLAN ${r.sql}", { cursor ->
                val sb = StringBuilder()
                while (cursor.next().value) sb.append("  ").append(cursor.getString(3)).append('\n')
                QueryResult.Value(sb.toString())
            }, r.parameters, r.binders).value
            line("```")
            line(plan.trimEnd())
            line("```")
        }
    }

    private fun SqlDriver.scalar(sql: String): String =
        executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getString(0) ?: "" else "") }, 0).value

    private fun line(s: String) {
        report.append(s).append('\n')
    }

    private fun gitCommit(): String = runCatching {
        ProcessBuilder("git", "describe", "--always", "--dirty").start().inputStream.bufferedReader().readText().trim()
    }.getOrDefault("?")

    private class Recorded(
        val sql: String,
        val parameters: Int,
        val binders: (SqlPreparedStatement.() -> Unit)?,
    )

    // Captures the SQL of each benchmark's first run so its plan can be printed.
    private inner class RecordingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            recordLabel?.let { label ->
                if (!sql.trimStart().startsWith("PRAGMA", ignoreCase = true)) {
                    val key = if (label in recorded) "$label (${recorded.keys.count { it.startsWith(label) } + 1})" else label
                    recorded[key] = Recorded(sql, parameters, binders)
                }
            }
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }

    private companion object {
        const val WARMUP = 5
        const val KEY_HEX = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    }
}
