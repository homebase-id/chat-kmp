package id.homebase.core.di

import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.settings.createSettings
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<FileOperationsProvider> { JvmFileOperationsProvider() }
    single { createSettings() }
    single {
        // Note: No disk cache - DriveFileProviderCached handles encrypted disk caching
        // Coil's memory cache is still enabled by default for fast UI redraws
        ImageLoader.Builder(PlatformContext.INSTANCE)
                .components { add(HomebaseImageFetcher.Factory(get())) }
                .build()
    }
}
