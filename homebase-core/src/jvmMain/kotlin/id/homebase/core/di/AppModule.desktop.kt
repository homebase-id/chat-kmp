package id.homebase.core.di

import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.core.audio.AudioPlayer
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.audio.JvmAudioPlayer
import id.homebase.core.audio.JvmAudioRecorder
import id.homebase.core.gallery.JvmGalleryManager
import id.homebase.core.gallery.PlatformGalleryManager
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.image.PublicImageFetcher
import id.homebase.core.settings.createSettings
import id.homebase.core.util.JvmPlatformInfo
import id.homebase.core.util.PlatformInfo
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<FileOperationsProvider> { JvmFileOperationsProvider() }
    single { createSettings() }
    single<PlatformGalleryManager> { JvmGalleryManager() }
    single<PlatformInfo> { JvmPlatformInfo() }
    single<AudioRecorder> { JvmAudioRecorder() }
    single<AudioPlayer> { JvmAudioPlayer() }
    single {
        // Note: No disk cache - DriveFileProviderCached handles encrypted disk caching
        // Coil's memory cache is still enabled by default for fast UI redraws
        ImageLoader.Builder(PlatformContext.INSTANCE)
                .components {
                    add(HomebaseImageFetcher.Factory(get()))
                    add(PublicImageFetcher.Factory(get()))
                }
                .build()
    }
}
