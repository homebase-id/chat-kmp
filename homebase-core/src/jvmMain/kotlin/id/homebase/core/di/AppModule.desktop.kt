package id.homebase.core.di

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.settings.createSettings
import java.io.File
import okio.Path.Companion.toOkioPath
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { createSettings() }
    single {
        ImageLoader.Builder(PlatformContext.INSTANCE)
                .components { add(HomebaseImageFetcher.Factory(get())) }
                .diskCache {
                    val cacheDir =
                            File(System.getProperty("user.home"), ".cache/homebase/image_cache")
                    DiskCache.Builder()
                            .directory(cacheDir.toOkioPath())
                            .maxSizePercent(0.02)
                            .build()
                }
                .build()
    }

    single<FileOperationsProvider> { JvmFileOperationsProvider() }
}
