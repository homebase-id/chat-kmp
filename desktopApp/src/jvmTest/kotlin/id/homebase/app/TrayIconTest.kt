package id.homebase.app

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TrayIconTest {

    // Kotlin-internal in ComposeNativeTray, but it is the exact loader Tray(iconPath, windowsIconPath) runs our paths through.
    private fun loadLikeTray(path: String): ByteArray =
        (Class.forName("com.kdroid.composetray.utils.JarResourceExtractorKt")
            .getMethod("extractToTempIfDifferent", String::class.java)
            .invoke(null, path) as File).readBytes()

    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun `every tray icon variant renders to a PNG and a PNG-wrapping ICO the tray can load`() {
        val dir = Files.createTempDirectory("tray icon+100%").toFile()
        val rendered = mutableSetOf<List<Byte>>()
        try {
            for (darkBar in listOf(true, false)) for (callIndicator in listOf(true, false)) {
                val files = writeTrayIconFiles(darkBar, callIndicator, dir)

                val png = loadLikeTray(files.pngPath)
                assertContentEquals(pngSignature, png.copyOf(8))
                rendered += png.toList()

                val ico = ByteBuffer.wrap(loadLikeTray(files.icoPath))
                    .order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(1, ico.getShort(2).toInt(), "ICO type")
                assertEquals(1, ico.getShort(4).toInt(), "ICO image count")
                assertEquals(png.size, ico.getInt(14))
                val offset = ico.getInt(18)
                assertContentEquals(png, ico.array().copyOfRange(offset, offset + png.size))
            }
            assertEquals(4, rendered.size, "tint and call dot must change the pixels")
        } finally {
            dir.deleteRecursively()
        }
    }
}
