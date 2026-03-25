package id.homebase.core.di

import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.WebFileOperationsProvider
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.settings.createSettings
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { createSettings() }
    single<FileOperationsProvider> { WebFileOperationsProvider() }
    single { id.homebase.core.share.ShareCacheStorage() }
    single {
        ImageLoader.Builder(PlatformContext.INSTANCE)
                .components { add(HomebaseImageFetcher.Factory(get())) }
                .build()
    }
}
