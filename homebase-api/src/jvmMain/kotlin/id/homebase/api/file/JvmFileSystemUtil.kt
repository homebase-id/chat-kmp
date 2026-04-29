package id.homebase.api.file

import java.io.File

object JvmFileSystemUtil {

    val appName = if (isProductionVersion()) "HomebaseChat" else "HomebaseChatDev"
    val appNameLinux = if (isProductionVersion()) "homebase-chat" else "homebase-chat-dev"

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

    fun isProductionVersion(): Boolean {
        return System.getProperty("app.rdns.name") == "id.homebase.feed"
    }
}

