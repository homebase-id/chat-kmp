package id.homebase.core.share

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareClaimTest {
    private val storage = ShareCacheStorage()
    private val processor = ShareContentProcessor(storage)

    @AfterTest
    fun tearDown() = storage.clearSharedContent()

    private fun stage(shareId: String, name: String): SharedContentDescriptor {
        val relative = "$shareId/0/$name"
        File(processor.resolveFilePath(relative)).apply { parentFile.mkdirs() }.writeText("x")
        return SharedContentDescriptor(
            contentType = SharedContentType.FILE,
            fileNames = listOf(relative),
            mimeTypes = listOf("application/pdf"),
            targetConversationIds = listOf("c1"),
        ).also { processor.writeContent(it) }
    }

    @Test
    fun aSecondClaimOfTheSameShareFindsNothing() = runBlocking<Unit> {
        stage("a", "report.pdf")

        assertNotNull(processor.claimPendingContent())
        assertNull(processor.claimPendingContent())
    }

    @Test
    fun cleanupOfOneShareLeavesAnotherSharesFiles() = runBlocking<Unit> {
        val first = stage("a", "report.pdf")
        val claimed = processor.claimPendingContent()
        val second = stage("b", "report.pdf")

        processor.cleanup(claimed!!)

        assertEquals(first, claimed)
        assertTrue(File(processor.resolveFilePath(second.fileNames.single())).exists())
        assertTrue(!File(processor.resolveFilePath(first.fileNames.single())).exists())
        assertNotNull(processor.claimPendingContent())
    }

    @Test
    fun aRefusedDescriptorStaysClaimable() = runBlocking<Unit> {
        stage("a", "report.pdf")

        assertNull(processor.claimPendingContent { false })
        assertNotNull(processor.claimPendingContent())
    }
}
