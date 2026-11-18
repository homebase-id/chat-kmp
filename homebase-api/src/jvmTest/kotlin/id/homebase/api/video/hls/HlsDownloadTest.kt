package id.homebase.api.video.hls

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.video.downloadHlsAsMp4
import id.homebase.api.video.isHlsVideo
import id.homebase.api.video.playableContentType
import id.homebase.api.video.VideoMetadata
import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.drives.files.ExportDestination
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadDownloadService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import okio.FileSystem

/**
 * A received HLS video saved to the gallery, end to end: our segmenter's encrypted payload, served as the server
 * stores it, through the real DriveFileProvider download and decrypt, remuxed, and compared with the source.
 */
class HlsDownloadTest {
    private val cache = Files.createTempDirectory("hls-download").toFile()
    private val tsUses = mutableListOf<String>()
    private val fileOps = object : OkioFileOperationsProvider(FileSystem.SYSTEM, cache.absolutePath) {
        override fun readFileAsFlow(path: String, chunkSize: Int) = super.readFileAsFlow(path, chunkSize).also { if (path.endsWith(".ts")) tsUses += "read $path" }

        override suspend fun writeStream(path: String, data: Flow<ByteArray>) {
            if (path.endsWith(".ts")) tsUses += "write $path"
            super.writeStream(path, data)
        }

        override suspend fun writeDownload(path: String, data: Flow<ByteArray>) {
            if (path.endsWith(".ts")) tsUses += "write $path"
            super.writeDownload(path, data)
        }
    }
    private val keyHeader = KeyHeader(Random.nextBytes(16), SecureByteArray(Random.nextBytes(16)))
    private val clients = mutableListOf<HttpClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        cache.deleteRecursively()
    }

    @Test
    fun aSavedHlsVideoIsTheSourceSampleForSample() = runTest { check("moov_first.mp4") }

    @Test
    fun aSourceWithItsIndexLastToo() = runTest { check("moov_last.mp4") }

    @Test
    fun anHlsVideoIsExportedAsAnMp4WhateverTheAskedSuffix() = runTest {
        val source = File(cache, "moov_first.mp4").apply { HlsDownloadTest::class.java.getResourceAsStream("/hls/moov_first.mp4")!!.use { i -> outputStream().use { i.copyTo(it) } } }
        val segmented = assertNotNull(HlsSegmenter.segmentAndEncrypt(fileOps, source.absolutePath, keyHeader))
        val current = DescriptorContentV1(kind = DescriptorContentV1.Kind.VIDEO)
            .withVideo(VideoMetadata(mimeType = "video/mp2t", isSegmented = true), original = false, playlistKey = "pls_0001").encode()
        val descriptors = listOf(current, """{"mimeType":"video/mp2t","isSegmented":true}""")
        for (descriptor in descriptors) {
            val payload = PayloadDescriptor(key = "chat_web0", contentType = "video/mp2t", descriptorContent = descriptor)
            assertTrue(payload.isHlsVideo, descriptor)
            assertEquals("video/mp4", payload.playableContentType)

            val service = PayloadDownloadService(server(File(segmented.segmentsPath).readBytes()), fileOps)
            val exported = assertNotNull(service.exportPlayable(DRIVE, FILE, payload, keyHeader, ExportDestination.ShareOutbound(".ts")))
            assertTrue(exported.endsWith(".mp4"), exported)
            assertSameMedia(fileOps, source, File(exported))
        }
    }

    @Test
    fun aVideoThatIsntHlsIsSentAsItIs() {
        val payload = PayloadDescriptor(key = "chat_web0", contentType = "video/mp4", descriptorContent = """{"mimeType":"video/mp4","isSegmented":false}""")
        assertFalse(payload.isHlsVideo)
        assertEquals("video/mp4", payload.playableContentType)
        assertFalse(PayloadDescriptor(key = "chat_web0", contentType = "image/jpeg").isHlsVideo)
    }

    private suspend fun check(name: String) {
        val source = File(cache, name).apply { HlsDownloadTest::class.java.getResourceAsStream("/hls/$name")!!.use { i -> outputStream().use { i.copyTo(it) } } }
        val segmented = assertNotNull(HlsSegmenter.segmentAndEncrypt(fileOps, source.absolutePath, keyHeader))
        assertTrue(File(segmented.playlistPath).readText().split("#EXTINF").size > 2, "several segments, so boundaries are crossed")
        val stored = File(segmented.segmentsPath).readBytes()

        tsUses.clear() // the fixture's own segmenting writes one
        val mp4 = File(cache, "saved.mp4")
        assertTrue(server(stored).downloadHlsAsMp4(DRIVE, FILE, "chat_web0", keyHeader, fileOps, mp4.absolutePath))
        assertSameMedia(fileOps, source, mp4)
        assertEquals(emptyList(), tsUses, "the download is remuxed as it arrives, with no .ts written or read")
    }

    /** A provider whose server holds [stored]: whole, or the byte range a request asks for. */
    private suspend fun server(stored: ByteArray): DriveFileProvider {
        val credentials = CredentialsManager().also {
            it.setActiveCredentials(ApiCredentials.create(OdinId("frodobaggins.me"), "t", SecureByteArray(ByteArray(32) { 1 })))
        }
        val http = HttpClient(MockEngine { request ->
            val range = request.headers["Range"]?.removePrefix("bytes=")?.split("-")
            val from = range?.getOrNull(0)?.toIntOrNull() ?: 0
            val to = range?.getOrNull(1)?.toIntOrNull()?.plus(1)?.coerceAtMost(stored.size) ?: stored.size
            respond(
                content = stored.copyOfRange(from, to),
                status = if (range == null) HttpStatusCode.OK else HttpStatusCode.PartialContent,
                headers = headersOf(
                    "payloadencrypted" to listOf("true"),
                    "Content-Range" to listOf("bytes $from-${to - 1}/${stored.size}"),
                ),
            )
        }).also { clients += it }
        return DriveFileProvider(http, credentials, DriveFileProviderCached(http, credentials, fileOps))
    }

    private companion object {
        val DRIVE = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val FILE = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    }
}
