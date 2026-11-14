package id.homebase.api.client.drives.upload

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.DescriptorContentSeal
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.withSealedDescriptor
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// The provider seals descriptor content on the way out, whoever queued the request (UPLOADS.md section 2).
class SealDescriptorContentTest {

    private val name = "secret-name.pdf"

    private fun request(dir: File, encrypted: Boolean = true, asks: Boolean = true, descriptor: String = name) = UploadFileRequest(
        driveId = Uuid.random(),
        keyHeader = KeyHeader.newRandom16(),
        metadata = UploadFileMetadata(allowDistribution = false, isEncrypted = encrypted, appData = UploadAppFileMetaData(uniqueId = Uuid.random(), fileType = 1)),
        payloads = listOf(
            PayloadFile(
                key = "chat_web0",
                filePath = File(dir, "p").apply { writeBytes(ByteArray(64) { 1 }) }.path,
                contentType = "application/pdf",
                descriptorContent = descriptor,
                isPreEncrypted = true,
                iv = ByteArray(16),
            ),
        ),
        sealDescriptorContent = asks,
    )

    private suspend fun sentBody(request: UploadFileRequest, dir: File): String {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(ApiCredentials.create(OdinId("owner.test"), "t", SecureByteArray(ByteArray(16))))
        val provider = DriveUploadProvider(HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }), credentials, JvmFileOperationsProvider())
        val body = File(dir, "body")
        provider.writeUploadBody(request, body.path)
        return body.readText()
    }

    private fun withDir(block: suspend (File) -> Unit) = runTest {
        val dir = Files.createTempDirectory("seal").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test
    fun anEncryptedUploadThatAsksIsSealedOnTheWayOut() = withDir { dir ->
        val sent = sentBody(request(dir), dir)
        assertFalse(name in sent, "the name must not reach the server readable")
        assertTrue("{\\\"e\\\":" in sent, "the manifest carries the sealed form")
    }

    @Test
    fun withTheSwitchOffAndNoAskItGoesOutAsBefore() = withDir { dir ->
        assertTrue(name in sentBody(request(dir, asks = false), dir))
    }

    @Test
    fun aFileThatIsntEncryptedIsNeverSealed() = withDir { dir ->
        assertTrue(name in sentBody(request(dir, encrypted = false), dir))
    }

    @Test
    fun sealedContentIsNotSealedAgain() = runTest {
        val key = KeyHeader.newRandom16().aesKey
        val once = PayloadFile(key = "chat_web0", filePath = "x", contentType = "a/b", descriptorContent = name).withSealedDescriptor(key)
        assertEquals(once, once.withSealedDescriptor(key))
        assertEquals(name, DescriptorContentSeal.open(once.descriptorContent!!, key))
    }

    @Test
    fun anUpdateSealsItsAppendedPayloadsAndLeavesDeletesAlone() = runTest {
        val key = KeyHeader.newRandom16().aesKey
        val manifest = UpdateManifest.build(
            payloads = listOf(PayloadFile(key = "chat_web0", filePath = "x", contentType = "a/b", descriptorContent = name, iv = ByteArray(16))),
            toDeletePayloads = listOf(PayloadDeleteKey("chat_web1")),
        ).withSealedDescriptors(key)
        val (append, delete) = manifest.payloadDescriptors!!
        assertEquals(name, DescriptorContentSeal.open(append.descriptorContent!!, key))
        assertEquals(null, delete.descriptorContent)
    }

    @Test
    fun theSizeCheckMeasuresWhatWillBeSent() = withDir { dir ->
        val big = "x".repeat(900)
        request(dir, asks = false, descriptor = big).validateForUpload()
        assertFailsWith<Exception> { request(dir, asks = true, descriptor = big).validateForUpload() }
    }
}
