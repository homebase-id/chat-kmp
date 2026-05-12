package id.homebase.chat.services

import platform.Foundation.NSFileManager

internal actual fun fileExists(path: String): Boolean =
    NSFileManager.defaultManager.fileExistsAtPath(path)
