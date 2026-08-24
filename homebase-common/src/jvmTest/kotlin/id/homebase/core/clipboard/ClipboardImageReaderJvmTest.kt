package id.homebase.core.clipboard

import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the Desktop clipboard reader prefers RAW image bytes (image/gif) over the
 * decoding imageFlavor path, so a pasted animated GIF keeps every frame instead of being
 * flattened to a single-frame PNG by ImageIO.
 */
class ClipboardImageReaderJvmTest {

    private val gifFlavor = DataFlavor("image/gif; class=java.io.InputStream")

    // GIF89a header + a trailing byte so the payload is recognizably non-PNG.
    private val gifBytes = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x2A, 0x00, 0x2A, 0x00,
    )

    private fun clipboardWith(flavor: DataFlavor, bytes: ByteArray): Clipboard {
        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(flavor)
            override fun isDataFlavorSupported(f: DataFlavor): Boolean = f == flavor
            override fun getTransferData(f: DataFlavor): Any {
                if (f != flavor) throw java.awt.datatransfer.UnsupportedFlavorException(f)
                return ByteArrayInputStream(bytes)
            }
        }
        return Clipboard("test").apply { setContents(transferable, null) }
    }

    @Test
    fun rawGifFlavorBytesAreReturnedUnmodified() {
        val clipboard = clipboardWith(gifFlavor, gifBytes)
        val result = readImageFromClipboard(clipboard)
        assertTrue(
            result != null && result.contentEquals(gifBytes),
            "Expected the original GIF bytes back, got ${result?.size} bytes",
        )
    }

    @Test
    fun emptyClipboardReturnsNull() {
        val empty = Clipboard("empty")
        assertNull(readImageFromClipboard(empty))
    }

    private fun clipboardWithFiles(files: List<File>): Clipboard {
        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> =
                arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(f: DataFlavor): Boolean =
                f == DataFlavor.javaFileListFlavor
            override fun getTransferData(f: DataFlavor): Any {
                if (f != DataFlavor.javaFileListFlavor) throw UnsupportedFlavorException(f)
                return files
            }
        }
        return Clipboard("files").apply { setContents(transferable, null) }
    }

    @Test
    fun copiedImageFileBytesAreReturnedUnmodified() {
        // An image copied from Finder/Explorer arrives as a file reference, not pixels.
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        val tmp = File.createTempFile("clip", ".PNG").apply { writeBytes(png) }
        try {
            val result = readImageFromClipboard(clipboardWithFiles(listOf(tmp)))
            assertTrue(
                result != null && result.contentEquals(png),
                "Expected the original file bytes back, got ${result?.size} bytes",
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun copiedNonImageFileReturnsNull() {
        val tmp = File.createTempFile("clip", ".txt").apply { writeText("not an image") }
        try {
            assertNull(readImageFromClipboard(clipboardWithFiles(listOf(tmp))))
        } finally {
            tmp.delete()
        }
    }
}
