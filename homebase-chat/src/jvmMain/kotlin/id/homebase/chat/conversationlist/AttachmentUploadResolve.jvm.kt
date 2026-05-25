package id.homebase.chat.conversationlist

import id.homebase.api.file.FileOperationsProvider
import io.github.vinceglb.filekit.PlatformFile

// Desktop PlatformFile carries a real filesystem path; the path-based send pipeline reads it
// directly. No copy needed.
actual suspend fun PlatformFile.toUploadPath(fileOps: FileOperationsProvider): String = toString()
