package id.homebase.core.files

import id.homebase.api.file.FileOperationsProvider
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name

// Native PlatformFile carries a real path / content:// URI; the path-based pipelines (and
// resolveToFilePath for content URIs) already handle it. No copy needed at send time.
actual suspend fun PlatformFile.toUploadPath(fileOps: FileOperationsProvider): String = toString()

// Copy WHILE the picker's security scope is still live (this runs in the picker-callback
// launch before any further suspension). FileKit's copyTo opens the source through its NSURL —
// which still holds the scope grant here — and writes a plain sandbox file, so the later
// scope-less path read always succeeds.
actual suspend fun PlatformFile.materializeForUpload(fileOps: FileOperationsProvider): PlatformFile {
    val dest = PlatformFile(FileKit.cacheDir, sandboxCopyName(name, mimeType()?.toString()))
    copyTo(dest)
    return dest
}
