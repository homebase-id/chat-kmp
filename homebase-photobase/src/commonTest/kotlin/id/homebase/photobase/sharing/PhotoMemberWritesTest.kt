package id.homebase.photobase.sharing

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.shares.ShareRequest
import id.homebase.api.client.drives.shares.ShareRow
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.common.OdinId
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DrivePermissionSet
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.async
import id.homebase.upload.MediaUploadSpec
import id.homebase.upload.UploadOutcome
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class PhotoMemberWritesTest {
    private val frodo = OdinId("frodo.test")
    private val circle = Uuid.random()
    private val row = ShareRow(circle, DrivePermissionSet(listOf(DrivePermission.Read, DrivePermission.React, DrivePermission.Comment)))
    private val album = SharedAlbum(frodo, Uuid.random(), "Trip", listOf(row), canAdd = true)
    private val frodosKey = ByteArray(97) { 4 }
    private val uniqueId = Uuid.random()
    private val events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 8)
    private val enqueued = mutableListOf<MediaUploadSpec>()
    private var onFrodosDrive = true

    private fun spec() = MediaUploadSpec(
        driveId = PhotobaseProtocol.DriveAlias,
        uniqueId = uniqueId,
        keyHeader = KeyHeader.newRandom16(),
        bundle = null,
        metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            accessControlList = AccessControlList(requiredSecurityGroup = "owner"),
            appData = UploadAppFileMetaData(uniqueId = uniqueId, fileType = PhotobaseProtocol.PhotoFileType, dataType = PhotobaseProtocol.ImageDataType),
        ),
        writeOptimistic = false,
        seedCache = false,
        existingFileIsSuccess = true,
    )

    private fun TestScope.writes() = PhotoMemberWrites(
        build = { _, _, _, _, send -> send(uniqueId, spec()) },
        sharesOfNewPhoto = { shared, _, gtid -> shared.shares.map { ShareRequest(Uuid.NIL, it.circleId, 13, lockedFileKey = gtid.toByteArray()) } },
        ownersDriveKey = { frodosKey },
        enqueue = { spec -> enqueued += spec; UploadOutcome.Enqueued(spec.uniqueId, null) },
        landed = { _, _ -> onFrodosDrive },
        outboxEvents = events,
        scope = backgroundScope,
        confirmDelays = listOf(1.seconds, 1.seconds),
        discard = { discarded += it },
    )

    private val discarded = mutableListOf<String>()

    @Test
    fun aPhotoGoesToTheOwnersDriveSealedTaggedAndSharedWithTheAlbumsCircles() = runTest {
        val add = writes().add(album, "/picked.jpg", "image/jpeg", "picked.jpg", 1_000)

        val sent = enqueued.single()
        assertEquals(PhotoMemberWrites.DEPOSIT_DRIVE, sent.driveId, "sent from the transient drive")
        assertEquals(listOf(frodo), sent.transit?.recipients)
        assertEquals(true, sent.transit?.isTransient)
        assertEquals(PhotobaseProtocol.DriveAlias, sent.transit?.remoteTargetDrive?.alias)
        assertContentEquals(frodosKey, sent.sealedTo)
        assertEquals(add.globalTransitId, sent.globalTransitId)
        assertEquals(listOf(circle), sent.shares?.map { it.circleId })
        assertContentEquals(add.globalTransitId.toByteArray(), sent.shares?.single()?.lockedFileKey, "locked for the id picked here")
        assertEquals(listOf(album.id), sent.metadata.appData.tags, "the album's app tag")
        assertEquals(uniqueId, sent.metadata.appData.uniqueId, "the id a backup of it gets")
        assertEquals(false, sent.existingFileIsSuccess)
    }

    @Test
    fun aPhotoSentAndFoundOnTheOwnersDriveIsDelivered() = runTest {
        val writes = writes()
        val add = writes.add(album, "/picked.jpg", "image/jpeg", "picked.jpg", 1_000)
        val outcome = backgroundScope.async { writes.outcomes.first() }
        testScheduler.runCurrent()

        events.emit(BackendEvent.OutboxEvent.ItemCompleted(PhotoMemberWrites.DEPOSIT_DRIVE, uniqueId))

        assertEquals(PhotoAddOutcome.Delivered(add), outcome.await())
    }

    @Test
    fun aPhotoSentButNotOnTheOwnersDriveIsNotConfirmed() = runTest {
        onFrodosDrive = false
        val writes = writes()
        val add = writes.add(album, "/picked.jpg", "image/jpeg", "picked.jpg", 1_000)
        val outcome = backgroundScope.async { writes.outcomes.first() }
        testScheduler.runCurrent()

        events.emit(BackendEvent.OutboxEvent.ItemCompleted(PhotoMemberWrites.DEPOSIT_DRIVE, uniqueId))

        assertEquals(PhotoAddOutcome.NotConfirmed(add), outcome.await())
    }

    @Test
    fun aPhotoTheOutboxGaveUpOnIsRefused() = runTest {
        val writes = writes()
        val add = writes.add(album, "/picked.jpg", "image/jpeg", "picked.jpg", 1_000)
        val outcome = backgroundScope.async { writes.outcomes.first() }
        testScheduler.runCurrent()

        events.emit(BackendEvent.OutboxEvent.OutboxItemDropped(PhotoMemberWrites.DEPOSIT_DRIVE, uniqueId, attempts = 5))

        assertEquals(PhotoAddOutcome.Refused(add), outcome.await())
    }

    @Test
    fun anAlbumThatTakesNoPhotosFromThisIdentitySendsNothing() = runTest {
        assertFailsWith<IllegalStateException> { writes().add(album.copy(canAdd = false), "/picked.jpg", "image/jpeg", "picked.jpg", 1_000) }
        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun anAlbumReachingThisIdentityThroughNoShareSendsNothing() = runTest {
        assertFailsWith<IllegalStateException> { writes().add(album.copy(shares = emptyList()), "/picked.jpg", "image/jpeg", "picked.jpg", 1_000) }
        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun theAppsCopyOfAPickIsDeletedOnceSentAndThePicksOwnFileNever() = runTest {
        val writes = writes()
        writes.add(album, "/copy.jpg", "image/jpeg", "picked.jpg", 1_000, ownedCopy = true)
        testScheduler.runCurrent()
        assertEquals(emptyList(), discarded, "kept while the outbox reads it")

        events.emit(BackendEvent.OutboxEvent.ItemCompleted(PhotoMemberWrites.DEPOSIT_DRIVE, uniqueId))
        testScheduler.runCurrent()
        writes.add(album, "/picked.jpg", "image/jpeg", "picked.jpg", 1_000)
        events.emit(BackendEvent.OutboxEvent.ItemCompleted(PhotoMemberWrites.DEPOSIT_DRIVE, uniqueId))
        testScheduler.runCurrent()

        assertEquals(listOf("/copy.jpg"), discarded)
    }

    @Test
    fun theAppsCopyOfAPickThatCouldntBeQueuedIsDeleted() = runTest {
        assertFailsWith<IllegalStateException> { writes().add(album.copy(shares = emptyList()), "/copy.jpg", "image/jpeg", "picked.jpg", 1_000, ownedCopy = true) }
        assertEquals(listOf("/copy.jpg"), discarded)
    }
}
