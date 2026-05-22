package id.homebase.core.util

const val CONTENT_TYPE_MARKDOWN = "text/markdown"

fun detectContentTypeFromExtensionOrHint(nameOrPath: String?): String {
    val ext = nameOrPath?.substringAfterLast('.')?.lowercase()?.takeIf { it.isNotBlank() }
        ?: return "application/octet-stream"
    return commonExtToMime[ext] ?: "application/octet-stream"
}

fun extensionForMimeType(mimeType: String): String? = commonMimeToExt[mimeType]

/**
 * Resolves the best content type for a file:
 * 1. Platform-provided MIME type (if specific, i.e. not octet-stream)
 * 2. Extension-based lookup from the filename (140+ extensions)
 * Falls back to "application/octet-stream" if both fail.
 *
 * [platformMimeType] is the optional result from PlatformFile.mimeType().
 */
fun resolveContentType(
    fileName: String?,
    platformMimeType: String? = null,
): String {
    // 1. Trust platform MIME type if it's specific
    if (platformMimeType != null && platformMimeType != "application/octet-stream") {
        return platformMimeType
    }

    // 2. Extension-based lookup
    return detectContentTypeFromExtensionOrHint(fileName)
}

private val commonExtToMime: Map<String, String> = mapOf(
    // Images
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "svg" to "image/svg+xml",
    "bmp" to "image/bmp",
    "ico" to "image/x-icon",
    "avif" to "image/avif",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "tiff" to "image/tiff",
    "tif" to "image/tiff",

    // Video
    "mp4" to "video/mp4",
    "mov" to "video/quicktime",
    "webm" to "video/webm",
    "avi" to "video/x-msvideo",
    "mkv" to "video/x-matroska",
    "m4v" to "video/x-m4v",
    "3gp" to "video/3gpp",
    "flv" to "video/x-flv",
    "wmv" to "video/x-ms-wmv",
    // "ts" intentionally omitted — ambiguous between TypeScript and MPEG transport stream

    // Audio
    "mp3" to "audio/mpeg",
    "wav" to "audio/wav",
    "ogg" to "audio/ogg",
    "m4a" to "audio/mp4",
    "flac" to "audio/flac",
    "aac" to "audio/aac",
    "opus" to "audio/opus",
    "weba" to "audio/webm",
    "wma" to "audio/x-ms-wma",
    "mid" to "audio/midi",
    "midi" to "audio/midi",
    "aiff" to "audio/aiff",
    "aif" to "audio/aiff",

    // Documents — Office
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "ppt" to "application/vnd.ms-powerpoint",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "odt" to "application/vnd.oasis.opendocument.text",
    "ods" to "application/vnd.oasis.opendocument.spreadsheet",
    "odp" to "application/vnd.oasis.opendocument.presentation",
    "rtf" to "application/rtf",
    "pages" to "application/x-iwork-pages-sffpages",
    "numbers" to "application/x-iwork-numbers-sffnumbers",
    "key" to "application/x-iwork-keynote-sffkey",

    // Documents — Other
    "pdf" to "application/pdf",
    "epub" to "application/epub+zip",

    // Text / Plain
    "txt" to "text/plain",
    "log" to "text/plain",
    "md" to "text/markdown",
    "markdown" to "text/markdown",
    "cfg" to "text/plain",
    "conf" to "text/plain",
    "ini" to "text/plain",
    "env" to "text/plain",
    "properties" to "text/plain",

    // Text / Markup & Stylesheets
    "html" to "text/html",
    "htm" to "text/html",
    "xml" to "application/xml",
    "xhtml" to "application/xhtml+xml",
    "css" to "text/css",
    "scss" to "text/x-scss",
    "less" to "text/x-less",
    "yaml" to "application/x-yaml",
    "yml" to "application/x-yaml",
    "toml" to "application/toml",

    // Text / Source Code
    "js" to "application/javascript",
    "mjs" to "application/javascript",
    "jsx" to "text/jsx",
    "tsx" to "text/tsx",
    "json" to "application/json",
    "csv" to "text/csv",
    "tsv" to "text/tab-separated-values",
    "sh" to "application/x-sh",
    "bash" to "application/x-sh",
    "zsh" to "application/x-sh",
    "py" to "text/x-python",
    "kt" to "text/x-kotlin",
    "kts" to "text/x-kotlin",
    "java" to "text/x-java-source",
    "swift" to "text/x-swift",
    "c" to "text/x-c",
    "cpp" to "text/x-c++src",
    "h" to "text/x-c",
    "hpp" to "text/x-c++hdr",
    "cs" to "text/x-csharp",
    "go" to "text/x-go",
    "rs" to "text/x-rustsrc",
    "rb" to "text/x-ruby",
    "php" to "application/x-httpd-php",
    "pl" to "text/x-perl",
    "lua" to "text/x-lua",
    "r" to "text/x-r",
    "sql" to "application/sql",
    "graphql" to "application/graphql",
    "proto" to "text/x-protobuf",
    "gradle" to "text/x-groovy",
    "groovy" to "text/x-groovy",
    "dart" to "text/x-dart",

    // Archives
    "zip" to "application/zip",
    "rar" to "application/x-rar-compressed",
    "7z" to "application/x-7z-compressed",
    "tar" to "application/x-tar",
    "gz" to "application/gzip",
    "tgz" to "application/gzip",
    "bz2" to "application/x-bzip2",
    "xz" to "application/x-xz",
    "zst" to "application/zstd",
    "dmg" to "application/x-apple-diskimage",
    "iso" to "application/x-iso9660-image",

    // Executables / Packages
    "apk" to "application/vnd.android.package-archive",
    "ipa" to "application/x-itunes-ipa",
    "exe" to "application/x-msdownload",
    "msi" to "application/x-msi",
    "deb" to "application/x-debian-package",
    "rpm" to "application/x-rpm",
    "jar" to "application/java-archive",
    "war" to "application/java-archive",
    "wasm" to "application/wasm",

    // Fonts
    "ttf" to "font/ttf",
    "otf" to "font/otf",
    "woff" to "font/woff",
    "woff2" to "font/woff2",

    // Data / Database
    "sqlite" to "application/x-sqlite3",
    "db" to "application/x-sqlite3",
    "plist" to "application/x-plist",

    // Other
    "ics" to "text/calendar",
    "vcf" to "text/vcard",
    "eml" to "message/rfc822",
    "msg" to "application/vnd.ms-outlook",
)

/** Reverse map: MIME type → preferred file extension (without dot). */
private val commonMimeToExt: Map<String, String> by lazy {
    val reverse = mutableMapOf<String, String>()
    // Build reverse, preferring shorter/canonical extensions when duplicates exist
    val preferred = setOf(
        "jpg", "htm", "js", "kt", "ts", "c", "h", "mid", "aif", "tif",
        "mp4", "mp3", "wav", "ogg", "pdf", "zip", "txt", "html", "json",
        "csv", "xml", "yaml", "sh", "py", "java", "swift", "go", "rs",
        "rb", "css", "sql", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "apk", "gz", "rar", "7z",
    )
    // First pass: add all entries
    for ((ext, mime) in commonExtToMime) {
        if (!reverse.containsKey(mime) || ext in preferred) {
            reverse[mime] = ext
        }
    }
    reverse
}
