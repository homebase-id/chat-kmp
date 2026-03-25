package id.homebase.core.clipboard

import io.github.vinceglb.filekit.PlatformFile
import platform.Foundation.NSURL

actual fun platformFileFromPath(path: String): PlatformFile {
    return PlatformFile(NSURL.fileURLWithPath(path))
}
