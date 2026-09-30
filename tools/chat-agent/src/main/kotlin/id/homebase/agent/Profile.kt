package id.homebase.agent

import id.homebase.api.file.JvmFileSystemUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

private val DIR_PERMS = PosixFilePermissions.fromString("rwx------")
private val FILE_PERMS = PosixFilePermissions.fromString("rw-------")

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

    fun dataDir(name: String): File =
        File(System.getProperty("user.home"), "Library/Application Support/HomebaseChatAgent/$name")
}
