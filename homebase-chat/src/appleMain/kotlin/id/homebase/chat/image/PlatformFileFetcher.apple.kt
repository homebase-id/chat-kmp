package id.homebase.chat.image

import io.github.vinceglb.filekit.PlatformFile
import platform.Foundation.NSFileManager

internal actual fun PlatformFile.localPathOrNull(): String? =
    nsUrl.takeIf { it.isFileURL() }?.path?.takeIf { NSFileManager.defaultManager.isReadableFileAtPath(it) }
