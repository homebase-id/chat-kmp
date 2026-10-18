package id.homebase.chat.image

import io.github.vinceglb.filekit.PlatformFile

internal actual fun PlatformFile.localPathOrNull(): String? = file.takeIf { it.canRead() }?.path
