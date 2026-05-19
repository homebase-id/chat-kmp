package id.homebase.core.pdf

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.pdf.viewer.fragment.PdfViewerFragment
import java.io.File

@Composable
actual fun PdfPageViewer(
    filePath: String,
    onTap: (() -> Unit)?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val uri = remember(filePath) { Uri.fromFile(File(filePath)) }
    val fragmentTag = remember(filePath) { "pdf_viewer_${filePath.hashCode()}_${filePath.length}" }

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
