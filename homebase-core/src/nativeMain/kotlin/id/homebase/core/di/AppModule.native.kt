package id.homebase.core.di

import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.IOSFileOperationsProvider
import id.homebase.core.gallery.IOSGalleryManager
import id.homebase.core.gallery.PlatformGalleryManager
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.image.PHAssetFetcher
import id.homebase.core.settings.createSettings
import id.homebase.core.util.IOSPlatformInfo
import id.homebase.core.util.PlatformInfo
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<FileOperationsProvider> { IOSFileOperationsProvider() }
    single { createSettings() }
    single<PlatformGalleryManager> { IOSGalleryManager() }
    single<PlatformInfo> { IOSPlatformInfo() }
    single {
        ImageLoader.Builder(PlatformContext.INSTANCE)
                .components {
                    add(PHAssetFetcher.Factory())
                    add(HomebaseImageFetcher.Factory(get()))
                }
                .build()
    }
}
