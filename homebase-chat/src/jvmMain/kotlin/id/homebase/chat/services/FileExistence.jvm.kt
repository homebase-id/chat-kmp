package id.homebase.chat.services

internal actual fun fileExists(path: String): Boolean {
    // Non-filesystem references (content://, file://, http(s)://, …) are
    // opaque from here — assume they're valid and let the consumer
    // resolve them. Filesystem paths (POSIX "/x/y", Windows "C:\x") fall
    // through to the real existence check.
    if (path.contains("://")) return true
    return java.io.File(path).exists()
}
