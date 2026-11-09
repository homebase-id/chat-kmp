package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.encryptWithPayloadHash
import id.homebase.api.file.OkioFileOperationsProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.uuid.Uuid

class FilesyncSharedPayloadTest {

    private val dir = Files.createTempDirectory("shared-payload")
    private val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, dir.toString())

    private suspend fun download(plain: ByteArray): ByteArray {
        val source = dir.resolve("plain").also { Files.write(it, plain) }.toString()
        val encryptedPath = dir.resolve("enc").toString()
        val aesKey = SecureByteArray(ByteArray(16) { 5 })
        val hashed = fileOps.encryptWithPayloadHash(source, encryptedPath, aesKey, PayloadHashAlgorithm.Blake3)
        val stored = Files.readAllBytes(dir.resolve("enc"))
        val engine = MockEngine { request ->
            if (request.url.host.startsWith("cdn")) return@MockEngine respond("", HttpStatusCode.InternalServerError)
            respond(stored, HttpStatusCode.OK, headersOf("payloadencrypted", "true"))
        }
        val credentials = CredentialsManager().apply { setActiveCredentials(ApiCredentials.create(OdinId("frodo.test"), "token", SecureByteArray(ByteArray(16)))) }
        val repository = testFilesyncRepository(HttpClient(engine), credentials)
        val payload = FilesyncPayload(Uuid.random(), KeyHeader(hashed.iv, aesKey), hashed.hash)
        val out = ByteArrayOutputStream()
        repository.streamSharedPayload(OdinId("sam.test"), Uuid.random(), payload) { flow -> flow.collect { out.write(it) } }
        return out.toByteArray()
    }

    @Test
    fun aSharedFileDownloadsWhole() = runTest {
        val plain = ByteArray(5_000) { (it % 251).toByte() }
        assertContentEquals(plain, download(plain))
    }

    @Test
    fun aSharedFileOnABlockBoundaryDownloadsWhole() = runTest {
        val plain = ByteArray(4_096) { (it % 251).toByte() }
        assertContentEquals(plain, download(plain))
    }

    @Test
    fun aSharedFileOverSeveralChunksDownloadsWhole() = runTest {
        val plain = ByteArray(4 * 1024 * 1024 + 100) { (it % 251).toByte() }
        assertContentEquals(plain, download(plain))
    }
}
