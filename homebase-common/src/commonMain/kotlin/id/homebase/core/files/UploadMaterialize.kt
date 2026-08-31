package id.homebase.core.files

import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.util.extensionForMimeType
import io.github.vinceglb.filekit.PlatformFile
import kotlin.uuid.Uuid

/**
 * Resolves a picked [PlatformFile] to a path that [fileOps] can read for upload.
 *
 * Path-based upload pipelines keep `file.toString()` and later read the bytes via
 * [FileOperationsProvider]. That works on native, where a picked [PlatformFile] is backed by a
 * real filesystem path (or an Android `content://` URI that `resolveToFilePath` copies
 * downstream) — so the native actuals return [PlatformFile.toString] unchanged, with no extra
 * copy.
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
 * This is the platform's one answer to "I picked a file now and will read it later" — every
 * picker that stores a path across time must go through it, because two platforms revoke
 * access behind your back:
 *
 * - Android grants only transient read permission to a picked `content://` URI; keep the raw
 *   URI and read it later and you get FileNotFoundException when the grant lapses (WebDrop's
 *   issue #1420 was exactly this).
 * - An iOS document/Files-app picker vends a **security-scoped** `NSURL`: the read grant is
 *   bound to that specific URL object, not to its path string. A later scope-LESS
 *   `NSURL.fileURLWithPath(path)` read returns nil. Copying into the sandbox **while the
 *   picker's scope is still live** (call this synchronously in the picker callback's launch,
 *   before any further suspension) yields a path that can always be read with no scope.
 *
 * FileKit's `copyTo` is what handles the security scope on apple.
 *
 * - apple/android/jvm: copy into the FileKit cache dir, preserving the original file name (so
 *   content-type / display-name resolution from `file.name` is unchanged).
 * - web: no-op (`this`). The browser has no path and no security scope; the picked
 *   [PlatformFile] keeps its bytes and [toUploadPath]'s web actual materializes them at send
 *   time via `readBytes()`.
 */
expect suspend fun PlatformFile.materializeForUpload(fileOps: FileOperationsProvider): PlatformFile

/**
 * Name for the pick-time sandbox copy: the original name behind a collision-proof prefix, plus an
 * extension derived from [mimeType] when the picker's name has none. Belt to
 * `PlatformFile.contentType`'s braces — it keeps the copy self-describing for anything that only
 * sees the file (#1149). The prefix is historical (the helper grew up in chat) and is deliberately
 * kept: CacheSweeper reaps it as untracked either way, and a rename would orphan nothing but
 * grep results.
 */
internal fun sandboxCopyName(name: String, mimeType: String?): String {
    val hasExtension = name.substringAfterLast('.', "").isNotEmpty()
    val ext = if (hasExtension) null else mimeType?.let(::extensionForMimeType)
    return "chat_attach_${Uuid.random()}_$name" + if (ext != null) ".$ext" else ""
}

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
