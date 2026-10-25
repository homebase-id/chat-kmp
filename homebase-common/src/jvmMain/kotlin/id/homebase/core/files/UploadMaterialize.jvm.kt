package id.homebase.core.files

import id.homebase.api.file.FileOperationsProvider
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name

// Desktop has no security scope, but copying a real filesystem path is cheap and harmless
// and keeps all native platforms behaving the same.
actual suspend fun PlatformFile.materializeForUpload(fileOps: FileOperationsProvider): PlatformFile {
    val dest = PlatformFile(fileOps.sandboxCopyDest(name, mimeType()?.toString()))
    copyTo(dest)
    return dest
}
