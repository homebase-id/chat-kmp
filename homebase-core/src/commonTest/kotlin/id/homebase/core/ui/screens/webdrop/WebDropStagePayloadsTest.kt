package id.homebase.core.ui.screens.webdrop

import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.ui.screens.webdrop.model.PickedDropFile
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The read-failure contract of issue #1420: an unreadable source aborts the WHOLE drop as a
 * typed [WebDropSourceReadException] naming the file - never a partial drop, never a bare
 * exception - and resolved content-URI copies are reaped either way.
 */
class WebDropStagePayloadsTest {

    private val key = ByteArray(16) { it.toByte() }

    private fun picked(path: String, name: String) =
        PickedDropFile(path = path, name = name, contentType = "application/pdf", size = 0)

    @Test
    fun stagesEveryFileAndCollectsOneIvPerPayload() = runTest {
        val fs = StageFakeFileOps(mutableMapOf("/a.pdf" to ByteArray(40) { 1 }, "/b.pdf" to ByteArray(7) { 2 }))
        val ivs = mutableMapOf<String, ByteArray>()

        val (manifest, payloads) = stageWebDropPayloads(
            fs, listOf(picked("/a.pdf", "A.pdf"), picked("/b.pdf", "B.pdf")), key, ivs,
        )

        assertEquals(listOf("A.pdf", "B.pdf"), manifest.map { it.name })
        assertEquals(listOf(40L, 7L), manifest.map { it.size })
        assertEquals(2, payloads.size)
        assertEquals(manifest.map { it.key }, payloads.map { it.key })
        assertEquals(manifest.map { it.key }.toSet(), ivs.keys)
        // Staged bytes are ciphertext, not the source bytes.
        val staged = fs.files[payloads[0].filePath]!!
        assertFalse(staged.take(7) == List<Byte>(7) { 1 }, "staged payload must be encrypted")
        assertTrue(payloads.all { it.isPreEncrypted })
    }

    @Test
    fun anUnreadableSourceAbortsTheWholeDropTypedAndNamed() = runTest {
        val fs = StageFakeFileOps(mutableMapOf("/a.pdf" to ByteArray(3) { 1 }))
        val ivs = mutableMapOf<String, ByteArray>()

        val e = assertFailsWith<WebDropSourceReadException> {
            stageWebDropPayloads(
                fs, listOf(picked("/a.pdf", "A.pdf"), picked("/gone.pdf", "Gone.pdf")), key, ivs,
            )
        }

        assertEquals("Gone.pdf", e.fileName)
        assertEquals("/gone.pdf", e.filePath)
        // The failed file left no orphan IV behind; the successful one kept its slot.
        assertEquals(1, ivs.size)
    }

    @Test
    fun aResolvedContentUriCopyIsReapedAfterStaging() = runTest {
        val fs = StageFakeFileOps(
            files = mutableMapOf("/resolved-copy.pdf" to ByteArray(5) { 3 }),
            resolveMap = mapOf("content://picked" to "/resolved-copy.pdf"),
        )

        stageWebDropPayloads(fs, listOf(picked("content://picked", "C.pdf")), key, mutableMapOf())

        assertContentEquals(listOf("/resolved-copy.pdf"), fs.deleted, "the resolve copy must be reaped")
    }
}

private class StageFakeFileOps(
    val files: MutableMap<String, ByteArray>,
    private val resolveMap: Map<String, String> = emptyMap(),
) : FileOperationsProvider {
    val deleted = mutableListOf<String>()
    private var counter = 0

    override suspend fun resolveToFilePath(path: String): String = resolveMap[path] ?: path

    override suspend fun readFileBytes(path: String): ByteArray =
        files[path] ?: error("file not found: $path")

    override fun getFileSize(path: String): Long =
        (files[path] ?: error("file not found: $path")).size.toLong()

    override suspend fun createOutboxStagingPath(prefix: String, suffix: String): String =
        "/outbox/$prefix${counter++}$suffix"

    override suspend fun writeStream(path: String, data: Flow<ByteArray>) {
        val chunks = data.toList()
        files[path] = chunks.fold(ByteArray(0)) { acc, c -> acc + c }
    }

    override fun deleteTempFile(path: String): Boolean {
        deleted += path
        return files.remove(path) != null
    }

    override fun getCacheDirectory(): String = "/tmp"
    override fun openFileInput(path: String): InputProvider = throw NotImplementedError()
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String =
        throw NotImplementedError()
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String =
        throw NotImplementedError()
}
