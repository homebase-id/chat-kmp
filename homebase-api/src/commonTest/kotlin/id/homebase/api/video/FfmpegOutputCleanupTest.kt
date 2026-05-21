package id.homebase.api.video

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-platform red-green test for [deleteFailedFfmpegOutput]. Uses an okio
 * [FakeFileSystem], so the shared cleanup logic is exercised on every target —
 * no real FFmpeg, no device needed.
 */
class FfmpegOutputCleanupTest {

    @Test
    fun deletesLeftoverFile() {
        val fs = FakeFileSystem()
        val file = "/cache/compressed_clip.mp4".toPath()
        fs.createDirectories("/cache".toPath())
        fs.write(file) { write(ByteArray(16)) }

        val deleted = deleteFailedFfmpegOutput(file.toString(), fs)

        assertTrue(deleted, "deleteFailedFfmpegOutput must report it deleted the file")
        assertFalse(fs.exists(file), "partial output file must be deleted")
    }

    @Test
    fun deletesLeftoverDirectoryRecursively() {
        val fs = FakeFileSystem()
        val dir = "/cache/hls_abc".toPath()
        fs.createDirectories(dir)
        fs.write(dir / "index.ts") { write(ByteArray(32)) }
        fs.write(dir / "index.m3u8") { write(ByteArray(8)) }
        fs.write(dir / "enc.key") { write(ByteArray(16)) }

        val deleted = deleteFailedFfmpegOutput(dir.toString(), fs)

        assertTrue(deleted, "deleteFailedFfmpegOutput must report it deleted the directory")
        assertFalse(fs.exists(dir), "leftover hls_<uuid>/ dir must be deleted recursively")
    }

    @Test
    fun missingPath_returnsFalse_doesNotThrow() {
        val fs = FakeFileSystem()
        val deleted = deleteFailedFfmpegOutput("/cache/does_not_exist.mp4", fs)
        assertFalse(deleted, "a missing path is not an error — just nothing to delete")
    }

    // --- safety guards: a blank / relative / root path here means an upstream
    //     bug; deleteRecursively must never run on it. ---

    @Test
    fun refusesRootPath_doesNotRecurseIntoFilesystemRoot() {
        val fs = FakeFileSystem()
        val sentinel = "/keep_me.txt".toPath()
        fs.write(sentinel) { write(ByteArray(4)) }

        val deleted = deleteFailedFfmpegOutput("/", fs)

        assertFalse(deleted, "must refuse to delete the filesystem root")
        assertTrue(fs.exists(sentinel), "refusing the root must not touch anything under it")
    }

    @Test
    fun refusesRelativePath() {
        val fs = FakeFileSystem()
        val deleted = deleteFailedFfmpegOutput("hls_relative", fs)
        assertFalse(deleted, "must refuse a non-absolute path")
    }

    @Test
    fun refusesBlankPath() {
        val fs = FakeFileSystem()
        val deleted = deleteFailedFfmpegOutput("   ", fs)
        assertFalse(deleted, "must refuse a blank path")
    }
}
