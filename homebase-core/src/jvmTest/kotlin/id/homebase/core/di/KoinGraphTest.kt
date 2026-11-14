package id.homebase.core.di

import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.verify.definition
import org.koin.test.verify.injectedParameters
import org.koin.test.verify.verify
import java.io.File
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertTrue

// The desktop app's whole graph: a constructor parameter nothing provides crashes the app at the first screen that needs it.
class KoinGraphTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun everyDefinitionCanBeBuiltFromTheGraph() {
        module { includes(allModules) }.verify(
            // Parameters a definition passes in by hand rather than resolving from the graph.
            injections = injectedParameters(
                definition<id.homebase.chat.calling.CallWindowModel>(kotlin.Function1::class),
                definition<id.homebase.api.sync.database.DatabaseManager>(kotlin.Function0::class),
                definition<io.ktor.client.HttpClient>(io.ktor.client.engine.HttpClientEngine::class),
                definition<id.homebase.api.client.contacts.AvatarResolver>(kotlinx.coroutines.flow.StateFlow::class),
                definition<id.homebase.api.client.websockets.StunUrlStore>(kotlinx.coroutines.flow.Flow::class),
                definition<id.homebase.core.moments.services.MomentsUserStateStore>(kotlin.Function3::class, kotlin.Function2::class),
                definition<id.homebase.core.location.tracking.LocationFixRouter>(kotlin.Function0::class, kotlin.Function3::class, kotlin.Function2::class),
                definition<id.homebase.chat.services.livelocation.LiveLocationShareService>(kotlin.Function4::class),
                definition<id.homebase.chat.services.calling.CallService>(kotlin.Function5::class, id.homebase.chat.services.calling.CallEngineFactory::class, kotlin.Function2::class),
                definition<id.homebase.core.location.LocationService>(id.homebase.core.location.tracking.OneShotLocationProvider::class),
                definition<id.homebase.core.sync.DriveRegistry>(kotlin.Function3::class, kotlin.Function2::class),
                definition<id.homebase.api.sync.DriveSyncManager>(kotlin.collections.Map::class),
                definition<id.homebase.core.session.IdentitySessionScope>(org.koin.core.Koin::class),
                definition<id.homebase.core.avatars.OwnerAvatarStatus>(kotlinx.coroutines.flow.Flow::class),
                definition<id.homebase.core.sync.BackgroundSyncOrchestrator>(kotlinx.coroutines.flow.StateFlow::class),
                definition<id.homebase.chat.services.ChatMessageStream>(kotlin.Function0::class),
                definition<id.homebase.core.ui.screens.card.CardRepository>(id.homebase.core.ui.screens.card.CardAttributeStore::class),
                definition<id.homebase.chat.agentui.AgentUiTreeLoader>(kotlin.Function4::class),
                definition<id.homebase.chat.viewonce.ViewOncePayloadLoader>(kotlin.Function0::class, kotlin.Function3::class),
                definition<id.homebase.core.notifications.NotificationService>(kotlinx.coroutines.flow.StateFlow::class, kotlinx.coroutines.flow.Flow::class),
                definition<id.homebase.core.share.IncomingLinks>(id.homebase.core.notifications.AppNavigator::class),
                definition<id.homebase.core.upgrade.PendingUpgradeManager>(kotlin.Function1::class),
                definition<id.homebase.photobase.backup.PhotoBackupController>(id.homebase.photobase.backup.PhotoBackupRun::class, id.homebase.photobase.backup.BackupSettingsStore::class, id.homebase.photobase.backup.BackupPushSchedule::class, id.homebase.photobase.PhotobaseConfigFile::class, id.homebase.photobase.backup.BackupOutboxResults::class, id.homebase.photobase.backup.BackupTriggers::class),
                definition<id.homebase.photobase.backup.PhotoBackupEntry>(kotlin.Function0::class),
                definition<id.homebase.api.client.drives.cache.DedicatedThumbCache>(kotlin.uuid.Uuid::class),
                definition<id.homebase.photobase.gallery.PhotoThumbnailPrefetcher>(id.homebase.photobase.gallery.PhotoGalleryPageSource::class, kotlin.Function1::class, kotlin.Function3::class),
                definition<id.homebase.core.ui.screens.filesync.FilesyncShareUpkeep>(kotlinx.coroutines.flow.StateFlow::class),
                definition<id.homebase.core.ui.screens.filesync.FilesyncUploadQueue>(kotlin.Function4::class),
                definition<id.homebase.chat.calling.CallViewModel>(id.homebase.chat.services.calling.CallSession::class),
                definition<id.homebase.chat.calling.history.CallsViewModel>(id.homebase.chat.services.calling.history.CallHistorySource::class, kotlinx.coroutines.flow.Flow::class, id.homebase.chat.services.calling.CallSession::class, kotlin.Boolean::class, id.homebase.chat.calling.history.CallLogDeleter::class),
                definition<id.homebase.chat.calling.history.NewCallViewModel>(kotlinx.coroutines.flow.Flow::class, id.homebase.chat.services.calling.CallSession::class, kotlin.Boolean::class),
                definition<id.homebase.chat.createconversationgroup.CreateConversationGroupViewModel>(androidx.lifecycle.SavedStateHandle::class),
                definition<id.homebase.chat.messageinfo.MessageInfoViewModel>(androidx.lifecycle.SavedStateHandle::class),
                definition<id.homebase.chat.conversationsettings.ConversationSettingsViewModel>(androidx.lifecycle.SavedStateHandle::class),
                definition<id.homebase.chat.conversationmedia.ConversationMediaViewModel>(androidx.lifecycle.SavedStateHandle::class),
                definition<id.homebase.chat.groupsettings.GroupSettingsViewModel>(androidx.lifecycle.SavedStateHandle::class),
                definition<id.homebase.chat.addgroupmembers.AddGroupMembersViewModel>(androidx.lifecycle.SavedStateHandle::class),
                definition<id.homebase.chat.editconversationgroup.EditConversationGroupViewModel>(androidx.lifecycle.SavedStateHandle::class),
                definition<id.homebase.chat.conversationlist.ExtendPermissionViewModel>(id.homebase.api.youauth.PermissionExtensionConfig::class),
                definition<id.homebase.core.ui.screens.location.share.ShareLocationViewModel>(kotlin.uuid.Uuid::class),
                definition<id.homebase.core.ui.screens.contactbook.CircleMemberPickerViewModel>(kotlin.uuid.Uuid::class),
                definition<id.homebase.core.ui.screens.contactbook.ShareContactPickerViewModel>(kotlin.uuid.Uuid::class),
                definition<id.homebase.core.ui.screens.contactbook.detail.ContactDetailViewModel>(androidx.lifecycle.SavedStateHandle::class),
                definition<id.homebase.core.ui.screens.location.devices.FindDeviceViewModel>(kotlin.uuid.Uuid::class),
                definition<id.homebase.core.ui.screens.feed.PostDetailViewModel>(kotlin.uuid.Uuid::class),
                definition<id.homebase.core.ui.screens.moments.MomentDetailViewModel>(kotlin.uuid.Uuid::class),
                definition<id.homebase.core.ui.screens.contactbook.CreateCircleViewModel>(id.homebase.core.ui.screens.contactbook.CircleAccess::class),
                definition<id.homebase.photobase.PhotobaseUninstallCleanup>(kotlin.Function1::class),
                definition<id.homebase.photobase.ui.PhotoGalleryViewModel>(id.homebase.photobase.gallery.PhotoGalleryPageSource::class),
                definition<id.homebase.core.ui.screens.profile.ProfileAvatarEditViewModel>(id.homebase.core.ui.screens.profile.AvatarOwnerSession::class),
                definition<id.homebase.core.ui.screens.card.ProfileCardViewModel>(kotlin.Function1::class),
                definition<id.homebase.core.ui.screens.openedfile.OpenedFileViewModel>(id.homebase.core.ui.screens.openedfile.OpenedFileRequest::class),
                definition<id.homebase.core.ui.screens.vault.note.VaultNoteEditorViewModel>(kotlin.uuid.Uuid::class),
                definition<id.homebase.imageeditor.ui.CropEditorViewModel>(kotlin.uuid.Uuid::class),
                definition<id.homebase.imageeditor.ui.DrawEditorViewModel>(kotlin.uuid.Uuid::class)
            ),
        )
    }

    // verify() treats a defaulted parameter as optional, but singleOf(::X) can't use the default: the app crashes.
    @OptIn(KoinInternalApi::class)
    @Test
    fun aClassKoinBuildsFromItsConstructorHasNoDefaultsItWouldIgnore() {
        val skip = setOf("build", ".git", ".gradle", "node_modules", ".claude")
        val byConstructor = File("..").walkTopDown().onEnter { it.name !in skip }
            .filter { it.isFile && it.extension == "kt" && Regex("/src/[A-Za-z]*[Mm]ain/").containsMatchIn(it.invariantSeparatorsPath) }
            .flatMap { file -> file.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") } }
            .flatMap { Regex("""\b(?:single|factory|viewModel|scoped)Of\(::(\w+)""").findAll(it).map { m -> m.groupValues[1] } }
            .toSet()
        fun Module.withIncluded(): List<Module> = listOf(this) + includedModules.flatMap { it.withIncluded() }
        val definitions = allModules.flatMap { it.withIncluded() }.flatMap { it.mappings.values }.map { it.beanDefinition }
        val provided = definitions.flatMap { listOf(it.primaryType) + it.secondaryTypes }.toSet()
        val built = definitions.map { it.primaryType }.filter { it.simpleName in byConstructor }.distinct()

        assertTrue(built.size >= 10, "found ${built.size} constructor-built classes; the source scan is broken")
        // A defaulted parameter of a type the graph provides is injected; any other one is a crash.
        val offenders = built.filter { k ->
            k.primaryConstructor?.parameters.orEmpty().any { it.isOptional && it.type.classifier !in provided }
        }
        assertTrue(offenders.isEmpty(), "Koin ignores these constructors' default values; inject the value or build it in a lambda: $offenders")
    }
}
