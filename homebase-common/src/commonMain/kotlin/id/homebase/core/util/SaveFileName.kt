package id.homebase.core.util

import id.homebase.api.file.OCTET_STREAM
import id.homebase.api.file.detectContentTypeFromExtensionOrHint
import id.homebase.api.file.extensionForContentType
import id.homebase.api.file.extensionForMimeType
import id.homebase.api.util.safeFileName
import id.homebase.api.util.splitExtension
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The one name for a payload saved to the device: the sender's name, else `Homebase_yyyyMMdd_HHmmss` from [savedAtMs],
 * ending in the extension of the bytes saved ([contentType]). The name's own extension stays when it means that type,
 * or when [contentType] is one we don't know (`audio/x-m4a`) and the extension is.
 */
fun saveFileName(name: String?, contentType: String?, savedAtMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val full = safeFileName(name, "").ifEmpty { datedStem(savedAtMs, zone) }
    val (stem, ownExt) = splitExtension(full)
    val ownType = detectContentTypeFromExtensionOrHint(ownExt)
    val type = contentType?.substringBefore(';')?.trim()?.lowercase()
    val ext = extensionForContentType(contentType)
    val typeUnknown = type?.let(::extensionForMimeType) == null
    if (ownExt.isNotEmpty() && (ownType == type || typeUnknown && (ownType != OCTET_STREAM || ext == "bin"))) return full
    return safeFileName(if (ownType == OCTET_STREAM) full else stem, extension = ext)
}

private fun datedStem(ms: Long, zone: TimeZone): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
    fun Int.p(n: Int = 2) = toString().padStart(n, '0')
    return "Homebase_${t.year.p(4)}${t.month.number.p()}${t.day.p()}_${t.hour.p()}${t.minute.p()}${t.second.p()}"
}
