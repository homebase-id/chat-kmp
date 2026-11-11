package id.homebase.core.moments

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.MediaQuality
import id.homebase.api.video.VideoPayloadProcessor
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.chat.services.outbox.OptimisticWriterPort
import id.homebase.core.clipboard.platformFileFromPath
import id.homebase.core.feed.FeedTestEnv
import id.homebase.core.moments.services.MomentsPostSenderService
import id.homebase.core.ui.screens.moments.toAttachmentInput
import id.homebase.core.ui.screens.moments.toMediaInfo
import id.homebase.upload.PayloadBundle
import id.homebase.upload.PayloadBundleEncryptionService
import id.homebase.upload.PayloadBundleEncryptor
import id.homebase.upload.PayloadCacheSeeder
import id.homebase.upload.UploadService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MomentsHeicOriginalScrubTest {

    private class Sent(val bundle: PayloadBundle, val aesKey: SecureByteArray)

    @Test
    fun heicPostedOnOriginalWithLocationOff_uploadsNoGps() = runTest {
        val env = FeedTestEnv(this)
        env.login()
        val fileOps = JvmFileOperationsProvider()
        val sent = CompletableDeferred<Sent>()
        val staged = mutableListOf<String>()
        val realEncryptor = PayloadBundleEncryptionService(fileOps, VideoPayloadProcessor(fileOps), env.eventBus)
        val recordingEncryptor = object : PayloadBundleEncryptor {
            override suspend fun encryptBundle(
                uniqueId: Uuid,
                bundle: PayloadBundle?,
                aesKey: SecureByteArray,
                scope: CoroutineScope,
            ) = realEncryptor.encryptBundle(uniqueId, bundle, aesKey, scope).also { result ->
                staged += result.payloads.map { it.filePath }
                sent.complete(Sent(result, aesKey))
            }
        }
        val http = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
        val driveFileProvider = DriveFileProvider(
            httpClient = http,
            credentialsManager = env.credentialsManager,
            driveCache = DriveFileProviderCached(http, env.credentialsManager, fileOps),
        )
        val noCacheSeed = object : PayloadCacheSeeder(driveFileProvider, fileOps) {
            override suspend fun seed(driveId: Uuid, fileId: Uuid, bundle: PayloadBundle) {}
        }
        env.userPreferences.mediaQuality = MediaQuality.ORIGINAL
        val sender = MomentsPostSenderService(
            uploadService = UploadService(
                encryptor = recordingEncryptor,
                outboxSync = env.outboxSync,
                optimisticWriter = OptimisticWriterPort(env.optimisticWriter),
                payloadCacheSeeder = noCacheSeed,
            ),
            fileOps = fileOps,
            driveFileProvider = driveFileProvider,
            optimisticWriter = env.optimisticWriter,
            credentialsManager = env.credentialsManager,
            dbm = env.databaseManager,
            eventBus = env.eventBus,
            scope = env.scope,
            userPreferences = env.userPreferences,
        )

        val original = assertNotNull(javaClass.getResourceAsStream("/geotagged_rotated.heic")).readBytes()
        val src = File.createTempFile("moment_", ".heic").apply { writeBytes(original) }
        val picked = AttachmentPendingFile.FileImage(
            id = Uuid.random(),
            file = platformFileFromPath(src.absolutePath),
            metadata = ImageMetadata(latitude = 37.7749, longitude = -122.4194, cameraMake = "SecretCamera"),
            includeLocation = false,
        )
        val mediaInfo = picked.toMediaInfo()
        try {
            sender.postMomentAsync(
                attachments = listOf(picked.toAttachmentInput()),
                description = "",
                recipients = emptyList(),
                mediaInfoByAttachment = listOf(mediaInfo),
            )
            val upload = sent.await()
            val payload = upload.bundle.payloads.single()
            val uploaded = KeyHeader(aesKey = upload.aesKey, iv = assertNotNull(payload.iv))
                .decrypt(File(payload.filePath).readBytes())

            assertTrue(payload.contentType == "image/heic", "Original must upload the HEIC itself, got ${payload.contentType}")
            val gpsIfdTagBigEndian = byteArrayOf(0x88.toByte(), 0x25)
            val gpsIfdTagLittleEndian = byteArrayOf(0x25, 0x88.toByte())
            assertTrue(original.exifItem().contains(gpsIfdTagBigEndian), "fixture must carry a GPS IFD")
            listOf(gpsIfdTagBigEndian, gpsIfdTagLittleEndian).forEach {
                assertFalse(uploaded.exifItem().contains(it), "GPS IFD reached the wire")
            }
            assertFalse(uploaded.contains("SECRETSERIAL".encodeToByteArray()), "camera serial reached the wire")
            assertNull(mediaInfo?.latitude)
            assertNull(mediaInfo?.longitude)
        } finally {
            src.delete()
            staged.forEach { File(it).delete() }
            env.close()
        }
    }

    // Offsets from homebase-api's HeicFixtures: the Exif item sits at 572..824.
    private fun ByteArray.exifItem() = copyOfRange(572, 824)

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { at -> needle.indices.all { this[at + it] == needle[it] } }
}
