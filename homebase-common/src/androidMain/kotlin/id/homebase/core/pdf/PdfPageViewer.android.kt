package id.homebase.core.pdf

import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.pdf.viewer.fragment.PdfViewerFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun isPdfViewerAvailable(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13

@Composable
actual fun PdfPageViewer(
    filePath: String,
    onTap: (() -> Unit)?,
    modifier: Modifier,
) {
    if (isPdfViewerAvailable()) {
        NativePdfViewer(filePath = filePath, modifier = modifier)
    } else {
        BitmapPdfViewerFallback(filePath = filePath, onTap = onTap, modifier = modifier)
    }
}

@Composable
private fun NativePdfViewer(filePath: String, modifier: Modifier) {
    val context = LocalContext.current
    val uri = remember(filePath) { Uri.fromFile(File(filePath)) }
    val fragmentTag = remember(filePath) { "pdf_viewer_${filePath.hashCode()}" }

    AndroidView(
        factory = { ctx ->
            FragmentContainerView(ctx).apply {
                id = android.view.View.generateViewId()
            }
        },
        update = { containerView ->
            val activity = context as? FragmentActivity ?: return@AndroidView
            val fm = activity.supportFragmentManager
            val existing = fm.findFragmentByTag(fragmentTag) as? PdfViewerFragment
            if (existing == null) {
                val fragment = PdfViewerFragment()
                fm.beginTransaction()
                    .replace(containerView.id, fragment, fragmentTag)
                    .commitAllowingStateLoss()
                fm.executePendingTransactions()
                fragment.documentUri = uri
            } else if (existing.documentUri != uri) {
                existing.documentUri = uri
            }
        },
        onReset = { containerView ->
            val activity = context as? FragmentActivity
            if (activity != null) {
                val fm = activity.supportFragmentManager
                val fragment = fm.findFragmentByTag(fragmentTag)
                if (fragment != null) {
                    fm.beginTransaction().remove(fragment).commitAllowingStateLoss()
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun BitmapPdfViewerFallback(
    filePath: String,
    onTap: (() -> Unit)?,
    modifier: Modifier,
) {
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }

    LaunchedEffect(filePath) {
        renderer?.close()
        renderer = null
        val r = PdfRenderer()
        try {
            withContext(Dispatchers.Default) { r.open(filePath) }
            renderer = r
        } catch (e: CancellationException) {
            r.close()
            throw e
        } catch (_: Exception) {
            r.close()
        }
    }

    DisposableEffect(Unit) {
        onDispose { renderer?.close() }
    }

    val r = renderer
    if (r != null) {
        BitmapPdfPageViewer(renderer = r, onTap = onTap, modifier = modifier)
    } else {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
