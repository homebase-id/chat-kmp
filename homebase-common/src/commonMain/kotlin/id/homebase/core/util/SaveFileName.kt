package id.homebase.core.util

import id.homebase.api.file.OCTET_STREAM
import id.homebase.api.file.detectContentTypeFromExtensionOrHint
import id.homebase.api.file.extensionForContentType
import id.homebase.api.util.safeFileName
import id.homebase.api.util.splitExtension
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The one name for a payload saved to the device: the sender's name, else `Homebase_yyyyMMdd_HHmmss` from [savedAtMs],
 * ending in the extension of the bytes saved ([contentType]). A name whose extension already means that type is kept as is.
 */
fun saveFileName(name: String?, contentType: String?, savedAtMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val sent = safeFileName(name, "").takeIf { it.isNotEmpty() }
    val ext = extensionForContentType(contentType)
    val (stem, ownExt) = splitExtension(sent ?: datedStem(savedAtMs, zone))
    val ownType = detectContentTypeFromExtensionOrHint(ownExt)
    if (ownExt.isNotEmpty() && (ext == "bin" || ownType == baseType(contentType))) return stem + ownExt
    val unknownSuffix = ownExt.isNotEmpty() && ownType == OCTET_STREAM
    return safeFileName(if (unknownSuffix) stem + ownExt else stem, extension = ext)
}

private fun baseType(contentType: String?) = contentType?.substringBefore(';')?.trim()?.lowercase()

private fun datedStem(ms: Long, zone: TimeZone): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
    fun Int.p(n: Int = 2) = toString().padStart(n, '0')
    return "Homebase_${t.year.p(4)}${t.month.number.p()}${t.day.p()}_${t.hour.p()}${t.minute.p()}${t.second.p()}"
}
