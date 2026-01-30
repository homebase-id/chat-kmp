package id.homebase.core.di

import coil3.ImageLoader
import coil3.disk.DiskCache
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.settings.createSettings
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { createSettings(androidContext()) }
    single {
        ImageLoader.Builder(androidContext())
                .components { add(HomebaseImageFetcher.Factory(get())) }
                .diskCache {
                    DiskCache.Builder()
                            .directory(
                                    androidContext().cacheDir.resolve("image_cache").toOkioPath()
                            )
                            .maxSizePercent(0.02)
                            .build()
                }
                .build()
    }
}
