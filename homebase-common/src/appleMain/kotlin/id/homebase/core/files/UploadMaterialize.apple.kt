package id.homebase.core.files

import id.homebase.api.file.FileOperationsProvider
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path

// Copy WHILE the picker's security scope is still live (this runs in the picker-callback
// launch before any further suspension). FileKit's copyTo opens the source through its NSURL —
// which still holds the scope grant here — and writes a plain sandbox file, so the later
// scope-less path read always succeeds.
actual suspend fun PlatformFile.materializeForUpload(fileOps: FileOperationsProvider): PlatformFile {
    if (isUnderDir(fileOps.getCacheDirectory(), path)) return this
    val dest = fileOps.sandboxCopyDest(name, mimeType()?.toString())
    copyTo(dest)
    return dest
}
