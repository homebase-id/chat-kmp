@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import id.homebase.core.util.presentWhenMainWindowIsKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.UIKit.UIImageJPEGRepresentation
import platform.VisionKit.VNDocumentCameraScan
import platform.VisionKit.VNDocumentCameraViewController
import platform.VisionKit.VNDocumentCameraViewControllerDelegateProtocol
import platform.darwin.NSObject
import platform.posix.memcpy

private const val JPEG_QUALITY = 0.9

private class IosDocumentScanner(
    private val onScan: (VNDocumentCameraScan) -> Unit,
) : DocumentScanner {
    // VNDocumentCameraViewController.delegate is weak; without this the delegate is collected and the result is lost.
    private var delegate: VNDocumentCameraViewControllerDelegateProtocol? = null

    override fun launch(onNoPages: () -> Unit) {
        presentWhenMainWindowIsKey { presenter ->
            val scanner = VNDocumentCameraViewController()
            val finish = object : NSObject(), VNDocumentCameraViewControllerDelegateProtocol {
                override fun documentCameraViewController(
                    controller: VNDocumentCameraViewController,
                    didFinishWithScan: VNDocumentCameraScan,
                ) {
                    controller.dismissViewControllerAnimated(true) { if (delegate === this) delegate = null }
                    onScan(didFinishWithScan)
                }

                override fun documentCameraViewControllerDidCancel(controller: VNDocumentCameraViewController) {
                    controller.dismissViewControllerAnimated(true) { if (delegate === this) delegate = null }
                    onNoPages()
                }

                override fun documentCameraViewController(
                    controller: VNDocumentCameraViewController,
                    didFailWithError: NSError,
                ) {
                    controller.dismissViewControllerAnimated(true) { if (delegate === this) delegate = null }
                    onNoPages()
                }
            }
            delegate = finish
            scanner.delegate = finish
            presenter.presentViewController(scanner, animated = true, completion = null)
        }
    }
}

@Composable
actual fun rememberDocumentScanner(onPages: (List<ByteArray>) -> Unit): DocumentScanner? {
    val scope = rememberCoroutineScope()
    val currentOnPages by rememberUpdatedState(onPages)
    return remember(scope) {
        if (!VNDocumentCameraViewController.isSupported()) return@remember null
        IosDocumentScanner { scan ->
            scope.launch {
                val pages = withContext(Dispatchers.Default) {
                    (0 until scan.pageCount.toInt()).mapNotNull { index ->
                        UIImageJPEGRepresentation(scan.imageOfPageAtIndex(index.toULong()), JPEG_QUALITY)?.toByteArray()
                    }
                }
                if (pages.isNotEmpty()) currentOnPages(pages)
            }
        }
    }
}

private fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
