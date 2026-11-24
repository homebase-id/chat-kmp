package id.homebase.photobase.gallery

import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.files.PerFileResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class PhotoBinTest {
    /** The server as odin-core answers: a write lands only at the file's current version, and gives it a new one. */
    private class Server : PhotoBinServer {
        val files = mutableMapOf<Uuid, HomebaseFile>()
        val sent = mutableListOf<List<Uuid>>()
        var refuseWith: OdinClientErrorCode? = null
        // Requests from this one on throw, as a connection lost partway would.
        var throwFromRequest = Int.MAX_VALUE
        // Another device writing each photo again just before every write of this one.
        var alwaysAhead = false

        override suspend fun setArchivalStatus(status: ArchivalStatus, files: List<FileVersionTag>) =
            write(files) { it.at(status) }

        override suspend fun softDelete(files: List<FileVersionTag>) =
            write(files) { it.copy(fileState = FileState.Deleted) }

        var headersFail = false

        override suspend fun header(fileId: Uuid): HomebaseFile? {
            if (headersFail) throw IllegalStateException("offline")
            return files[fileId]
        }

        private fun write(tags: List<FileVersionTag>, change: (HomebaseFile) -> HomebaseFile): List<PerFileResult> {
            if (sent.size >= throwFromRequest) throw IllegalStateException("offline")
            sent += tags.map { it.fileId }
            return tags.map { tag ->
                if (alwaysAhead) files[tag.fileId]?.let { files[tag.fileId] = it.newVersion() }
                val file = files[tag.fileId]
                val code = when {
                    refuseWith != null -> refuseWith
                    file == null -> OdinClientErrorCode.FileNotFound
                    file.fileState != FileState.Active -> OdinClientErrorCode.CannotUpdateNonActiveFile
                    file.fileMetadata.versionTag != tag.versionTag -> OdinClientErrorCode.VersionTagMismatch
                    else -> null
                }
                if (code == null) files[tag.fileId] = change(file!!).newVersion()
                PerFileResult(tag.fileId, code)
            }
        }
    }

    private val server = Server()
    // The device's copy, as the last sync left it.
    private val device = mutableMapOf<Uuid, HomebaseFile>()
    private var syncs = 0
    private val bin = PhotoBin(
        server = server,
        filesOf = { ids -> ids.mapNotNull { device[it] } },
        binned = { device.values.filter { PhotoShelf.Bin.holds(it) } },
        sync = { syncs++ },
    )

    private fun photo(status: ArchivalStatus = ArchivalStatus.None): HomebaseFile {
        val file = galleryTestFile(0).at(status).newVersion()
        server.files[file.fileId] = file
        device[file.fileId] = file
        return file
    }

    private fun statusOnServer(file: HomebaseFile) = server.files.getValue(file.fileId).let { if (it.fileState == FileState.Deleted) null else it.fileMetadata.appData.archivalStatus }

    @Test
    fun aMoveSendsEachPhotoAtTheVersionTheDeviceHoldsThenSyncsOnce() = runTest {
        val photos = List(3) { photo() }

        val result = bin.moveToBin(photos.map { it.fileId })

        assertEquals(PhotoBinResult(done = 3), result.counts())
        assertEquals(List(3) { ArchivalStatus.Removed }, photos.map(::statusOnServer))
        assertEquals(1, server.sent.size, "one request for all")
        assertEquals(1, syncs)
    }

    @Test
    fun aPhotoWrittenElsewhereSinceIsSentAgainWhenItIsStillWhereThisDeviceSawIt() = runTest {
        val photo = photo()
        server.files[photo.fileId] = server.files.getValue(photo.fileId).newVersion()

        val result = bin.moveToBin(listOf(photo.fileId))

        assertEquals(PhotoBinResult(done = 1), result.counts())
        assertEquals(ArchivalStatus.Removed, statusOnServer(photo))
        assertEquals(2, server.sent.size)
    }

    @Test
    fun anotherDevicesRestoreStandsAgainstADeleteForGood() = runTest {
        val photo = photo(ArchivalStatus.Removed)
        server.files[photo.fileId] = server.files.getValue(photo.fileId).at(ArchivalStatus.None).newVersion()

        val result = bin.deleteForGood(listOf(photo.fileId))

        assertEquals(PhotoBinResult(done = 0, changedElsewhere = 1), result.counts())
        assertEquals(ArchivalStatus.None, statusOnServer(photo), "the photo another device brought back stays")
        assertEquals(1, server.sent.size, "not sent again")
    }

    @Test
    fun aPhotoAnotherDeviceAlreadyMovedTheSameWayIsDone() = runTest {
        val photo = photo(ArchivalStatus.Removed)
        server.files[photo.fileId] = server.files.getValue(photo.fileId).at(ArchivalStatus.None).newVersion()

        assertEquals(PhotoBinResult(done = 1), bin.restore(listOf(photo.fileId)).counts())
        assertEquals(1, server.sent.size)
    }

    @Test
    fun aPhotoGoneFromTheServerIsDoneForADeleteAndChangedElsewhereForAMove() = runTest {
        val moving = photo()
        val deleting = photo(ArchivalStatus.Removed)
        server.files.remove(moving.fileId)
        server.files.remove(deleting.fileId)

        assertEquals(PhotoBinResult(done = 0, changedElsewhere = 1), bin.moveToBin(listOf(moving.fileId)).counts())
        assertEquals(PhotoBinResult(done = 1), bin.deleteForGood(listOf(deleting.fileId)).counts())
    }

    @Test
    fun aPhotoWrittenElsewhereEveryTimeIsSentOnlyTwice() = runTest {
        val photo = photo()
        server.alwaysAhead = true

        assertEquals(PhotoBinResult(done = 0, changedElsewhere = 1), bin.moveToBin(listOf(photo.fileId)).counts())
        assertEquals(2, server.sent.size)
    }

    @Test
    fun photosAlreadyThereAreNotSentAndNothingSyncs() = runTest {
        val photo = photo(ArchivalStatus.Removed)

        assertEquals(PhotoBinResult(done = 1), bin.moveToBin(listOf(photo.fileId)).counts())
        assertEquals(0, server.sent.size)
        assertEquals(0, syncs)
    }

    @Test
    fun aPhotoTheDeviceNoLongerHoldsWasChangedElsewhere() = runTest {
        val photo = photo()

        assertEquals(PhotoBinResult(done = 1, changedElsewhere = 1), bin.moveToBin(listOf(photo.fileId, Uuid.random())).counts())
    }

    @Test
    fun emptyingDeletesForGoodWhatTheBinShowsAndNothingElse() = runTest {
        val binned = List(2) { photo(ArchivalStatus.Removed) }
        val kept = photo()

        assertEquals(PhotoBinResult(done = 2), bin.empty().counts())
        assertEquals(listOf(null, null), binned.map(::statusOnServer))
        assertEquals(ArchivalStatus.None, statusOnServer(kept))
    }

    @Test
    fun anyOtherRefusalCountsAsFailed() = runTest {
        val photo = photo()
        server.refuseWith = OdinClientErrorCode.InvalidFile

        assertEquals(PhotoBinResult(done = 0, failed = 1), bin.moveToBin(listOf(photo.fileId)).counts())
        assertEquals(1, syncs, "a refusal may follow writes that went through")
    }

    @Test
    fun undoRightAfterAMoveAsksTheServerWhereThePhotoIsRatherThanTheDevicesCopy() = runTest {
        val photo = photo()
        bin.moveToBin(listOf(photo.fileId))
        // The sync hasn't brought this phone's own move back yet.

        val result = bin.restore(listOf(photo.fileId))

        assertEquals(PhotoBinResult(done = 1), result.counts())
        assertEquals(setOf(photo.fileId), result.written)
        assertEquals(ArchivalStatus.None, statusOnServer(photo))
    }

    @Test
    fun onlyThePhotosThisCallMovedAreWritten() = runTest {
        val mine = photo()
        val movedElsewhere = photo()
        server.files[movedElsewhere.fileId] = server.files.getValue(movedElsewhere.fileId).at(ArchivalStatus.Removed).newVersion()

        val result = bin.moveToBin(listOf(mine.fileId, movedElsewhere.fileId))

        assertEquals(PhotoBinResult(done = 2), result.counts())
        assertEquals(setOf(mine.fileId), result.written, "Undo mustn't bring back what another device binned")
    }

    @Test
    fun aBatchThatFailsAfterAnotherWentThroughFailsTheRestNotTheCall() = runTest {
        val photos = List(DriveFileHttpProvider.FILE_BATCH + 5) { photo() }
        server.throwFromRequest = 1

        val result = bin.moveToBin(photos.map { it.fileId })

        assertEquals(PhotoBinResult(done = DriveFileHttpProvider.FILE_BATCH, failed = 5), result.counts())
        assertEquals(1, syncs)
    }

    @Test
    fun aFirstBatchThatDoesntGetThroughFailsTheCall() = runTest {
        val photo = photo()
        server.throwFromRequest = 0

        assertFailsWith<IllegalStateException> { bin.moveToBin(listOf(photo.fileId)) }
    }

    @Test
    fun deletingForGoodAPhotoTheDeviceAlreadyHoldsAsDeletedIsDone() = runTest {
        val photo = photo(ArchivalStatus.Removed)
        device[photo.fileId] = photo.copy(fileState = FileState.Deleted)

        assertEquals(PhotoBinResult(done = 1), bin.deleteForGood(listOf(photo.fileId)).counts())
        assertEquals(0, server.sent.size)
    }

    @Test
    fun aLookupThatFailsAfterSomethingWasWrittenCountsAsFailedRatherThanFailingTheCall() = runTest {
        val moving = photo()
        val writtenElsewhere = photo()
        server.files[writtenElsewhere.fileId] = server.files.getValue(writtenElsewhere.fileId).newVersion()
        server.headersFail = true

        val result = bin.moveToBin(listOf(moving.fileId, writtenElsewhere.fileId))

        assertEquals(PhotoBinResult(done = 1, failed = 1), result.counts())
        assertEquals(setOf(moving.fileId), result.written, "Undo still has the one that moved")
    }

    @Test
    fun aPhotoWhoseHeaderTheDeviceCantReadIsAskedOfTheServerNotCountedGone() = runTest {
        val photo = photo(ArchivalStatus.Removed)
        device.remove(photo.fileId)

        assertEquals(PhotoBinResult(done = 1), bin.deleteForGood(listOf(photo.fileId)).counts())
        assertEquals(null, statusOnServer(photo), "deleted, not just reported so")
    }
}

private fun PhotoBinResult.counts() = copy(written = emptySet())

private fun HomebaseFile.at(status: ArchivalStatus) =
    copy(fileMetadata = fileMetadata.copy(appData = fileMetadata.appData.copy(archivalStatus = status)))

private fun HomebaseFile.newVersion() = copy(fileMetadata = fileMetadata.copy(versionTag = Uuid.random()))
