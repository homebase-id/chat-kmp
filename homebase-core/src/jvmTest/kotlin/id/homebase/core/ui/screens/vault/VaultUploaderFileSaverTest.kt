@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault

import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class VaultUploaderFileSaverTest {
    private val deleted = mutableListOf<String>()
    private val uploads = mutableListOf<Triple<String, List<Pair<String, String>>, CoroutineScope>>()
    private val appScope = CoroutineScope(Job())

    private val fileOps: FileOperationsProvider = Proxy.newProxyInstance(
        FileOperationsProvider::class.java.classLoader,
        arrayOf(FileOperationsProvider::class.java),
    ) { _, method, args ->
        check(method.name == "deleteTempFile")
        deleted += args[0] as String
        true
    } as FileOperationsProvider

    private fun saver(activated: Boolean, result: Uuid?) = VaultUploaderFileSaver(
        isActivated = { activated },
        upload = { name, files, scope -> uploads += Triple(name, files, scope); result },
        fileOperationsProvider = fileOps,
        appScope = appScope,
    )

    @Test
    fun notActivatedReturnsFalseAndDeletesFile() = runTest {
        assertFalse(saver(activated = false, result = Uuid.random()).save("/tmp/a.jpg", "image/jpeg", "n"))
        assertEquals(listOf("/tmp/a.jpg"), deleted)
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun uploadsPathAndContentTypeAndReturnsTrue() = runTest {
        assertTrue(saver(activated = true, result = Uuid.random()).save("/tmp/a.jpg", "image/jpeg", "Chat image"))
        val (name, files, scope) = uploads.single()
        assertEquals("Chat image", name)
        assertEquals(listOf("/tmp/a.jpg" to "image/jpeg"), files)
        assertTrue(scope === appScope)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun nullUploadResultDeletesFile() = runTest {
        assertFalse(saver(activated = true, result = null).save("/tmp/a.jpg", "image/jpeg", "n"))
        assertEquals(listOf("/tmp/a.jpg"), deleted)
    }
}
