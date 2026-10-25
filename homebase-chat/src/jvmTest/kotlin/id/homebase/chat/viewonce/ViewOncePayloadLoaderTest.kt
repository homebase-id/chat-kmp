package id.homebase.chat.viewonce

import id.homebase.api.client.NotFoundException
import id.homebase.core.config.chatTargetDrive
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ViewOncePayloadLoaderTest {

    @Test
    fun theViewerPhotoComesFromTheNetworkAndNeverTouchesTheDiskCaches() = runTest {
        val server = ViewOnceFakeServer().start()
        val drive = chatTargetDrive.alias
        server.loader.begin(server.fileId)

        val first = server.loader.loadToTempFile(drive, server.fileId, VIEW_ONCE_PAYLOAD_KEY, server.keyHeader)
        val second = server.loader.loadToTempFile(drive, server.fileId, VIEW_ONCE_PAYLOAD_KEY, server.keyHeader)

        assertContentEquals(server.plainImage, File(first).readBytes())
        assertContentEquals(server.plainImage, File(second).readBytes())
        assertEquals(2, server.requests, "no read may be served from a cache")
        assertTrue(server.requestedPaths.all { it.endsWith("/payload/$VIEW_ONCE_PAYLOAD_KEY") })
        assertEquals(0L, server.cachedBytes())
    }

    @Test
    fun beginTakesAHoldThatEvictReleases() = runTest {
        val server = ViewOnceFakeServer().start()
        server.loader.begin(server.fileId)
        assertTrue(server.cached.isEphemeral(server.fileId), "video playback of the file must bypass the caches")

        server.loader.evict(chatTargetDrive.alias, server.fileId, VIEW_ONCE_PAYLOAD_KEY)

        assertTrue(!server.cached.isEphemeral(server.fileId), "evict must release the hold")
    }

    @Test
    fun aConsumedFileCanNeitherBeginNorLoadAgain() = runTest {
        val server = ViewOnceFakeServer().start()
        server.loader.markConsumed(server.fileId)

        assertFailsWith<ViewOnceAlreadyConsumedException> { server.loader.begin(server.fileId) }
        assertFailsWith<ViewOnceAlreadyConsumedException> {
            server.loader.loadToTempFile(chatTargetDrive.alias, server.fileId, VIEW_ONCE_PAYLOAD_KEY, server.keyHeader)
        }
        assertEquals(0, server.requests)
        assertTrue(!server.cached.isEphemeral(server.fileId))
    }

    @Test
    fun aMissingPhotoFailsAndLeavesNoTempFile() = runTest {
        val server = ViewOnceFakeServer().start()
        server.status = HttpStatusCode.NotFound

        assertFailsWith<NotFoundException> {
            server.loader.loadToTempFile(chatTargetDrive.alias, server.fileId, VIEW_ONCE_PAYLOAD_KEY, server.keyHeader)
        }
        // The delete runs on the loader's own IO scope, outside the test's virtual clock.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (server.tempFiles().isNotEmpty()) delay(10) } }
    }

    @Test
    fun evictingEndsTheBypassAndDropsTheDecodedImage() = runTest {
        val server = ViewOnceFakeServer().start()
        val drive = chatTargetDrive.alias
        server.loader.begin(server.fileId)

        server.loader.evict(drive, server.fileId, VIEW_ONCE_PAYLOAD_KEY)

        assertTrue(!server.cached.isEphemeral(server.fileId))
        assertEquals(listOf(drive to server.fileId), server.evictedImages)
    }
}
