package id.homebase.chat.conversationlist

import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.util.contentType
import id.homebase.core.util.extensionForMimeType
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import kotlin.uuid.Uuid

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
 * Copy a freshly-picked [PlatformFile] into the app sandbox **at pick time** and return a
 * sandbox-backed [PlatformFile], or return `this` unchanged when no copy is needed.
 *
 * This exists because an iOS document/Files-app picker vends a **security-scoped** `NSURL`: the
 * read grant is bound to that specific URL object, not to its path string. The chat send path is
 * path-based — it keeps only `file.toString()` and reads the bytes later, inside a separate
 * `scope.launch`, by rebuilding a scope-LESS `NSURL.fileURLWithPath(path)`. By then the original
 * scoped URL is gone, `startAccessingSecurityScopedResource()` on the path-built URL returns false,
 * and `NSData.dataWithContentsOfFile` returns nil → "Unable to read file". Copying into the sandbox
 * **while the picker's scope is still live** (this is called synchronously in the picker callback's
 * launch, before any further suspension) yields a path the send path can always read with no scope.
 *
 * Mirrors the working Vault contract (`VaultViewModel` copies picked files via FileKit `copyTo`
 * before upload). FileKit's `copyTo` is what handles the security scope on apple.
 *
 * - apple/android/jvm: copy into the FileKit cache dir, preserving the original file name (so the
 *   send path's content-type / display-name resolution from `file.name` is unchanged).
 * - web: no-op (`this`). The browser has no path and no security scope; the picked `PlatformFile`
 *   keeps its bytes and [toUploadPath]'s web actual materializes them at send time via `readBytes()`.
 */
expect suspend fun PlatformFile.materializeForUpload(fileOps: FileOperationsProvider): PlatformFile

/**
 * Name for the pick-time sandbox copy: the original name behind a collision-proof prefix, plus an
 * extension derived from [mimeType] when the picker's name has none. Belt to
 * [PlatformFile.contentType]'s braces — it keeps the copy self-describing for anything that only
 * sees the file (#1149).
 */
internal fun sandboxCopyName(name: String, mimeType: String?): String {
    val hasExtension = name.substringAfterLast('.', "").isNotEmpty()
    val ext = if (hasExtension) null else mimeType?.let(::extensionForMimeType)
    return "chat_attach_${Uuid.random()}_$name" + if (ext != null) ".$ext" else ""
}

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
