package id.homebase.core.util

import id.homebase.api.file.extensionForContentType
import id.homebase.api.util.safeFileName
import id.homebase.api.util.splitExtension

/** A payload saved to the device: the sender's name, with an extension from [contentType] when it has none. */
fun saveFileName(name: String?, fallbackStem: String, contentType: String?): String {
    val safe = safeFileName(name, fallbackStem)
    return if (splitExtension(safe).second.isNotEmpty()) safe else safeFileName(name, fallbackStem, extensionForContentType(contentType))
}
