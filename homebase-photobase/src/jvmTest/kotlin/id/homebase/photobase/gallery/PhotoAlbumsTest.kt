package id.homebase.photobase.gallery

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.files.LocalAppMetadata
import id.homebase.api.client.drives.files.PerFileResult
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.photobase.PhotobaseRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Through the real local index, as the drive sync writes it. */
class PhotoAlbumsTest {
    private val db = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })
    private val identity = Uuid.random()
    private val store = PhotoGalleryStore(db, { identity })

    private val onServer = mutableMapOf<Uuid, HomebaseFile>()
    private val uploads = mutableListOf<UploadFileRequest>()
    private val updates = mutableListOf<UpdateFileByUniqueIdRequest>()
    private val untagged = mutableListOf<Pair<Uuid, List<Uuid>>>()
    private val deleted = mutableListOf<FileVersionTag>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var syncs = 0
    private var refuseTags = false
    private val taggedElsewhere = mutableListOf<Uuid>()

    private val server = object : PhotoMoveServer {
        override suspend fun setArchivalStatus(status: ArchivalStatus, files: List<FileVersionTag>) = error("no moves")

        override suspend fun softDelete(files: List<FileVersionTag>) = error("no photo deletes")

        override suspend fun setLocalTag(tag: Uuid, on: Boolean, fileIds: List<Uuid>): List<PerFileResult> {
            check(!on)
            untagged += tag to fileIds
            return fileIds.map { PerFileResult(it, OdinClientErrorCode.ArgumentError.takeIf { refuseTags }) }
        }

        override suspend fun header(fileId: Uuid): HomebaseFile? = null
    }

    private val albums = PhotoAlbums(
        store = store,
        record = PhotobaseRecord({ _, uniqueId -> onServer[uniqueId] }, { uploads += it }, { updates += it }) { fileId, versionTag ->
            deleted += FileVersionTag(fileId, versionTag)
            null
        },
        mover = PhotoMover(server, filesOf = store::filesOf, binned = { emptyList() }, sync = {}),
        taggedOnServer = { taggedElsewhere },
        changes = emptyFlow(),
        scope = scope,
        sync = { syncs++ },
    )

    @AfterTest
    fun close() {
        scope.cancel()
        db.close()
    }

    private suspend fun synced(vararg files: HomebaseFile) =
        MainIndexMetaHelpers.HomebaseFileProcessor(db).baseUpsertEntryZapZap(identity, PhotobaseProtocol.DriveAlias, files.toList(), cursor = null)

    private fun record(id: Uuid, content: String, made: Long) = galleryTestFile(0).let {
        it.copy(
            fileMetadata = it.fileMetadata.copy(
                created = UnixTimeUtc(made),
                appData = it.fileMetadata.appData.copy(uniqueId = id, fileType = PhotobaseProtocol.AlbumFileType, content = content),
                payloads = emptyList(),
            ),
        )
    }

    private fun photo(capturedAtMs: Long, status: ArchivalStatus, vararg tags: Uuid) =
        galleryTestFile(capturedAtMs).at(status).let { it.copy(fileMetadata = it.fileMetadata.copy(localAppData = LocalAppMetadata(tags = tags.toList()))) }

    @Test
    fun albumsComeNewestMadeFirstCountingAndCoveringTheLibraryAndTheArchiveOnly() = runTest {
        val (trip, empty) = List(2) { Uuid.random() }
        val older = photo(1_000, ArchivalStatus.None, trip)
        val newest = photo(2_000, ArchivalStatus.Archived, trip, PhotobaseProtocol.FavoriteTag)
        synced(
            record(trip, """{"name":"Trip"}""", made = 10),
            record(empty, """{"name":"Empty","fromTheFuture":1}""", made = 20),
            record(Uuid.random(), "not json", made = 30),
            older,
            newest,
            photo(3_000, ArchivalStatus.Removed, trip),
        )

        val shown = albums.albums.filterNotNull().first()

        assertEquals(listOf(PhotoAlbum(empty, "Empty"), PhotoAlbum(trip, "Trip", photos = 2, cover = newest.toGalleryItem())), shown)
    }

    @Test
    fun aNewAlbumIsAnOwnerOnlyRecordUnderANewIdNamedAsTyped() = runTest {
        val id = albums.create("  Summer  ")

        val request = uploads.single()
        assertEquals(id, request.metadata.appData.uniqueId)
        assertEquals(PhotobaseProtocol.AlbumFileType, request.metadata.appData.fileType)
        assertEquals(SecurityGroupType.Owner.value, request.metadata.accessControlList?.requiredSecurityGroup)
        val content = request.keyHeader.decrypt(Base64.decode(request.metadata.appData.content!!)).decodeToString()
        assertEquals("""{"name":"Summer"}""", content)
        assertEquals(1, syncs, "the album shows once the sync brings it back")
    }

    @Test
    fun anAlbumDeletedElsewhereIsntMadeAgainByARename() = runTest {
        val id = Uuid.random()
        onServer[id] = record(id, """{"name":"Trip"}""", made = 1).copy(fileState = FileState.Deleted)

        assertFailsWith<IllegalStateException> { albums.rename(id, "Journey") }

        assertTrue(uploads.isEmpty() && updates.isEmpty())
    }

    @Test
    fun deletingTakesTheTagOffEveryPhotoCarryingItThenDeletesTheRecord() = runTest {
        val id = Uuid.random()
        val kept = photo(1_000, ArchivalStatus.None, id)
        val binned = photo(2_000, ArchivalStatus.Removed, id)
        synced(kept, binned, photo(3_000, ArchivalStatus.None))
        val notSyncedHere = Uuid.random()
        taggedElsewhere += listOf(kept.fileId, notSyncedHere)
        val versionTag = Uuid.random()
        onServer[id] = record(id, """{"name":"Trip"}""", made = 1).let { it.copy(fileMetadata = it.fileMetadata.copy(versionTag = versionTag)) }

        albums.delete(id)

        assertEquals(id, untagged.single().first)
        assertEquals(setOf(kept.fileId, binned.fileId, notSyncedHere), untagged.single().second.toSet(), "a binned photo that's restored mustn't come back in it")
        assertEquals(listOf(FileVersionTag(onServer.getValue(id).fileId, versionTag)), deleted)
    }

    @Test
    fun anAlbumWhoseTagDidntComeOffEveryPhotoStaysToBeDeletedAgain() = runTest {
        val id = Uuid.random()
        synced(photo(1_000, ArchivalStatus.None, id))
        onServer[id] = record(id, """{"name":"Trip"}""", made = 1).newVersion()
        refuseTags = true

        assertFailsWith<IllegalStateException> { albums.delete(id) }

        assertEquals(emptyList(), deleted)
    }
}
