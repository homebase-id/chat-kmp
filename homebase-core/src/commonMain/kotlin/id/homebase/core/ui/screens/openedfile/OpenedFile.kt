package id.homebase.core.ui.screens.openedfile

import id.homebase.api.file.createStagingPathIn
import id.homebase.api.file.systemFileSystem
import id.homebase.chat.widget.preview.FilePreviewKind
import id.homebase.chat.widget.preview.filePreviewKind
import id.homebase.core.ui.screens.filesync.FilesyncCrumb
import id.homebase.core.ui.screens.filesync.FilesyncNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okio.FileSystem
import okio.Path.Companion.toPath

/** A document another app handed to Homebase, already copied to [path] (its sanitized [name] is the file's own name). */
data class OpenedFileRequest(val path: String, val name: String, val mimeType: String?, val sizeBytes: Long)

const val OPENED_FILE_STAGING_DIR = "open-with"

fun openedFilePreviewKind(mimeType: String?, name: String, sizeBytes: Long?): FilePreviewKind? =
    filePreviewKind(mimeType, name, sizeBytes)

/** The name other apps give a file is theirs to choose: keep one path segment, no control or reserved characters, no leading dot. */
fun sanitizeOpenedFileName(raw: String?): String {
    val segment = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
    val scrubbed = segment.map { if (it.code < 0x20 || it.code == 0x7f || it in RESERVED) '_' else it }.joinToString("")
    val trimmed = scrubbed.trim().trimStart('.').trimEnd('.', ' ')
    return FilesyncNames.freeName(trimmed.ifEmpty { "file" }, emptySet())
}

private const val RESERVED = "<>:\"|?*"

/** An empty folder of its own under [root], so the upload is named by the file and two files with one name never share a path. */
fun reserveStagedPath(root: String, rawName: String?, fileSystem: FileSystem = systemFileSystem): String {
    val dir = createStagingPathIn(root, "open-", "", fileSystem).toPath()
    fileSystem.createDirectories(dir)
    return (dir / sanitizeOpenedFileName(rawName)).toString()
}

fun stageFileCopy(source: String, root: String, rawName: String?, fileSystem: FileSystem = systemFileSystem): String {
    val target = reserveStagedPath(root, rawName, fileSystem)
    fileSystem.copy(source.toPath(), target.toPath())
    return target
}

fun discardStaged(path: String, fileSystem: FileSystem = systemFileSystem) {
    val file = path.toPath()
    runCatching { fileSystem.delete(file, mustExist = false) }
    file.parent?.let { dir -> runCatching { fileSystem.delete(dir, mustExist = false) } }
}

/** Where an entry point leaves a document for the app to show once it is signed in. */
object OpenedFileInbox {
    private val _pending = MutableStateFlow<OpenedFileRequest?>(null)
    val pending: StateFlow<OpenedFileRequest?> = _pending.asStateFlow()

    /** The Filesync folder the "Show" action asks the browser to open. */
    val showFolder = MutableStateFlow<List<FilesyncCrumb>?>(null)

    fun offer(request: OpenedFileRequest) {
        _pending.value = request
    }

    fun clear(path: String) {
        _pending.update { if (it?.path == path) null else it }
    }
}
