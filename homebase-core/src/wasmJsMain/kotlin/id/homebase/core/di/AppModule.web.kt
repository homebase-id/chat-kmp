package id.homebase.core.di

import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.WebFileOperationsProvider
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseSizeProbe
import id.homebase.api.sync.database.DefaultDatabaseSizeProbe
import id.homebase.chat.dice.ShakeDetector
import id.homebase.chat.image.PlatformFileFetcher
import id.homebase.core.audio.AudioPlayer
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.audio.AudioWaveFormGenerator
import id.homebase.core.audio.getAudioPlayer
import id.homebase.core.diagnostics.DiagnosticsCrashTrigger
import id.homebase.core.diagnostics.NoOpDiagnosticsCrashTrigger
import id.homebase.core.gallery.GalleryCache
import id.homebase.core.gallery.PlatformGalleryManager
import id.homebase.core.image.AnimatedSkiaDecoder
import id.homebase.core.image.HomebaseImageFetcher
import id.homebase.core.image.HomebaseImageKeyer
import id.homebase.core.image.OrientedJpegDecoder
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
    single<DiagnosticsCrashTrigger> { NoOpDiagnosticsCrashTrigger }
    single<AudioRecorder> { WebAudioRecorder() }
    single<AudioPlayer> { getAudioPlayer() }
    single<AudioWaveFormGenerator> { WebAudioWaveFormGenerator() }
    single<DatabaseSizeProbe> { DefaultDatabaseSizeProbe(DatabaseDriverFactory()) }
    single<UpdateAppManager> { WebUpdateAppManager() }
    single<ShakeDetector> { WebShakeDetector() }

    single {
        // Web intentionally does NOT register PublicImageFetcher.Factory here, even though
        // android/desktop/native do. On web that fetcher routes public avatars (/pub/image)
        // through getPublicImage() (app httpClient + cache), which fails on this target and
        // drops EVERY avatar to its initials fallback. Coil's default network fetcher is what
        // makes web avatars load, so leave them on it. Do not add the Factory as a
        // "parity"/consistency fix — it's a regression here (verified). (Unrelated, separate
        // low-frequency cosmetic issue: a host-less "https:///pub/image" can resolve to the
        // app's own origin and fail; adding the Factory does not fix that either.)
        ImageLoader.Builder(PlatformContext.INSTANCE)
                .components {
                    add(HomebaseImageKeyer())
                    add(HomebaseImageFetcher.Factory(get(), get()))
                    add(PlatformFileFetcher.Factory())
                    // EXIF quarter-turn JPEGs are squished by Coil's browser
                    // decode path (see the class doc), so claim them first.
                    add(OrientedJpegDecoder.Factory())
                    // Animates multi-frame GIF/WebP in-house via skiko; static
                    // images fall through to Coil's default Skia decoder.
                    add(AnimatedSkiaDecoder.Factory())
                }
                .build()
    }
}
