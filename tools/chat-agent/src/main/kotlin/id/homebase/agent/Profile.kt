package id.homebase.agent

import id.homebase.api.file.JvmFileSystemUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

internal val DIR_PERMS = PosixFilePermissions.fromString("rwx------")
internal val FILE_PERMS = PosixFilePermissions.fromString("rw-------")

object Profile {
    const val APP_ID = "b7f3c1e2a94d4e6f8a1d5c0e9b2f7a36"
    const val APP_NAME = "Chat Agent"
    const val APP_SLUG = "chat-agent"

    // Must run before anything touches SecureStorage, which resolves its directory once.
    fun select(name: String) {
        require(Regex("[a-z0-9_-]+").matches(name)) { "invalid profile name '$name'" }
        System.setProperty(JvmFileSystemUtil.DATA_DIR_OVERRIDE_PROPERTY, dataDir(name).absolutePath)
        harden(dataDir(name))
    }

    fun harden(dir: File) {
        dir.mkdirs()
        dir.parentFile?.let { runCatching { Files.setPosixFilePermissions(it.toPath(), DIR_PERMS) } }
        dir.walkTopDown().forEach {
            runCatching { Files.setPosixFilePermissions(it.toPath(), if (it.isDirectory) DIR_PERMS else FILE_PERMS) }
        }
    }

    fun dataDir(
        name: String,
        osName: String = System.getProperty("os.name"),
        home: String = System.getProperty("user.home"),
        env: Map<String, String> = System.getenv(),
    ): File = File(baseDir(osName, home, env), name)

    fun baseDir(osName: String, home: String, env: Map<String, String>): File {
        env["CHAT_AGENT_HOME"]?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val os = osName.lowercase()
        return when {
            "mac" in os -> File(home, "Library/Application Support/HomebaseChatAgent")
            "win" in os -> File(env["APPDATA"]?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Roaming").path, "HomebaseChatAgent")
            else -> File(env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() } ?: File(home, ".local/share").path, "homebase-chat-agent")
        }
    }
}
