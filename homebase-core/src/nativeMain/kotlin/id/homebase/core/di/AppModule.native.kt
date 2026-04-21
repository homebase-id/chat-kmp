package id.homebase.core.di

import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.IOSFileOperationsProvider
import id.homebase.api.sync.database.DatabaseSizeProbe
import id.homebase.api.sync.database.NativeDatabaseSizeProbe
import id.homebase.core.audio.AudioPlayer
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.audio.AudioWaveFormGenerator
import id.homebase.core.audio.IOSAudioPlayer
import id.homebase.core.audio.IOSAudioRecorder
import id.homebase.core.audio.IOSWaveFormGenerator
import id.homebase.core.gallery.IOSGalleryManager
import id.homebase.core.gallery.PlatformGalleryManager
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.image.HomebaseImageKeyer
import id.homebase.core.image.PHAssetFetcher
import id.homebase.core.image.PublicImageFetcher
import id.homebase.core.settings.createSettings
import id.homebase.core.share.ShareCacheStorage
import id.homebase.core.util.IOSPlatformInfo
import id.homebase.core.util.PlatformInfo
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<FileOperationsProvider> { IOSFileOperationsProvider() }
    single { ShareCacheStorage() }
    single { createSettings() }
    single<PlatformGalleryManager> { IOSGalleryManager() }
    single<PlatformInfo> { IOSPlatformInfo() }
    single<AudioRecorder> { IOSAudioRecorder() }
    single<AudioPlayer> { IOSAudioPlayer() }
    single<AudioWaveFormGenerator> { IOSWaveFormGenerator() }

    single<DatabaseSizeProbe> { NativeDatabaseSizeProbe() }
    single(createdAtStart = true) {
        ImageLoader.Builder(PlatformContext.INSTANCE)
                .components {
                    add(HomebaseImageKeyer())
                    add(PHAssetFetcher.Factory())
                    add(HomebaseImageFetcher.Factory(get(), get()))
                    add(PublicImageFetcher.Factory(get()))
                }
                .diskCache(null)
                .build()
                .also(::assertNoCoilDiskCache)
    }
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
