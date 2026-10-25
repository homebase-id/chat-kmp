package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadHash
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.core.filesync.FilesyncProtocol
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FilesyncNodeTest {

    private val nodeId = Uuid.random()
    private val fileKey = ByteArray(16) { it.toByte() }
    private val payloadIv = ByteArray(16) { (it + 100).toByte() }
    private val hash = PayloadHash("blake3", "cw==", "Yw==")

    private fun row(
        fileType: Int?,
        content: String?,
        payloads: List<PayloadDescriptor>? = null,
        serverFileIsEncrypted: Boolean = true,
        status: ArchivalStatus? = null,
    ) = HomebaseFile(
        fileId = Uuid.random(),
        driveId = FilesyncProtocol.DriveAlias,
        serverFileIsEncrypted = serverFileIsEncrypted,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader(ByteArray(16), SecureByteArray(fileKey)),
        fileMetadata = FileMetadata(
            isEncrypted = false,
            updated = UnixTimeUtc(UPDATED_MS),
            appData = AppFileMetaData(
                uniqueId = nodeId,
                groupId = FilesyncProtocol.RootNodeId,
                fileType = fileType,
                content = content,
                archivalStatus = status,
            ),
            payloads = payloads,
        ),
        serverMetadata = ServerMetadata(),
    )

    private val syncData = PayloadDescriptor(key = FilesyncProtocol.PayloadKey, iv = Base64.encode(payloadIv), hash = hash)

    @Test
    fun aFolderHasANameAndNoPayload() {
        val (node, handle) = row(FilesyncProtocol.FolderFileType, """{"v":1,"name":"Photos"}""").toFilesyncNode()!!
        assertTrue(node.isFolder)
        assertEquals("Photos", node.name)
        assertEquals(nodeId, node.nodeId)
        assertNull(handle.payload)
        assertNull(node.removedMs)
    }

    @Test
    fun aFileCarriesItsPayloadKeyAndHashAndIgnoresUnknownKeys() {
        val (node, handle) = row(
            FilesyncProtocol.FileFileType,
            """{"v":1,"name":"Report.pdf","mtime":1759920000123,"size":52311,"x":true,"later":"field"}""",
            listOf(syncData),
        ).toFilesyncNode()!!
        val payload = handle.payload
        assertFalse(node.isFolder)
        assertEquals(1759920000123, node.modifiedMs)
        assertEquals(52311, node.sizeBytes)
        assertContentEquals(payloadIv, payload!!.payloadKey.iv)
        assertContentEquals(fileKey, payload.payloadKey.aesKey.unsafeBytes)
        assertEquals(hash, payload.hash)
    }

    @Test
    fun aRowDecryptedByTheQueryIsListed() = runTest {
        val sharedSecret = SecureByteArray(ByteArray(16) { (it + 7).toByte() })
        val fileKeyHeader = KeyHeader.newRandom16()
        val transportIv = ByteArray(16) { (it + 50).toByte() }
        val serverFile = ServerFile(
            fileId = Uuid.random(),
            driveId = FilesyncProtocol.DriveAlias,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            sharedSecretEncryptedKeyHeader = EncryptedKeyHeader.encryptKeyHeaderAes(fileKeyHeader, transportIv, sharedSecret),
            fileMetadata = FileMetadata(
                isEncrypted = true,
                appData = AppFileMetaData(
                    uniqueId = nodeId,
                    groupId = FilesyncProtocol.RootNodeId,
                    fileType = FilesyncProtocol.FolderFileType,
                    content = Base64.encode(fileKeyHeader.encryptDataAes("""{"v":1,"name":"Photos"}""".encodeToByteArray())),
                ),
            ),
            serverMetadata = ServerMetadata(),
        )
        assertEquals("Photos", serverFile.asHomebaseFile(sharedSecret).toFilesyncNode()?.first?.name)
    }

    @Test
    fun rowsThatAreNotReadableNodesAreSkipped() {
        assertNull(row(fileType = 1, content = """{"v":1,"name":"x"}""").toFilesyncNode())
        assertNull(row(FilesyncProtocol.FolderFileType, """{"message":"Decryption Failure"}""").toFilesyncNode())
        assertNull(row(FilesyncProtocol.FolderFileType, "not json").toFilesyncNode())
        assertNull(row(FilesyncProtocol.FolderFileType, """{"name":"no version"}""").toFilesyncNode())
        assertNull(row(FilesyncProtocol.FolderFileType, """{"v":1,"name":"plain"}""", serverFileIsEncrypted = false).toFilesyncNode())
        assertNull(row(FilesyncProtocol.FileFileType, """{"v":1,"name":"no-payload.txt","mtime":1,"size":5}""").toFilesyncNode())
        assertNull(row(FilesyncProtocol.FileFileType, """{"v":1,"name":"no-size.txt","mtime":1}""", listOf(syncData)).toFilesyncNode())
        val unhashed = syncData.copy(hash = null)
        assertNull(row(FilesyncProtocol.FileFileType, """{"v":1,"name":"no-hash.txt","mtime":1,"size":5}""", listOf(unhashed)).toFilesyncNode())
    }

    @Test
    fun namesThatBreakTheSchemaAreSkipped() {
        for (name in listOf("", ".", "..", "a/b", "nul\\u0000")) {
            assertNull(row(FilesyncProtocol.FolderFileType, """{"v":1,"name":"$name"}""").toFilesyncNode(), name)
        }
        assertEquals(" ", row(FilesyncProtocol.FolderFileType, """{"v":1,"name":" "}""").toFilesyncNode()!!.first.name)
    }

    @Test
    fun aLaterVersionIsStillListed() {
        assertEquals("next.txt", row(FilesyncProtocol.FolderFileType, """{"v":2,"name":"next.txt"}""").toFilesyncNode()!!.first.name)
    }

    @Test
    fun aWriteKeepsEveryKeyItRead() {
        val (_, handle) = row(
            FilesyncProtocol.FileFileType,
            """{"v":1,"name":"a.txt","mtime":1,"size":5,"x":true,"ct":1451606400123,"later":{"k":1}}""",
            listOf(syncData),
        ).toFilesyncNode()!!
        assertEquals(setOf("v", "name", "mtime", "size", "x", "ct", "later"), handle.content.keys)
    }

    @Test
    fun aRemovedNodeWasRemovedAtItsUpdateWhateverItsRmSays() {
        for (rm in listOf("", ""","rm":{"at":5,"root":"${Uuid.random()}"}""")) {
            val content = """{"v":1,"name":"a"$rm}"""
            val (node, _) = row(FilesyncProtocol.FolderFileType, content, status = ArchivalStatus.Removed).toFilesyncNode()!!
            assertEquals(UPDATED_MS, node.removedMs, rm)
        }
    }

    @Test
    fun onlyStatusTwoRemoves() {
        val content = """{"v":1,"name":"a"}"""
        for (status in listOf(null, ArchivalStatus.None, ArchivalStatus.Archived)) {
            val node = row(FilesyncProtocol.FolderFileType, content, status = status).toFilesyncNode()!!.first
            assertNull(node.removedMs)
            assertEquals(UPDATED_MS, node.updatedMs)
        }
    }

    @Test
    fun theInfoPaneFieldsComeFromContentHeaderAndPayload() {
        val content = """{"v":1,"name":"run.sh","mtime":1,"size":2,"x":true,"ct":1451606400123.4}"""
        val descriptor = syncData.copy(lastModified = 1759920007000)
        val (node, _) = row(FilesyncProtocol.FileFileType, content, listOf(descriptor)).toFilesyncNode()!!
        assertEquals(1451606400123, node.createdMs)
        assertTrue(node.executable)
        assertEquals(1759920007000, node.uploadedMs)
        assertEquals(PayloadHashAlgorithm.Blake3, node.hashAlgorithm)
    }

    @Test
    fun anOddCreationTimeOrExecutableFlagReadsAsNone() {
        for (extra in listOf(""","ct":"2016"""", ""","ct":null""", ""","x":"true"""", "")) {
            val (node, _) = row(FilesyncProtocol.FolderFileType, """{"v":1,"name":"a"$extra}""").toFilesyncNode()!!
            assertNull(node.createdMs, extra)
            assertFalse(node.executable, extra)
        }
    }

    @Test
    fun tagsAreReadLenientlyOnePerNameAndSortedByName() {
        val tags = """[{"n":"work","c":4},{"n":"Red","c":6},{"n":"Odd","c":9},{"n":"Plain"},{"c":2},{"n":""},{"n":"Red","c":1},"x"]"""
        val (node, _) = row(FilesyncProtocol.FolderFileType, """{"v":1,"name":"a","tags":$tags}""").toFilesyncNode()!!
        assertEquals(
            listOf(FilesyncTag("Odd", null), FilesyncTag("Plain", null), FilesyncTag("Red", 6), FilesyncTag("work", 4)),
            node.tags,
        )
    }

    @Test
    fun noTagsKeyOrABadOneMeansNoTags() {
        for (tags in listOf("", ""","tags":{}""", ""","tags":"Red"""")) {
            assertEquals(emptyList(), row(FilesyncProtocol.FolderFileType, """{"v":1,"name":"a"$tags}""").toFilesyncNode()!!.first.tags, tags)
        }
    }

    private companion object {
        const val UPDATED_MS = 1759920009000
    }
}
