@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.di

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.IOSFileOperationsProvider
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.settings.createSettings
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

actual fun platformModule(): Module = module {
    single { createSettings() }
    single {
        ImageLoader.Builder(PlatformContext.INSTANCE)
                .components { add(HomebaseImageFetcher.Factory(get())) }
                .diskCache {
                    val fileManager = NSFileManager.defaultManager
                    val cacheUrl =
                            fileManager.URLForDirectory(
                                    directory = NSCachesDirectory,
                                    inDomain = NSUserDomainMask,
                                    appropriateForURL = null,
                                    create = true,
                                    error = null
                            )
                    val cachePath = cacheUrl?.path ?: NSTemporaryDirectory()
                    val cacheDir = "$cachePath/homebase_image_cache"

                    DiskCache.Builder().directory(cacheDir.toPath()).maxSizePercent(0.02).build()
                }
                .build()
    }

    single<FileOperationsProvider> { IOSFileOperationsProvider() }
}
