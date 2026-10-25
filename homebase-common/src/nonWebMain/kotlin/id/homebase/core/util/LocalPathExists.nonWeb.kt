package id.homebase.core.util

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

actual fun localPathExists(path: String): Boolean = SystemFileSystem.exists(Path(path))
