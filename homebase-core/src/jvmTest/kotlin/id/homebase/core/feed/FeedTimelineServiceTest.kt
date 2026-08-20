@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.config.feedLabeledDrive
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.FeedTimelineService
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class FeedTimelineServiceTest {

    private val feedDrive = feedLabeledDrive.drive.alias
    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias

    private fun keyHeader() = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16)))

    private fun postFile(
        uniqueId: Uuid,
        driveId: Uuid,
        caption: String,
        createdMs: Long,
        deleted: Boolean = false,
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
            fileState = if (deleted) FileState.Deleted else FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = keyHeader(),
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

    // A followed identity's post lands on the feed drive with a globalTransitId and no uniqueId.
    private fun feedReferenceFile(
        globalTransitId: Uuid,
        caption: String,
        createdMs: Long,
        deleted: Boolean = false,
    ): HomebaseFile {
        val base = postFile(globalTransitId, feedDrive, caption, createdMs, deleted)
        return base.copy(
            fileMetadata = base.fileMetadata.copy(
                globalTransitId = globalTransitId,
                appData = base.fileMetadata.appData.copy(uniqueId = null),
            ),
        )
    }

    private fun TestScope.newService(): Pair<FeedTimelineService, EventBus> {
        val eventBus = EventBus()
        // No active credentials → cold-load returns early; the test drives logic via BatchReceived.
        // Unconfined so the collector subscribes before the test emits; a StandardTestDispatcher
        // would leave it unsubscribed until the next dispatch and drop the batch.
        val serviceScope = kotlinx.coroutines.CoroutineScope(
            UnconfinedTestDispatcher(testScheduler)
        )
        val service = FeedTimelineService(
            databaseManager = DatabaseManager(driverProvider = { stubDriver }),
            credentialsManager = CredentialsManager(),
            eventBus = eventBus,
            scope = serviceScope,
        )
        return service to eventBus
    }

    private suspend fun emitBatch(eventBus: EventBus, driveId: Uuid, files: List<HomebaseFile>) {
        eventBus.emit(BackendEvent.DataEvent.BatchReceived(driveId, files))
    }

    @Test
    fun mergesPostsFromBothDrivesNewestFirstByCreatedMs() = runTest {
        val (service, eventBus) = newService()
        service.start()
        advanceUntilIdle()

        val older = postFile(Uuid.random(), feedDrive, "old followed post", createdMs = 1_000)
        val newer = postFile(Uuid.random(), feedDrive, "new followed post", createdMs = 3_000)
        val own = postFile(Uuid.random(), channelDrive, "my own post", createdMs = 2_000)

        emitBatch(eventBus, feedDrive, listOf(older, newer))
        emitBatch(eventBus, channelDrive, listOf(own))

        val timeline = service.timeline.value
        assertEquals(3, timeline.size)
        assertEquals(listOf("new followed post", "my own post", "old followed post"),
            timeline.map { it.caption })
    }

    @Test
    fun dedupsSamePostArrivingOnBothDrivesByUniqueId() = runTest {
        val (service, eventBus) = newService()
        service.start()
        advanceUntilIdle()

        val sharedId = Uuid.random()
        val onFeed = postFile(sharedId, feedDrive, "shared post", createdMs = 5_000)
        val onChannel = postFile(sharedId, channelDrive, "shared post", createdMs = 5_000)

        emitBatch(eventBus, feedDrive, listOf(onFeed))
        emitBatch(eventBus, channelDrive, listOf(onChannel))

        assertEquals(1, service.timeline.value.size)
        assertEquals(sharedId, service.timeline.value.single().id)
    }

    @Test
    fun feedReferenceWithoutUniqueIdStillAppliesIncrementally() = runTest {
        val (service, eventBus) = newService()
        service.start()
        advanceUntilIdle()

        // Keying the incremental path on uniqueId would drop every feed reference.
        val gtid = Uuid.random()
        emitBatch(eventBus, feedDrive, listOf(feedReferenceFile(gtid, "followed post", 4_000)))

        assertEquals(1, service.timeline.value.size)
        assertEquals(gtid, service.timeline.value.single().id)

        emitBatch(
            eventBus,
            feedDrive,
            listOf(feedReferenceFile(gtid, "followed post", 4_000, deleted = true)),
        )
        assertEquals(0, service.timeline.value.size)
    }

    @Test
    fun softDeleteRemovesPostAndResetClears() = runTest {
        val (service, eventBus) = newService()
        service.start()
        advanceUntilIdle()

        val id = Uuid.random()
        emitBatch(eventBus, feedDrive, listOf(postFile(id, feedDrive, "to delete", createdMs = 1_000)))
        assertEquals(1, service.timeline.value.size)

        emitBatch(eventBus, feedDrive,
            listOf(postFile(id, feedDrive, "to delete", createdMs = 1_000, deleted = true)))
        assertEquals(0, service.timeline.value.size)

        emitBatch(eventBus, feedDrive, listOf(postFile(Uuid.random(), feedDrive, "after", createdMs = 9_000)))
        assertEquals(1, service.timeline.value.size)
        service.reset()
        assertEquals(emptyList(), service.timeline.value)
    }

    private val stubDriver = object : SqlDriver {
        override fun close() {}
        override fun currentTransaction(): Transacter.Transaction? = null
        override fun execute(
            identifier: Int?, sql: String, parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> = QueryResult.Value(0L)

        override fun <R> executeQuery(
            identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            val emptyCursor = object : SqlCursor {
                override fun getBoolean(index: Int) = null
                override fun getBytes(index: Int) = null
                override fun getDouble(index: Int) = null
                override fun getLong(index: Int) = null
                override fun getString(index: Int) = null
                override fun next(): QueryResult<Boolean> = QueryResult.Value(false)
            }
            return mapper(emptyCursor)
        }

        override fun newTransaction(): QueryResult<Transacter.Transaction> {
            val tx = object : Transacter.Transaction() {
                override val enclosingTransaction: Transacter.Transaction? = null
                override fun endTransaction(successful: Boolean): QueryResult<Unit> =
                    QueryResult.Value(Unit)
            }
            return QueryResult.Value(tx)
        }

        override fun addListener(vararg queryKeys: String, listener: Query.Listener) {}
        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {}
        override fun notifyListeners(vararg queryKeys: String) {}
    }
}
