package id.homebase.core.util

import id.homebase.api.file.resolveContentType
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name

/**
 * [resolveContentType] over a [PlatformFile]'s own name and platform MIME. Use this instead of
 * spelling the pair out, and never `mimeType()` alone — the raw MIME has no extension fallback.
 *
 * Call it on the **originally picked** handle, before any sandbox copy. Only that handle knows the
 * real type: on Android it is a `content://` URI whose `ContentResolver.getType()` answers
 * `image/jpeg`, while a copy is a plain file that can only be typed from its name — and the system
 * photo picker's names (`photopicker-1000022602`) carry no extension, so a copy resolves to
 * `application/octet-stream` and the message ships as a generic file chip with no thumbnail (#1149).
 */
fun PlatformFile.contentType(): String =
    resolveContentType(fileName = name, platformMimeType = mimeType()?.toString())
