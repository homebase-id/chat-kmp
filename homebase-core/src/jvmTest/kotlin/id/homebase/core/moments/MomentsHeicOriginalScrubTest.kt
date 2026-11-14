package id.homebase.core.moments

import id.homebase.core.contains
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.ImageMetadataScrubber
import id.homebase.api.image.MediaQuality
import id.homebase.api.video.VideoPayloadProcessor
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.clipboard.platformFileFromPath
import id.homebase.core.feed.FeedTestEnv
import id.homebase.core.moments.services.MomentsPostSenderService
import id.homebase.core.ui.screens.moments.toAttachmentInput
import id.homebase.core.ui.screens.moments.toMediaInfo
import id.homebase.upload.PayloadBundle
import id.homebase.upload.PayloadBundleEncryptionService
import id.homebase.upload.PayloadBundleEncryptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MomentsHeicOriginalScrubTest {

    @Test
    fun heicPostedOnOriginalWithLocationOff_uploadsNoGps() = runTest {
        val fileOps = JvmFileOperationsProvider()
        val realEncryptor = PayloadBundleEncryptionService(fileOps, VideoPayloadProcessor(fileOps), EventBus())
        val sent = CompletableDeferred<Pair<PayloadBundle, SecureByteArray>>()
        val env = FeedTestEnv(
            this,
            fileOps = fileOps,
            payloadBundleEncryptor = object : PayloadBundleEncryptor {
                override suspend fun encryptBundle(
                    uniqueId: Uuid,
                    bundle: PayloadBundle?,
                    aesKey: SecureByteArray,
                    scope: CoroutineScope,
                ) = realEncryptor.encryptBundle(uniqueId, bundle, aesKey, scope).also { sent.complete(it to aesKey) }
            },
        )
        env.login()
        env.userPreferences.mediaQuality = MediaQuality.ORIGINAL
        val sender = MomentsPostSenderService(
            uploadService = env.uploadService,
            fileOps = fileOps,
            driveFileProvider = env.driveFileProvider,
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
            val (bundle, aesKey) = sent.await()
            val payload = bundle.payloads.single()
            val uploaded = KeyHeader(aesKey = aesKey, iv = assertNotNull(payload.iv))
                .decrypt(File(payload.filePath).readBytes())

            assertEquals("image/heic", payload.contentType)
            assertContentEquals(ImageMetadataScrubber.scrub(original), uploaded)
            // Fixture EXIF is big-endian; the scrubber's replacement is little-endian.
            val gpsIfdTag = byteArrayOf(0x88.toByte(), 0x25)
            assertTrue(original.exifItem().contains(gpsIfdTag), "fixture must carry a GPS IFD")
            assertFalse(uploaded.exifItem().contains(gpsIfdTag), "GPS IFD reached the wire")
            assertFalse(uploaded.exifItem().contains(gpsIfdTag.reversedArray()), "GPS IFD reached the wire")
            assertNotNull(mediaInfo, "camera make keeps the sidecar")
            assertNull(mediaInfo.latitude)
            assertNull(mediaInfo.longitude)
        } finally {
            src.delete()
            if (sent.isCompleted) sent.await().first.payloads.forEach { File(it.filePath).delete() }
            env.close()
        }
    }

    // homebase-api's HeicFixtures puts the Exif item at 572..824; that object isn't visible from here.
    private fun ByteArray.exifItem() = copyOfRange(572, 824)
}
