package id.homebase.api.client.drives.files

import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.classifyPermanentFailure
import id.homebase.api.sync.database.createInMemoryDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The edited-item counterpart to [RetryAsUpdateRecoverLostAckTest], on the same
 * lost-ack premise: a message *edit* (`UpdateFile`) upload completes on the
 * server — applying the edit and bumping the file's versionTag V1 → V2 — but
 * the success ack is swallowed by the network, so the row stays in the outbox.
 * The outbox replays the *same* serialized request, still carrying the stale
 * base versionTag V1 (see ChatMessageSenderService.updateMessage, which stamps
 * `versionTag = versionTag`), so the retry is rejected **because the server
 * already received the edit**.
 *
 * Unlike the create case — which looped forever because "must change the Iv"
 * was unclassified — the edit case is already terminal: the realistic
 * rejections for "you already applied this" are a version-tag mismatch (the
 * server advanced past V1) or, if the server checks the replayed payload IV
 * first, the same "must change the Iv". Both are permanent failures, so the row
 * is DROPPED (Failed + Retry) rather than retried for ~48h. This test drives a
 * real `UpdateFile` row through [DriveOutboxUploader.upload] for each phrasing
 * and asserts exactly that.
 *
 * (A genuine re-send against the *current* server version is the manual-Retry
 * path — ChatMessageSenderService.resendAsUpdate re-stamps with the server's
 * current versionTag — not this automatic outbox replay.)
 */
class EditedItemLostAckRetryIsDroppedTest {

    private val driveId = Uuid.random()
    private val uniqueId = Uuid.random()

    private val credentialsManager = CredentialsManager()

    // updateFile() never touches the DB, but main's DriveOutboxUploader
    // constructor requires one (for the create path's cache re-key) — an
    // in-memory DB satisfies it without being used.
    private val dbm = DatabaseManager({ createInMemoryDatabase() })

    @AfterTest
    fun tearDown() {
        dbm.close()
    }

    private fun mockEngineReturning(problemTitle: String): MockEngine {
        // The server's response to the retry: errorCode collapsed to
        // UnhandledScenario with the title intact (the shape in homebase.log).
        val problemJson =
            """{"status":400,"title":"$problemTitle","extensions":{"errorCode":"UnhandledScenario"}}"""
        return MockEngine {
            respond(
                content = problemJson,
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
    }

    private val fileOps = object : FileOperationsProvider {
        override fun getCacheDirectory() = ""
        override fun openFileInput(path: String): InputProvider = error("not used")
        override suspend fun readFileBytes(path: String): ByteArray = error("not used")
        override fun deleteTempFile(path: String) = false
        override fun getFileSize(path: String) = 0L
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
    }

    private fun uploader(httpClient: HttpClient) = DriveOutboxUploader(
        driveUploadProvider = DriveUploadProvider(httpClient, credentialsManager, fileOps),
        fileProvider = DriveFileProvider(
            httpClient,
            credentialsManager,
            DriveFileProviderCached(httpClient, credentialsManager, fileOps),
        ),
        operationsProvider = DriveFileOperationsProvider(httpClient, credentialsManager),
        reactionProvider = DriveFileGroupReactionProvider(httpClient, credentialsManager),
        databaseManager = dbm,
        credentialsManager = credentialsManager,
    )

    /** A queued *edit* on its second attempt (checkOutCount=1) — an `UpdateFile`
     *  row carrying the original edit's stale base versionTag, exactly as the
     *  outbox replays it after a lost ack. */
    private fun editRetryRow(): Outbox {
        val request = UpdateFileByUniqueIdRequest(
            driveId = driveId,
            uniqueId = uniqueId,
            keyHeader = KeyHeader.newRandom16(),
            instructions = FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = emptyList(),
                manifest = UpdateManifest.build(
                    payloads = emptyList(),
                    toDeletePayloads = null,
                    thumbnails = emptyList(),
                    generatePayloadIv = false,
                ),
            ),
            metadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                // The stale base versionTag the edit was authored against; the
                // server has since advanced past it (the first attempt applied).
                versionTag = Uuid.random(),
                appData = UploadAppFileMetaData(uniqueId = uniqueId, fileType = 7878, content = "{}"),
            ),
        )
        return Outbox(
            rowId = 1L,
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 0L,
            lastAttempt = 0L,
            nextRunTime = 0L,
            checkOutCount = 1L,
            checkOutStamp = 1L,
            uploadType = DriveOutboxUploader.UpdateFile,
            json = OdinSystemSerializer.serialize(request).encodeToByteArray(),
            files = null,
        )
    }

    private suspend fun setupCredentials() {
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("owner.test"),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(ByteArray(16)),
            )
        )
    }

    private suspend fun assertRetryIsDropped(problemTitle: String) {
        val httpClient = HttpClient(mockEngineReturning(problemTitle))
        // The uploader must THROW — never swallow a server rejection by
        // returning normally (that would fake a successful send).
        val thrown = assertFailsWith<ClientException> {
            uploader(httpClient).upload(editRetryRow(), EventBus())
        }
        assertTrue(
            thrown.message?.contains(problemTitle, ignoreCase = true) == true,
            "the uploader must surface the server's rejection verbatim, got: ${thrown.message}",
        )
        // …and the classifier must treat it as permanent so OutboxSync drops the
        // row instead of looping ~48h for an edit that already landed.
        assertNotNull(
            classifyPermanentFailure(thrown),
            "an edited item rejected because the server already applied it must be " +
                "dropped, not retried for 48h (rejection: $problemTitle)",
        )
        httpClient.close()
    }

    @Test
    fun retryRejectedByVersionTagMismatchIsDropped() = runTest {
        // The realistic lost-ack edit failure: the server advanced V1 → V2 when
        // it applied the edit, so the replayed update's stale versionTag no
        // longer matches.
        setupCredentials()
        assertRetryIsDropped("Mismatching version tag")
    }

    @Test
    fun retryRejectedByPayloadIvReuseIsDropped() = runTest {
        // The other phrasing: if the server validates the replayed (unchanged)
        // payload IV before the versionTag, it rejects with the same message the
        // create case looped on. Now classified, so the edit drops too.
        setupCredentials()
        assertRetryIsDropped("When updating a file, you must change the Iv")
    }
}
