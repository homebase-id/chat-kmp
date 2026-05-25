package id.homebase.core.di

import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.video.VideoFrameDecoder
import id.homebase.api.file.AndroidFileOperationsProvider
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.AndroidDatabaseSizeProbe
import id.homebase.chat.dice.AndroidShakeDetector
import id.homebase.chat.dice.ShakeDetector
import id.homebase.chat.image.PlatformFileFetcher
import id.homebase.api.sync.database.DatabaseSizeProbe
import id.homebase.core.audio.AndroidAudioPlayer
import id.homebase.core.audio.AndroidAudioRecorder
import id.homebase.core.audio.AndroidWaveFormGenerator
import id.homebase.core.audio.AudioPlayer
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.audio.AudioWaveFormGenerator
import id.homebase.core.gallery.AndroidGalleryManager
import id.homebase.core.gallery.GalleryCache
import id.homebase.core.gallery.PlatformGalleryManager
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.image.HomebaseImageKeyer
import id.homebase.core.image.PublicImageFetcher
import id.homebase.core.notifications.KMPNotifierBackend
import id.homebase.core.notifications.NotificationBackend
import id.homebase.core.settings.createSettings
import id.homebase.core.share.ShareCacheStorage
import id.homebase.core.updater.AndroidUpdateAppManager
import id.homebase.core.updater.UpdateAppManager
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
    single(createdAtStart = true) {
        val cache = GalleryCache(get<PlatformGalleryManager>())
        // Refresh whenever the system reports new gallery items. The observer lives
        // for the Application lifetime, mirroring the Koin singleton scope.
        val resolver = androidContext().contentResolver
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val observer = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                cache.refresh()
            }
        }
        resolver.registerContentObserver(
            android.provider.MediaStore.Files.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL
            ),
            true,
            observer,
        )
        cache
    }
    single<PlatformInfo> { AndroidPlatformInfo(androidContext()) }
    single<AudioRecorder> { AndroidAudioRecorder(androidContext()) }
    single<AudioPlayer> { AndroidAudioPlayer() }
    single<AudioWaveFormGenerator> { AndroidWaveFormGenerator() }
    single<DatabaseSizeProbe> { AndroidDatabaseSizeProbe(androidContext()) }
    single<UpdateAppManager> { AndroidUpdateAppManager(androidContext()) }
    single<ShakeDetector> { AndroidShakeDetector(androidContext()) }
    single<NotificationBackend> { KMPNotifierBackend() }
    single(createdAtStart = true) {
        ImageLoader.Builder(androidContext())
                .components {
                    add(HomebaseImageKeyer())
                    add(HomebaseImageFetcher.Factory(get(), get()))
                    add(PublicImageFetcher.Factory(get()))
                    add(PlatformFileFetcher.Factory())
                    add(VideoFrameDecoder.Factory())
                }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(androidContext(), 0.25)
                    .build()
            }
                .diskCache(null)
                .build()
                .also(::assertNoCoilDiskCache)
                .also(::installAsCoilSingleton)
    }
}

// Rewire Coil's default SingletonImageLoader to our Koin-configured instance.
// Without this, any SubcomposeAsyncImage / AsyncImage call that forgets the
// `imageLoader=` argument falls back to Coil's auto-created singleton — a
// separate loader with zero custom fetchers/keyers and a default 250 MB disk
// cache (coil3_disk_cache). That path silently bypasses PublicImageFetcher
// and stores avatar bytes unencrypted in a directory our Storage screen
// cannot see.
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
