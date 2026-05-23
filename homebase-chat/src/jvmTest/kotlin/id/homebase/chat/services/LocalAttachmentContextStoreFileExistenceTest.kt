package id.homebase.chat.services

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class LocalAttachmentContextStoreFileExistenceTest {

    private val store = LocalAttachmentContextStore(EventBus(), CoroutineScope(SupervisorJob()))
    private val messageId = Uuid.random()
    private val payloadKey = "vlt_pg_00"

    private fun createTempFile(): File =
        File.createTempFile("store_test_", ".jpg").also { it.deleteOnExit() }

    private fun image(path: String) =
        LocalAttachmentContext.Image(localFilePath = path, aspectRatio = 1f)

    // region get()

    @Test
    fun get_returnsContext_whenFileExists() {
        val file = createTempFile()
        store.put(messageId, payloadKey, image(file.absolutePath))

        val result = store.get(messageId, payloadKey)
        assertNotNull(result)
        assertEquals(file.absolutePath, result.localFilePath)
    }

    @Test
    fun get_returnsNull_whenFileDeleted() {
        val file = createTempFile()
        store.put(messageId, payloadKey, image(file.absolutePath))
        file.delete()

        assertNull(store.get(messageId, payloadKey))
    }

    @Test
    fun get_evictsStaleEntry_whenFileDeleted() {
        val file = createTempFile()
        store.put(messageId, payloadKey, image(file.absolutePath))
        file.delete()

        store.get(messageId, payloadKey)
        assertFalse(store.hasAny(messageId))
    }

    @Test
    fun get_returnsNull_whenPathNeverExisted() {
        store.put(messageId, payloadKey, image("/nonexistent/path/image.jpg"))

        assertNull(store.get(messageId, payloadKey))
    }

    // endregion

    // region getAll()

    @Test
    fun getAll_filtersOutDeletedFiles() {
        val fileA = createTempFile()
        val fileB = createTempFile()
        store.put(messageId, "pg_00", image(fileA.absolutePath))
        store.put(messageId, "pg_01", image(fileB.absolutePath))
        fileA.delete()

        val result = store.getAll(messageId)
        assertEquals(1, result.size)
        assertEquals(fileB.absolutePath, result["pg_01"]?.localFilePath)
    }

    @Test
    fun getAll_returnsEmpty_whenAllFilesDeleted() {
        val file = createTempFile()
        store.put(messageId, payloadKey, image(file.absolutePath))
        file.delete()

        assertTrue(store.getAll(messageId).isEmpty())
    }

    // endregion

    // region hasAny()

    @Test
    fun hasAny_returnsFalse_whenAllFilesDeleted() {
        val file = createTempFile()
        store.put(messageId, payloadKey, image(file.absolutePath))
        file.delete()

        assertFalse(store.hasAny(messageId))
    }

    @Test
    fun hasAny_returnsTrue_whenAtLeastOneFileExists() {
        val fileA = createTempFile()
        val fileB = createTempFile()
        store.put(messageId, "pg_00", image(fileA.absolutePath))
        store.put(messageId, "pg_01", image(fileB.absolutePath))
        fileA.delete()

        assertTrue(store.hasAny(messageId))
    }

    // endregion

    // region observe()

    @Test
    fun observe_emitsNull_whenFileDeleted() = runTest {
        val file = createTempFile()
        store.put(messageId, payloadKey, image(file.absolutePath))
        file.delete()

        val result = store.observe(messageId, payloadKey).first()
        assertNull(result)
    }

    @Test
    fun observe_emitsContext_whenFileExists() = runTest {
        val file = createTempFile()
        store.put(messageId, payloadKey, image(file.absolutePath))

        val result = store.observe(messageId, payloadKey).first()
        assertNotNull(result)
        assertEquals(file.absolutePath, result.localFilePath)
    }

    // endregion

    // region observeAll()

    @Test
    fun observeAll_excludesDeletedFiles() = runTest {
        val fileA = createTempFile()
        val fileB = createTempFile()
        store.put(messageId, "pg_00", image(fileA.absolutePath))
        store.put(messageId, "pg_01", image(fileB.absolutePath))
        fileA.delete()

        val result = store.observeAll(messageId).first()
        assertEquals(1, result.size)
        assertNull(result["pg_00"])
        assertNotNull(result["pg_01"])
    }

    // endregion

    // region Non-filesystem paths (content URIs) are assumed valid

    @Test
    fun get_returnsContext_forContentUri() {
        val uri = "content://media/external/images/media/12345"
        store.put(messageId, payloadKey, image(uri))

        val result = store.get(messageId, payloadKey)
        assertNotNull(result)
        assertEquals(uri, result.localFilePath)
    }

    @Test
    fun observe_emitsContext_forContentUri() = runTest {
        val uri = "content://com.android.providers.media/document/image%3A42"
        store.put(messageId, payloadKey, image(uri))

        val result = store.observe(messageId, payloadKey).first()
        assertNotNull(result)
    }

    @Test
    fun hasAny_returnsTrue_forContentUri() {
        store.put(messageId, payloadKey, image("content://media/external/images/media/1"))
        assertTrue(store.hasAny(messageId))
    }

    // endregion

    // region Eviction does not break valid entries

    @Test
    fun eviction_preservesValidEntriesForSameMessage() {
        val fileA = createTempFile()
        val fileB = createTempFile()
        store.put(messageId, "pg_00", image(fileA.absolutePath))
        store.put(messageId, "pg_01", image(fileB.absolutePath))
        fileA.delete()

        // Getting the stale entry should evict only that key
        assertNull(store.get(messageId, "pg_00"))
        assertNotNull(store.get(messageId, "pg_01"))
    }

    @Test
    fun eviction_doesNotAffectOtherMessages() {
        val otherMessageId = Uuid.random()
        val staleFile = createTempFile()
        val validFile = createTempFile()
        store.put(messageId, payloadKey, image(staleFile.absolutePath))
        store.put(otherMessageId, payloadKey, image(validFile.absolutePath))
        staleFile.delete()

        assertNull(store.get(messageId, payloadKey))
        assertNotNull(store.get(otherMessageId, payloadKey))
    }

    // endregion

    // region Logout (BackendEvent.SessionEnded) clears all cached previews

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sessionEnded_clearsAllContexts() = runTest(UnconfinedTestDispatcher()) {
        val eventBus = EventBus()
        // Unconfined dispatcher so the store's init eventBus collector subscribes
        // eagerly before we emit — exercising the real logout wiring, not just reset().
        val store = LocalAttachmentContextStore(eventBus, backgroundScope)

        val otherMessageId = Uuid.random()
        store.put(messageId, "pg_00", image(createTempFile().absolutePath))
        store.put(messageId, "pg_01", image(createTempFile().absolutePath))
        store.put(otherMessageId, "pg_00", image(createTempFile().absolutePath))
        assertTrue(store.hasAny(messageId))
        assertTrue(store.hasAny(otherMessageId))

        eventBus.emit(BackendEvent.SessionEnded)

        assertFalse(store.hasAny(messageId), "logout must drop the previous identity's previews")
        assertFalse(store.hasAny(otherMessageId))
        assertTrue(store.getAll(messageId).isEmpty())
    }

    // endregion
}
