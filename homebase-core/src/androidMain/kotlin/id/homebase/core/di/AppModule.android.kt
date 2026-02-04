package id.homebase.core.di

import coil3.ImageLoader
import id.homebase.api.file.AndroidFileOperationsProvider
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.settings.createSettings
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<FileOperationsProvider> { AndroidFileOperationsProvider(androidContext()) }
    single { createSettings(androidContext()) }
    single {
        ImageLoader.Builder(androidContext())
                .components { add(HomebaseImageFetcher.Factory(get())) }
                .build()
    }
}
