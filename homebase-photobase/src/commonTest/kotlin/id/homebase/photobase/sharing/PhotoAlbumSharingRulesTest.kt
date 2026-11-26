package id.homebase.photobase.sharing

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.shares.ShareRow
import id.homebase.api.common.OdinId
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DrivePermissionSet
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.photobase.sharing.PhotoAlbumSharing.Companion.otherAlbums
import id.homebase.photobase.sharing.PhotoAlbumSharing.Companion.toSharedAlbum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PhotoAlbumSharingRulesTest {
    private val frodo = OdinId("frodo.test")
    private val sam = OdinId("sam.test")
    private val pippin = OdinId("pippin.test")
    private val samsCircle = Uuid.random()
    private val pippinsCircle = Uuid.random()
    private val audiences = mapOf(samsCircle to AlbumAudience.Person(sam), pippinsCircle to AlbumAudience.Person(pippin))

    private fun row(circleId: Uuid) = ShareRow(circleId, DrivePermissionSet(listOf(DrivePermission.Read, DrivePermission.React, DrivePermission.Comment)))

    private fun albumRecord(content: String, vararg rows: ShareRow) = HomebaseFile(
        fileId = Uuid.random(),
        driveId = PhotobaseProtocol.DriveAlias,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader.newRandom16(),
        fileMetadata = FileMetadata(appData = AppFileMetaData(uniqueId = Uuid.random(), fileType = PhotobaseProtocol.AlbumFileType, content = content)),
        serverMetadata = ServerMetadata(),
        shares = rows.toList(),
    )

    @Test
    fun noRowLetsItsCircleChangeOrDeleteAPhoto() {
        val granted = DrivePermission.entries.filter { PhotoAlbumSharing.ROW_PERMISSIONS and it.value != 0 }
        assertEquals(listOf(DrivePermission.Read, DrivePermission.React, DrivePermission.Comment), granted)
    }

    @Test
    fun theAddingCirclesAreReadFromTheRecordAndAnythingElseInItIsIgnored() {
        val content = """{"name":"Trip","adding":["$samsCircle","not a uuid"],"fromTheFuture":1}"""

        assertEquals(setOf(samsCircle), PhotoAlbumSharing.addingCircles(content))
        assertEquals(emptySet(), PhotoAlbumSharing.addingCircles("""{"name":"Trip"}"""))
        assertEquals(emptySet(), PhotoAlbumSharing.addingCircles("not json"))
        assertEquals(emptySet(), PhotoAlbumSharing.addingCircles(null))
    }

    @Test
    fun anEditorIsAMemberOfACircleTheAlbumListsAsAddingAndStillSharesWith() {
        val records = listOf(
            // Sam adds; Pippin views.
            albumRecord("""{"name":"Trip","adding":["$samsCircle"]}""", row(samsCircle), row(pippinsCircle)),
            // Pippin was listed as adding, but the share ended: no live row.
            albumRecord("""{"name":"Old","adding":["$pippinsCircle"]}"""),
        )

        assertEquals(setOf(sam), PhotoAlbumSharing.editorsOf(records, audiences))
    }

    @Test
    fun aSharedAlbumCanBeAddedToOnlyThroughARowForAnAddingCircle() {
        val adding = albumRecord("""{"name":"Trip","adding":["$samsCircle"]}""", row(samsCircle)).toSharedAlbum(frodo)
        val viewing = albumRecord("""{"name":"Trip","adding":["$samsCircle"]}""", row(pippinsCircle)).toSharedAlbum(frodo)

        assertEquals("Trip", adding?.name)
        assertTrue(adding!!.canAdd)
        assertFalse(viewing!!.canAdd)
        assertEquals(listOf(row(pippinsCircle)), viewing.shares)
        assertNull(albumRecord("""{"adding":[]}""").toSharedAlbum(frodo), "no name, no album")
    }

    @Test
    fun aPhotosOtherAlbumsAreItsOtherAppTags() {
        val album = Uuid.random()
        val other = Uuid.random()
        val photo = albumRecord("{}").let { it.copy(fileMetadata = it.fileMetadata.copy(appData = it.fileMetadata.appData.copy(tags = listOf(album, other)))) }

        assertEquals(listOf(other), photo.otherAlbums(album))
    }

    @Test
    fun aSharerIsKeptUnderTheSameIdOnEveryDevice() {
        assertEquals(PhotoAlbumSharing.sharerId(sam, frodo), PhotoAlbumSharing.sharerId(OdinId("SAM.test"), frodo))
        assertTrue(PhotoAlbumSharing.sharerId(sam, frodo) != PhotoAlbumSharing.sharerId(pippin, frodo))
        assertEquals(sam, PhotoAlbumSharing.sharerOf("""{"odinId":"sam.test"}"""))
        assertNull(PhotoAlbumSharing.sharerOf("{}"))
    }
}
