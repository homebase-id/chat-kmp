package id.homebase.api.serialization

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.GlobalTransitIdFileIdentifier
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.SendReadReceiptByFileIdsOutboxRequest
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.files.reactions.SetReactionsOutboxRequest
import id.homebase.api.client.drives.files.reactions.ToggleReactionOutboxRequest
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.drives.upload.FileIdFileIdentifier
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.PayloadOperationType
import id.homebase.api.client.drives.upload.PriorityOptions
import id.homebase.api.client.drives.upload.ScheduleOptions
import id.homebase.api.client.drives.upload.SendContents
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateLocalAppdataContentOutboxRequest
import id.homebase.api.client.drives.upload.UpdateLocalMetadataTagsOutboxRequest
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.drives.upload.UploadManifestPayloadDescriptor
import id.homebase.api.client.notifications.CancelScheduledPushRequest
import id.homebase.api.client.notifications.ScheduledPushNotificationOptions
import id.homebase.api.client.notifications.SchedulePushNotificationRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.sync.database.Outbox
import id.homebase.api.video.VideoQuality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class OutboxSerializerTest {

    private val drive = Uuid.random()
    private val file = Uuid.random()
    private val unique = Uuid.random()
    private val targetDrive = TargetDrive(Uuid.random(), Uuid.random())
    private val peer = OdinId("frodo.dotyou.cloud")
    private val keyHeader = KeyHeader(ByteArray(16) { it.toByte() }, SecureByteArray(ByteArray(16) { (it + 1).toByte() }))
    private val thumb = EmbeddedThumb(20, 30, "image/webp", "AAEC")

    private fun metadata(populated: Boolean) = UploadFileMetadata(
        allowDistribution = populated,
        isEncrypted = true,
        accessControlList = if (populated) AccessControlList("owner", listOf("c1"), listOf(peer)) else null,
        appData = UploadAppFileMetaData(
            uniqueId = unique,
            tags = if (populated) listOf(Uuid.random()) else null,
            fileType = 101,
            dataType = if (populated) 7 else null,
            userDate = if (populated) 1_700_000_000_000 else null,
            groupId = if (populated) Uuid.random() else null,
            archivalStatus = if (populated) ArchivalStatus.Archived else null,
            content = if (populated) "{\"m\":\"hi\"}" else null,
            previewThumbnail = if (populated) thumb else null,
        ),
        referencedFile = if (populated) GlobalTransitIdFileIdentifier(Uuid.random(), targetDrive) else null,
        versionTag = if (populated) Uuid.random() else null,
        ttl = if (populated) -5000 else null,
    )

    private val payloads = listOf(
        PayloadFile(
            key = "pyld_abc123",
            filePath = "/cache/outbox-temp/x.enc",
            previewThumbnail = thumb,
            contentType = "video/mp4",
            descriptorContent = "d",
            isPreEncrypted = true,
            iv = ByteArray(16) { 9 },
            trimStartMs = 10,
            trimEndMs = 2000,
            videoQuality = VideoQuality.LOW,
            inputBlobUrl = "blob:x",
        ),
        PayloadFile(key = "pyld_plain1", filePath = "/cache/y"),
    )

    private val thumbnails = listOf(ThumbnailFile(10, 20, ByteArray(4) { 3 }, "pyld_abc123"))

    private val instructions = FileUpdateInstructionSet(
        transferIv = ByteArray(16) { 5 },
        locale = UpdateLocale.Peer,
        recipients = listOf(peer),
        manifest = UpdateManifest(
            listOf(
                UploadManifestPayloadDescriptor(
                    payloadKey = "pyld_abc123",
                    operationType = PayloadOperationType.AppendOrOverwrite,
                    descriptorContent = "d",
                    contentType = "video/mp4",
                    previewThumbnail = thumb,
                    iv = ByteArray(16) { 7 },
                ),
                UploadManifestPayloadDescriptor("gone_key1", PayloadOperationType.DeletePayload),
            )
        ),
    )

    private inline fun <reified T> assertRoundTrips(value: T) {
        val old = OdinSystemSerializer.serialize(value)
        val row = outboxRow(old)
        val decoded = OutboxSerializer.decode<T>(row)
        assertEquals(old, OutboxSerializer.serialize(decoded), "${T::class.simpleName} changed on strict round-trip")
    }

    @Test
    fun uploadFileRequest_populated_and_sparse() {
        assertRoundTrips(
            UploadFileRequest(
                drive, keyHeader, metadata(true), payloads, thumbnails,
                TransitOptions(
                    recipients = listOf(peer), isTransient = false, schedule = ScheduleOptions.SendLater,
                    priority = PriorityOptions.High, sendContents = SendContents.Thumbnails,
                    remoteTargetDrive = targetDrive, useAppNotification = false,
                ),
                FileSystemType.Comment,
            )
        )
        assertRoundTrips(UploadFileRequest(drive, keyHeader, metadata(false)))
    }

    @Test
    fun updateFileByUniqueIdRequest_populated_and_sparse() {
        assertRoundTrips(UpdateFileByUniqueIdRequest(drive, unique, keyHeader, instructions, metadata(true), payloads, thumbnails))
        assertRoundTrips(UpdateFileByUniqueIdRequest(drive, unique, null, instructions, metadata(false)))
    }

    @Test
    fun deleteLocalFilesByFileIdRequest() {
        assertRoundTrips(DeleteLocalFilesByFileIdRequest(drive, listOf(file, unique), listOf(peer), true))
        assertRoundTrips(DeleteLocalFilesByFileIdRequest(drive, listOf(file)))
    }

    @Test
    fun updateLocalMetadataTagsOutboxRequest() {
        val id = FileIdFileIdentifier(file.toString(), targetDrive)
        assertRoundTrips(UpdateLocalMetadataTagsOutboxRequest(id, "vt", listOf("a", "b"), unique))
        assertRoundTrips(UpdateLocalMetadataTagsOutboxRequest(id, null, null))
    }

    @Test
    fun updateLocalAppdataContentOutboxRequest() {
        assertRoundTrips(UpdateLocalAppdataContentOutboxRequest(drive, file, "vt", "content", "iv"))
        assertRoundTrips(UpdateLocalAppdataContentOutboxRequest(drive, file, null, null, null))
    }

    @Test
    fun sendReadReceiptByFileIdsOutboxRequest() {
        assertRoundTrips(SendReadReceiptByFileIdsOutboxRequest(drive, listOf(file, unique)))
    }

    @Test
    fun toggleAndSetReactionRequests() {
        assertRoundTrips(ToggleReactionOutboxRequest(drive, file, "thumbsup", listOf(peer)))
        assertRoundTrips(SetReactionsOutboxRequest(drive, file, listOf("a"), listOf("b"), listOf(peer)))
    }

    @Test
    fun deleteFilesByGroupIdOutboxRequest() {
        assertRoundTrips(DeleteFilesByGroupIdOutboxRequest(drive, listOf(file, unique)))
    }

    @Test
    fun scheduledPushRequests() {
        val options = ScheduledPushNotificationOptions(Uuid.random(), Uuid.random(), Uuid.random(), true, Uuid.random(), listOf(peer), "hi")
        assertRoundTrips(SchedulePushNotificationRequest(options, UnixTimeUtc(1_700_000_000_000), 60))
        assertRoundTrips(SchedulePushNotificationRequest(options.copy(peerSubscriptionId = null, recipients = null, unEncryptedMessage = null), UnixTimeUtc(1)))
        assertRoundTrips(CancelScheduledPushRequest(Uuid.random()))
    }

    private fun validUpdateJson(): JsonObject =
        Json.parseToJsonElement(
            OdinSystemSerializer.serialize(UpdateFileByUniqueIdRequest(drive, unique, keyHeader, instructions, metadata(true)))
        ).jsonObject

    private fun decodeUpdate(obj: JsonObject) =
        OutboxSerializer.decode<UpdateFileByUniqueIdRequest>(outboxRow(obj.toString()))

    @Test
    fun unknownKeyIsRejected() {
        val e = assertFailsWith<OutboxDecodeException> {
            decodeUpdate(JsonObject(validUpdateJson() + ("removedInThisRelease" to JsonPrimitive(1))))
        }
        assertEquals("UpdateFileByUniqueIdRequest", e.requestType)
    }

    @Test
    fun unknownEnumValueIsRejected() {
        val obj = validUpdateJson()
        val ins = obj.getValue("instructions").jsonObject
        val bad = JsonObject(obj + ("instructions" to JsonObject(ins + ("locale" to JsonPrimitive("Bogus")))))
        assertFailsWith<OutboxDecodeException> { decodeUpdate(bad) }
    }

    @Test
    fun missingRequiredFieldIsRejected() {
        assertFailsWith<OutboxDecodeException> { decodeUpdate(JsonObject(validUpdateJson() - "driveId")) }
    }

    @Test
    fun nullOnNonNullFieldIsRejectedNotCoerced() {
        val obj = validUpdateJson()
        val ins = obj.getValue("instructions").jsonObject
        val bad = JsonObject(obj + ("instructions" to JsonObject(ins + ("useAppNotification" to JsonNull))))
        assertFailsWith<OutboxDecodeException> { decodeUpdate(bad) }
    }

    @Test
    fun garbageJsonIsRejected() {
        assertFailsWith<OutboxDecodeException> {
            OutboxSerializer.decode<UploadFileRequest>(outboxRow("{not json"))
        }
        assertFailsWith<OutboxDecodeException> {
            OutboxSerializer.decode<UploadFileRequest>(outboxRow(""))
        }
    }

    private fun outboxRow(json: String) = Outbox(
        rowId = 42,
        driveId = drive,
        uniqueId = unique,
        dependencyUniqueId = null,
        priority = 0,
        lastAttempt = 0,
        nextRunTime = 0,
        checkOutCount = 0,
        checkOutStamp = null,
        uploadType = 0,
        json = json.encodeToByteArray(),
        files = null,
    )
}
