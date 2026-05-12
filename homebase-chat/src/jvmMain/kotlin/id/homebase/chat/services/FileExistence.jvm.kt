package id.homebase.chat.services

internal actual fun fileExists(path: String): Boolean = java.io.File(path).exists()
