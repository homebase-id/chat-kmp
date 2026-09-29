package id.homebase.core.files

import id.homebase.api.file.AppCacheDirs
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.scratchDir
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name

// Native PlatformFile carries a real path / content:// URI; the path-based pipelines (and
// resolveToFilePath for content URIs) already handle it. No copy needed at send time.
actual suspend fun PlatformFile.toUploadPath(fileOps: FileOperationsProvider): String = toString()

// Desktop has no security scope, but copying a real filesystem path is cheap and harmless
// and keeps all native platforms behaving the same.
actual suspend fun PlatformFile.materializeForUpload(fileOps: FileOperationsProvider): PlatformFile {
    val dest = PlatformFile("${fileOps.scratchDir(AppCacheDirs.PICKER_COPIES)}/${sandboxCopyName(name, mimeType()?.toString())}")
    copyTo(dest)
    return dest
}
