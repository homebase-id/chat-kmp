package id.homebase.api.sync.database

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal actual fun deleteSqliteFileIfExists(path: String) {
    SystemFileSystem.delete(Path(path), mustExist = false)
}
