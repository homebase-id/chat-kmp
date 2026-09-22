package id.homebase.chat.widget

import kotlin.test.Test
import kotlin.test.assertEquals

private fun file(mimeType: String) = DropItem("file", mimeType, isDirectory = false)

private fun folder() = DropItem("file", "", isDirectory = true)

private fun text() = DropItem("string", "text/plain", isDirectory = false)

class DropPreviewTest {

    @Test
    fun allImagesReportsEveryItemAsAnImage() {
        assertEquals(
            FileDropPreview.Attachable(total = 3, images = 3),
            dropPreviewOf(listOf(file("image/png"), file("image/jpeg"), file("image/webp"))),
        )
    }

    @Test
    fun mixedCountsOnlyTheImages() {
        assertEquals(
            FileDropPreview.Attachable(total = 3, images = 1),
            dropPreviewOf(listOf(file("image/png"), file("application/pdf"), file("text/plain"))),
        )
    }

    @Test
    fun noItemsIsRejected() {
        assertEquals(FileDropPreview.Rejected, dropPreviewOf(emptyList()))
    }

    @Test
    fun draggedTextIsRejected() {
        assertEquals(FileDropPreview.Rejected, dropPreviewOf(listOf(text())))
    }

    @Test
    fun foldersAreRejected() {
        assertEquals(FileDropPreview.Rejected, dropPreviewOf(listOf(folder(), folder())))
    }

    @Test
    fun aFolderDoesNotCountTowardsTheFilesBesideIt() {
        assertEquals(
            FileDropPreview.Attachable(total = 1, images = 0),
            dropPreviewOf(listOf(folder(), file("application/zip"), text())),
        )
    }

    @Test
    fun anUntypedFileStillCounts() {
        assertEquals(
            FileDropPreview.Attachable(total = 1, images = 0),
            dropPreviewOf(listOf(file(""))),
        )
    }
}
