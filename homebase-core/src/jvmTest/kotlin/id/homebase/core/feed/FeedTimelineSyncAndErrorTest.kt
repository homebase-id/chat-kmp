@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.core.config.feedLabeledDrive
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.FeedTimelineService
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * What a completed drive sync does to an already-paged timeline, and what a failed cold load
 * reports. Both run against the REAL in-memory index ([FeedTestEnv]) so the QueryBatch cursors are
 * the real ones — the paging behaviour under test is entirely about those.
 */
class FeedTimelineSyncAndErrorTest {

    private val feedDrive = feedLabeledDrive.drive.alias
    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias

    /** Mirrors the service's private `PageSize`. */
    private val pageSize = 30

    private val feedCount = 70
    private val channelCount = 5
    private val firstPageSize = pageSize + channelCount

    // ---------------------------------------------------------------- sync

    @Test
    fun stoppedSyncAfterPagingKeepsThePagedPostsAndMergesNewOnesAtTheHead() =
        feedTest { env, service ->
            env.seedBothDrives()
            service.start()
            advanceUntilIdle()
            service.timeline.first { it.isNotEmpty() }
            service.loadMore()
            val paged = service.timeline.value.size
            assertEquals(firstPageSize + pageSize, paged)

            // A bulk sync landed a post that no live push announced.
            env.seed(feedDrive, postFile(Uuid.random(), feedDrive, "synced", createdMs = 9_000_000))
            env.eventBus.emit(stopped(feedDrive, totalCount = 1))
            advanceUntilIdle()

            assertEquals(paged + 1, service.timeline.value.size, "paged posts were not discarded")
            assertEquals("synced", service.timeline.value.first().caption)
            assertFalse(service.endReached.value, "the paging cursor was left where it was")
        }

    @Test
    fun stoppedSyncTopUpWalksDownUntilItOverlapsWhatIsAlreadyLoaded() = feedTest { env, service ->
        env.seedBothDrives()
        service.start()
        advanceUntilIdle()
        service.timeline.first { it.isNotEmpty() }
        service.loadMore()
        val paged = service.timeline.value.size

        // More new posts than one page holds: a single-page top-up would leave a hole between the
        // newest 30 and the pages the user already scrolled through.
        val arrived = pageSize + 10
        env.seedPosts(feedDrive, arrived, baseMs = 9_000_000)
        env.eventBus.emit(stopped(feedDrive, totalCount = arrived))
        advanceUntilIdle()

        assertEquals(paged + arrived, service.timeline.value.size, "no gap below the new posts")
        val createdMs = service.timeline.value.map { it.createdMs }
        assertEquals(createdMs.sortedDescending(), createdMs, "still newest-first after the merge")
    }

    @Test
    fun stoppedSyncBeforeAnyPagingRebuildsTheCursorsSoTheFeedStaysPageable() =
        feedTest { env, service ->
            // Cold-load an empty index: every cursor is recorded as depleted.
            service.start()
            advanceUntilIdle()
            assertTrue(service.endReached.value)

            // The first sync fills the index. Merging alone would leave those depleted cursors in
            // place and the drive unpageable, so this case must still cold-load.
            env.seedBothDrives()
            env.eventBus.emit(stopped(feedDrive, totalCount = feedCount))
            advanceUntilIdle()
            service.timeline.first { it.isNotEmpty() }

            assertEquals(firstPageSize, service.timeline.value.size)
            assertFalse(service.endReached.value, "cursors were rebuilt against the filled index")

            service.loadMore()
            assertEquals(firstPageSize + pageSize, service.timeline.value.size)
        }

    // ---------------------------------------------------------------- errors

    @Test
    fun failedColdLoadIsReportedOnLoadErrorAndClearsWhenRetrySucceeds() = runTest {
        lateinit var driver: FailableReadDriver
        val env = FeedTestEnv(this, wrapDriver = { FailableReadDriver(it).also { d -> driver = d } })
        try {
            env.login()
            val service = newService(env)
            env.seedPosts(feedDrive, 3, baseMs = 1_000_000)

            service.refresh()
            assertEquals(3, service.timeline.value.size)
            assertNull(service.loadError.value, "a load that worked reports no error")

            driver.failReads = true
            service.refresh()

            assertNotNull(
                service.loadError.value,
                "a failed read must be distinguishable from an empty feed",
            )
            assertEquals(3, service.timeline.value.size, "the failure did not wipe the timeline")

            driver.failReads = false
            service.refresh()

            assertNull(service.loadError.value, "retry cleared the error")
            assertEquals(3, service.timeline.value.size)
        } finally {
            env.close()
        }
    }

    @Test
    fun successfulButEmptyColdLoadReportsNoError() = feedTest { _, service ->
        service.refresh()

        assertNull(service.loadError.value)
        assertEquals(emptyList(), service.timeline.value)
    }

    // ---------------------------------------------------------------- helpers

    private fun stopped(driveId: Uuid, totalCount: Int) = BackendEvent.DriveEvent.Stopped(
        driveId = driveId,
        totalCount = totalCount,
        result = BackendEvent.DriveResult.Completed,
    )

    private fun TestScope.newService(env: FeedTestEnv) = FeedTimelineService(
        databaseManager = env.databaseManager,
        credentialsManager = env.credentialsManager,
        eventBus = env.eventBus,
        // Unconfined so start()'s EventBus collector subscribes eagerly — on a StandardTestDispatcher
        // it stays unsubscribed and emitted events are dropped (as in FeedTimelinePaginationTest).
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
    )

    private fun feedTest(body: suspend TestScope.(FeedTestEnv, FeedTimelineService) -> Unit) =
        runTest {
            val env = FeedTestEnv(this)
            try {
                env.login()
                body(env, newService(env))
            } finally {
                env.close()
            }
        }

    private suspend fun FeedTestEnv.seedBothDrives() {
        seedPosts(feedDrive, feedCount, baseMs = 2_000_000)
        seedPosts(channelDrive, channelCount, baseMs = 1_000_000)
    }

    private suspend fun FeedTestEnv.seedPosts(driveId: Uuid, count: Int, baseMs: Long) {
        repeat(count) { i ->
            val id = Uuid.random()
            seed(driveId, postFile(id, driveId, "post $i", createdMs = baseMs + i))
        }
    }

    private suspend fun FeedTestEnv.seed(driveId: Uuid, file: HomebaseFile) {
        val identityId = credentialsManager.getActiveCredentials()!!.getIdentityId()
        val record = MainIndexMetaHelpers.HomebaseFileProcessor(databaseManager)
            .convertFileHeaderToDriveMainIndexRecord(identityId, driveId, file)
        MainIndexMetaHelpers.upsertDriveMainIndex(databaseManager, record)
    }

    private fun postFile(
        uniqueId: Uuid,
        driveId: Uuid,
        caption: String,
        createdMs: Long,
    ): HomebaseFile {
        val content = OdinSystemSerializer.serialize(
            PostContent(
                version = FeedProtocol.PostVersion,
                id = uniqueId.toString(),
                channelId = channelDrive.toString(),
                type = PostType.Tweet,
                caption = caption,
                slug = caption,
            )
        )
        return HomebaseFile(
            fileId = Uuid.random(),
            driveId = driveId,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
            fileMetadata = FileMetadata(
                created = UnixTimeUtc(createdMs),
                updated = UnixTimeUtc(createdMs),
                appData = AppFileMetaData(
                    uniqueId = uniqueId,
                    fileType = FeedProtocol.PostFileType,
                    userDate = createdMs,
                    content = content,
                ),
            ),
            serverMetadata = ServerMetadata(),
        )
    }
}

/**
 * Passes everything through to the real in-memory driver until [failReads] is flipped, from which
 * point every read throws — the DB-read failure the cold load has to report rather than swallow.
 */
private class FailableReadDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    var failReads = false

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> =
        if (failReads) {
            throw IllegalStateException("index read refused")
        } else {
            delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }
}
