package id.homebase.core.clipboard

import io.github.vinceglb.filekit.PlatformFile

actual fun platformFileFromPath(path: String): PlatformFile {
    // Web has no filesystem path concept; this code path is unreachable
    // because getImageFromClipboard() always returns null on web.
    error("platformFileFromPath is not supported on web")
}
