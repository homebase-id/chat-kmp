package id.homebase.core.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import id.homebase.core.util.boundedFirstVisibleItemIndex
import id.homebase.resources.MR
import id.homebase.resources.vault_gallery_page_counter
import id.homebase.resources.vault_pdf_page_content_description
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
expect fun PdfPageViewer(
    filePath: String,
    onTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
)

// Bitmap-based viewer used by Android, JVM, and Web actuals
private const val PAGE_CACHE_WINDOW = 5

@Composable
internal fun BitmapPdfPageViewer(
    renderer: PdfRenderer,
    onTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val pageCount = renderer.pageCount
    val renderedPages = remember { mutableStateMapOf<Int, ImageBitmap>() }

    val currentPage by remember {
        derivedStateOf {
            val bounded = listState.boundedFirstVisibleItemIndex(pageCount)
            (bounded ?: 0) + 1
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { listState.boundedFirstVisibleItemIndex(pageCount) }
            .collect { visibleIndex ->
                val center = visibleIndex ?: return@collect
                val keep = (center - PAGE_CACHE_WINDOW)..(center + PAGE_CACHE_WINDOW)
                val toEvict = renderedPages.keys.filter { it !in keep }
                toEvict.forEach { renderedPages.remove(it) }
            }
    }

    val tapModifier = if (onTap != null) {
        Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
    } else {
        Modifier
    }

    Box(modifier = modifier.fillMaxSize().then(tapModifier)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(pageCount) { index ->
                BitmapPdfPage(
                    renderer = renderer,
                    pageIndex = index,
                    pageCount = pageCount,
                    cachedBitmap = renderedPages[index],
                    onRendered = { renderedPages[index] = it },
                    modifier = Modifier.fillParentMaxHeight(),
                )
            }
        }

        if (pageCount > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(MR.string.vault_gallery_page_counter, currentPage, pageCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}

@Composable
private fun BitmapPdfPage(
    renderer: PdfRenderer,
    pageIndex: Int,
    pageCount: Int,
    cachedBitmap: ImageBitmap?,
    onRendered: (ImageBitmap) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val pixelWidth = with(density) { maxWidth.roundToPx() }
        val pixelHeight = with(density) { maxHeight.roundToPx() }

        LaunchedEffect(pageIndex, pixelWidth, pixelHeight) {
            if (cachedBitmap == null && pixelWidth > 0 && pixelHeight > 0) {
                val bitmap = withContext(Dispatchers.Default) {
                    renderer.renderPage(pageIndex, pixelWidth, pixelHeight)
                }
                onRendered(bitmap)
            }
        }

        val bitmap = cachedBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(
                    MR.string.vault_pdf_page_content_description,
                    pageIndex + 1,
                    pageCount,
                ),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
