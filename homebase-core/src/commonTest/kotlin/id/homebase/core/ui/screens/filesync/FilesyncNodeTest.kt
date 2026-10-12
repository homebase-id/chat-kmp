package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadHash
import id.homebase.api.common.SecureByteArray
import id.homebase.core.filesync.FilesyncProtocol
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
    ) = HomebaseFile(
        fileId = Uuid.random(),
        driveId = FilesyncProtocol.DriveAlias,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader(ByteArray(16), SecureByteArray(fileKey)),
        fileMetadata = FileMetadata(
            isEncrypted = true,
            appData = AppFileMetaData(uniqueId = nodeId, groupId = FilesyncProtocol.RootNodeId, fileType = fileType, content = content),
            payloads = payloads,
        ),
        serverMetadata = ServerMetadata(),
    )

    private val syncData = PayloadDescriptor(key = FilesyncProtocol.PayloadKey, iv = Base64.encode(payloadIv), hash = hash)

    @Test
    fun aFolderHasANameAndNoPayload() {
        val (node, payload) = row(FilesyncProtocol.FolderFileType, """{"v":1,"name":"Photos"}""").toFilesyncNode()!!
        assertTrue(node.isFolder)
        assertEquals("Photos", node.name)
        assertEquals(nodeId, node.nodeId)
        assertNull(payload)
    }

    @Test
    fun aFileCarriesItsPayloadKeyAndHashAndIgnoresUnknownKeys() {
        val (node, payload) = row(
            FilesyncProtocol.FileFileType,
            """{"v":1,"name":"Report.pdf","mtime":1759920000123,"size":52311,"x":true,"later":"field"}""",
            listOf(syncData),
        ).toFilesyncNode()!!
        assertFalse(node.isFolder)
        assertEquals(1759920000123, node.modifiedMs)
        assertEquals(52311, node.sizeBytes)
        assertContentEquals(payloadIv, payload!!.payloadKey.iv)
        assertContentEquals(fileKey, payload.payloadKey.aesKey.unsafeBytes)
        assertEquals(hash, payload.hash)
    }

    @Test
    fun rowsThatAreNotReadableNodesAreSkipped() {
        assertNull(row(fileType = 1, content = """{"v":1,"name":"x"}""").toFilesyncNode())
        assertNull(row(FilesyncProtocol.FolderFileType, """{"message":"Decryption Failure"}""").toFilesyncNode())
        assertNull(row(FilesyncProtocol.FolderFileType, "not json").toFilesyncNode())
        assertNull(row(FilesyncProtocol.FolderFileType, """{"v":1,"name":" "}""").toFilesyncNode())
        assertNull(row(FilesyncProtocol.FileFileType, """{"v":1,"name":"no-payload.txt"}""").toFilesyncNode())
    }
}
