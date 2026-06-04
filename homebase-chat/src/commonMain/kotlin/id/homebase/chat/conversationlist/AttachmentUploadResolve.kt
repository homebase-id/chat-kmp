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
 * A handle the in-editor video decoder/player can read **immediately**, without the expensive
 * okio materialization [toUploadPath] performs (whole-file read + in-memory copy).
 *
 * - Native (Android/iOS/Desktop): the real filesystem path — identical to [PlatformFile.toString];
 *   the native decoders/players read it directly.
 * - Web: a `blob:` object URL minted straight from the picked browser `File` via
 *   `URL.createObjectURL` — O(1), copies no bytes into wasm and uses no base64. The same URL is
 *   reused for the poster, duration probe, filmstrip and playback; the `<video>`/canvas stream
 *   from it. Release it with [revokePlayableUrl] once the attachment is gone.
 *
 * This is what gets stored in `AttachmentPendingFile.FileVideo.playablePath`. The upload pipeline
 * still calls [toUploadPath] at send time to materialize bytes into okio for encryption — that is
 * unaffected by this handle.
 */
expect fun PlatformFile.toPlayableUrl(): String

/** Release a [toPlayableUrl] handle. No-op on native and for non-`blob:` strings. */
expect fun revokePlayableUrl(url: String)

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
