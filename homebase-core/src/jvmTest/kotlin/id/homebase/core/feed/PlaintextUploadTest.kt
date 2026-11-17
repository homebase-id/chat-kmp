package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.core.feed.services.enqueuePlaintextUpload
import id.homebase.core.feed.services.FeedProtocol
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.FileSystem

/** A public post's sent copy is in the cache from the start, as an encrypted send's is, so the sender sees it. */
class PlaintextUploadTest {
    private val drive = SystemDriveConstants.publicPostChannelDrive.alias

    @Test
    fun aPublicPostsSentCopyIsSeededPlainUnderItsOptimisticFileId() = runTest {
        val dir = Files.createTempDirectory("plaintext-upload").toFile()
        val env = FeedTestEnv(this, fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, dir.absolutePath))
        env.login()
        advanceUntilIdle()
        try {
            val photo = File(dir, "photo.bin").apply { writeBytes(Random.nextBytes(2_000)) }
            val thumb = Random.nextBytes(300)
            val uniqueId = Uuid.random()
            val fileId = Uuid.random()
            enqueuePlaintextUpload(
                outboxSync = env.outboxSync,
                optimisticWriter = env.optimisticWriter,
                payloadCacheSeeder = env.payloadCacheSeeder,
                fileOps = env.fileOps,
                request = UploadFileRequest(
                    driveId = drive,
                    keyHeader = KeyHeader.empty(),
                    metadata = UploadFileMetadata(
                        allowDistribution = true,
                        isEncrypted = false,
                        appData = UploadAppFileMetaData(uniqueId = uniqueId, fileType = FeedProtocol.PostFileType),
                    ),
                    payloads = listOf(PayloadFile(key = "pst_mdi0", filePath = photo.absolutePath, contentType = "image/jpeg", stripMetadata = false)),
                    thumbnails = listOf(ThumbnailFile(pixelWidth = 320, pixelHeight = 240, thumbnailBytes = thumb, key = "pst_mdi0")),
                ),
                tag = "test",
                optimisticFileId = fileId,
            )

            // Every network request fails here: bytes back are the seeded entry, served as they are.
            val payload = env.driveFileProvider.getPayloadBytesDecrypted(drive, fileId, "pst_mdi0", KeyHeader.empty(), null, null, null)
            assertContentEquals(photo.readBytes(), assertNotNull(payload).bytes)
            val thumbnail = env.driveFileProvider.getThumbBytesDecrypted(drive, fileId, "pst_mdi0", KeyHeader.empty(), 320, 240)
            assertContentEquals(thumb, assertNotNull(thumbnail).bytes)

            // The row lists the seeded size, which is the one the views ask for.
            val row = assertNotNull(
                env.databaseManager.driveMainIndex.selectHomebaseFileByUnique(
                    env.credentialsManager.requireActiveCredentials().getIdentityId(), drive, uniqueId,
                )
            )
            assertEquals(fileId, row.fileId)
            val thumbs = assertNotNull(row.fileMetadata.payloads?.single()?.thumbnails)
            assertEquals(listOf(320 to 240), thumbs.map { it.pixelWidth to it.pixelHeight })
        } finally {
            env.close()
            dir.deleteRecursively()
        }
    }
}
