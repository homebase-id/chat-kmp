package id.homebase.api.file

import okio.FileSystem
import java.io.File

class JvmFileOperationsProvider : OkioFileOperationsProvider(FileSystem.SYSTEM, "") {

    override fun getCacheDirectory(): String = JvmFileSystemUtil.getCacheDirectory().absolutePath

    // Encrypted, ready-to-transmit payloads live in the durable app-data staging dir (#842),
    // NOT the OS-reclaimable cache dir.
    override fun getOutboxStagingDirectory(): String =
        File(JvmFileSystemUtil.getAppDataDirectory(), OUTBOX_STAGING_DIR_NAME)
            .apply { mkdirs() }
            .absolutePath
}
