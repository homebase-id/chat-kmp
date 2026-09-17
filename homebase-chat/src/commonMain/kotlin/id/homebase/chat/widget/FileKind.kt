package id.homebase.chat.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.vector.ImageVector
import id.homebase.core.ui.assets.Apk
import id.homebase.core.ui.assets.Excel
import id.homebase.core.ui.assets.File
import id.homebase.core.ui.assets.FileCode
import id.homebase.core.ui.assets.FileZip
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.Pdf
import id.homebase.core.ui.assets.WordFile

internal enum class FileKind {
    Archive, Pdf, Word, Spreadsheet, Presentation, Text, Code, Audio, Video, Image, Apk, Generic,
}

internal val FileKind.icon: ImageVector
    get() = when (this) {
        FileKind.Archive -> HomebaseIcons.FileZip
        FileKind.Pdf -> HomebaseIcons.Pdf
        FileKind.Word -> HomebaseIcons.WordFile
        FileKind.Spreadsheet -> HomebaseIcons.Excel
        FileKind.Presentation -> Icons.Filled.Slideshow
        FileKind.Text -> Icons.Filled.Description
        FileKind.Code -> HomebaseIcons.FileCode
        FileKind.Audio -> Icons.Filled.AudioFile
        FileKind.Video -> Icons.Filled.VideoFile
        FileKind.Image -> Icons.Filled.Image
        FileKind.Apk -> HomebaseIcons.Apk
        FileKind.Generic -> HomebaseIcons.File
    }

// Desktop pickers often report application/octet-stream, so an unresolved MIME defers to the extension.
internal fun fileKindOf(contentType: String?, fileName: String?): FileKind {
    val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    kindForMime(mime)?.let { return it }
    val extension = fileName?.trim()?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
    return extension?.let(::kindForExtension) ?: FileKind.Generic
}

private val archiveMimes = setOf(
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
)

private val wordMimes = setOf(
    "application/msword",
    "application/rtf",
    "text/rtf",
    "application/vnd.oasis.opendocument.text",
)

private val spreadsheetMimes = setOf(
    "text/csv",
    "text/tab-separated-values",
    "application/vnd.oasis.opendocument.spreadsheet",
)

private val codeMimes = setOf(
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
)

private fun kindForMime(mime: String): FileKind? = when {
    mime.isEmpty() || mime == "application/octet-stream" -> null
    mime.startsWith("image/") -> FileKind.Image
    mime.startsWith("video/") -> FileKind.Video
    mime.startsWith("audio/") -> FileKind.Audio
    mime == "application/pdf" -> FileKind.Pdf
    mime == "application/vnd.android.package-archive" -> FileKind.Apk
    mime in archiveMimes -> FileKind.Archive
    mime in wordMimes || "wordprocessingml" in mime -> FileKind.Word
    mime in spreadsheetMimes || mime.startsWith("application/vnd.ms-excel") ||
        "spreadsheetml" in mime -> FileKind.Spreadsheet
    mime == "application/vnd.oasis.opendocument.presentation" ||
        mime.startsWith("application/vnd.ms-powerpoint") ||
        "presentationml" in mime -> FileKind.Presentation
    mime == "text/markdown" || mime == "text/x-markdown" -> FileKind.Text
    mime in codeMimes || mime.startsWith("text/x-") ||
        mime.endsWith("+json") || mime.endsWith("+xml") -> FileKind.Code
    mime.startsWith("text/") -> FileKind.Text
    else -> null
}

private fun kindForExtension(extension: String): FileKind? = when (extension) {
    "zip", "7z", "rar", "gz", "tgz", "tar", "bz2", "xz" -> FileKind.Archive
    "pdf" -> FileKind.Pdf
    "doc", "docx", "rtf", "odt" -> FileKind.Word
    "xls", "xlsx", "csv", "tsv", "ods" -> FileKind.Spreadsheet
    "ppt", "pptx", "odp" -> FileKind.Presentation
    "txt", "md", "markdown", "log" -> FileKind.Text
    "json", "js", "ts", "xml", "yaml", "yml", "html", "css", "sh",
    "kt", "kts", "java", "py", "swift", "c", "h", "cpp", "go", "rs" -> FileKind.Code
    "mp3", "m4a", "aac", "wav", "ogg", "opus", "flac" -> FileKind.Audio
    "mp4", "mov", "m4v", "webm", "mkv", "avi" -> FileKind.Video
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg" -> FileKind.Image
    "apk" -> FileKind.Apk
    else -> null
}
