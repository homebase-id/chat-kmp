package id.homebase.core.clipboard

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

internal actual suspend fun writePngToClipboard(png: ByteArray) =
    writePngToClipboard(Toolkit.getDefaultToolkit().systemClipboard, png)

internal fun writePngToClipboard(clipboard: Clipboard, png: ByteArray) {
    val image = ImageIO.read(ByteArrayInputStream(png)) ?: error("undecodable PNG")
    clipboard.setContents(ImageSelection(image), null)
}

private class ImageSelection(private val image: Image) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
    override fun getTransferData(flavor: DataFlavor): Any =
        if (isDataFlavorSupported(flavor)) image else throw UnsupportedFlavorException(flavor)
}
