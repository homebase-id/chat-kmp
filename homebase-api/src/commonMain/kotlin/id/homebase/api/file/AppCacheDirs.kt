package id.homebase.api.file

import okio.FileSystem
import okio.Path.Companion.toPath

object AppCacheDirs {
    const val SCRATCH_DIR_NAME: String = "hb-scratch"
    const val HLS_DIR_PREFIX: String = "hls_"

    const val DOWNLOADS: String = "downloads"
    const val EXPORT: String = "export"
    const val HLS: String = "hls"
    const val MEDIA_WORK: String = "media-work"
    const val PICKER_COPIES: String = "picker-copies"
    const val AUDIO: String = "audio"
    const val SHARE_INBOUND: String = "share-inbound"
    const val VIEW_ONCE: String = "view-once"

    fun scratchRoot(cacheDir: String): String = cacheDir.trimEnd('/') + "/" + SCRATCH_DIR_NAME

    fun scratchPath(cacheDir: String, sub: String): String = scratchRoot(cacheDir) + "/" + sub

    fun scratchDir(cacheDir: String, sub: String, fileSystem: FileSystem = systemFileSystem): String =
        scratchPath(cacheDir, sub).also { fileSystem.createDirectories(it.toPath()) }
}

fun FileOperationsProvider.scratchDir(sub: String): String =
    AppCacheDirs.scratchDir(getCacheDirectory(), sub)
