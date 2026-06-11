package id.homebase.api.di

import co.touchlab.kermit.Logger
import id.homebase.api.client.HttpClientProvider
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.connections.ConnectionIntroductionProvider
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.connections.ConnectionRequestProvider
import id.homebase.api.client.connections.IntroductionSender
import id.homebase.api.client.contacts.ContactsProvider
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.DriveFileOperationsProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.client.link.LinkPreviewProvider
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.api.client.notifications.PushNotificationApi
import id.homebase.api.client.peer.PeerDriveQueryProvider
import id.homebase.api.client.peer.PeerDriveUploadProvider
import id.homebase.api.client.peer.PeerNotificationProvider
import id.homebase.api.client.peer.PeerWebSocketManager
import id.homebase.api.client.profile.PublicProfileProvider
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.client.upgrade.IdentityUpgradeProvider
import id.homebase.api.file.StartupCacheAudit
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.api.video.VideoPayloadProcessor
import id.homebase.api.video.VideoPrefetchDriveAccess
import id.homebase.api.video.VideoPreloadService
import id.homebase.api.video.VideoPreloader
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.api.youauth.UsernameStorage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val apiModule = module {
    single { DatabaseManager.appDb }

    single<CoroutineScope> {
        val handler = CoroutineExceptionHandler { _, e ->
            Logger.e(throwable = e, tag = "AppScope") { "Unhandled coroutine exception" }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }

    // this creates the HttpClient
    single { HttpClientProvider.create() }

    // Constructed via the lambda DSL (not singleOf) so Kotlin's default values for
    // the compressor/probe params are honored — Koin's constructor DSL would instead
    // try to resolve VideoCompressor/VideoProber from the graph (they aren't bound)
    // and fail at first video send.
    single { VideoPayloadProcessor(get()) }
    singleOf(::VideoPreloader)
    singleOf(::VideoPreloadService)
    singleOf(::CredentialsManager)
    singleOf(::OwnerSessionRepository)
    singleOf(::PublicIdentityRepository)
    singleOf(::DriveFileHttpProvider)
    singleOf(::DriveFileProviderCached)
    single<OutboxUploader> { DriveOutboxUploader(get(), get(), get(), get()) }
    singleOf(::OutboxSync)

    // YouAuthFlowManager is bound in homebase-core's AppModule where the platform
    // singletons (ImageLoader, FileOperationsProvider) needed by its
    // clearPlatformCaches hook are available. See homebase-core/.../AppModule.kt.

    single { UsernameStorage() }

    factoryOf(::DriveQueryProvider)
    factoryOf(::DriveUploadProvider)

    factoryOf(::DriveFileProvider)
    factory<VideoPrefetchDriveAccess> { get<DriveFileProvider>() }
    factoryOf(::DriveFileOperationsProvider)
    factoryOf(::DriveFileGroupReactionProvider)

    factoryOf(::ConnectionNetworkProvider)
    factoryOf(::PeerDriveQueryProvider)
    factoryOf(::PeerDriveUploadProvider)
    factoryOf(::PeerNotificationProvider)
    // Single: one set of peer (owner-hosted) websocket connections per app session; reset on logout
    // via AuthConnectionCoordinator.disconnect(). Uses its own internal scope (default ctor arg).
    single {
        PeerWebSocketManager(
            credentialsManager = get(),
            peerNotificationProvider = get(),
            eventBus = get(),
            databaseManager = get(),
        )
    }
    factoryOf(::ConnectionRequestProvider)
    factoryOf(::ConnectionIntroductionProvider) bind IntroductionSender::class
    factoryOf(::ContactsProvider)
    factoryOf(::IdentityUpgradeProvider)
    singleOf(::PublicProfileProviderCached)
    factoryOf(::PublicProfileProvider)

    factoryOf(::SecurityContextProvider)
    factoryOf(::PushNotificationApi)
    singleOf(::LinkPreviewProvider)
    singleOf(::LocationPreviewProvider)

    single { EventBus() }

    // Eager startup task: logs a one-shot breakdown of the cache directory so an
    // `adb logcat -s CacheAudit:*` capture shows where disk usage is going. The
    // audit work runs off the main thread (see StartupCacheAudit). Diagnostics only.
    single(createdAtStart = true) { StartupCacheAudit(get()) }
}
