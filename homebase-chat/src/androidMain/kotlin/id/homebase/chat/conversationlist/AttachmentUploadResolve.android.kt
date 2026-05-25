package id.homebase.chat.conversationlist

import id.homebase.api.file.FileOperationsProvider
import io.github.vinceglb.filekit.PlatformFile

// Native PlatformFile carries a real path / content:// URI; the existing path-based send
// pipeline (and resolveToFilePath for content URIs) already handles it. No copy needed.
actual suspend fun PlatformFile.toUploadPath(fileOps: FileOperationsProvider): String = toString()
