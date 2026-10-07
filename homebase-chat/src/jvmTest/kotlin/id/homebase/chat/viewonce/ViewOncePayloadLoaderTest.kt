package id.homebase.chat.viewonce

import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
        assertTrue(server.cached.isEphemeral(server.fileId), "video playback of the same file must also bypass the caches")
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
