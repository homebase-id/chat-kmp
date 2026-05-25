package id.homebase.chat.conversationlist

import id.homebase.api.file.FileOperationsProvider
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes

// The browser has no filesystem path for a picked PlatformFile (FileKit's PlatformFile.path is
// non-web-only); its bytes are reachable only via readBytes(). Copy them into the okio in-memory
// cache so the path-based send pipeline can read them back. Without this, web sends fail because
// file.toString() isn't an okio path ("file not found").
actual suspend fun PlatformFile.toUploadPath(fileOps: FileOperationsProvider): String =
    materializeBytesToUploadPath(fileOps, name, readBytes())
