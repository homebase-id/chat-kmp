package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.shares.ShareRequest
import id.homebase.api.client.drives.shares.ShareRow
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.crypto.EncryptionType
import id.homebase.api.crypto.ShareKeys
import id.homebase.api.crypto.ZkSeal
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DrivePermissionSet
import id.homebase.core.filesync.FilesyncProtocol
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.FileSystem
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** A member's writes to a folder Frodo shares with them, as odin-core SelectiveShareTests.MemberWrites sends them. */
class FilesyncMemberWritesTest {

    private val frodo = OdinId("frodo.dotyou.cloud")
    private val writeOnlyPrivateKey = Base64.decode(
        "MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDCZAel29rv0BgBwa7LGp4+tmSi5g4LlRzkW9/ZLQNgeVdrB44imUOGeaKmn1eiPZsShZANiAATKPHfd0ojszgjchR06XVN2p/iaosbJedgQGD86jcH39CM7EjWiXa6wVTW3cWGdDXwWQYx4NHDg8lIWULARJhvn02W1/eoTtDkhdpQrY6KmVbxpY/ehNZlDlhlh3MntF40="
    )
    private val frodosDriveKey = ShareKeys.pointOfJwk(
        """{"kty":"EC","crv":"P-384","x":"yjx33dKI7M4I3IUdOl1Tdqf4mqLGyXnYEBg_Oo3B9_QjOxI1ol2usFU1t3FhnQ18","y":"FkGMeDRw4PJSFlCwESYb59Nltf3qE7Q5IXaUK2OiplW8aWP3oTWZQ5YZYdzJ7ReN"}"""
    )

    private lateinit var tempDir: String
    private lateinit var source: String
    private val uploaded = mutableListOf<UploadFileRequest>()
    private val updated = mutableListOf<Update>()
    private var uploadAnswer: () -> Uuid = { Uuid.random() }
    private var sharesFor: (FilesyncHandle, KeyHeader, Uuid) -> List<ShareRequest> = { folder, fileKey, gtid -> lockedFor(folder, fileKey, gtid) }

    private class Update(val owner: OdinId, val globalTransitId: Uuid, val keyHeader: EncryptedKeyHeader, val metadata: UploadFileMetadata, val payload: PayloadFile)

    private lateinit var writes: FilesyncMemberWrites

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("filesync-member").toString()
        source = File(tempDir, "notes.txt").apply { writeBytes(ByteArray(3000) { it.toByte() }) }.path
        writes = FilesyncMemberWrites(
            sharesOfNewFile = { _, folder, fileKey, gtid -> sharesFor(folder, fileKey, gtid) },
            ownersDriveKey = { frodosDriveKey },
            upload = { request, _ -> uploaded += request; uploadAnswer() },
            update = { owner, gtid, keyHeader, metadata, payload, _ -> updated += Update(owner, gtid, keyHeader, metadata, payload); Uuid.random() },
            fileOperationsProvider = OkioFileOperationsProvider(FileSystem.SYSTEM, tempDir),
            clock = kotlin.time.Clock.System,
        )
    }

    @AfterTest
    fun tearDown() {
        File(tempDir).deleteRecursively()
    }

    @Test
    fun aNewFileIsADepositSealedToTheOwnersDriveAndSharedForEachOfTheMembersCirclesOnTheFolder() = runTest {
        val folderId = Uuid.random()
        val circles = listOf(Uuid.random(), Uuid.random())

        val sent = writes.send(FilesyncPeerTarget.Add(frodo, folderId, folder(circles), emptySet()), source, "notes.txt", 1_000L) {}

        val request = uploaded.single()
        assertEquals(SystemDriveConstants.transientTempDrive.alias, request.driveId, "sent from the transient drive")
        val transit = assertNotNull(request.transitOptions)
        assertEquals(true, transit.isTransient)
        assertEquals(listOf(frodo), transit.recipients)
        assertEquals(FilesyncProtocol.DriveAlias, transit.remoteTargetDrive?.alias)
        assertContentEquals(frodosDriveKey, request.sealedTo, "sealed to Frodo's drive key")
        assertEquals(sent.globalTransitId, request.globalTransitId)
        assertEquals(circles, request.shares.orEmpty().map { it.circleId }, "one share per circle the folder reaches this member through")
        assertTrue(request.shares.orEmpty().all { it.fileId == Uuid.NIL })

        val metadata = request.metadata
        assertTrue(metadata.allowDistribution)
        assertEquals(folderId, metadata.appData.groupId)
        assertEquals(FilesyncProtocol.FileFileType, metadata.appData.fileType)
        val content = OdinSystemSerializer.json.parseToJsonElement(request.keyHeader.decrypt(Base64.decode(metadata.appData.content!!)).decodeToString()).jsonObject
        assertEquals("notes.txt", content["name"]?.jsonPrimitive?.content)
        assertEquals(3000L, content["size"]?.jsonPrimitive?.long)
        assertEquals(1_000L, content["mtime"]?.jsonPrimitive?.long)
        assertNotNull(request.payloads.single().hash, "hashed like any Filesync payload")

        assertFalse(sent.isUpdate)
    }

    @Test
    fun aFolderReachingTheMemberThroughNoShareTakesNoFile() = runTest {
        sharesFor = { _, _, _ -> emptyList() }

        assertFailsWith<IllegalStateException> {
            writes.send(FilesyncPeerTarget.Add(frodo, Uuid.random(), folder(emptyList()), emptySet()), source, "notes.txt", null) {}
        }
        assertTrue(uploaded.isEmpty(), "a file Frodo alone would see is never sent")
    }

    @Test
    fun aDepositThatFailsIsNotSentAgainByItself() = runTest {
        uploadAnswer = { throw java.io.IOException("connection reset") }

        assertFailsWith<java.io.IOException> {
            writes.send(FilesyncPeerTarget.Add(frodo, Uuid.random(), folder(listOf(Uuid.random())), emptySet()), source, "notes.txt", null) {}
        }
        assertEquals(1, uploaded.size, "the outbox may have sent it already: a second send would be a second file")
    }

    @Test
    fun aReplaceKeepsTheFilesKeyUnderANewIvAndNamesTheVersionItRead() = runTest {
        val (node, handle) = sharedFile()

        val sent = writes.send(FilesyncPeerTarget.Replace(frodo, node, handle), source, "notes.txt", 2_000L) {}

        val update = updated.single()
        assertEquals(handle.globalTransitId, update.globalTransitId)
        assertEquals(EncryptionType.Share, update.keyHeader.type)
        assertTrue(update.keyHeader.encryptedAesKey.isEmpty(), "the new IV alone")
        assertFalse(update.keyHeader.iv.contentEquals(handle.keyHeader.iv), "a new IV")
        assertEquals(handle.versionTag, update.metadata.versionTag)
        assertEquals(node.nodeId, update.metadata.appData.uniqueId, "the header is replaced, so the node is named again")
        assertEquals(node.parentId, update.metadata.appData.groupId)
        assertEquals(SecurityGroupType.Connected.value, update.metadata.accessControlList?.requiredSecurityGroup)

        val sameKeyNewIv = KeyHeader(update.keyHeader.iv, handle.keyHeader.aesKey)
        val content = OdinSystemSerializer.json.parseToJsonElement(sameKeyNewIv.decrypt(Base64.decode(update.metadata.appData.content!!)).decodeToString()).jsonObject
        assertEquals("kept", content["tagsLike"]?.jsonPrimitive?.content, "what it doesn't know stays")
        assertEquals("notes.txt", content["name"]?.jsonPrimitive?.content)
        assertEquals(2_000L, content["mtime"]?.jsonPrimitive?.long)
        assertNotNull(update.payload.hash)

        assertTrue(sent.isUpdate)
        assertEquals(handle.versionTag, sent.namedVersionTag)
    }

    @Test
    fun aReplaceOfAFileStillSealedSealsTheSameKeyWithTheNewIv() = runTest {
        val (node, handle) = sharedFile()

        writes.send(FilesyncPeerTarget.Replace(frodo, node, handle, sealed = true), source, "notes.txt", null) {}

        val keyHeader = updated.single().keyHeader
        assertEquals(EncryptionType.Sealed, keyHeader.type)
        val opened = ZkSeal.open(keyHeader.encryptedAesKey, writeOnlyPrivateKey)
        assertContentEquals(keyHeader.iv, opened.copyOf(16))
        assertContentEquals(handle.keyHeader.aesKey.toByteArray(), opened.copyOfRange(16, opened.size), "the same key")
    }

    private fun folder(circles: List<Uuid>) = FilesyncHandle(
        fileId = Uuid.random(),
        versionTag = Uuid.random(),
        fileType = FilesyncProtocol.FolderFileType,
        keyHeader = KeyHeader.newRandom16(),
        content = JsonObject(mapOf("v" to JsonPrimitive(1), "name" to JsonPrimitive("Projects"))),
        payload = null,
        globalTransitId = Uuid.random(),
        shares = circles.map { ShareRow(it, DrivePermissionSet(listOf(DrivePermission.Read, DrivePermission.Write))) },
    )

    private fun sharedFile(): Pair<FilesyncNode, FilesyncHandle> {
        val node = FilesyncNode(Uuid.random(), Uuid.random(), isFolder = false, name = "notes.txt", modifiedMs = 1L, sizeBytes = 10L)
        val handle = FilesyncHandle(
            fileId = Uuid.random(),
            versionTag = Uuid.random(),
            fileType = FilesyncProtocol.FileFileType,
            keyHeader = KeyHeader.newRandom16(),
            content = JsonObject(mapOf("v" to JsonPrimitive(1), "name" to JsonPrimitive("notes.txt"), "tagsLike" to JsonPrimitive("kept"), "size" to JsonPrimitive(10))),
            payload = null,
            globalTransitId = Uuid.random(),
            shares = listOf(ShareRow(Uuid.random(), DrivePermissionSet(listOf(DrivePermission.Read, DrivePermission.Write)))),
        )
        return node to handle
    }

    private fun lockedFor(folder: FilesyncHandle, fileKey: KeyHeader, gtid: Uuid): List<ShareRequest> =
        folder.shares.map { ShareRequest(Uuid.NIL, it.circleId, 3, lockedFileKey = fileKey.aesKey.toByteArray() + gtid.toByteArray()) }
}
