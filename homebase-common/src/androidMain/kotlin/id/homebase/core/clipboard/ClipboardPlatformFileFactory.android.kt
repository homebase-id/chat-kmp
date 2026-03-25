package id.homebase.core.clipboard

import io.github.vinceglb.filekit.PlatformFile
import java.io.File

actual fun platformFileFromPath(path: String): PlatformFile {
    return PlatformFile(File(path))
}
