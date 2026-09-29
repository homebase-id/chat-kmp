package id.homebase.core.files

import id.homebase.api.file.AppCacheDirs
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.scratchDir
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path

// Native PlatformFile carries a real path / content:// URI; the path-based pipelines (and
// resolveToFilePath for content URIs) already handle it. No copy needed at send time.
actual suspend fun PlatformFile.toUploadPath(fileOps: FileOperationsProvider): String = toString()

// On Android the picked file is a content:// URI whose read grant can be transient;
// copying through FileKit's copyTo (which reads the content URI) yields a stable plain file the
// send path can always read — equivalent to the downstream resolveToFilePath copy, just earlier.
actual suspend fun PlatformFile.materializeForUpload(fileOps: FileOperationsProvider): PlatformFile {
    if (isUnderDir(fileOps.getCacheDirectory(), path)) return this
    val dest = PlatformFile("${fileOps.scratchDir(AppCacheDirs.PICKER_COPIES)}/${sandboxCopyName(name, mimeType()?.toString())}")
    copyTo(dest)
    return dest
}
