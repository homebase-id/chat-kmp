package id.homebase.core.di

import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.sync.database.DatabaseSizeProbe
import id.homebase.api.sync.database.JvmDatabaseSizeProbe
import id.homebase.core.audio.AudioPlayer
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.audio.AudioWaveFormGenerator
import id.homebase.core.audio.JvmAudioPlayer
import id.homebase.core.audio.JvmAudioRecorder
import id.homebase.core.audio.JvmWaveFormGenerator
import id.homebase.core.gallery.JvmGalleryManager
import id.homebase.core.gallery.PlatformGalleryManager
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.image.HomebaseImageKeyer
import id.homebase.core.image.PublicImageFetcher
import id.homebase.core.settings.createSettings
import id.homebase.core.share.ShareCacheStorage
import id.homebase.core.util.JvmPlatformInfo
import id.homebase.core.util.PlatformInfo
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<FileOperationsProvider> { JvmFileOperationsProvider() }
    single { ShareCacheStorage() }
    single { createSettings() }
    single<PlatformGalleryManager> { JvmGalleryManager() }
    single<PlatformInfo> { JvmPlatformInfo() }
    single<AudioRecorder> { JvmAudioRecorder() }
    single<AudioPlayer> { JvmAudioPlayer() }
    single<AudioWaveFormGenerator> { JvmWaveFormGenerator() }
    single<DatabaseSizeProbe> { JvmDatabaseSizeProbe() }
    single(createdAtStart = true) {
        // Note: No disk cache - DriveFileProviderCached handles encrypted disk caching
        // Coil's memory cache is still enabled by default for fast UI redraws
        ImageLoader.Builder(PlatformContext.INSTANCE)
                .components {
                    add(HomebaseImageKeyer())
                    add(HomebaseImageFetcher.Factory(get(), get()))
                    add(PublicImageFetcher.Factory(get()))
                }
                .diskCache(null)
                .build()
                .also(::assertNoCoilDiskCache)
                .also(::installAsCoilSingleton)
    }
}

// See AppModule.android.kt for the full rationale. tl;dr: SubcomposeAsyncImage
// without an explicit `imageLoader=` falls back to Coil's auto-created
// singleton — a loader with zero of our custom fetchers and a default disk
// cache. Rewiring the singleton to our Koin-configured instance closes that
// leak globally.
private fun installAsCoilSingleton(loader: ImageLoader) {
    SingletonImageLoader.setSafe { loader }
}

private fun assertNoCoilDiskCache(loader: ImageLoader) {
    val disk = loader.diskCache
    if (disk != null) {
        Logger.e(tag = "ImageLoader") {
            "Unexpected Coil disk cache enabled (max=${disk.maxSize} bytes). " +
                    "DriveFileProviderCached handles encrypted disk caching; Coil's disk cache must stay off."
        }
    }
}
