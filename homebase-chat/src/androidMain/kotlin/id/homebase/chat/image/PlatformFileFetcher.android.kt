package id.homebase.chat.image

import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile

internal actual fun PlatformFile.localPathOrNull(): String? =
    (androidFile as? AndroidFile.FileWrapper)?.file?.takeIf { it.canRead() }?.path
