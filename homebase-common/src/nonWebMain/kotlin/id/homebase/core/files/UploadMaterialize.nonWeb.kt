package id.homebase.core.files

import id.homebase.api.file.FileOperationsProvider
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.lastModified
import kotlin.time.ExperimentalTime

// Native PlatformFile carries a real path / content:// URI; the path-based pipelines (and
// resolveToFilePath for content URIs) already handle it. No copy needed at send time.
actual suspend fun PlatformFile.toUploadPath(fileOps: FileOperationsProvider): String = toString()

@OptIn(ExperimentalTime::class)
actual fun PlatformFile.modifiedAtMs(): Long? =
    runCatching { lastModified().toEpochMilliseconds() }.getOrNull()?.takeIf { it > 0 }
