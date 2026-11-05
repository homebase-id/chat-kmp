package id.homebase.photobase

import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PhotobaseConfigFileTest {
    private val versionTag = Uuid.random()
    private var stored: HomebaseFile? = null
    private val uploads = mutableListOf<UploadFileRequest>()
    private val updates = mutableListOf<UpdateFileByUniqueIdRequest>()
    private val failures = ArrayDeque<OdinClientErrorCode>()

    private val configFile = PhotobaseConfigFile(
        getFileHeaderByUid = { driveId, uniqueId ->
            assertEquals(PhotobaseProtocol.DriveAlias, driveId)
            assertEquals(PhotobaseProtocol.ConfigUniqueId, uniqueId)
            stored
        },
        uploadFile = { request ->
            failures.removeFirstOrNull()?.let { throw conflict(it) }
            uploads += request
        },
        updateFileByUniqueId = { request ->
            failures.removeFirstOrNull()?.let { throw conflict(it) }
            updates += request
        },
    )

    private fun conflict(code: OdinClientErrorCode) = ClientException(
        status = 400,
        errorCode = code,
        message = "test $code",
        correlationId = null,
        problem = ProblemDetails(status = 400, title = "test"),
    )

    private fun file(content: String?): HomebaseFile {
        val contentField = content?.let { OdinSystemSerializer.serialize(it) } ?: "null"
        return OdinSystemSerializer.deserialize(
            """{
              "fileId": "${Uuid.random()}",
              "driveId": "${PhotobaseProtocol.DriveAlias}",
              "fileState": "active",
              "fileSystemType": "standard",
              "serverFileIsEncrypted": "true",
              "keyHeader": {
                "iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                "aesKey": {"bytes": [1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1]}
              },
              "fileMetadata": {
                "created": 1,
                "updated": 1,
                "transitCreated": 0,
                "transitUpdated": 0,
                "isEncrypted": true,
                "appData": {
                  "uniqueId": "${PhotobaseProtocol.ConfigUniqueId}",
                  "fileType": ${PhotobaseProtocol.ConfigFileType},
                  "dataType": 0,
                  "userDate": 1,
                  "content": $contentField,
                  "archivalStatus": 0
                },
                "versionTag": "$versionTag",
                "payloads": []
              },
              "serverMetadata": {
                "accessControlList": { "requiredSecurityGroup": "owner" },
                "doNotIndex": false,
                "allowDistribution": false,
                "fileSystemType": "standard",
                "fileByteCount": 0,
                "originalRecipientCount": 0
              },
              "priority": 0,
              "fileByteCount": 0
            }""",
        )
    }

    private suspend fun plaintext(metadata: UploadFileMetadata, keyHeader: KeyHeader): String =
        keyHeader.decrypt(Base64.decode(metadata.appData.content!!)).decodeToString()

    @Test
    fun readsNothingWhenNoDeviceHasSavedOne() = runTest {
        assertNull(configFile.read())
    }

    @Test
    fun readsTheSavedInterval() = runTest {
        stored = file("""{"version":1,"pushIntervalMs":7200000}""")
        assertEquals(7_200_000, configFile.read()?.pushIntervalMs)
    }

    @Test
    fun theFirstSaveCreatesAnEncryptedOwnerOnlyFile() = runTest {
        configFile.update { it.copy(pushIntervalMs = 3_600_000) }

        val request = uploads.single()
        assertEquals(PhotobaseProtocol.DriveAlias, request.driveId)
        assertEquals(PhotobaseProtocol.ConfigUniqueId, request.metadata.appData.uniqueId)
        assertEquals(PhotobaseProtocol.ConfigFileType, request.metadata.appData.fileType)
        assertTrue(request.metadata.isEncrypted)
        assertFalse(request.metadata.allowDistribution)
        assertEquals(SecurityGroupType.Owner.value, request.metadata.accessControlList?.requiredSecurityGroup)
        val saved = OdinSystemSerializer.deserialize<PhotobaseConfig>(plaintext(request.metadata, request.keyHeader))
        assertEquals(3_600_000, saved.pushIntervalMs)
    }

    @Test
    fun aLaterSaveUpdatesTheSameFileUnderItsVersionTag() = runTest {
        stored = file("""{"version":1,"pushIntervalMs":3600000}""")

        configFile.update { it.copy(pushIntervalMs = 7_200_000) }

        val request = updates.single()
        assertTrue(uploads.isEmpty())
        assertEquals(versionTag, request.metadata.versionTag)
        assertEquals(SecurityGroupType.Owner.value, request.metadata.accessControlList?.requiredSecurityGroup)
        assertTrue(request.keyHeader!!.aesKey.unsafeBytes.all { it == 1.toByte() }, "same file, same key")
        assertTrue("7200000" in plaintext(request.metadata, request.keyHeader!!))
    }

    @Test
    fun savingKeepsFieldsAddedByANewerApp() = runTest {
        stored = file("""{"version":2,"pushIntervalMs":3600000,"fromTheFuture":{"a":1}}""")

        configFile.update { it.copy(pushIntervalMs = 7_200_000) }

        val request = updates.single()
        val saved = plaintext(request.metadata, request.keyHeader!!)
        assertTrue(""""fromTheFuture":{"a":1}""" in saved, saved)
        assertTrue("7200000" in saved, saved)
    }

    @Test
    fun aConflictWithAnotherDeviceRetries() = runTest {
        stored = file("""{"version":1,"pushIntervalMs":3600000}""")
        failures += OdinClientErrorCode.VersionTagMismatch

        configFile.update { it.copy(pushIntervalMs = 7_200_000) }

        assertEquals(1, updates.size)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun anUnreadableFileReadsAsEmptyAndASaveReplacesIt() = runTest {
        stored = file("not json")
        assertNull(configFile.read())

        configFile.update { it.copy(pushIntervalMs = 7_200_000) }

        val request = updates.single()
        assertEquals(7_200_000, OdinSystemSerializer.deserialize<PhotobaseConfig>(plaintext(request.metadata, request.keyHeader!!)).pushIntervalMs)
    }
}
