package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.HttpClientProvider
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.connections.CircleShareKeyProvider
import id.homebase.api.client.drives.shares.DriveShareProvider
import id.homebase.api.client.drives.shares.ShareRequest
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.peer.PeerDriveQueryProvider
import id.homebase.api.client.peer.PeerSendStatus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ShareKeys
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.youauth.ZkKeyStore
import id.homebase.core.filesync.FilesyncProtocol
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The collaborative folder against a live odin-core: Pippin shares a folder with two of Tom's circles, Tom adds a file
 * to it, replaces Pippin's file keeping its key, and replaces his own still-sealed file (first refused, then sealed).
 * Runs only when COLLAB_FOLDER_FIXTURE names the fixture JSON odin-core's dev setup wrote; prints no secret.
 */
class CollabFolderLiveTest {

    private val fixturePath: String? = System.getenv("COLLAB_FOLDER_FIXTURE")

    @Test
    fun aMemberAddsAndReplacesFilesInAFolderSharedWithTwoOfHisCircles() = runBlocking {
        val path = fixturePath ?: return@runBlocking println("COLLAB_FOLDER_FIXTURE not set: skipped")
        val fixture = Json.parseToJsonElement(File(path).readText()).jsonObject
        val pippinJson = fixture.obj("pippin")
        val tomJson = fixture.obj("tom")
        val pippin = OdinId(pippinJson.str("identity"))
        val tom = OdinId(tomJson.str("identity"))
        val port = Regex(":(\\d+)").find(fixture.str("devServer"))?.groupValues?.get(1)?.toInt() ?: 4444
        val http = HttpClientProvider.create().config {
            install(createClientPlugin("DevPort") { onRequest { request, _ -> request.url.port = port } })
        }
        val dir = Files.createTempDirectory("collab-live").toFile()
        val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, dir.path)

        val asPippin = credentials(pippin, pippinJson.obj("filesyncDevice"))
        val asTom = credentials(tom, tomJson.obj("chatApp"))
        val circles = pippinJson["circles"]!!.jsonArray.map { it.jsonObject }
        val circleKeys = circles.associate { Uuid.parse(it.str("circleId")) to Base64.decode(it.str("circleKey")) }

        // Pippin's folder and a file in it, each shared with both circles through format-3 rows
        useKeys(drive = pippinJson.obj("filesyncDrive").str("driveKey"))
        val pippinUploads = DriveUploadProvider(http, asPippin, fileOps)
        val folderId = Uuid.random()
        val folderKey = KeyHeader.newRandom16()
        upload(pippinUploads, folderId, FilesyncProtocol.RootNodeId, FilesyncProtocol.FolderFileType, FilesyncNodeContent(v = 1, name = "Live ${Uuid.random().toString().take(8)}"), folderKey, circleKeys, null)
        val pippinsFileId = Uuid.random()
        val pippinsFileKey = KeyHeader.newRandom16()
        val first = File(dir, "plan.txt").apply { writeText("Pippin's plan") }
        fileOps.withFilesyncPayload(first.path, pippinsFileKey.aesKey) { payload, size ->
            upload(pippinUploads, pippinsFileId, folderId, FilesyncProtocol.FileFileType, FilesyncNodeContent(v = 1, name = "plan.txt", mtime = 1, size = size), pippinsFileKey, circleKeys, payload)
        }
        println("Pippin made folder $folderId with plan.txt, shared with ${circleKeys.size} circles")

        // Tom reads them through his circles: format 3, opened by the global transit id
        useKeys(writeOnly = tomJson.obj("filesyncDrive").str("writeOnlyPrivateKeyPkcs8"))
        val sharing = FilesyncSharing(
            http, asTom, ConnectionNetworkProvider(http, asTom), CircleShareKeyProvider(http, asTom),
            PeerDriveQueryProvider(http, asTom), DriveQueryProvider(http, asTom), DriveUploadProvider(http, asTom, fileOps),
        )
        val peer = PeerDriveQueryProvider(http, asTom)
        val listed = sharing.sharedBy(pippin).associate { (node, handle) -> node.nodeId to (node to handle) }
        val (folderNode, folderHandle) = listed.getValue(folderId)
        val (planNode, planHandle) = listed.getValue(pippinsFileId)
        assertEquals(circleKeys.keys, folderHandle.shares.map { it.circleId }.toSet(), "Tom reads the folder through both his circles")
        assertTrue(folderHandle.editable && planHandle.editable)
        println("Tom opened ${folderNode.name} and ${planNode.name} through format-3 keys")

        val writes = FilesyncMemberWrites(sharing, DriveUploadProvider(http, asTom, fileOps), fileOps)

        // 1. A new file: a sealed deposit with a row per circle
        val notes = File(dir, "notes.txt").apply { writeText("Tom's notes") }
        val added = writes.send(FilesyncPeerTarget.Add(pippin, folderId, folderHandle, emptySet()), notes.path, "tom-notes.txt", null) {}
        println("deposit queued: gtid=${added.globalTransitId} status=${waitUntilSent(peer, pippin, added)}")
        val (addedNode, addedHandle) = sharing.sharedByGlobalTransitId(pippin, listOf(added.globalTransitId)).single()
        assertEquals("tom-notes.txt", addedNode.name)
        assertEquals(folderId, addedNode.parentId)
        assertEquals(circleKeys.keys, addedHandle.shares.map { it.circleId }.toSet(), "the deposit came with a row for each of Tom's circles")
        useKeys(drive = pippinJson.obj("filesyncDrive").str("driveKey"), writeOnly = pippinJson.obj("filesyncDrive").str("writeOnlyPrivateKeyPkcs8"))
        val pippinSees = DriveQueryProvider(http, asPippin)
        val depositFileId = rawFileId(pippinSees, added.globalTransitId)
        assertEquals(circleKeys.keys, DriveShareProvider(http, asPippin).shares(FilesyncProtocol.DriveAlias, depositFileId).map { it.circleId }.toSet(), "rows on Pippin's drive")
        println("deposit landed in Pippin's folder with rows for both circles")

        // 2. Pippin's file replaced, keeping its key
        useKeys(writeOnly = tomJson.obj("filesyncDrive").str("writeOnlyPrivateKeyPkcs8"))
        val second = File(dir, "plan2.txt").apply { writeText("Tom's version of the plan") }
        val kept = writes.send(FilesyncPeerTarget.Replace(pippin, planNode, planHandle), second.path, "plan.txt", 2) {}
        println("keep-key edit queued: status=${waitUntilSent(peer, pippin, kept)}")
        val (_, planAfter) = sharing.sharedByGlobalTransitId(pippin, listOf(planHandle.globalTransitId!!)).single()
        assertEquals(kept.sentVersionTag, planAfter.versionTag, "Pippin's copy is at the version Tom's edit sent")
        assertEquals(2L, planAfter.content["mtime"]?.jsonPrimitive?.content?.toLong())
        println("keep-key edit landed")

        // 3. Tom's own deposit, which Pippin's device hasn't taken in: a kept key is refused, a new seal lands
        val third = File(dir, "notes2.txt").apply { writeText("Tom's notes, edited") }
        val refused = writes.send(FilesyncPeerTarget.Replace(pippin, addedNode, addedHandle), third.path, "tom-notes.txt", 3) {}
        println("keep-key edit of the deposit queued: status=${waitUntilSent(peer, pippin, refused)}")
        val (_, stillSealed) = sharing.sharedByGlobalTransitId(pippin, listOf(added.globalTransitId)).single()
        assertNotEquals(refused.sentVersionTag, stillSealed.versionTag, "a kept key isn't taken on a sealed deposit")
        val resealed = writes.send(FilesyncPeerTarget.Replace(pippin, addedNode, addedHandle, sealed = true), third.path, "tom-notes.txt", 3) {}
        println("sealed edit queued: status=${waitUntilSent(peer, pippin, resealed)}")
        val (_, afterSeal) = sharing.sharedByGlobalTransitId(pippin, listOf(added.globalTransitId)).single()
        assertEquals(resealed.sentVersionTag, afterSeal.versionTag, "the sealed edit landed")
        println("sealed edit landed")

        dir.deleteRecursively()
        ZkKeyStore.clear()
    }

    private suspend fun credentials(identity: OdinId, app: JsonObject) = CredentialsManager().apply {
        setActiveCredentials(ApiCredentials.create(identity, app.str("clientAuthToken"), SecureByteArray(Base64.decode(app.str("sharedSecret")))))
    }

    // The process holds one identity's zero-knowledge keys at a time, as an app does.
    private fun useKeys(drive: String? = null, writeOnly: String? = null) {
        val stored = mutableMapOf<String, String>()
        drive?.let { stored["zk_drive_keys"] = """{"${FilesyncProtocol.DriveAlias}":"$it"}""" }
        writeOnly?.let { stored["zk_drive_write_only_keys"] = """{"${FilesyncProtocol.DriveAlias}":"$it"}""" }
        ZkKeyStore.storage = object : ZkKeyStore.Storage {
            override fun get(key: String) = stored[key]
            override fun put(key: String, value: String) { stored[key] = value }
            override fun remove(key: String) { stored.remove(key) }
        }
    }

    private suspend fun upload(
        uploads: DriveUploadProvider,
        nodeId: Uuid,
        parentId: Uuid,
        fileType: Int,
        content: FilesyncNodeContent,
        key: KeyHeader,
        circleKeys: Map<Uuid, ByteArray>,
        payload: id.homebase.api.client.drives.files.PayloadFile?,
    ) {
        val gtid = Uuid.random()
        val shares = circleKeys.map { (circleId, circleKey) ->
            ShareRequest(Uuid.NIL, circleId, 15, lockedFileKey = ShareKeys.lockFileKeyByGlobalTransitId(circleKey, key.aesKey.toByteArray(), FilesyncProtocol.DriveAlias, gtid, circleId))
        }
        uploads.uploadFile(
            UploadFileRequest(
                driveId = FilesyncProtocol.DriveAlias,
                keyHeader = key,
                metadata = filesyncMetadata(nodeId, parentId, fileType, OdinSystemSerializer.serialize(content), ArchivalStatus.None).encryptContent(key),
                payloads = listOfNotNull(payload),
                shares = shares,
                globalTransitId = gtid,
            )
        )
    }

    private suspend fun waitUntilSent(peer: PeerDriveQueryProvider, owner: OdinId, send: FilesyncPeerSend): PeerSendStatus {
        repeat(60) {
            val status = peer.sendStatus(owner, FilesyncProtocol.DriveAlias, send.globalTransitId, send.sentVersionTag)
            if (!status.pending) return status
            delay(2_000)
        }
        error("${send.globalTransitId} still pending after two minutes")
    }

    private suspend fun rawFileId(query: DriveQueryProvider, gtid: Uuid): Uuid {
        val page = query.queryBatch(
            FilesyncProtocol.DriveAlias,
            id.homebase.api.client.drives.QueryBatchRequest(
                queryParams = id.homebase.api.client.drives.query.FileQueryParams(globalTransitId = listOf(gtid)),
                resultOptionsRequest = id.homebase.api.client.drives.QueryBatchResultOptionsRequest(maxRecords = 1, includeMetadataHeader = false),
            ),
        )
        return page.searchResults.single().fileId
    }

    private fun JsonObject.obj(name: String) = getValue(name).jsonObject
    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content
}
