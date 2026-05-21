package id.homebase.core.pdf

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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun PdfPageViewer(
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
