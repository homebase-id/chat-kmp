package id.homebase.api.client.drives.upload

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.JvmFileOperationsProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class WriteUploadBodyTest {
    @Test
    fun theBodyFileHoldsTheWholeMultipartRequest() = runTest {
        val dir = Files.createTempDirectory("upload-body").toFile()
        try {
            val credentials = CredentialsManager()
            credentials.setActiveCredentials(ApiCredentials.create(OdinId("owner.test"), "test-token", SecureByteArray(ByteArray(16))))
            val provider = DriveUploadProvider(HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }), credentials, JvmFileOperationsProvider())
            val payloadBytes = ByteArray(200_000) { (it % 251).toByte() }
            val payloadFile = File(dir, "payload").apply { writeBytes(payloadBytes) }
            val keyHeader = KeyHeader.newRandom16()
            val driveId = Uuid.random()
            val request = UploadFileRequest(
                driveId = driveId,
                keyHeader = keyHeader,
                metadata = UploadFileMetadata(allowDistribution = false, isEncrypted = true, appData = UploadAppFileMetaData(uniqueId = Uuid.random(), fileType = 7201)),
                payloads = listOf(PayloadFile(key = "photo_main", filePath = payloadFile.path, contentType = "image/jpeg", isPreEncrypted = true, iv = ByteArray(16))),
                thumbnails = listOf(ThumbnailFile(320, 320, "thumbbytes".encodeToByteArray(), "photo_main")),
            )
            val bodyFile = File(dir, "body")

            val prepared = provider.writeUploadBody(request, bodyFile.path)

            assertEquals("test-token", prepared.bearerToken)
            assertTrue(prepared.url.endsWith("/drives/$driveId/files"), prepared.url)
            assertTrue(prepared.contentType.startsWith("multipart/form-data"), prepared.contentType)
            val boundary = prepared.contentType.substringAfter("boundary=").trim('"')
            val body = bodyFile.readBytes()
            val text = body.decodeToString()
            assertTrue(text.contains("--$boundary"))
            assertTrue(text.trimEnd().endsWith("--$boundary--"), "the closing boundary is there")
            assertTrue(text.contains("instructions"))
            assertTrue(text.contains("metadata"))
            assertTrue(indexOf(body, payloadBytes) >= 0, "the payload bytes are in the body whole")
            assertTrue(payloadFile.exists(), "the caller owns the staged payload")
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) if (haystack[start + offset] != needle[offset]) continue@outer
            return start
        }
        return -1
    }
}
