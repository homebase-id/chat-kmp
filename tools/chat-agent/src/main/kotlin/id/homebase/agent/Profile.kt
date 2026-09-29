package id.homebase.agent

import id.homebase.api.file.JvmFileSystemUtil
import java.io.File

object Profile {
    const val APP_ID = "b7f3c1e2a94d4e6f8a1d5c0e9b2f7a36"
    const val APP_NAME = "Chat Agent"
    const val APP_SLUG = "chat-agent"

    // Must run before anything touches SecureStorage, which resolves its directory once.
    fun select(name: String) {
        require(Regex("[a-z0-9_-]+").matches(name)) { "invalid profile name '$name'" }
        val home = System.getProperty("user.home")
        val dir = File(home, "Library/Application Support/HomebaseChatAgent/$name")
        System.setProperty(JvmFileSystemUtil.DATA_DIR_OVERRIDE_PROPERTY, dir.absolutePath)
    }
}
