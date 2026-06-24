package id.homebase.core.di

import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.IOSFileOperationsProvider
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseSizeProbe
import id.homebase.api.sync.database.DefaultDatabaseSizeProbe
import id.homebase.chat.dice.IosShakeDetector
import id.homebase.chat.dice.ShakeDetector
import id.homebase.chat.image.PlatformFileFetcher
import id.homebase.chat.widget.video.AVPlayerPool
import id.homebase.core.audio.AudioPlayer
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.audio.AudioWaveFormGenerator
import id.homebase.core.audio.IOSAudioPlayer
import id.homebase.core.audio.IOSAudioRecorder
import id.homebase.core.audio.IOSWaveFormGenerator
import id.homebase.core.gallery.GalleryCache
import id.homebase.core.gallery.IOSGalleryLibraryObserver
import id.homebase.core.gallery.IOSGalleryManager
import id.homebase.core.gallery.PlatformGalleryManager
import id.homebase.core.image.AnimatedSkiaDecoder
import id.homebase.core.image.HeicDecoder
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.image.HomebaseImageKeyer
import id.homebase.core.image.PHAssetFetcher
import id.homebase.core.image.PublicImageFetcher
import id.homebase.core.notifications.KMPNotifierBackend
import id.homebase.core.notifications.NotificationBackend
import id.homebase.core.settings.createSettings
import id.homebase.core.share.ShareCacheStorage
import id.homebase.core.updater.IOSUpdateAppManager
import id.homebase.core.updater.UpdateAppManager
import id.homebase.core.util.IOSPlatformInfo
import id.homebase.core.util.PlatformInfo
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<FileOperationsProvider> { IOSFileOperationsProvider() }
    single { ShareCacheStorage() }
    single { createSettings() }
    single<PlatformGalleryManager> { IOSGalleryManager() }
    single { GalleryCache(get<PlatformGalleryManager>()) }
    // Held as its own singleton so PHPhotoLibrary's weak observer reference is
    // backed by something with Application-scoped lifetime.
    // Explicit <Any> avoids Koin calling KClass on an NSObject subclass (unsupported in K/N).
    single<Any>(createdAtStart = true) { IOSGalleryLibraryObserver(get<GalleryCache>()) }
    single<PlatformInfo> { IOSPlatformInfo() }
    single<AudioRecorder> { IOSAudioRecorder() }
    single<AudioPlayer> { IOSAudioPlayer() }
    single<AudioWaveFormGenerator> { IOSWaveFormGenerator() }
    single<DatabaseSizeProbe> { DefaultDatabaseSizeProbe(DatabaseDriverFactory()) }
    single<UpdateAppManager> { IOSUpdateAppManager(get()) }
    single<ShakeDetector> { IosShakeDetector() }
    // Warm pool of AVPlayer instances reused across moments inline-tile
    // plays when `useInlineOptimizations = true`. Mirrors ExoPlayerPool on
    // Android. Only resolved from VideoPlayerSurface.native.kt — see
    // AVPlayerPool.kt for the rationale.
    single { AVPlayerPool() }
    single<NotificationBackend> { KMPNotifierBackend() }
    single(createdAtStart = true) {
        ImageLoader.Builder(PlatformContext.INSTANCE)
                .components {
                    add(HomebaseImageKeyer())
                    add(PHAssetFetcher.Factory())
                    add(HomebaseImageFetcher.Factory(get(), get()))
                    add(PublicImageFetcher.Factory(get()))
                    add(PlatformFileFetcher.Factory())
                    // Animates multi-frame GIF/WebP in-house via skiko; static
                    // images fall through to Coil's default Skia decoder.
                    add(AnimatedSkiaDecoder.Factory())
                    add(HeicDecoder.Factory())
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
