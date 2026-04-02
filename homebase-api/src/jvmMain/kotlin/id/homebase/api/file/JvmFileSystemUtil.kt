package id.homebase.api.file

import java.io.File

object JvmFileSystemUtil {

    val appName = if (isDebugMode()) "HomebaseChatDev" else "HomebaseChat"
    val appNameLinux = if (isDebugMode()) "homebase-chat-dev" else "homebase-chat"

    fun getAppDataDirectory(): File {
        val osName = System.getProperty("os.name").lowercase()
        val userHome = System.getProperty("user.home")

        val appDataDir = when {
            osName.contains("mac") -> File(userHome, "Library/Application Support/$appName")
            osName.contains("win") -> File(System.getenv("APPDATA"), appName)
            else -> File(userHome, ".$appNameLinux") // Linux/Unix
        }

        if (!appDataDir.exists()) {
            appDataDir.mkdirs()
        }
        return appDataDir
    }

    fun getCacheDirectory(): File {
        val osName = System.getProperty("os.name").lowercase()
        val userHome = System.getProperty("user.home")
        val cacheDir =
            when {
                osName.contains("mac") -> File(userHome, "Library/Caches/$appName")
                osName.contains("win") -> {
                    val localAppData = System.getenv("LOCALAPPDATA") ?: File(userHome, "AppData/Local").absolutePath
                    File(localAppData, "$appName/cache")
                }

                else -> { // Linux and other Unix-like systems
                    val xdgCache = System.getenv("XDG_CACHE_HOME") ?: File(userHome, ".cache").absolutePath
                    File(xdgCache, appNameLinux)
                }
            }

        return cacheDir
    }

    private fun isDebugMode(): Boolean {
        var debug = false
        assert({ debug = true; true }())
        return debug
    }
}

