package id.homebase.core.di

import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.WebFileOperationsProvider
import id.homebase.api.sync.database.DatabaseSizeProbe
import id.homebase.chat.dice.ShakeDetector
import id.homebase.core.audio.AudioPlayer
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.audio.AudioWaveFormGenerator
import id.homebase.core.audio.getAudioPlayer
import id.homebase.core.gallery.GalleryCache
import id.homebase.core.gallery.PlatformGalleryManager
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.image.HomebaseImageKeyer
import id.homebase.core.notifications.NoopNotificationBackend
import id.homebase.core.notifications.NotificationBackend
import id.homebase.core.settings.createSettings
import id.homebase.core.share.ShareCacheStorage
import id.homebase.core.updater.UpdateAppManager
import id.homebase.core.util.PlatformInfo
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { createSettings() }
    single<FileOperationsProvider> { WebFileOperationsProvider() }
    single { ShareCacheStorage() }
    single<NotificationBackend> { NoopNotificationBackend() }

    // Web stubs (see WebPlatformStubs.kt) — inert browser implementations so the Koin graph
    // the view models depend on resolves.
    single<PlatformGalleryManager> { WebGalleryManager() }
    single { GalleryCache(get<PlatformGalleryManager>()) }
    single<PlatformInfo> { WebPlatformInfo() }
    single<AudioRecorder> { WebAudioRecorder() }
    single<AudioPlayer> { getAudioPlayer() }
    single<AudioWaveFormGenerator> { WebAudioWaveFormGenerator() }
    single<DatabaseSizeProbe> { WebDatabaseSizeProbe() }
    single<UpdateAppManager> { WebUpdateAppManager() }
    single<ShakeDetector> { WebShakeDetector() }

    single {
        ImageLoader.Builder(PlatformContext.INSTANCE)
                .components {
                    add(HomebaseImageKeyer())
                    add(HomebaseImageFetcher.Factory(get(), get()))
                }
                .build()
    }
}
