package id.homebase.chat.services

internal actual fun fileExists(path: String): Boolean {
    if (!path.startsWith("/")) return true
    return java.io.File(path).exists()
}
