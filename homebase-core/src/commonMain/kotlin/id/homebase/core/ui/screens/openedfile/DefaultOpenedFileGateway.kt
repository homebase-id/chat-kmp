package id.homebase.core.ui.screens.openedfile

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.coroutines.supervisedScope
import id.homebase.api.youauth.PermissionCheckResult
import id.homebase.api.youauth.PermissionExtensionManager
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.core.clipboard.platformFileFromPath
import id.homebase.core.config.getFilesyncPermissionExtensionConfig
import id.homebase.core.filesync.FilesyncPreferences
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.ui.screens.filesync.FilesyncCrumb
import id.homebase.core.ui.screens.filesync.FilesyncError
import id.homebase.core.ui.screens.filesync.FilesyncNode
import id.homebase.core.ui.screens.filesync.FilesyncRepository
import id.homebase.core.ui.screens.filesync.FilesyncUploadQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

class DefaultOpenedFileGateway(
    youAuth: YouAuthFlowManager,
    private val drives: DriveQueryProvider,
    private val repository: FilesyncRepository,
    private val queue: FilesyncUploadQueue,
    private val preferences: FilesyncPreferences,
    private val credentials: CredentialsManager,
    private val securityContext: SecurityContextProvider,
) : OpenedFileGateway {

    override val signedIn: StateFlow<Boolean> = youAuth.authState
        .map { it is YouAuthState.Authenticated }
        .stateIn(supervisedScope("opened-file"), SharingStarted.Eagerly, youAuth.authState.value is YouAuthState.Authenticated)

    override val errors: Flow<FilesyncError> get() = queue.errors

    // The check Filesync itself runs: installed on the drive, and this token holds the drive grant.
    override suspend fun filesyncReady(): Boolean {
        val active = credentials.getActiveCredentials() ?: return false
        val config = getFilesyncPermissionExtensionConfig(isInstalled = { drives.isAppRegistered(FilesyncProtocol.AppSlug) })
        return PermissionExtensionManager.create(securityContext, active.domain.domainName).getMissingPermissions(config) == PermissionCheckResult.AllGranted
    }

    override suspend fun rememberedFolder(): List<FilesyncCrumb>? {
        val stored = preferences.lastSaveFolder() ?: return null
        return runCatching {
            Json.decodeFromString<List<StoredCrumb>>(stored.decodeToString()).map { FilesyncCrumb(Uuid.parse(it.id), it.name) }
        }.getOrNull()?.takeIf { it.firstOrNull()?.nodeId == FilesyncProtocol.RootNodeId }
    }

    override suspend fun remember(path: List<FilesyncCrumb>) {
        preferences.setLastSaveFolder(Json.encodeToString(path.map { StoredCrumb(it.nodeId.toString(), it.name) }).encodeToByteArray())
    }

    override suspend fun listFolders(parent: Uuid): List<FilesyncNode> {
        val listed = repository.listFolder(parent)
        return listed.nodes.values.filter { it.takesMoves(listed.handles[it.nodeId]?.writable == true) }
    }

    override suspend fun enqueue(folderId: Uuid, path: String): Boolean = queue.add(folderId, listOf(platformFileFromPath(path))).await() > 0

    override fun waitForSignIn(request: OpenedFileRequest) = OpenedFileInbox.waitForSignIn(request)

    override fun discard(path: String) {
        discardStaged(path)
        OpenedFileInbox.clear(path)
    }

    @Serializable
    private class StoredCrumb(val id: String, val name: String?)
}
