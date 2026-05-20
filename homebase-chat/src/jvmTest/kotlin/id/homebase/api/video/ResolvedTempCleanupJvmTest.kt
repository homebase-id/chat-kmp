package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.file.safeDeleteRecursively
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue

/**
 * Regression test for the `resolved_*` cache leak.
 *
 * On Android the video picker hands [VideoPayloadProcessor.process] a
 * `content://` URI; `resolveToFilePath` copies the entire picked video into
 * cacheDir as `resolved_*.<ext>` before any FFmpeg work. That copy is the full
 * size of the source — a 125 MB send was observed leaving a 125 MB
 * `resolved_*.mp4` in cacheDir until the next cold-start CacheSweeper run
 * (logcat: `CacheSweeper deleting resolved_… — 125223174 bytes`). `process`
 * must reap that copy itself, on the same send, not lean on the startup sweep.
 *
 * JVM's real `resolveToFilePath` is a no-op (no copy), so this test wraps the
 * JVM provider with one that copies on resolve — mirroring Android — and
 * asserts the copy is gone once `process` returns, while the encrypted payload
 * it produced survives.
 *
 * Skips silently when bundled ffmpeg binaries aren't on the classpath. Run with:
 *   ./gradlew homebase-chat:jvmTest --tests "*ResolvedTempCleanupJvm*"
 */
class ResolvedTempCleanupJvmTest {

    private val cacheDir: File = File.createTempFile("resolved_test_cache_", "").let {
        it.delete(); it.mkdirs(); it
    }

    @AfterTest
    fun tearDown() {
        if (cacheDir.exists()) safeDeleteRecursively(cacheDir.parent, cacheDir.name)
    }

    /**
     * Wraps [JvmFileOperationsProvider] but (a) routes scratch into a test-owned
     * cacheDir and (b) makes `resolveToFilePath` COPY its input, the way the
     * Android actual does for a `content://` pick. Records the copies it makes
     * and the paths `process` asks to delete.
     */
    private class CopyOnResolveProvider(
        private val cacheDir: File,
        private val delegate: JvmFileOperationsProvider = JvmFileOperationsProvider(),
    ) : FileOperationsProvider by delegate {

        val resolvedCopies = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        override fun getCacheDirectory(): String = cacheDir.absolutePath

        override suspend fun resolveToFilePath(path: String): String {
            val copy = File.createTempFile("resolved_", ".mp4", cacheDir)
            copy.writeBytes(File(path).readBytes())
            resolvedCopies += copy.absolutePath
            return copy.absolutePath
        }

        override fun deleteTempFile(path: String): Boolean {
            deleted += path
            return delegate.deleteTempFile(path)
        }
    }

    @Test
    fun process_reapsTheResolvedCopyButKeepsTheEncryptedPayload() = runTest {
        assumeTrue(
            "FFmpeg binaries not bundled in this test classpath",
            FFmpegBinaryManager.isAvailable(),
        )

        val fixture = VideoTestHelper.copyToTempFile("sample.mp4")
        val provider = CopyOnResolveProvider(cacheDir)
        val processor = VideoPayloadProcessor(provider)

        try {
            val result = processor.process(
                payload = PayloadFile(key = "vid", filePath = fixture, contentType = "video/mp4"),
                keyHeader = KeyHeader.newRandom16(),
                onProgress = null,
                descriptorContentPayloadKey = "desc",
            )

            val resolvedCopy = provider.resolvedCopies.single()
            assertFalse(
                File(resolvedCopy).exists(),
                "the resolved_* plaintext copy must be deleted by process(), not left for the startup sweep",
            )
            assertTrue(
                provider.deleted.contains(resolvedCopy),
                "process() must call deleteTempFile on the resolved copy it created",
            )

            // We deleted the right file: the encrypted payload process produced
            // is a different path and still exists for the outbox to upload.
            val payloadPath = result.payloads.first().filePath
            assertTrue(payloadPath != resolvedCopy, "payload must not point at the reaped copy")
            assertTrue(File(payloadPath).exists(), "the encrypted payload must survive for upload")
        } finally {
            File(fixture).delete()
        }
    }
}
