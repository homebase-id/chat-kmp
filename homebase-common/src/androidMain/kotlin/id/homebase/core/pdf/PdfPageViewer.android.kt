package id.homebase.core.pdf

import android.content.Context
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.pdf.viewer.fragment.PdfViewerFragment
import java.io.File

private class TapDetectingFrameLayout(
    ctx: Context,
    private val onSingleTap: (() -> Unit)?,
) : FrameLayout(ctx) {

    private val detector = onSingleTap?.let {
        GestureDetector(
            ctx,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    it()
                    return true
                }
            },
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount == 1) detector?.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}

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
            TapDetectingFrameLayout(ctx, onTap).apply {
                val container = FragmentContainerView(ctx).apply {
                    id = View.generateViewId()
                }
                addView(
                    container,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        update = { frameLayout ->
            val activity = context as? FragmentActivity ?: return@AndroidView
            val fm = activity.supportFragmentManager
            val container = frameLayout.getChildAt(0) as? FragmentContainerView ?: return@AndroidView
            val existing = fm.findFragmentByTag(fragmentTag) as? PdfViewerFragment
            if (existing == null) {
                val fragment = PdfViewerFragment()
                fm.beginTransaction()
                    .replace(container.id, fragment, fragmentTag)
                    .commitAllowingStateLoss()
                fm.executePendingTransactions()
                fragment.documentUri = uri
            } else if (existing.documentUri != uri) {
                existing.documentUri = uri
            }
        },
        onReset = { removePdfFragment(context, fragmentTag) },
        onRelease = { removePdfFragment(context, fragmentTag) },
        modifier = modifier,
    )
}

private fun removePdfFragment(context: Context, fragmentTag: String) {
    val activity = context as? FragmentActivity ?: return
    val fm = activity.supportFragmentManager
    val fragment = fm.findFragmentByTag(fragmentTag) ?: return
    fm.beginTransaction().remove(fragment).commitAllowingStateLoss()
}
