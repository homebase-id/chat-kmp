package id.homebase.photobase.gallery

import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.files.PerFileResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class PhotoBinTest {
    /** The server as odin-core answers: a write lands only at the file's current version, and gives it a new one. */
    private class Server : PhotoBinServer {
        val files = mutableMapOf<Uuid, HomebaseFile>()
        val sent = mutableListOf<List<Uuid>>()
        var refuseWith: OdinClientErrorCode? = null
        // Another device writing each photo again just before every write of this one.
        var alwaysAhead = false

        override suspend fun setArchivalStatus(status: ArchivalStatus, files: List<FileVersionTag>) =
            write(files) { it.at(status) }

        override suspend fun softDelete(files: List<FileVersionTag>) =
            write(files) { it.copy(fileState = FileState.Deleted) }

        override suspend fun header(fileId: Uuid): HomebaseFile? = files[fileId]

        private fun write(tags: List<FileVersionTag>, change: (HomebaseFile) -> HomebaseFile): List<PerFileResult> {
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

        assertEquals(PhotoBinResult(done = 3), result)
        assertEquals(List(3) { ArchivalStatus.Removed }, photos.map(::statusOnServer))
        assertEquals(1, server.sent.size, "one request for all")
        assertEquals(1, syncs)
    }

    @Test
    fun aPhotoWrittenElsewhereSinceIsSentAgainWhenItIsStillWhereThisDeviceSawIt() = runTest {
        val photo = photo()
        server.files[photo.fileId] = server.files.getValue(photo.fileId).newVersion()

        val result = bin.moveToBin(listOf(photo.fileId))

        assertEquals(PhotoBinResult(done = 1), result)
        assertEquals(ArchivalStatus.Removed, statusOnServer(photo))
        assertEquals(2, server.sent.size)
    }

    @Test
    fun anotherDevicesRestoreStandsAgainstADeleteForGood() = runTest {
        val photo = photo(ArchivalStatus.Removed)
        server.files[photo.fileId] = server.files.getValue(photo.fileId).at(ArchivalStatus.None).newVersion()

        val result = bin.deleteForGood(listOf(photo.fileId))

        assertEquals(PhotoBinResult(done = 0, changedElsewhere = 1), result)
        assertEquals(ArchivalStatus.None, statusOnServer(photo), "the photo another device brought back stays")
        assertEquals(1, server.sent.size, "not sent again")
    }

    @Test
    fun aPhotoAnotherDeviceAlreadyMovedTheSameWayIsDone() = runTest {
        val photo = photo(ArchivalStatus.Removed)
        server.files[photo.fileId] = server.files.getValue(photo.fileId).at(ArchivalStatus.None).newVersion()

        assertEquals(PhotoBinResult(done = 1), bin.restore(listOf(photo.fileId)))
        assertEquals(1, server.sent.size)
    }

    @Test
    fun aPhotoGoneFromTheServerIsDoneForADeleteAndChangedElsewhereForAMove() = runTest {
        val moving = photo()
        val deleting = photo(ArchivalStatus.Removed)
        server.files.remove(moving.fileId)
        server.files.remove(deleting.fileId)

        assertEquals(PhotoBinResult(done = 0, changedElsewhere = 1), bin.moveToBin(listOf(moving.fileId)))
        assertEquals(PhotoBinResult(done = 1), bin.deleteForGood(listOf(deleting.fileId)))
    }

    @Test
    fun aPhotoWrittenElsewhereEveryTimeIsSentOnlyTwice() = runTest {
        val photo = photo()
        server.alwaysAhead = true

        assertEquals(PhotoBinResult(done = 0, changedElsewhere = 1), bin.moveToBin(listOf(photo.fileId)))
        assertEquals(2, server.sent.size)
    }

    @Test
    fun photosAlreadyThereAreNotSentAndNothingSyncs() = runTest {
        val photo = photo(ArchivalStatus.Removed)

        assertEquals(PhotoBinResult(done = 1), bin.moveToBin(listOf(photo.fileId)))
        assertEquals(0, server.sent.size)
        assertEquals(0, syncs)
    }

    @Test
    fun aPhotoTheDeviceNoLongerHoldsWasChangedElsewhere() = runTest {
        val photo = photo()

        assertEquals(PhotoBinResult(done = 1, changedElsewhere = 1), bin.moveToBin(listOf(photo.fileId, Uuid.random())))
    }

    @Test
    fun emptyingDeletesForGoodWhatTheBinShowsAndNothingElse() = runTest {
        val binned = List(2) { photo(ArchivalStatus.Removed) }
        val kept = photo()

        assertEquals(PhotoBinResult(done = 2), bin.empty())
        assertEquals(listOf(null, null), binned.map(::statusOnServer))
        assertEquals(ArchivalStatus.None, statusOnServer(kept))
    }

    @Test
    fun anyOtherRefusalCountsAsFailed() = runTest {
        val photo = photo()
        server.refuseWith = OdinClientErrorCode.InvalidFile

        assertEquals(PhotoBinResult(done = 0, failed = 1), bin.moveToBin(listOf(photo.fileId)))
        assertEquals(1, syncs, "a refusal may follow writes that went through")
    }
}

private fun HomebaseFile.at(status: ArchivalStatus) =
    copy(fileMetadata = fileMetadata.copy(appData = fileMetadata.appData.copy(archivalStatus = status)))

private fun HomebaseFile.newVersion() = copy(fileMetadata = fileMetadata.copy(versionTag = Uuid.random()))
