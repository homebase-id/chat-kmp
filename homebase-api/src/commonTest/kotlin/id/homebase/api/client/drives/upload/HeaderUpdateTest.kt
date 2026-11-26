package id.homebase.api.client.drives.upload

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.FileMetadata
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class HeaderUpdateTest {
    private val album = Uuid.random()
    private val other = Uuid.random()
    private val key = KeyHeader.newRandom16()
    private val versionTag = Uuid.random()

    private fun photo(tags: List<Uuid>?) = HomebaseFile(
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        serverFileIsEncrypted = true,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = key,
        fileMetadata = FileMetadata(
            versionTag = versionTag,
            appData = AppFileMetaData(
                uniqueId = Uuid.random(),
                tags = tags,
                fileType = 7201,
                dataType = 1,
                userDate = 1234L,
                content = """{"latitude":1.5}""",
                archivalStatus = ArchivalStatus.Archived,
            ),
        ),
        serverMetadata = ServerMetadata(accessControlList = AccessControlList(requiredSecurityGroup = "connected")),
    )

    @Test
    fun aTagIsAddedAndEverythingElseIsWrittenAsRead() = runTest {
        val file = photo(listOf(other))

        val request = file.headerUpdate { it.copy(tags = it.tags.withTag(album, on = true)) }

        val appData = request.metadata.appData
        assertEquals(listOf(other, album), appData.tags)
        assertEquals(file.fileMetadata.appData.uniqueId, appData.uniqueId)
        assertEquals(7201 to 1, appData.fileType to appData.dataType)
        assertEquals(1234L, appData.userDate)
        assertEquals(ArchivalStatus.Archived, appData.archivalStatus)
        assertEquals("connected", request.metadata.accessControlList?.requiredSecurityGroup, "a shared photo stays shared")
        assertEquals(versionTag, request.metadata.versionTag)
        assertEquals(UpdateLocale.Local, request.instructions.locale)
        assertEquals(file.fileId, request.fileId)
    }

    @Test
    fun theContentGoesUnderTheFilesKeyWithANewIv() = runTest {
        val request = photo(null).headerUpdate { it }

        val sent = requireNotNull(request.keyHeader)
        assertContentEquals(key.aesKey.unsafeBytes, sent.aesKey.unsafeBytes)
        assertFalse(key.iv.contentEquals(sent.iv))
        assertEquals("""{"latitude":1.5}""", sent.decrypt(Base64.decode(requireNotNull(request.metadata.appData.content))).decodeToString())
    }

    @Test
    fun takingTheLastTagOffLeavesNone() {
        assertNull(listOf(album).withTag(album, on = false))
        assertEquals(listOf(album), null.withTag(album, on = true))
        assertEquals(listOf(other, album), listOf(album, other).withTag(album, on = true))
    }
}
