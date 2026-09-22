package id.homebase.core.util

enum class FileKind {
    Archive, Pdf, Word, Spreadsheet, Presentation, Text, Code, Audio, Video, Image, Apk, Generic,
}

// Desktop pickers often report application/octet-stream, so an unresolved MIME defers to the extension.
fun fileKindOf(contentType: String?, fileName: String?): FileKind {
    val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return kindForMime(mime)
        ?: kindForMime(detectContentTypeFromExtensionOrHint(fileName?.trim()))
        ?: FileKind.Generic
}

private val kindByMime: Map<String, FileKind> = mapOf(
    FileKind.Pdf to listOf("application/pdf"),
    FileKind.Apk to listOf("application/vnd.android.package-archive"),
    FileKind.Archive to listOf(
        "application/zip",
        "application/x-zip",
        "application/x-zip-compressed",
        "application/x-7z-compressed",
        "application/x-rar",
        "application/x-rar-compressed",
        "application/vnd.rar",
        "application/gzip",
        "application/x-gzip",
        "application/x-tar",
        "application/x-bzip2",
        "application/x-xz",
    ),
    FileKind.Word to listOf(
        "application/msword",
        "application/rtf",
        "text/rtf",
        "application/vnd.oasis.opendocument.text",
    ),
    FileKind.Spreadsheet to listOf(
        "text/csv",
        "text/tab-separated-values",
        "application/vnd.oasis.opendocument.spreadsheet",
    ),
    FileKind.Presentation to listOf("application/vnd.oasis.opendocument.presentation"),
    FileKind.Text to listOf("text/markdown", "text/x-markdown"),
    FileKind.Code to listOf(
        "application/json",
        "application/javascript",
        "application/x-javascript",
        "text/javascript",
        "application/xml",
        "text/xml",
        "application/yaml",
        "application/x-yaml",
        "text/yaml",
        "application/x-sh",
        "text/html",
        "text/css",
    ),
).flatMap { (kind, mimes) -> mimes.map { it to kind } }.toMap()

private fun kindForMime(mime: String): FileKind? = kindByMime[mime] ?: when {
    mime.startsWith("image/") -> FileKind.Image
    mime.startsWith("video/") -> FileKind.Video
    mime.startsWith("audio/") -> FileKind.Audio
    "wordprocessingml" in mime -> FileKind.Word
    mime.startsWith("application/vnd.ms-excel") || "spreadsheetml" in mime -> FileKind.Spreadsheet
    mime.startsWith("application/vnd.ms-powerpoint") || "presentationml" in mime -> FileKind.Presentation
    mime.startsWith("text/x-") || mime.endsWith("+json") || mime.endsWith("+xml") -> FileKind.Code
    mime.startsWith("text/") -> FileKind.Text
    else -> null
}
