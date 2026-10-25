package id.homebase.core.ui.screens.webdrop

import id.homebase.core.ui.screens.webdrop.model.PickedDropFile
import id.homebase.core.webdrop.WebDropManifestEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class WebDropViewOnlyHeicTest {

    private val key = ByteArray(16) { it.toByte() }
    private val heic = ByteArray(40) { 7 }
    private val jpeg = ByteArray(11) { 9 }
    private val picked = PickedDropFile("/IMG_1.HEIC", "IMG_1.HEIC", "image/heic", 0)

    private suspend fun stage(viewOnly: Boolean): List<WebDropManifestEntry> =
        stageWebDropPayloads(
            StageFakeFileOps(mutableMapOf("/IMG_1.HEIC" to heic)),
            listOf(picked), key, mutableMapOf(), viewOnly, heicToJpeg = { jpeg },
        ).first

    @Test
    fun viewOnlyTranscodesHeicToJpeg() = runTest {
        val entry = stage(viewOnly = true).single()
        assertEquals("IMG_1.jpg", entry.name)
        assertEquals("image/jpeg", entry.contentType)
        assertEquals(jpeg.size.toLong(), entry.size)
    }

    @Test
    fun normalDropKeepsTheOriginalHeic() = runTest {
        val entry = stage(viewOnly = false).single()
        assertEquals("IMG_1.HEIC", entry.name)
        assertEquals("image/heic", entry.contentType)
        assertEquals(heic.size.toLong(), entry.size)
    }

    @Test
    fun aFailedTranscodeFallsBackToTheOriginal() = runTest {
        val entry = stageWebDropPayloads(
            StageFakeFileOps(mutableMapOf("/IMG_1.HEIC" to heic)),
            listOf(picked), key, mutableMapOf(), viewOnly = true, heicToJpeg = { null },
        ).first.single()
        assertEquals("image/heic", entry.contentType)
        assertFalse(entry.name.endsWith(".jpg"))
    }
}
