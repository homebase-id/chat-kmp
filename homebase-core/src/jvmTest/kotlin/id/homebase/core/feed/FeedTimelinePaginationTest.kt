@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

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
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Cursored paging over the two source drives, exercised against a REAL in-memory index
 * ([FeedTestEnv]) — the stub driver in `FeedTimelineServiceTest` hands back an empty cursor, so it
 * can only cover the incremental (`BatchReceived`) path, never the QueryBatch cursor itself.
 *
 * The service is driven through directly-awaited suspend calls ([FeedTimelineService.refresh] /
 * [FeedTimelineService.loadMore]) rather than `start()` + `advanceUntilIdle()`: the cold-load
 * `start()` launches into the service's own scope is NOT drained by `advanceUntilIdle`, so a test
 * that relied on it would race its own assertions (and leave a second cold-load in flight, which
 * the generation guard would then make one of the two discard).
 */
class FeedTimelinePaginationTest {

    private val feedDrive = feedLabeledDrive.drive.alias
    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias

    /** Mirrors the service's private `PageSize`; the assertions below are about that boundary. */
    private val pageSize = 30

    private val feedCount = 70
    private val channelCount = 5

    /** Feed drive is deeper than one page; channel drive fits in one. */
    private val firstPageSize = pageSize + channelCount

    // ---------------------------------------------------------------- tests

    @Test
    fun coldLoadEmitsOnlyTheFirstPagePerDriveAndEndReachedStaysFalse() = feedTest { env, service ->
        val feedIds = env.seedBothDrives()

        service.refresh()

        assertEquals(firstPageSize, service.timeline.value.size)
        assertFalse(service.endReached.value, "the feed drive still has rows")

        val shown = service.timeline.value.map { it.id }.toSet()
        assertTrue(feedIds.last() in shown, "newest feed post is on page 1")
        assertFalse(feedIds.first() in shown, "oldest feed post is beyond page 1")
    }

    @Test
    fun loadMoreWalksEveryDriveToExhaustionThenFlipsEndReached() = feedTest { env, service ->
        env.seedBothDrives()
        service.refresh()

        val sizes = drain(service)

        assertEquals(listOf(firstPageSize + pageSize, feedCount + channelCount), sizes)
        assertTrue(service.endReached.value, "both drives ran dry")
        assertEquals(
            feedCount + channelCount,
            service.timeline.value.map { it.id }.toSet().size,
            "no post appeared twice across pages",
        )
        val createdMs = service.timeline.value.map { it.createdMs }
        assertEquals(createdMs.sortedDescending(), createdMs, "still newest-first after paging")
    }

    @Test
    fun loadMoreAfterExhaustionIsANoOp() = feedTest { env, service ->
        env.seedBothDrives()
        service.refresh()
        drain(service)

        val exhausted = service.timeline.value.map { it.id }
        assertEquals(feedCount + channelCount, exhausted.size)
        assertTrue(service.endReached.value)

        service.loadMore()
        service.loadMore()

        assertEquals(exhausted, service.timeline.value.map { it.id })
        assertTrue(service.endReached.value)
    }

    @Test
    fun refreshAfterPagingRewindsToTheFirstPage() = feedTest { env, service ->
        val feedIds = env.seedBothDrives()
        service.refresh()
        drain(service)
        assertEquals(feedCount + channelCount, service.timeline.value.size)

        service.refresh()

        assertEquals(firstPageSize, service.timeline.value.size)
        assertFalse(service.endReached.value, "the rewound cursor has rows again")
        assertEquals(
            firstPageSize,
            service.timeline.value.map { it.id }.toSet().size,
            "the rebuild did not duplicate anything",
        )
        assertFalse(
            feedIds.first() in service.timeline.value.map { it.id }.toSet(),
            "posts paged in before the refresh were dropped, not left stale",
        )

        // The rewound cursors still page correctly.
        drain(service)
        assertEquals(feedCount + channelCount, service.timeline.value.size)
    }

    @Test
    fun incrementalBatchWhilePagedStillAppears() = feedTest { env, service ->
        env.seedBothDrives()

        // start() is what subscribes the EventBus collector; await its cold-load through the
        // timeline rather than advanceUntilIdle (see the class KDoc) so no second one is in flight.
        service.start()
        advanceUntilIdle()
        service.timeline.first { it.isNotEmpty() }
        service.loadMore()
        val paged = service.timeline.value.size
        assertEquals(firstPageSize + pageSize, paged)

        // A live WebSocket push for a post that is in no page of the local index.
        val pushed = postFile(Uuid.random(), feedDrive, "pushed", createdMs = 9_000_000)
        env.eventBus.emit(BackendEvent.DataEvent.BatchReceived(feedDrive, listOf(pushed)))
        advanceUntilIdle()

        assertEquals(paged + 1, service.timeline.value.size)
        assertEquals(pushed.fileMetadata.appData.uniqueId, service.timeline.value.first().id)
    }

    @Test
    fun dedupsAcrossDrivesWhilePaging() = feedTest { env, service ->
        val feedIds = env.seedBothDrives()

        // The same post on both drives, republished on the channel drive with an older userDate so
        // the two copies land on different pages.
        val shared = feedIds.last()
        env.seed(channelDrive, postFile(shared, channelDrive, "shared", createdMs = 1_500_000))

        service.refresh()
        drain(service)

        assertEquals(feedCount + channelCount, service.timeline.value.size)
        assertEquals(1, service.timeline.value.count { it.id == shared })
    }

    // ---------------------------------------------------------------- helpers

    /** Pages to exhaustion, returning the timeline size after each page. */
    private suspend fun drain(service: FeedTimelineService): List<Int> {
        val sizes = mutableListOf<Int>()
        // Bounded so a cursor that stops advancing fails the test instead of hanging it.
        repeat(20) {
            if (service.endReached.value) return sizes
            service.loadMore()
            sizes += service.timeline.value.size
        }
        error("loadMore never reached the end — cursor is not advancing")
    }

    private fun feedTest(body: suspend TestScope.(FeedTestEnv, FeedTimelineService) -> Unit) =
        runTest {
            val env = FeedTestEnv(this)
            try {
                env.login()
                val service = FeedTimelineService(
                    databaseManager = env.databaseManager,
                    credentialsManager = env.credentialsManager,
                    eventBus = env.eventBus,
                    // Unconfined so start()'s EventBus collector subscribes eagerly — on a
                    // StandardTestDispatcher it stays unsubscribed and emitted batches are dropped
                    // (same reasoning as FeedTimelineServiceTest.newService).
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                )
                body(env, service)
            } finally {
                env.close()
            }
        }

    /** Seeds both drives; returns the feed drive's uniqueIds oldest → newest. */
    private suspend fun FeedTestEnv.seedBothDrives(): List<Uuid> {
        val feedIds = seedPosts(feedDrive, feedCount, baseMs = 2_000_000)
        seedPosts(channelDrive, channelCount, baseMs = 1_000_000)
        return feedIds
    }

    private suspend fun FeedTestEnv.seedPosts(
        driveId: Uuid,
        count: Int,
        baseMs: Long,
    ): List<Uuid> = (0 until count).map { i ->
        val id = Uuid.random()
        seed(driveId, postFile(id, driveId, "post $i", createdMs = baseMs + i))
        id
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
