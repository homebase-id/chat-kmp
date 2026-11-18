package id.homebase.chat.widget.preview

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.file.WEB_DOWNLOAD_LIMIT_BYTES
import id.homebase.api.file.systemFileSystem
import id.homebase.chat.widget.ChatMarkdown
import id.homebase.chat.widget.video.LocalVideoPlayerSurface
import id.homebase.common.util.formatBytes
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.core.media.subsample.ZoomableSubSamplingImage
import id.homebase.core.pdf.PdfPageViewer
import id.homebase.core.pdf.pdfPageViewerAvailable
import id.homebase.api.file.detectContentTypeFromExtensionOrHint
import id.homebase.core.util.isWeb
import id.homebase.api.util.trimToUtf8Boundary
import id.homebase.resources.MR
import id.homebase.resources.close
import id.homebase.resources.file_preview_docx_simplified
import id.homebase.resources.file_preview_failed
import id.homebase.resources.file_preview_markdown_formatted
import id.homebase.resources.file_preview_markdown_raw
import id.homebase.resources.file_preview_truncated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import org.jetbrains.compose.resources.stringResource

enum class FilePreviewKind { Image, Video, Pdf, Text, Markdown, Docx }

// Above this a video is not previewed: it would be decrypted whole before it plays, and the web holds it in memory about three times.
const val VIDEO_PREVIEW_LIMIT_BYTES: Long = 256L shl 20

// One Text per line, lazily: laying out a whole file as one Text stalls the UI.
internal const val TEXT_PREVIEW_BYTES = 256L shl 10
internal const val TEXT_PREVIEW_MAX_LINE = 2_000
internal const val MARKDOWN_BLOCK_MAX = 4_000

// What every platform's decoder and player take; HEIC, WebM and the like fail on at least one.
private val PREVIEW_IMAGES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")
private val PREVIEW_VIDEOS = setOf("video/mp4", "video/x-m4v", "video/quicktime")
private val PREVIEW_TEXT_APPLICATION = setOf(
    "application/json", "application/xml", "application/x-yaml", "application/javascript", "application/x-sh",
)
// A contact, not a document: its chip keeps adding the contact. RTF is markup; iOS names it text/rtf, Android application/rtf.
private val NOT_TEXT = setOf("text/vcard", "text/x-vcard", "text/calendar", "text/rtf")
private val MARKDOWN_TYPES = setOf("text/markdown", "text/x-markdown")
private const val DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
private val MONOSPACE_TYPES = setOf("text/csv", "application/json")

private fun resolvedType(contentType: String?, name: String): String {
    val declared = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
    return declared ?: detectContentTypeFromExtensionOrHint(name)
}

/** CSV and JSON read better in a fixed-width face; the type wins over the extension as in [filePreviewKind]. */
fun isMonospacePreview(name: String, contentType: String?): Boolean = resolvedType(contentType, name) in MONOSPACE_TYPES

/** What a preview shows a file as, or null when it can't: [contentType] wins over [name]'s extension; [sizeBytes] null means unknown. */
fun filePreviewKind(contentType: String?, name: String, sizeBytes: Long?): FilePreviewKind? =
    filePreviewKind(contentType, name, sizeBytes, web = isWeb(), pdfAvailable = pdfPageViewerAvailable)

internal fun filePreviewKind(
    contentType: String?,
    name: String,
    sizeBytes: Long?,
    web: Boolean,
    pdfAvailable: Boolean,
    docxAvailable: Boolean = docxPreviewAvailable,
): FilePreviewKind? {
    val type = resolvedType(contentType, name)
    val kind = when {
        type in PREVIEW_IMAGES -> FilePreviewKind.Image
        type in PREVIEW_VIDEOS -> FilePreviewKind.Video
        type == "application/pdf" -> FilePreviewKind.Pdf.takeIf { pdfAvailable }
        type == DOCX_TYPE -> FilePreviewKind.Docx.takeIf { docxAvailable }
        type in NOT_TEXT -> null
        type in MARKDOWN_TYPES -> FilePreviewKind.Markdown
        type.startsWith("text/") || type in PREVIEW_TEXT_APPLICATION -> FilePreviewKind.Text
        else -> null
    } ?: return null
    if (web && sizeBytes != null && sizeBytes > WEB_DOWNLOAD_LIMIT_BYTES) return null
    if (kind == FilePreviewKind.Video && (sizeBytes == null || sizeBytes > VIDEO_PREVIEW_LIMIT_BYTES)) return null
    return kind
}

/** [lines] with any line over [maxLength] chars cut into pieces, never between the halves of a surrogate pair. */
internal fun splitLongLines(lines: List<String>, maxLength: Int = TEXT_PREVIEW_MAX_LINE): List<String> {
    if (lines.all { it.length <= maxLength }) return lines
    val out = ArrayList<String>(lines.size)
    for (line in lines) {
        var start = 0
        while (line.length - start > maxLength) {
            var end = start + maxLength
            if (line[end - 1].isHighSurrogate()) end--
            out += line.substring(start, end)
            start = end
        }
        out += line.substring(start)
    }
    return out
}

/** [text] cut into blocks at blank lines, so each is laid out on its own; a blank line inside a code fence does not cut it, and a block over [maxLength] chars is cut too (a fence is closed and reopened across the cut). */
internal fun splitMarkdownBlocks(text: String, maxLength: Int = MARKDOWN_BLOCK_MAX): List<String> {
    val blocks = ArrayList<String>()
    val current = StringBuilder()
    var fence: String? = null
    fun flush() {
        if (current.isNotBlank()) blocks += current.toString().trimEnd('\n')
        current.clear()
    }
    for (line in splitLongLines(text.replace("\r\n", "\n").replace('\r', '\n').split('\n'), maxLength)) {
        val trimmed = line.trimStart()
        val marker = if (trimmed.startsWith("```")) "```" else if (trimmed.startsWith("~~~")) "~~~" else null
        if (marker != null) fence = if (fence == null) marker else null
        if (line.isBlank() && fence == null) {
            flush()
            continue
        }
        if (marker == null && current.isNotEmpty() && current.length + line.length + 1 > maxLength) {
            val open = fence
            if (open != null) current.append(open).append('\n')
            flush()
            if (open != null) current.append(open).append('\n')
        }
        current.append(line).append('\n')
    }
    flush()
    return blocks
}

/** The preview itself, with no chrome around it; [path] is a local file of [kind]. */
@Composable
fun FilePreviewContent(
    path: String,
    name: String,
    kind: FilePreviewKind,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    onFailed: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    contentType: String? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (kind) {
            FilePreviewKind.Image -> ZoomableSubSamplingImage(
                source = remember(path) { SubSamplingImageSource.LocalFile(path) },
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                onTap = onTap,
                onError = onFailed,
            )
            FilePreviewKind.Video -> LocalVideoPlayerSurface(path, Modifier.fillMaxSize())
            FilePreviewKind.Pdf -> PdfPageViewer(path, onTap = onTap, onFailed = onFailed, modifier = Modifier.fillMaxSize().padding(contentPadding))
            FilePreviewKind.Text -> TextPreview(path, isMonospacePreview(name, contentType), onTap, onFailed, contentPadding)
            FilePreviewKind.Markdown -> MarkdownPreview(path, onTap, onFailed, contentPadding)
            FilePreviewKind.Docx -> DocxPreview(path, onTap, onFailed, contentPadding)
        }
    }
}

private class TextPage(val lines: List<String>, val truncated: Boolean, val blocks: List<String>)

private suspend fun loadTextPage(path: String, markdown: Boolean): TextPage = withContext(ioDispatcher) {
    systemFileSystem.source(path.toPath()).buffer().use { source ->
        val more = source.request(TEXT_PREVIEW_BYTES + 1)
        val bytes = source.readByteArray(minOf(TEXT_PREVIEW_BYTES, source.buffer.size))
        val text = (if (more) bytes.trimToUtf8Boundary() else bytes).decodeToString()
        TextPage(splitLongLines(text.lines()), truncated = more, blocks = if (markdown) splitMarkdownBlocks(text) else emptyList())
    }
}

@Composable
private fun rememberTextPage(path: String, markdown: Boolean, onFailed: () -> Unit): TextPage? =
    produceState<TextPage?>(null, path, markdown) {
        value = try {
            loadTextPage(path, markdown)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailed()
            null
        }
    }.value

private suspend fun loadDocxPage(path: String): TextPage = withContext(ioDispatcher) {
    val bytes = readDocxDocumentXml(path)
    val truncated = bytes.size > DOCX_XML_LIMIT_BYTES
    val xml = (if (truncated) bytes.copyOf(DOCX_XML_LIMIT_BYTES.toInt()).trimToUtf8Boundary() else bytes).decodeToString()
    TextPage(splitLongLines(docxParagraphs(xml)), truncated, emptyList())
}

@Composable
private fun DocxPreview(path: String, onTap: (() -> Unit)?, onFailed: () -> Unit, contentPadding: PaddingValues) {
    val page = produceState<TextPage?>(null, path) {
        value = try {
            loadDocxPage(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailed()
            null
        }
    }.value ?: return CircularProgressIndicator()
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Text(
            stringResource(MR.string.file_preview_docx_simplified),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        TextLines(page, monospace = false, onTap, PaddingValues(bottom = contentPadding.calculateBottomPadding()))
    }
}

@Composable
private fun TextPreview(path: String, monospace: Boolean, onTap: (() -> Unit)?, onFailed: () -> Unit, contentPadding: PaddingValues) {
    val shown = rememberTextPage(path, markdown = false, onFailed) ?: return CircularProgressIndicator()
    TextLines(shown, monospace, onTap, contentPadding)
}

@Composable
private fun TextLines(page: TextPage, monospace: Boolean, onTap: (() -> Unit)?, contentPadding: PaddingValues) {
    val tap = if (onTap == null) Modifier else Modifier.pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) }
    val style = if (monospace) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium
    SelectionContainer(tap) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = textPadding(contentPadding)) {
            if (page.truncated) item { TruncatedNote() }
            items(page.lines) { Text(it, style = style) }
        }
    }
}

@Composable
private fun MarkdownPreview(path: String, onTap: (() -> Unit)?, onFailed: () -> Unit, contentPadding: PaddingValues) {
    var raw by remember(path) { mutableStateOf(false) }
    val page = rememberTextPage(path, markdown = true, onFailed) ?: return CircularProgressIndicator()
    val tap = if (onTap == null) Modifier else Modifier.pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) }
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { raw = !raw }) {
                Text(stringResource(if (raw) MR.string.file_preview_markdown_formatted else MR.string.file_preview_markdown_raw))
            }
        }
        if (raw) {
            TextLines(page, monospace = true, onTap, PaddingValues(bottom = contentPadding.calculateBottomPadding()))
        } else {
            SelectionContainer(tap) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = textPadding(PaddingValues(bottom = contentPadding.calculateBottomPadding()))) {
                    if (page.truncated) item { TruncatedNote() }
                    items(page.blocks) { ChatMarkdown(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
    }
}

private fun textPadding(outer: PaddingValues) = PaddingValues(
    start = 16.dp,
    end = 16.dp,
    top = 16.dp + outer.calculateTopPadding(),
    bottom = 16.dp + outer.calculateBottomPadding(),
)

@Composable
private fun TruncatedNote() {
    Text(
        stringResource(MR.string.file_preview_truncated, formatBytes(TEXT_PREVIEW_BYTES)),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
internal fun PreviewFailed() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(MR.string.file_preview_failed), style = MaterialTheme.typography.bodyLarge)
    }
}

/** A full-screen preview of the local file at [path]; [onGone] gets the path once the viewer has let go of it, to delete the copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewDialog(
    path: String,
    name: String,
    kind: FilePreviewKind,
    onClose: () -> Unit,
    onGone: (path: String) -> Unit,
) {
    // First in, so last out: the copy goes once the viewer below has let go of it, which Windows insists on.
    DisposableEffect(path) { onDispose { onGone(path) } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.close))
                        }
                    },
                )
            },
        ) { padding ->
            var failed by remember(path) { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (failed) PreviewFailed() else FilePreviewContent(path, name, kind, onFailed = { failed = true })
            }
        }
    }
}

private sealed interface StreamedState {
    data object Loading : StreamedState
    data class Ready(val path: String) : StreamedState
    data object Failed : StreamedState
}

/**
 * Preview of a file that has to be fetched first: a spinner while [load] makes its local copy (null means it couldn't),
 * then the content. The copy is handed to [discard] when [key] changes or the preview leaves composition.
 */
@Composable
fun StreamedFilePreview(
    key: Any,
    name: String,
    kind: FilePreviewKind,
    load: suspend () -> String?,
    discard: (path: String) -> Unit,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var state by remember(key) { mutableStateOf<StreamedState>(StreamedState.Loading) }
    val copy = remember(key) { arrayOfNulls<String>(1) }
    LaunchedEffect(key) {
        state = try {
            load()?.let { copy[0] = it; StreamedState.Ready(it) } ?: StreamedState.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StreamedState.Failed
        }
    }
    DisposableEffect(key) { onDispose { copy[0]?.let(discard) } }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            StreamedState.Loading -> CircularProgressIndicator()
            StreamedState.Failed -> PreviewFailed()
            is StreamedState.Ready -> FilePreviewContent(s.path, name, kind, onTap = onTap, onFailed = { state = StreamedState.Failed }, contentPadding = contentPadding)
        }
    }
}
