@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.di

import co.touchlab.kermit.Logger
import coil3.ImageLoader
import id.homebase.api.di.apiModule
import id.homebase.api.file.CacheAudit
import id.homebase.api.file.CacheSweeper
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.systemFileSystem
import id.homebase.api.client.upgrade.IdentityUpgradeProvider
import id.homebase.core.config.dataUpgradeReturnUrl

import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.youauth.YouAuthFlowManager
import okio.Path.Companion.toPath
import id.homebase.auth.login.LoginViewModel
import id.homebase.chat.addgroupmembers.AddGroupMembersViewModel
import id.homebase.chat.archivedconversations.ArchivedConversationsViewModel
import id.homebase.chat.conversationlist.ConversationListViewModel
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.conversationmedia.ConversationMediaViewModel
import id.homebase.chat.services.sticker.StickerService
import id.homebase.chat.services.sticker.StickerStream
import id.homebase.chat.conversationsettings.ConversationSettingsViewModel
import id.homebase.chat.createconversation.CreateConversationViewModel
import id.homebase.chat.createconversationgroup.CreateConversationGroupViewModel
import id.homebase.chat.data.ConversationState
import id.homebase.chat.dice.DiceRollPreferences
import id.homebase.chat.editconversationgroup.EditConversationGroupViewModel
import id.homebase.chat.groupsettings.GroupSettingsViewModel
import id.homebase.chat.messageinfo.MessageInfoViewModel
import id.homebase.chat.selectmembers.SelectMembersViewModel
import id.homebase.api.client.liverelay.LiveRelayProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.livelocation.LiveLocationShareService
import id.homebase.chat.services.livelocation.LiveShareReadiness
import id.homebase.core.config.locationLabeledDrive
import id.homebase.core.permissions.isLocationPermissionGranted
import id.homebase.core.ui.screens.location.livelocation.LiveLocationReceiveStore
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.content.MessageContentParser
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.PayloadCacheSeeder
import id.homebase.chat.services.PayloadBundleEncryptor
import id.homebase.chat.services.ShareSuggestionDonor
import id.homebase.chat.services.StatusMessageData
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.chat.services.convo.ConversationLoader
import id.homebase.chat.services.convo.ConversationMapper
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.GroupHealConversationOps
import id.homebase.chat.services.convo.GroupHealService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.LocalLastReadUpdater
import id.homebase.chat.services.convo.StatusMessageSender
import id.homebase.chat.services.MessageLookup
import id.homebase.chat.services.convo.PostCreateIntroductionPreflightBus
import id.homebase.chat.services.convo.contact.ConnectionCacheRepository
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.core.NotificationActionBridge
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.util.PlatformInfo
import id.homebase.core.vault.VaultPreferences
import id.homebase.core.contactbook.ContactBookPreferences
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.core.contactbook.EmergencyContactReceiveService
import id.homebase.core.contactbook.EmergencyContactReconciler
import id.homebase.core.ui.screens.contactbook.ContactBookViewModel
import id.homebase.core.ui.screens.contactbook.detail.ContactDetailViewModel
import id.homebase.core.ui.screens.contactbook.settings.ContactBookSettingsViewModel
import id.homebase.core.ui.screens.vault.VaultService
import id.homebase.core.ui.screens.vault.VaultStream
import id.homebase.core.ui.screens.vault.settings.VaultSettingsViewModel
import id.homebase.core.ui.screens.vault.VaultUploaderService
import id.homebase.core.ui.screens.vault.VaultViewModel
import id.homebase.core.ui.screens.vault.note.VaultNoteEditorViewModel
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import id.homebase.core.config.getFeedPermissionExtensionConfig
import id.homebase.core.config.getMomentsPermissionExtensionConfig
import id.homebase.core.config.getPermissionExtensionConfig
import id.homebase.core.config.getStickerPermissionExtensionConfig
import id.homebase.core.config.mandatorySyncDrives
import id.homebase.core.moments.MomentsPreferences
import id.homebase.core.moments.services.MomentActionService
import id.homebase.core.moments.services.MomentCreateFlowState
import id.homebase.core.moments.services.MomentCommentsService
import id.homebase.core.moments.services.MomentGroupService
import id.homebase.core.moments.services.MomentsFeedService
import id.homebase.core.ui.screens.moments.CreateMomentGroupViewModel
import id.homebase.core.moments.services.MomentsPostSenderService
import id.homebase.core.moments.services.MomentsRecipientLookupService
import id.homebase.core.moments.services.MomentsVideoSession
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.core.config.momentsLabeledDrive
import id.homebase.core.moments.services.MomentsUserStateStore
import id.homebase.core.sync.DriveRegistry
import id.homebase.core.sync.OptionalDriveActivation
import id.homebase.core.connections.ConnectRequestViewModel
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.notifications.NotificationEntry
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
import id.homebase.core.ui.screens.moments.MomentAudienceViewModel
import id.homebase.core.ui.screens.moments.MomentComposeViewModel
import id.homebase.core.ui.screens.moments.MomentDetailViewModel
import id.homebase.core.ui.screens.moments.MomentsFeedViewModel
import id.homebase.core.ui.screens.moments.MomentsSettingsViewModel
import id.homebase.core.ui.screens.moments.MomentsViewModel
import id.homebase.core.ui.screens.notifications.NotificationSettingsViewModel
import id.homebase.core.ui.screens.settings.SettingsViewModel
import id.homebase.core.ui.screens.defragmenter.DefragmenterViewModel
import id.homebase.core.ui.screens.defragmenter.service.DefragSource
import id.homebase.core.ui.screens.defragmenter.service.LiveDefragSource
import id.homebase.core.ui.screens.storage.StorageSettingsViewModel
import id.homebase.core.upgrade.PendingUpgradeManager
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import id.homebase.core.config.getLocationPermissionExtensionConfig
import id.homebase.core.config.getVaultPermissionExtensionConfig
import id.homebase.core.location.EmergencyCircleNotifier
import id.homebase.core.location.GpsRequestReason
import id.homebase.core.location.LocationPreferences
import id.homebase.core.location.tracking.LocationDeviceId
import id.homebase.core.location.tracking.DeviceSensors
import id.homebase.core.location.tracking.createDeviceSensors
import id.homebase.core.location.LocationService
import id.homebase.core.location.tracking.LocationFixRouter
import id.homebase.core.location.tracking.LocationPointStore
import id.homebase.core.location.tracking.createOneShotLocationProvider
import id.homebase.core.location.tracking.LocationTracker
import id.homebase.core.location.tracking.LocationTrackingCoordinator
import id.homebase.core.location.tracking.createLocationTracker
import id.homebase.core.ui.screens.location.LocationTrackUploaderService
import id.homebase.core.ui.screens.location.LocationViewModel
import id.homebase.core.ui.screens.location.devices.FindDeviceViewModel
import id.homebase.core.ui.screens.location.devices.LocationDeviceDirectory
import id.homebase.core.ui.screens.location.history.LocationHistoryViewModel
import id.homebase.core.ui.screens.location.livelocation.LiveLocationViewModel

val VaultPermissionQualifier = named("vaultPermission")

val FeedPermissionQualifier = named("feedPermission")
val MomentsPermissionQualifier = named("momentsPermission")
val StickerPermissionQualifier = named("stickerPermission")
val LocationPermissionQualifier = named("locationPermission")

val appModule = module {
    single { UserPreferences(get()) }
    single { MomentsPreferences(get()) }
    singleOf(::MomentsPostSenderService)
    // User-state store mirrors DriveRegistry's wiring — narrow lambda deps for
    // the write path (DriveFileProvider.getFileHeaderByUid + DriveUploadProvider
    // for the MRU lane; OptimisticWriter + OutboxSync for the watermark lane) so
    // tests can swap in fakes.
    single {
        val files = get<id.homebase.api.client.drives.files.DriveFileProvider>()
        val uploader = get<id.homebase.api.client.drives.upload.DriveUploadProvider>()
        val optimisticWriter = get<OptimisticWriter>()
        val outboxSync = get<OutboxSync>()
        MomentsUserStateStore(
            credentialsManager = get(),
            databaseManager = get(),
            getFileHeaderByUid = { driveId, uniqueId ->
                files.getFileHeaderByUid(driveId, uniqueId)
            },
            uploadFile = { request -> uploader.uploadFile(request) },
            updateFileByUniqueId = { request -> uploader.updateFileByUniqueId(request) },
            stampLocalAppData = { uniqueId, content ->
                optimisticWriter.stampLocalAppDataContent(
                    driveId = momentsLabeledDrive.drive.alias,
                    uniqueId = uniqueId,
                    content = content,
                )
            },
            enqueueOutbox = { request -> outboxSync.tryEnqueue(request).enqueued },
            eventBus = get(),
            scope = get(),
        )
    }
    singleOf(::MomentsRecipientLookupService)
    singleOf(::MomentsFeedService)
    singleOf(::MomentsVideoSession)
    singleOf(::MomentCommentsService)
    singleOf(::MomentActionService)
    singleOf(::MomentGroupService)
    single { MomentCreateFlowState() }
    single { VaultPreferences(get()) }

    // Contact Book add-on (contact manager). Reads from the mandatory Contacts
    // drive; writes through the api-layer ContactsProvider. No optional-drive
    // activation — the drive is always mounted.
    single { ContactBookPreferences(get()) }
    // Read+write contact source of truth lives in homebase-api (ContactRepository); the contact
    // book consumes it directly. No core-side stream/service wrapper.

    // region Location add-on
    single { LocationPreferences(get()) }
    single { LocationDeviceId() }
    single<DeviceSensors> { createDeviceSensors() }
    single { LocationPointStore(databaseManager = get(), deviceSensors = get()) }
    // The single routing seam (#835): every capture path submits here. It owns the persist-vs-relay
    // decision so the policy is greppable in one place instead of smeared across store + DI + share.
    single {
        LocationFixRouter(
            store = get(),
            // The only place (besides the coordinator's acquire gate) that reads the history flag:
            // persist iff history on; a live share's fixes relay but aren't recorded (#823).
            allowHistory = { get<LocationPreferences>().allowLocationHistory.value },
            // History: persist + drain to hour files (rate-gated). Lazy get() avoids the
            // construction-time cycle; runs only when history is on.
            persistAsHistory = { points ->
                get<LocationPointStore>().persistHistory(points)
                get<LocationTrackUploaderService>().flushIfDue()
            },
            // Live: relay the latest fix. Rides this same background-capable seam (NOT a UI Flow) so
            // it fires on cold-woken background points; self-gates on the share roster.
            relayLatest = { point -> get<LiveLocationShareService>().relayLatest(point) },
        )
    }
    single {
        LiveLocationShareService(
            relay = get<LiveRelayProvider>()::relay,
            locationPointStore = get(),
            databaseManager = get(),
            // The coordinator is the single owner of the GPS tracker; the share service only declares
            // that it needs GPS and pokes the coordinator to re-evaluate.
            onLiveShareChanged = { get<LocationTrackingCoordinator>().refreshGpsHold() },
        )
    }
    single { LiveLocationReceiveStore(eventBus = get(), scope = get()) }
    // Readiness gate for "Share live location": activated add-on + location permission, so the chat
    // layer can prompt to set up location instead of starting a share that captures nothing.
    single<LiveShareReadiness> {
        val activation = get<OptionalDriveActivation>()
        LiveShareReadiness {
            activation.isActivated(locationLabeledDrive) &&
                isLocationPermissionGranted()
        }
    }
    single<LocationTracker> { createLocationTracker(get<LocationFixRouter>()) }
    single {
        LocationTrackUploaderService(
            outboxSync = get(),
            optimisticWriter = get(),
            payloadEncryptionService = get(),
            fileOperationsProvider = get(),
            driveFileProvider = get(),
            databaseManager = get(),
            credentialsManager = get(),
            eventBus = get(),
            deviceId = get(),
            optionalDriveActivation = get(),
            scope = get(),
            // Battery saver: defer background uploads (but a foregrounded user, or a >24h
            // un-uploaded backlog, still uploads). #878 follow-up.
            powerSaveMode = { get<DeviceSensors>().isPowerSaveMode() },
            isAppForeground = { get<LocationTrackingCoordinator>().isForeground },
        )
    }
    single {
        LocationTrackingCoordinator(
            preferences = get(),
            tracker = get(),
            scope = get(),
        ).apply {
            // The uploader lives in homebase-core; the coordinator (homebase-common)
            // reaches it through this seam only.
            onFlushDue = { get<LocationTrackUploaderService>().flushIfDue() }
            // Lets the coordinator keep GPS armed for an active live-location share (incl. across a
            // cold start / iOS relaunch) without referencing homebase-chat.
            liveShareActive = { get<LiveLocationShareService>().hasLiveShare() }
            // Force a fresh fix on app-open when stale (#878). Goes through the gated
            // forceCaptureIfTracking() — the single entry for automatic triggers — so the
            // "only when a persistent consumer wants GPS" decision lives in one place (the
            // coordinator's own isCaptureWanted() pre-check just avoids launching when not wanted).
            onForegroundEntry = { get<LocationService>().forceCaptureIfTracking(GpsRequestReason.AppForeground) }
        }
    }
    // The single public entry point for "this device's location" — composes coordinator (acquire) +
    // router (route) + store/permission (access). One-shot fixes route through the router too.
    // The one-shot provider is constructed HERE (not a standalone single) so nothing can inject it
    // directly and bypass getCurrentGps's routing — every fetched fix is guaranteed to be routed.
    single {
        LocationService(
            coordinator = get(),
            router = get(),
            pointStore = get(),
            preferences = get(),
            oneShot = createOneShotLocationProvider(),
            scope = get(),
            // Battery saver: on-demand fixes go cache-only (no radio). #878 follow-up.
            powerSaveMode = { get<DeviceSensors>().isPowerSaveMode() },
        )
    }
    // endregion

    // DriveRegistry reads/writes a cross-device list of optional drives from the user's
    // Chat drive. See id.homebase.core.sync.DriveRegistry for the storage model.
    single {
        val uploader = get<id.homebase.api.client.drives.upload.DriveUploadProvider>()
        val files = get<id.homebase.api.client.drives.files.DriveFileProvider>()
        DriveRegistry(
            credentialsManager = get(),
            databaseManager = get(),
            getFileHeaderByUid = { driveId, uniqueId ->
                files.getFileHeaderByUid(
                    driveId,
                    uniqueId
                )
            },
            uploadFile = { request -> uploader.uploadFile(request) },
            updateFileByUniqueId = { request -> uploader.updateFileByUniqueId(request) },
            eventBus = get(),
        )
    }

    // Mandatory drives (chat, contacts) are baked into the constructor as an invariant
    // of the sync engine. Optional drives from the cross-device registry are mounted
    // dynamically by AuthConnectionCoordinator after authentication, because reading
    // the registry requires active credentials (not available at Koin time).
    single {
        DriveSyncManager(
            get(), get(), get(), get(), get(),
            mandatoryDrives = mandatorySyncDrives.associate { it.drive.alias to it.label },
            // Per-drive fresh-sync policy (sync-back window + custom initial queries) is
            // wired and tested, but no drive opts in yet — chat behaves like every other
            // drive (sync everything). Part 2 re-enables the chat policy below together
            // with the "load older messages when scrolling to the top" feature; until
            // then a fresh login would only see the last N days, which is confusing
            // without that scroll-to-load path. Diagnostics confirmed the window itself
            // is correct (cursor: zero duplicates / no floor breaches at 7/30/60 days).
            //
            // To re-enable, add the imports
            //   id.homebase.api.client.drives.SystemDriveConstants
            //   id.homebase.api.client.drives.query.FileQueryParams
            //   id.homebase.api.sync.DriveSyncPolicy
            //   kotlin.time.Duration.Companion.days
            // and pass:
            // driveSyncPolicies = mapOf(
            //     SystemDriveConstants.chatDrive.alias to DriveSyncPolicy(
            //         fullSyncWindow = 30.days,
            //         initialQueries = listOf(
            //             FileQueryParams(fileType = listOf(ChatProtocol.ConversationFileType)),
            //         ),
            //     ),
            // ),
        )
    }

    // Shared activation primitive for optional add-on drives (Vault, Moments, Location,
    // Stickers) — see OptionalDriveActivation.
    single { OptionalDriveActivation(get(), get()) }

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
        val homebaseImageLoader: HomebaseImageLoader = get()
        val fileOps: FileOperationsProvider = get()
        val pendingUpgradeManager: PendingUpgradeManager = get()
        YouAuthFlowManager(
            driveSyncManager = get(),
            credentialsManager = get(),
            httpClient = get(),
            driveFileProviderCached = get(),
            publicProfileProviderCached = get(),
            clearPlatformCaches = {
                // Logout sweep. In dry-run for the broader cleanup; the orphan
                // coil3_disk_cache is actually deleted by CacheSweeper now — that absorbs
                // the role the standalone safeDeleteRecursively("coil3_disk_cache") line
                // used to play here.
                runCatching {
                    CacheSweeper.sweepAll(CacheAudit.audit(fileOps.getCacheDirectory()))
                }.onFailure {
                    Logger.w(tag = "YouAuthFlowManager", throwable = it) {
                        "logout cache sweep failed"
                    }
                }
                runCatching { imageLoader.memoryCache?.clear() }
                    .onFailure {
                        Logger.w(tag = "YouAuthFlowManager", throwable = it) {
                            "coil memory cache clear failed on logout"
                        }
                    }
                // Decrypted full-payload bytes must not survive the outgoing
                // identity either — same rationale as the Coil clear above.
                runCatching { homebaseImageLoader.clearMemoryCacheAsync() }
                    .onFailure {
                        Logger.w(tag = "YouAuthFlowManager", throwable = it) {
                            "full-payload cache clear failed on logout"
                        }
                    }
                pendingUpgradeManager.reset()
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
            securityContextProvider = get(),
            peerWebSocketManager = get(),
            // Start headless only where the OS can cold-wake us in the background
            // (Android/iOS). Desktop/Web report false → start in foreground mode so
            // a missing promoteToForeground() can't hang the app on "syncing".
            startsHeadless = get<PlatformInfo>().supportsBackgroundWake,
            onPostAuthenticated = {
                // Live Relay receive store: clear any prior identity's positions for a clean
                // slate (they rehydrate from the server's flush-on-connect). Resolved FIRST and
                // independent of the other services so its app-lifetime init{} collector is
                // guaranteed up — a throw in a later location reset() below can't prevent the
                // consumer from existing when relay packets arrive (bug #824). The collector is
                // never cancelled here; logout clears it in-stream via SessionEnded.
                get<LiveLocationReceiveStore>().reset()

                // Preload conversations and contacts from local DB while navigation
                // and Compose composition are still in progress, saving ~800ms.
                val conversationStream = get<ConversationStream>()
                conversationStream.reset()
                conversationStream.start()
                get<ContactService>().start()
                // MRU store before lookup: lookup's combine() reads
                // mruStore.stableKeys, and started-first means the cold-load
                // emits before the lookup builds its first list.
                get<MomentsUserStateStore>().start()
                get<MomentsRecipientLookupService>().start()
                get<MomentsFeedService>().start()
                get<MomentGroupService>().start()
                // Notify peers when our emergency-location circle membership changes (grant/revoke).
                get<EmergencyCircleNotifier>().start()

                // Let ChatMessageStream skip messages for left conversations
                get<ChatMessageStream>().isConversationLeft = { conversationId ->
                    conversationStream.getConversationById(conversationId)
                        ?.conversationState.let { it == ConversationState.Left || it == ConversationState.Removed }
                }

                // Auto-pin (#887) freshly arrived/sent typed messages. Settable hook
                // breaks the ActionService → MessageLookup → ChatMessageStream cycle.
                val chatMessageActionService = get<ChatMessageActionService>()
                get<ChatMessageStream>().autoPinTypedMessage = { messageId, dependencyUniqueId ->
                    chatMessageActionService.pinMessage(messageId, dependencyUniqueId)
                }

                // region Recovery: missing or deleted conversation file
                val conversationService = get<ConversationService>()
                conversationStream.onRecoverConversation = { conversationId, originalAuthor ->
                    conversationService.recoverConversation(conversationId, originalAuthor)
                }
                // endregion

                // region Heal group: incoming GroupHealRequested status
                val groupHealService = get<GroupHealService>()
                conversationStream.onIncomingHealRequest = { status, sender, messageFile ->
                    groupHealService.handleIncomingHealRequest(status, sender, messageFile)
                }
                // endregion

                // region Emergency contact: incoming designation / revocation status messages.
                // A peer adding us to (designation) or removing us from (revocation) their emergency
                // circle posts us a status; the receive service records/clears our can-locate flag for
                // them and consumes the message so a re-delivery can't re-apply stale state.
                val emergencyContactReceive = get<EmergencyContactReceiveService>()
                conversationStream.onEmergencyContactDesignated = { sender, file ->
                    emergencyContactReceive.onDesignated(sender, file)
                }
                conversationStream.onEmergencyContactRevoked = { sender, file ->
                    emergencyContactReceive.onRevoked(sender, file)
                }
                // Background backstop: the live status-message handlers above only fire on the
                // WS-push path, so a designation/revocation that arrived during cold sync (or a
                // dropped event) is never applied. Reconcile both directions against the
                // authoritative temporal-access grant in the background — no screen required.
                get<EmergencyContactReconciler>().start()
                // endregion

                // region Auto-unarchive: incoming message for archived conversation
                conversationStream.onUnarchiveConversation = { conversationId ->
                    conversationService.unarchiveConversation(conversationId)
                }
                // endregion

                get<VaultPreferences>().reset()
                get<VaultStream>().apply { reset(); start() }
                // Contact Book: re-seed prefs + reload the contact list for the new
                // identity (singletons survive logout — clear stale in-memory state).
                get<ContactBookPreferences>().reset()
                get<ContactRepository>().apply { reset(); start() }
                // Hydrate the saved-stickers tray for the new identity (mirror Vault).
                get<id.homebase.chat.services.sticker.StickerStream>().apply { reset(); start() }

                // Location add-on: re-seed prefs from the (possibly wiped) DB,
                // clear in-memory capture state, restart the flusher's outbox
                // observer + retention sweep, and stop the tracker if the wipe
                // turned the master switch off. Order matters: prefs first.
                get<LocationPreferences>().reset()
                get<LocationPointStore>().reset()
                get<LocationTrackUploaderService>().apply { reset(); start() }
                // Live Relay debug-flow: re-seed the live-share roster from this identity's DB FIRST
                // (it must survive app open/kill until expiry), so the coordinator's reset() below sees
                // the right GPS hold. reset() pokes the coordinator via refreshGpsHold().
                get<LiveLocationShareService>().reset()
                get<LocationTrackingCoordinator>().reset()
            }
        )
    }
    single {
        BackgroundSyncOrchestrator(
            credentialsManager = get(),
            driveSyncManager = get(),
            driveFileHttpProvider = get(),
            authConnectionCoordinator = get(),
            // Narrow seam — pass the StateFlow, not the whole YouAuthFlowManager.
            // See BackgroundSyncOrchestrator's class kdoc.
            authState = get<id.homebase.api.youauth.YouAuthFlowManager>().authState,
        )
    }

    factoryOf(::PayloadBundleEncryptionService) bind PayloadBundleEncryptor::class
    factoryOf(::OptimisticWriter)
    // Shared optimistic-send cache seeder — used by Chat, Moments, Vault, Stickers
    // so a just-sent image shows its sharp thumbnail through "finalizing".
    singleOf(::PayloadCacheSeeder)

    singleOf(::ShareConversationCacheWriter)
    singleOf(::ShareContentProcessor)
    singleOf(::LocalAttachmentContextStore)

    singleOf(::ConnectionCacheRepository)
    singleOf(::ConnectionService)
    singleOf(::EmergencyCircleNotifier)
    singleOf(::EmergencyContactReceiveService)
    singleOf(::EmergencyContactReconciler)
    singleOf(::ContactService)
    singleOf(::ConversationStream) bind ConversationLoader::class
    single<id.homebase.chat.services.convo.ConversationParticipantLookup> { get<ConversationStream>() }
    // Manual `single` (not `singleOf(::ConversationService)`) because the
    // ctor carries a `lastReadDebounceMs: Long = 1_000L` test affordance
    // that Koin's reflective binder would try to resolve as a Long
    // singleton (`NoDefinitionFoundException` at app startup). Spelling
    // the injections out lets the Long default through.
    single {
        ConversationService(
            credentialsManager = get(),
            payloadBundleEncryptionService = get(),
            dbm = get(),
            introductionProvider = get(),
            scope = get(),
            outboxSync = get(),
            chatMessageSenderService = get(),
            optimisticWriter = get(),
            conversationStream = get(),
            participantLookup = get(),
            driveSyncManager = get(),
            driveFileProvider = get<id.homebase.api.client.drives.files.DriveFileProvider>(),
            fileOperationsProvider = get(),
        )
    }
    single<LocalLastReadUpdater> { get<ConversationService>() }
    single<GroupHealConversationOps> { get<ConversationService>() }
    singleOf(::GroupHealService)
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
    singleOf(::DiceRollPreferences)
    // singleOf(::PendingNotificationTap) would force Koin to resolve every
    // constructor parameter from the container — including the Duration TTL
    // and the CoroutineScope, which are intentionally Kotlin-default args.
    // Use the explicit lambda form so the defaults take effect.
    single { PendingNotificationTap() }
    // Explicit `single` (not `singleOf`) because the ctor takes a
    // `StateFlow<YouAuthState>` seam that Koin can't resolve reflectively.
    // Same narrow-seam pattern as BackgroundSyncOrchestrator above.
    single {
        NotificationService(
            api = get(),
            scope = get(),
            profileProvider = get(),
            userPreferences = get(),
            credentialsManager = get(),
            pendingNotificationTap = get(),
            notificationBackend = get(),
            eventBus = get(),
            authState = get<id.homebase.api.youauth.YouAuthFlowManager>().authState,
        )
    }
    singleOf(::NotificationEntry)
    single {
        val upgradeProvider = get<IdentityUpgradeProvider>()
        PendingUpgradeManager(
            credentialsManager = get(),
            checkUpgradeStatus = { upgradeProvider.checkUpgradeStatus() },
            dataUpgradeReturnUrl = ::dataUpgradeReturnUrl,
        )
    }
    singleOf(::ConnectionRequestService)
    singleOf(::NotificationActionBridge)
    singleOf(::VaultStream)
    singleOf(::VaultService)
    singleOf(::VaultUploaderService)

    // Sticker library (saved "My Stickers" tray) — mirrors the Vault singles. The
    // Stickers drive is optional/on-demand (mounted lazily by StickerService), so it
    // is NOT in mandatorySyncDrives.
    singleOf(::StickerStream)
    singleOf(::StickerService)

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
        // Detects whether a chat-message file's appData.content can be decoded
        // as the runtime payload type ChatMessageStream.mapToMessageData would
        // pick. Returns null on success, the throwable on failure. Mirrors
        // ChatMessageStream.kt:454-496 so the Defragmenter agrees with what the
        // conversation list actually tries to render — three branches:
        //   1. Status messages → StatusMessageData
        //   2. Typed rich-content (Event=210, DiceRoll=212, future polls/etc.)
        //      → MessageContentParser.parse, which returns non-null for any
        //      known dataType. Even malformed typed content surfaces as
        //      MessageContent.X(null) so the bubble shows an unsupported-format
        //      chip rather than the message vanishing — that's still "decoded"
        //      from the Defragmenter's perspective. The unrecognized-dataType
        //      arm is the parser returning null itself.
        //   3. Everything else (plain text, media, link previews, Location's
        //      dataType 211 which has no typed-parser branch) → MessageAppData.
        val decodeMessageContentProbe: suspend (HomebaseFile) -> Throwable? = { file ->
            val appData = file.fileMetadata.appData
            val content = appData.content
            when {
                content.isNullOrEmpty() -> null
                appData.dataType == ChatProtocol.ChatStatusMessageDataType ->
                    runCatching {
                        OdinSystemSerializer.deserialize<StatusMessageData>(content)
                    }.exceptionOrNull()
                MessageContentParser.parse(appData.dataType, content) != null -> null
                else ->
                    runCatching {
                        OdinSystemSerializer.deserialize<MessageAppData>(content)
                    }.exceptionOrNull()
            }
        }
        LiveDefragSource(
            driveSyncManager = get(),
            credentialsManager = get(),
            databaseManager = get(),
            driveFileProvider = get<id.homebase.api.client.drives.files.DriveFileProvider>(),
            conversationService = get(),
            mapToBasicProbe = mapToBasicProbe,
            decodeMessageContentProbe = decodeMessageContentProbe,
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
            driveFileProvider = get<id.homebase.api.client.drives.files.DriveFileProvider>(),
            shareContentProcessor = get(),
            localVideoContextStore = get(),
            pendingNotificationTap = get(),
            cropResultBus = get(),
            drawResultBus = get(),
            postCreateIntroductionPreflightBus = get(),
            stickerStream = get(),
            stickerService = get(),
            stickerPermissionViewModel = get(StickerPermissionQualifier),
            liveLocationShareService = get(),
            liveShareReadiness = get(),
        )
    }
    viewModelOf(::ArchivedConversationsViewModel)
    viewModelOf(::CreateConversationViewModel)
    viewModelOf(::CreateConversationGroupViewModel)
    viewModelOf(::SelectMembersViewModel)
    viewModelOf(::MessageInfoViewModel)
    viewModelOf(::ConversationSettingsViewModel)
    viewModelOf(::ConversationMediaViewModel)
    viewModelOf(::GroupSettingsViewModel)
    viewModelOf(::AddGroupMembersViewModel)
    viewModelOf(::EditConversationGroupViewModel)
    viewModel { ExtendPermissionViewModel(get(), get(), get(), getPermissionExtensionConfig()) }
    viewModel(FeedPermissionQualifier) { ExtendPermissionViewModel(get(), get(), get(), getFeedPermissionExtensionConfig()) }
    viewModel(MomentsPermissionQualifier) { ExtendPermissionViewModel(get(), get(), get(), getMomentsPermissionExtensionConfig()) }
    // Stickers permission VM — autoCheck=false so the missing-permissions dialog only
    // surfaces once the user actively enters the sticker feature (opens the tray or
    // saves/imports a sticker), mirroring Vault's lazy extend-permissions gate rather
    // than eagerly prompting on app launch.
    viewModel(StickerPermissionQualifier) {
        ExtendPermissionViewModel(
            get(),
            get(),
            get(),
            getStickerPermissionExtensionConfig(),
            autoCheck = false,
        )
    }
    viewModel { MomentsViewModel(get(), get(MomentsPermissionQualifier), get()) }
    viewModelOf(::MomentsSettingsViewModel)
    viewModel(LocationPermissionQualifier) {
        ExtendPermissionViewModel(get(), get(), get(), getLocationPermissionExtensionConfig())
    }
    viewModel {
        LocationViewModel(
            locationPreferences = get(),
            locationPermissionViewModel = get(LocationPermissionQualifier),
            optionalDriveActivation = get(),
            trackingCoordinator = get(),
            pointStore = get(),
            uploaderService = get(),
            deviceDirectory = get(),
            contactRepository = get(),
            connectionService = get(),
            contactService = get(),
            emergencyContactReconciler = get(),
            credentialsManager = get(),
            tracker = get(),
            receiveStore = get(),
            liveShareService = get(),
        )
    }
    viewModelOf(::LocationHistoryViewModel)
    // Manual block (not viewModelOf): the constructor has a `nowMs: () -> Long` param with a default;
    // viewModelOf would try to autowire that Function0 from Koin and fail at creation time.
    viewModel {
        LiveLocationViewModel(
            receiveStore = get(),
            contactService = get(),
            locationPreferences = get(),
            pointStore = get(),
            credentialsManager = get(),
            locationService = get(),
        )
    }
    viewModelOf(::ContactBookViewModel)
    viewModelOf(::ContactDetailViewModel)
    viewModelOf(::ContactBookSettingsViewModel)
    singleOf(::LocationDeviceDirectory)
    viewModel { params ->
        FindDeviceViewModel(
            deviceIdArg = params.getOrNull(),
            deviceDirectory = get(),
            locationPreferences = get(),
        )
    }
    viewModelOf(::MomentComposeViewModel)
    viewModelOf(::MomentAudienceViewModel)
    viewModelOf(::CreateMomentGroupViewModel)
    viewModelOf(::MomentsFeedViewModel)
    viewModel { params ->
        MomentDetailViewModel(
            momentId = params.get(),
            initialPayloadKey = params.getOrNull(),
            feedService = get(),
            commentsService = get(),
            postSender = get(),
            actionService = get(),
            credentialsManager = get(),
            userPreferences = get(),
            momentGroupService = get(),
            conversationStream = get(),
            contactService = get(),
            driveFileProvider = get(),
            fileOperationsProvider = get(),
            recipientLookup = get(),
        )
    }
    viewModel(VaultPermissionQualifier) {
        ExtendPermissionViewModel(
            get(),
            get(),
            get(),
            getVaultPermissionExtensionConfig(),
            autoCheck = false,
        )
    }
    viewModel(FeedPermissionQualifier) {
        ExtendPermissionViewModel(
            get(),
            get(),
            get(),
            getFeedPermissionExtensionConfig()
        )
    }
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
    viewModel {
        VaultViewModel(
            vaultPreferences = get(),
            vaultPermissionViewModel = get(VaultPermissionQualifier),
            vaultStream = get(),
            vaultService = get(),
            vaultUploaderService = get(),
            eventBus = get(),
            optionalDriveActivation = get(),
            driveRegistry = get(),
            localAttachmentStore = get(),
            fileOperationsProvider = get(),
            driveSyncManager = get(),
            cropResultBus = get(),
            drawResultBus = get(),
        )
    }
    viewModelOf(::VaultSettingsViewModel)
    viewModel { params ->
        VaultNoteEditorViewModel(
            sectionId = params[0],
            editEntryId = params.values.getOrNull(1) as? Uuid,
            vaultStream = get(),
            vaultService = get(),
            vaultUploaderService = get(),
            fileOperationsProvider = get(),
        )
    }
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
