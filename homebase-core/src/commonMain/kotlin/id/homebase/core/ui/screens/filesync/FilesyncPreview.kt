package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.file.systemFileSystem
import id.homebase.chat.widget.video.LocalVideoPlayerSurface
import id.homebase.common.util.formatBytes
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.core.media.subsample.ZoomableSubSamplingImage
import id.homebase.core.pdf.PdfPageViewer
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import id.homebase.core.util.isWeb
import id.homebase.resources.MR
import id.homebase.resources.close
import id.homebase.resources.filesync_preview_failed
import id.homebase.resources.filesync_preview_truncated
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import org.jetbrains.compose.resources.stringResource

enum class FilesyncPreviewKind { Image, Video, Pdf, Text }

/** A downloaded file shown in the app: [path] is its copy in the cache, thrown away when the preview closes. */
data class FilesyncPreviewFile(val path: String, val name: String, val kind: FilesyncPreviewKind)

/** What a quick look shows [name] as, or null when every platform can't: the web has no PDF renderer. */
internal fun filesyncPreviewKind(name: String): FilesyncPreviewKind? {
    val type = detectContentTypeFromExtensionOrHint(name)
    return when {
        type in PREVIEW_IMAGES -> FilesyncPreviewKind.Image
        type in PREVIEW_VIDEOS -> FilesyncPreviewKind.Video
        type == "application/pdf" -> FilesyncPreviewKind.Pdf.takeUnless { isWeb() }
        type.startsWith("text/") || type == "application/json" -> FilesyncPreviewKind.Text
        else -> null
    }
}

// What every platform's decoder and player take; HEIC, WebM and the like fail on at least one.
private val PREVIEW_IMAGES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")
private val PREVIEW_VIDEOS = setOf("video/mp4", "video/x-m4v", "video/quicktime")

// One Text per line, lazily: laying out a whole file as one Text stalls the UI.
private const val TEXT_PREVIEW_BYTES = 256L shl 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilesyncPreview(file: FilesyncPreviewFile, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.close))
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                var failed by remember { mutableStateOf(false) }
                when {
                    failed -> Text(stringResource(MR.string.filesync_preview_failed), modifier = Modifier.padding(24.dp))
                    file.kind == FilesyncPreviewKind.Image -> ZoomableSubSamplingImage(
                        source = remember(file.path) { SubSamplingImageSource.LocalFile(file.path) },
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxSize(),
                        onError = { failed = true },
                    )
                    file.kind == FilesyncPreviewKind.Video -> LocalVideoPlayerSurface(file.path, Modifier.fillMaxSize())
                    file.kind == FilesyncPreviewKind.Pdf -> PdfPageViewer(file.path, modifier = Modifier.fillMaxSize())
                    else -> TextPreview(file.path) { failed = true }
                }
            }
        }
    }
}

private class TextPage(val lines: List<String>, val truncated: Boolean)

@Composable
private fun TextPreview(path: String, onFailed: () -> Unit) {
    val page by produceState<TextPage?>(null, path) {
        value = try {
            withContext(ioDispatcher) {
                systemFileSystem.source(path.toPath()).buffer().use { source ->
                    val more = source.request(TEXT_PREVIEW_BYTES + 1)
                    TextPage(source.readUtf8(minOf(TEXT_PREVIEW_BYTES, source.buffer.size)).lines(), truncated = more)
                }
            }
        } catch (e: Exception) {
            onFailed()
            null
        }
    }
    val shown = page ?: return CircularProgressIndicator()
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            if (shown.truncated) {
                item {
                    Text(
                        stringResource(MR.string.filesync_preview_truncated, formatBytes(TEXT_PREVIEW_BYTES)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            items(shown.lines) { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
