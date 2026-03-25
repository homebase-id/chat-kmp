package id.homebase.core.di

import coil3.ImageLoader
import coil3.memory.MemoryCache
import id.homebase.api.file.AndroidFileOperationsProvider
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.audio.AndroidAudioPlayer
import id.homebase.core.audio.AndroidAudioRecorder
import id.homebase.core.audio.AndroidWaveFormGenerator
import id.homebase.core.audio.AudioPlayer
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.audio.AudioWaveFormGenerator
import id.homebase.core.gallery.AndroidGalleryManager
import id.homebase.core.gallery.PlatformGalleryManager
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.image.PublicImageFetcher
import id.homebase.core.settings.createSettings
import id.homebase.core.share.ShareCacheStorage
import id.homebase.core.util.AndroidPlatformInfo
import id.homebase.core.util.PlatformInfo
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<FileOperationsProvider> { AndroidFileOperationsProvider(androidContext()) }
    single { ShareCacheStorage(androidContext()) }
    single { createSettings(androidContext()) }
    single<PlatformGalleryManager> { AndroidGalleryManager(androidContext()) }
    single<PlatformInfo> { AndroidPlatformInfo(androidContext()) }
    single<AudioRecorder> { AndroidAudioRecorder(androidContext()) }
    single<AudioPlayer> { AndroidAudioPlayer() }
    single<AudioWaveFormGenerator> { AndroidWaveFormGenerator() }
    single {
        ImageLoader.Builder(androidContext())
                .components {
                    add(HomebaseImageFetcher.Factory(get()))
                    add(PublicImageFetcher.Factory(get()))
                }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(androidContext(), 0.25)
                    .build()
            }
                .build()
    }
}
