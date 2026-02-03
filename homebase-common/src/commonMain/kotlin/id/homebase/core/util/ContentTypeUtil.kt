package id.homebase.core.util

fun detectContentTypeFromExtensionOrHint(nameOrPath: String?): String {
    val ext = nameOrPath?.substringAfterLast('.')?.lowercase()?.takeIf { it.isNotBlank() } ?: return "application/octet-stream"
    return commonExtToMime[ext] ?: "application/octet-stream"
}

private val commonExtToMime: Map<String, String> = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "pdf" to "application/pdf",
    "zip" to "application/zip",
    "txt" to "text/plain",
    "html" to "text/html",
    "htm" to "text/html",
    "mp4" to "video/mp4",
    "mov" to "video/quicktime",
    "json" to "application/json",
    "csv" to "text/csv",
    "svg" to "image/svg+xml",
    "bmp" to "image/bmp",
    "ico" to "image/x-icon",
    "avif" to "image/avif",
    "webm" to "video/webm"
)