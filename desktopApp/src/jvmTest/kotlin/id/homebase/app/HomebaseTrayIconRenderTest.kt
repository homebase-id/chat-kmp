package id.homebase.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Icon
import dev.nucleusframework.composenativetray.utils.ComposableIconUtils
import dev.nucleusframework.composenativetray.utils.IconRenderProperties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HomebaseTrayIconRenderTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    @Test
    fun rendersEveryTrayVariantThroughTheLibraryRenderer() {
        val variants = listOf(false to false, false to true, true to false, true to true).map { (dark, call) ->
            ComposableIconUtils.renderComposableToPngBytes(IconRenderProperties.forCurrentOperatingSystem()) {
                HomebaseTrayIcon(dark, call, "x")
            }
        }
        variants.forEach { assertContentEquals(png, it.copyOfRange(0, 4)) }
        assertEquals(4, variants.map { it.toList() }.distinct().size)
    }

    @Test
    fun rendersIcoForWindows() {
        val ico = ComposableIconUtils.renderComposableToIcoBytes(IconRenderProperties.forCurrentOperatingSystem()) {
            HomebaseTrayIcon(darkBar = true, callIndicator = true, contentDescription = "x")
        }
        assertContentEquals(byteArrayOf(0, 0, 1, 0), ico.copyOfRange(0, 4))
    }

    @Test
    fun rendersMenuItemIcon() {
        val bytes = ComposableIconUtils.renderComposableToPngBytes(IconRenderProperties.forMenuItem()) {
            Icon(Icons.Default.Update, null)
        }
        assertContentEquals(png, bytes.copyOfRange(0, 4))
        assertFalse(bytes.isEmpty())
    }
}
