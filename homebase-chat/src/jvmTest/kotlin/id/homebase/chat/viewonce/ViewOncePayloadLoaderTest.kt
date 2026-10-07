package id.homebase.chat.viewonce

import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ViewOncePayloadLoaderTest {

    @Test
    fun theViewerBytesComeFromTheNetworkAndNeverTouchTheDiskCaches() = runTest {
        val server = ViewOnceFakeServer().start()
        val drive = chatTargetDrive.alias

        val first = server.loader.loadBytes(drive, server.fileId, VIEW_ONCE_PAYLOAD_KEY, server.keyHeader)
        val second = server.loader.loadBytes(drive, server.fileId, VIEW_ONCE_PAYLOAD_KEY, server.keyHeader)

        assertContentEquals(server.plainImage, first)
        assertContentEquals(server.plainImage, second)
        assertEquals(2, server.requests, "no read may be served from a cache")
        assertTrue(server.requestedPaths.all { it.endsWith("/payload/$VIEW_ONCE_PAYLOAD_KEY") })
        assertEquals(0L, server.cachedBytes())
    }

    @Test
    fun beginTakesAHoldThatEvictReleases() = runTest {
        val server = ViewOnceFakeServer().start()
        server.loader.begin(server.fileId)
        assertTrue(server.cached.isEphemeral(server.fileId), "video playback of the file must bypass the caches")
    }

    @Test
    fun aConsumedFileCanNeitherBeginNorLoadAgain() = runTest {
        val server = ViewOnceFakeServer().start()
        server.loader.markConsumed(server.fileId)

        assertFailsWith<ViewOnceAlreadyConsumedException> { server.loader.begin(server.fileId) }
        assertFailsWith<ViewOnceAlreadyConsumedException> {
            server.loader.loadBytes(chatTargetDrive.alias, server.fileId, VIEW_ONCE_PAYLOAD_KEY, server.keyHeader)
        }
        assertEquals(0, server.requests)
        assertTrue(!server.cached.isEphemeral(server.fileId))
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
