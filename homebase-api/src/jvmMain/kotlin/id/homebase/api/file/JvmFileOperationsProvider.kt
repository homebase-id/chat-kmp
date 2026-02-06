package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider
import io.ktor.utils.io.streams.asInput
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JvmFileOperationsProvider : FileOperationsProvider {
    override fun openFileInput(path: String): InputProvider {
        val inputProvider = InputProvider { File(path).inputStream().asInput() }
        return inputProvider
    }

    override suspend fun readFileBytes(path: String): ByteArray =
            withContext(Dispatchers.IO) { File(path).readBytes() }

    override fun deleteTempFile(path: String): Boolean {
        val file = File(path)

        if (!file.exists() || file.isDirectory) return true

        return runCatching {
            if (file.delete()) {
                true
            } else {
                file.deleteOnExit()
                false
            }
        }.getOrDefault(false)
    }


    override fun getCacheDirectory(): String {
        val osName = System.getProperty("os.name").lowercase()
        val userHome = System.getProperty("user.home")

        val cacheDir =
                when {
                    osName.contains("mac") -> File(userHome, "Library/Caches/homebase-chat")
                    osName.contains("win") -> {
                        val localAppData =
                                System.getenv("LOCALAPPDATA")
                                        ?: File(userHome, "AppData/Local").absolutePath
                        File(localAppData, "homebase-chat/cache")
                    }
                    else -> { // Linux and other Unix-like systems
                        val xdgCache =
                                System.getenv("XDG_CACHE_HOME")
                                        ?: File(userHome, ".cache").absolutePath
                        File(xdgCache, "homebase-chat")
                    }
                }

        return cacheDir.absolutePath
    }

    override fun getFileSize(path: String): Long {
        val file = File(path)
        return if (file.exists() && file.isFile) file.length() else 0L
    }

}
