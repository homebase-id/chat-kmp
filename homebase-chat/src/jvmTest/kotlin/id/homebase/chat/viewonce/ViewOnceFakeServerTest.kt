package id.homebase.chat.viewonce

import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewOnceFakeServerTest {

    @Test
    fun concurrentRequestsAreAllCounted() = runBlocking(Dispatchers.Default) {
        val server = ViewOnceFakeServer().start()
        val viewer = server.viewer()
        val n = 200
        List(n) {
            async { server.loader.loadToTempFile(chatTargetDrive.alias, viewer.fileId, viewer.payload.key, viewer.keyHeader) }
        }.awaitAll()
        assertEquals(n, server.requests)
        assertEquals(n, server.requestedPaths.size)
    }
}
