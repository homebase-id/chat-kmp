package id.homebase.chat.conversationlist

import id.homebase.api.file.FileOperationsProvider
import io.github.vinceglb.filekit.PlatformFile

/**
 * Resolves a picked [PlatformFile] to a path that [fileOps] can read for upload.
 *
 * The send pipeline is path-based: it takes `attachment.file.toString()` and later reads the
 * bytes via [FileOperationsProvider]. That works on native, where a picked [PlatformFile] is
 * backed by a real filesystem path (or an Android `content://` URI that `resolveToFilePath`
 * copies downstream) — so the native actuals return [PlatformFile.toString] unchanged, with no
 * extra copy.
 *
 * On the web there is NO path: FileKit's `PlatformFile.path` lives in its non-web source set,
 * and a browser-picked file's bytes are reachable only through `readBytes()`. So `file.toString()`
 * is not an okio path and the upload's `readFileBytes(...)` fails with "file not found". The web
 * actual fixes this by copying the picked bytes into the okio (in-memory) cache via [fileOps] and
 * returning that path, so the rest of the path-based pipeline just works.
 */
expect suspend fun PlatformFile.toUploadPath(fileOps: FileOperationsProvider): String

/**
 * Shared materialize step used by the web actual: copy [bytes] into a temp file via [fileOps]
 * and return its path, preserving [fileName]'s extension in the suffix. Extracted to commonMain
 * so the copy-and-readability contract can be unit-tested without a browser.
 */
internal suspend fun materializeBytesToUploadPath(
    fileOps: FileOperationsProvider,
    fileName: String,
    bytes: ByteArray,
): String {
    val ext = fileName.substringAfterLast('.', "")
    val suffix = if (ext.isNotEmpty()) ".$ext" else ""
    return fileOps.writeBytesToTempFile(bytes, "attach_", suffix)
}
