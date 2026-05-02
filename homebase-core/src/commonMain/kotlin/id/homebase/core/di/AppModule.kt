package id.homebase.core.di

import co.touchlab.kermit.Logger
import coil3.ImageLoader
import id.homebase.api.di.apiModule
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.youauth.YouAuthFlowManager
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import id.homebase.auth.login.LoginViewModel
import id.homebase.chat.addgroupmembers.AddGroupMembersViewModel
import id.homebase.chat.archivedconversations.ArchivedConversationsViewModel
import id.homebase.chat.contactinfo.ContactInfoViewModel
import id.homebase.chat.conversationlist.ConversationListViewModel
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.conversationsettings.ConversationSettingsViewModel
import id.homebase.chat.createconversation.CreateConversationViewModel
import id.homebase.chat.createconversationgroup.CreateConversationGroupViewModel
import id.homebase.chat.data.ConversationState
import id.homebase.chat.editconversationgroup.EditConversationGroupViewModel
import id.homebase.chat.groupsettings.GroupSettingsViewModel
import id.homebase.chat.messageinfo.MessageInfoViewModel
import id.homebase.chat.selectmembers.SelectMembersViewModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.PayloadBundleEncryptor
import id.homebase.chat.services.ShareSuggestionDonor
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.chat.services.convo.ConversationLoader
import id.homebase.chat.services.convo.ConversationMapper
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.LocalLastReadUpdater
import id.homebase.chat.services.convo.StatusMessageSender
import id.homebase.chat.services.convo.UnreadCountEnricher
import id.homebase.chat.services.MessageLookup
import id.homebase.chat.services.convo.PostCreateIntroductionPreflightBus
import id.homebase.chat.services.convo.contact.ConnectionCacheRepository
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.convo.contact.DriveContactService
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.core.NotificationActionBridge
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.getFeedPermissionExtensionConfig
import id.homebase.core.config.getPermissionExtensionConfig
import id.homebase.core.config.mandatorySyncDrives
import id.homebase.core.sync.DriveRegistry
import id.homebase.core.connections.ConnectRequestViewModel
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.notifications.NotificationService
import id.homebase.core.notifications.PendingNotificationTap
import id.homebase.core.settings.UserPreferences
import id.homebase.core.share.ShareContentProcessor
import id.homebase.core.share.ShareConversationCacheWriter
import id.homebase.core.sync.BackgroundSyncOrchestrator
import id.homebase.core.ui.navigation.AppViewModel
import id.homebase.core.ui.screens.appearance.AppearanceSettingsViewModel
import id.homebase.core.ui.screens.connections.ConnectionsViewModel
import id.homebase.core.ui.screens.desktop.DesktopViewModel
import id.homebase.core.ui.screens.devmenu.DeveloperMenuViewModel
import id.homebase.core.ui.screens.feed.FeedViewModel
import id.homebase.core.ui.screens.help.HelpViewModel
import id.homebase.core.ui.screens.home.HomeViewModel
import id.homebase.core.ui.screens.loading.AppLoadingViewModel
import id.homebase.core.ui.screens.notifications.NotificationSettingsViewModel
import id.homebase.core.ui.screens.settings.SettingsViewModel
import id.homebase.core.ui.screens.defragmenter.DefragmenterViewModel
import id.homebase.core.ui.screens.defragmenter.service.DefragSource
import id.homebase.core.ui.screens.defragmenter.service.LiveDefragSource
import id.homebase.core.ui.screens.storage.StorageSettingsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val FeedPermissionQualifier = named("feedPermission")

val appModule = module {
    single { UserPreferences(get()) }

    // DriveRegistry reads/writes a cross-device list of optional drives from the user's
    // Chat drive. See id.homebase.core.sync.DriveRegistry for the storage model.
    single {
        val uploader = get<id.homebase.api.client.drives.upload.DriveUploadProvider>()
        val files = get<id.homebase.api.client.drives.files.DriveFileProvider>()
        DriveRegistry(
            credentialsManager = get(),
            databaseManager = get(),
            getFileHeaderByUid = { driveId, uniqueId -> files.getFileHeaderByUid(driveId, uniqueId) },
            uploadFile = { request -> uploader.uploadFile(request) },
            updateFileByUniqueId = { request -> uploader.updateFileByUniqueId(request) },
            eventBus = get(),
        )
    }

    // Seeded with mandatory drives only — optional drives from the registry are cold-loaded
    // into DriveSyncManager by AuthConnectionCoordinator after authentication, because
    // reading the registry requires active credentials (not available at Koin time).
    single {
        DriveSyncManager(
            get(), get(), get(), get(), get(),
            mandatorySyncDrives.associate { it.drive.alias to it.label },
        )
    }

    // Bound here rather than in homebase-api's ApiModule because the logout hook
    // needs platform singletons (Coil ImageLoader, FileOperationsProvider) that
    // don't exist at the homebase-api layer. The hook clears every cache that
    // outlives the identity:
    //   - Coil in-memory image cache: avatars and thumbnails decoded for the
    //     outgoing user must not leak to the next login on the same machine.
    //   - Orphan coil3_disk_cache directory: our ImageLoader sets diskCache(null),
    //     but if a regression ever re-enables it, or a prior install populated
    //     it, we want logout to clean it up. Matches StorageSettingsViewModel's
    //     "Clear caches" button behaviour.
    // DriveFileProviderCached and PublicProfileProviderCached already delete
    // their encrypted disk directories in their own clearCaches(), which
    // YouAuthFlowManager.logout() invokes immediately before this hook.
    single {
        val imageLoader: ImageLoader = get()
        val fileOps: FileOperationsProvider = get()
        val fileSystem = FileSystem.SYSTEM
        YouAuthFlowManager(
            driveSyncManager = get(),
            credentialsManager = get(),
            httpClient = get(),
            driveFileProviderCached = get(),
            publicProfileProviderCached = get(),
            clearPlatformCaches = {
                runCatching { imageLoader.memoryCache?.clear() }
                    .onFailure {
                        Logger.w(tag = "YouAuthFlowManager", throwable = it) {
                            "coil memory cache clear failed on logout"
                        }
                    }
                runCatching {
                    val orphan = "${fileOps.getCacheDirectory()}/coil3_disk_cache".toPath()
                    if (fileSystem.exists(orphan)) fileSystem.deleteRecursively(orphan)
                }.onFailure {
                    Logger.w(tag = "YouAuthFlowManager", throwable = it) {
                        "orphan coil disk cache delete failed on logout"
                    }
                }
            },
        )
    }

    single {
        AuthConnectionCoordinator(
            credentialsManager = get(),
            ownerSessionRepository = get(),
            youAuthFlowManager = get(),
            driveSyncManager = get(),
            outboxSync = get(),
            eventBus = get(),
            databaseManager = get(),
            driveRegistry = get(),
            onPostAuthenticated = {
                // Preload conversations and contacts from local DB while navigation
                // and Compose composition are still in progress, saving ~800ms.
                val conversationStream = get<ConversationStream>()
                conversationStream.start()
                get<ContactService>().start()

                // Let ChatMessageStream skip messages for left conversations
                get<ChatMessageStream>().isConversationLeft = { conversationId ->
                    conversationStream.getConversationById(conversationId)
                        ?.conversationState.let { it == ConversationState.Left || it == ConversationState.Removed }
                }

                // region Recovery: missing or deleted conversation file
                val conversationService = get<ConversationService>()
                conversationStream.onRecoverConversation = { conversationId, originalAuthor ->
                    conversationService.recoverConversation(conversationId, originalAuthor)
                }
                // endregion

                // region Auto-unarchive: incoming message for archived conversation
                conversationStream.onUnarchiveConversation = { conversationId ->
                    conversationService.unarchiveConversation(conversationId)
                }
                // endregion
            }
        )
    }
    singleOf(::BackgroundSyncOrchestrator)

    factoryOf(::PayloadBundleEncryptionService) bind PayloadBundleEncryptor::class
    factoryOf(::OptimisticWriter)

    singleOf(::ShareConversationCacheWriter)
    singleOf(::ShareContentProcessor)
    singleOf(::LocalAttachmentContextStore)

    singleOf(::ConnectionCacheRepository)
    singleOf(::ConnectionService)
    singleOf(::DriveContactService)
    singleOf(::ContactService)
    singleOf(::ConversationStream) bind ConversationLoader::class
    single<UnreadCountEnricher> { get<ConversationStream>() }
    single<id.homebase.chat.services.convo.ConversationParticipantLookup> { get<ConversationStream>() }
    singleOf(::ConversationService)
    single<LocalLastReadUpdater> { get<ConversationService>() }
    // One-shot bus for post-create introduction preflight: CreateConversationGroupViewModel
    // emits after successful group creation, ConversationListViewModel collects and
    // surfaces the IntroducePreflight dialog if any recipient is non-Ready.
    singleOf(::PostCreateIntroductionPreflightBus)
    singleOf(::ChatMessageStream)
    single<MessageLookup> { get<ChatMessageStream>() }
    singleOf(::ShareSuggestionDonor)
    singleOf(::ChatMessageSenderService) bind StatusMessageSender::class
    singleOf(::HomebaseImageLoader)
    singleOf(::ChatMessageActionService)
    // singleOf(::PendingNotificationTap) would force Koin to resolve every
    // constructor parameter from the container — including the Duration TTL
    // and the CoroutineScope, which are intentionally Kotlin-default args.
    // Use the explicit lambda form so the defaults take effect.
    single { PendingNotificationTap() }
    singleOf(::NotificationService)
    singleOf(::ConnectionRequestService)
    singleOf(::NotificationActionBridge)

    single<DefragSource> {
        // Probe for the Defragmenter's classifier: detects whether a
        // conversation file (fileType=8888) is salvageable via
        // ConversationMapper.mapToBasic. The mapper catches its own throws
        // and returns a degraded `ConversationState.Invalid` model in that
        // case, so we treat Invalid as the "unmappable" signal. An actual
        // exception escaping the call (rare) is also treated as unmappable.
        // ConversationMapper is a thin class with credentialsManager + dbm
        // deps; we construct one here rather than wiring it into DI.
        val mapper = ConversationMapper(
            credentialsManager = get(),
            dbm = get(),
        )
        val mapToBasicProbe: suspend (HomebaseFile) -> Throwable? = { file ->
            try {
                val ui = mapper.mapToBasic(file)
                if (ui.conversationState == ConversationState.Invalid) {
                    IllegalStateException("ConversationMapper returned Invalid state")
                } else {
                    null
                }
            } catch (t: Throwable) {
                t
            }
        }
        LiveDefragSource(
            driveSyncManager = get(),
            credentialsManager = get(),
            databaseManager = get(),
            driveFileProvider = get(),
            conversationService = get(),
            mapToBasicProbe = mapToBasicProbe,
        )
    }

    viewModelOf(::AppViewModel)
    viewModelOf(::AppLoadingViewModel)
    viewModelOf(::HomeViewModel)
    viewModel { FeedViewModel(get(), get(), get(FeedPermissionQualifier)) }
    // Manual `viewModel { ... }` rather than viewModelOf because the constructor
    // exceeds Koin's reified-generic helper ceiling (22 params). Adding the 23rd
    // (PostCreateIntroductionPreflightBus) overflowed the helpers; spelling the
    // injections out works fine.
    viewModel {
        ConversationListViewModel(
            conversationStream = get(),
            chatMessageStream = get(),
            chatMessageSenderService = get(),
            chatMessageActionService = get(),
            conversationService = get(),
            userPreferences = get(),
            fileOperationsProvider = get(),
            ownerSessionRepository = get(),
            credentialsManager = get(),
            authConnectionCoordinator = get(),
            audioRecorder = get(),
            audioWaveFormGenerator = get(),
            eventBus = get(),
            contactService = get(),
            connectionService = get(),
            connectionRequestService = get(),
            driveFileProvider = get(),
            shareContentProcessor = get(),
            localVideoContextStore = get(),
            pendingNotificationTap = get(),
            cropResultBus = get(),
            drawResultBus = get(),
            postCreateIntroductionPreflightBus = get(),
        )
    }
    viewModelOf(::ArchivedConversationsViewModel)
    viewModelOf(::CreateConversationViewModel)
    viewModelOf(::CreateConversationGroupViewModel)
    viewModelOf(::SelectMembersViewModel)
    viewModelOf(::MessageInfoViewModel)
    viewModelOf(::ContactInfoViewModel)
    viewModelOf(::ConversationSettingsViewModel)
    viewModelOf(::GroupSettingsViewModel)
    viewModelOf(::AddGroupMembersViewModel)
    viewModelOf(::EditConversationGroupViewModel)
    viewModel { ExtendPermissionViewModel(get(), get(), get(), getPermissionExtensionConfig()) }
    viewModel(FeedPermissionQualifier) { ExtendPermissionViewModel(get(), get(), get(), getFeedPermissionExtensionConfig()) }
    viewModelOf(::SettingsViewModel)
    viewModelOf(::NotificationSettingsViewModel)
    viewModelOf(::DeveloperMenuViewModel)
    viewModelOf(::AppearanceSettingsViewModel)
    viewModelOf(::StorageSettingsViewModel)
    viewModelOf(::DefragmenterViewModel)
    viewModelOf(::HelpViewModel)
    viewModelOf(::ConnectionsViewModel)
    viewModelOf(::ConnectRequestViewModel)
    viewModelOf(::LoginViewModel)
    viewModelOf(::DesktopViewModel)
}

// Common module that each platform will implement
expect fun platformModule(): Module

/** All Koin modules for the application. */
val allModules = listOf(
    platformModule(),
    apiModule,
    appModule,
    id.homebase.imageeditor.ui.di.imageEditorModule,
)
