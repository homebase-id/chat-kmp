package id.homebase.core.clipboard

import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.ByteArrayInputStream
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
}
