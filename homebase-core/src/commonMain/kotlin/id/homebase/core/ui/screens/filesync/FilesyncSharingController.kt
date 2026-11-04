package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.youauth.PermissionCheckResult
import id.homebase.api.youauth.PermissionExtensionConfig
import id.homebase.api.youauth.PermissionExtensionManager
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.config.filesyncAppRegistration
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Turning sharing on: signing in to Filesync too ([FilesyncSession]), and the permission its registration may lack. */
class FilesyncSharingSetup(
    private val flow: YouAuthFlowManager,
    private val httpClient: HttpClient,
    private val appCredentials: CredentialsManager,
) {
    suspend fun identity(): OdinId? = appCredentials.getActiveCredentials()?.domain

    suspend fun restore(): Boolean = identity()?.let { FilesyncSession.restore(it) } ?: false

    suspend fun signInUrl(onDone: suspend (Boolean) -> Unit): String =
        FilesyncSession.signInUrl(flow, checkNotNull(identity()) { "signed out" }, onDone)

    /** The owner's page granting Filesync what sharing needs, or null once it has it. */
    suspend fun missingPermissionsUrl(returnUrl: () -> String): String? {
        val identity = checkNotNull(identity()) { "signed out" }
        val config = PermissionExtensionConfig(
            appId = filesyncAppRegistration.appId,
            appName = filesyncAppRegistration.name,
            drives = emptyList(),
            permissions = FilesyncSession.sharingPermissions,
            returnUrl = returnUrl,
            requester = "filesync",
        )
        return when (val result = PermissionExtensionManager(SecurityContextProvider(httpClient, FilesyncSession.credentials), identity.domainName).getMissingPermissions(config)) {
            PermissionCheckResult.AllGranted -> null
            is PermissionCheckResult.Missing -> result.details.buildExtendPermissionUrl()
            PermissionCheckResult.Unknown -> error("couldn't read Filesync's permissions")
        }
    }
}

/** A screen's sharing: turning it on, sharing with a person, who a file is shared with, and what others share. */
internal class FilesyncSharingController(
    private val scope: CoroutineScope,
    private val sharing: FilesyncSharing,
    private val setup: FilesyncSharingSetup,
    private val handles: () -> Map<Uuid, FilesyncHandle>,
    private val listFolders: suspend (List<Uuid>) -> FilesyncNodes,
    private val returnUrl: () -> String,
    private val state: () -> FilesyncSharingState,
    private val update: ((FilesyncSharingState) -> FilesyncSharingState) -> Unit,
    private val send: suspend (FilesyncUiEvent) -> Unit,
) {
    private var awaitingOwner = false
    private var sharedWithJob: Job? = null
    private var sharedFilesJob: Job? = null
    private val sharedHandles = mutableMapOf<Uuid, FilesyncHandle>()
    private var sharedNodes = emptyList<FilesyncNode>()
    private var shownFolder: Uuid? = null
    private var sharedInJob: Job? = null
    private var recipientFilesJob: Job? = null
    private val recipientHandles = mutableMapOf<Uuid, FilesyncHandle>()
    private var pendingMove: (suspend () -> Unit)? = null

    fun start() {
        scope.launch {
            if (guarded { setup.restore() } == true) {
                update { it.copy(on = true) }
                loadRecipients()
                loadSharedIn()
                // Keeps the circle every connection joins filled with the ones made before it.
                guarded { sharing.openToMarkers() }
            }
        }
        loadSharers()
    }

    /** Asks whom to share [nodes]' files with, turning sharing on first when it's off. */
    fun ask(nodes: List<FilesyncNode>) {
        val live = nodes.filter { it.removedMs == null }.takeIf { it.isNotEmpty() } ?: return
        update { it.copy(shareWith = live) }
        if (state().on) loadOffered() else turnOn()
    }

    fun dismiss() = update { it.copy(shareWith = null) }

    fun turnOn() {
        if (state().turningOn && !awaitingOwner) return
        update { it.copy(turningOn = true) }
        scope.launch {
            if (FilesyncSession.signedIn.value) {
                finishTurningOn()
                return@launch
            }
            val url = guarded { setup.signInUrl { ok -> if (ok) finishTurningOn() else failed(FilesyncError.SharingFailed) } } ?: return@launch failed(FilesyncError.SharingFailed)
            send(FilesyncUiEvent.OpenSignIn(url))
        }
    }

    /** Back from the owner's page: the permission may be granted now. */
    fun resumed() {
        if (!awaitingOwner) return
        awaitingOwner = false
        scope.launch { finishTurningOn() }
    }

    fun share(audience: FilesyncAudience, access: FilesyncAccess, expiry: FilesyncExpiry) = share(access, expiry) { audience }

    fun shareWithNewGroup(name: String, members: List<OdinId>, access: FilesyncAccess, expiry: FilesyncExpiry) =
        share(access, expiry) { sharing.createGroup(name, members) }

    private fun share(access: FilesyncAccess, expiry: FilesyncExpiry, audience: suspend () -> FilesyncAudience) {
        val files = state().shareWith ?: return
        update { it.copy(sending = true) }
        scope.launch {
            var fileHandles = emptyList<FilesyncHandle>()
            var recipient: FilesyncAudience? = null
            try {
                fileHandles = withDescendants(files).values.toList()
                recipient = audience()
                sharing.share(fileHandles, recipient, access, expiry.expiresAt(Clock.System.now().toEpochMilliseconds()))
                update { it.copy(sending = false, shareWith = null) }
                send(FilesyncUiEvent.Notice(FilesyncNotice.Shared))
                loadRecipients()
                loadSharedIn()
                state().sharedWith?.nodeId?.takeIf { id -> files.any { it.nodeId == id } }?.let { loadSharedWith(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "sharing ${fileHandles.size} files with ${recipient ?: "a new group"} failed" }
                update { it.copy(sending = false) }
                send(if (e is FilesyncSharingException.RecipientNotReady) FilesyncUiEvent.NotReady(e.recipients) else FilesyncUiEvent.Error(FilesyncError.SharingFailed))
            }
        }
    }

    /**
     * Moves [nodes] into [into] with [move], which says which of them moved, and shares those like their new folder;
     * asks first when someone would lose access. Nothing moves when their sharing can't be read.
     */
    fun move(nodes: List<FilesyncNode>, handles: Map<Uuid, FilesyncHandle>, into: Uuid, move: suspend () -> List<Uuid>) {
        scope.launch {
            val going = nodes.filter { it.parentId != into && it.removedMs == null }
            val plan = try {
                if (going.isEmpty()) FilesyncMoveSharing.None
                else sharing.planMove(going, into) { node -> withDescendants(listOf(node), handles, listFolders) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "reading the sharing of ${going.size} nodes moving to $into failed" }
                send(FilesyncUiEvent.Error(FilesyncError.MoveCheckFailed))
                return@launch
            }
            val go: suspend () -> Unit = {
                val moved = move()
                if (moved.isNotEmpty() && plan !== FilesyncMoveSharing.None) {
                    try {
                        sharing.applyMove(plan, moved)
                        loadSharedIn()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(e, TAG) { "sharing ${moved.size} moved nodes like $into failed" }
                        send(FilesyncUiEvent.Error(FilesyncError.MoveSharingFailed))
                    }
                }
            }
            if (plan.losingAccess.isEmpty()) {
                go()
            } else {
                pendingMove = go
                update { it.copy(losingAccess = plan.losingAccess) }
            }
        }
    }

    fun confirmLosingAccess() {
        val go = pendingMove ?: return
        pendingMove = null
        update { it.copy(losingAccess = null) }
        scope.launch { go() }
    }

    fun dismissLosingAccess() {
        pendingMove = null
        update { it.copy(losingAccess = null) }
    }

    fun showSharedWith(nodeId: Uuid) {
        if (state().sharedWith?.nodeId == nodeId) return
        update { it.copy(sharedWith = FilesyncSharedWith(nodeId)) }
        if (state().on) loadSharedWith(nodeId)
    }

    fun unshare(nodeId: Uuid, recipient: FilesyncAudience) {
        scope.launch {
            try {
                sharing.withdraw(handles()[nodeId]?.fileId ?: return@launch, recipient)
                loadSharedWith(nodeId)
                loadSharedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "withdrawing $nodeId from $recipient failed" }
                send(FilesyncUiEvent.Error(FilesyncError.SharingFailed))
            }
        }
    }

    fun loadSharers() {
        scope.launch {
            guarded { sharing.sharers() }?.let { sharers -> update { it.copy(sharers = sharers) } }
        }
    }

    /** Marks which of [folderId]'s nodes are shared; with sharing off, remembers it for when it's on. */
    fun loadSharedIn(folderId: Uuid? = shownFolder) {
        shownFolder = folderId
        if (!state().on || folderId == null) return
        sharedInJob?.cancel()
        sharedInJob = scope.launch {
            guarded { sharing.sharedIn(folderId) }?.let { ids -> update { it.copy(sharedIds = ids) } }
        }
    }

    fun loadRecipients() {
        if (!state().on) return
        scope.launch { guarded { sharing.audiences() }?.let { recipients -> update { it.copy(recipients = recipients) } } }
    }

    /** Shows what [recipient] is given, each file with a way to stop; null goes back to the folder. */
    fun openRecipient(recipient: FilesyncAudience?) {
        closeViews()
        recipient ?: return
        update { it.copy(recipient = recipient) }
        recipientFilesJob = scope.launch {
            val listed = guarded { sharing.sharedWithAudience(recipient) }
            if (listed == null) {
                update { it.copy(recipientFilesFailed = true) }
                return@launch
            }
            listed.forEach { recipientHandles[it.node.nodeId] = it.handle }
            val byId = listed.associateBy { it.node.nodeId }
            update { it.copy(recipientFiles = listed.map { f -> f.node }.arranged(FilesyncSort()).mapNotNull { node -> byId[node.nodeId]?.let { FilesyncOutgoingShare(node, it.access, it.expiresAt) } }) }
        }
    }

    fun stopSharing(nodeId: Uuid) {
        val recipient = state().recipient ?: return
        val fileId = recipientHandles[nodeId]?.fileId ?: return
        scope.launch {
            try {
                sharing.withdraw(fileId, recipient)
                update { s -> s.copy(recipientFiles = s.recipientFiles?.filter { it.node.nodeId != nodeId }) }
                loadSharedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "withdrawing $nodeId from $recipient failed" }
                send(FilesyncUiEvent.Error(FilesyncError.SharingFailed))
            }
        }
    }

    private suspend fun withDescendants(nodes: List<FilesyncNode>) = withDescendants(nodes, handles(), listFolders)

    /** Shares the file with the shown person again, for [expiry] from now; the new row replaces the old. */
    fun changeExpiry(nodeId: Uuid, expiry: FilesyncExpiry) {
        val recipient = state().recipient ?: return
        val handle = recipientHandles[nodeId] ?: return
        val shown = state().recipientFiles?.firstOrNull { it.node.nodeId == nodeId } ?: return
        scope.launch {
            try {
                val expiresAt = expiry.expiresAt(Clock.System.now().toEpochMilliseconds())
                sharing.share(listOf(handle), recipient, shown.access, expiresAt)
                update { s -> s.copy(recipientFiles = s.recipientFiles?.map { if (it.node.nodeId == nodeId) it.copy(expiresAt = expiresAt) else it }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "changing $nodeId's expiry for $recipient failed" }
                send(FilesyncUiEvent.Error(FilesyncError.SharingFailed))
            }
        }
    }

    fun openSharer(sharer: OdinId?) {
        closeViews()
        sharer ?: return
        update { it.copy(sharer = sharer) }
        sharedFilesJob = scope.launch {
            val listed = guarded { sharing.sharedBy(sharer) }
            if (listed == null) {
                update { it.copy(sharedFilesFailed = true) }
                return@launch
            }
            listed.forEach { (node, handle) -> sharedHandles[node.nodeId] = handle }
            sharedNodes = listed.map { it.first }
            openSharedFolder(emptyList())
        }
    }

    /** Shows a folder of the shown sharer's; at the top, whatever's shared without the folder it's in. */
    fun openSharedFolder(path: List<FilesyncCrumb>) {
        update { it.copy(sharerPath = path, sharedFiles = sharedLevel(sharedNodes, path.lastOrNull()?.nodeId).arranged(FilesyncSort())) }
    }

    private fun closeViews() {
        sharedFilesJob?.cancel()
        sharedHandles.clear()
        sharedNodes = emptyList()
        recipientFilesJob?.cancel()
        recipientHandles.clear()
        update { it.copy(sharer = null, sharerPath = emptyList(), sharedFiles = null, sharedFilesFailed = false, recipient = null, recipientFiles = null, recipientFilesFailed = false) }
    }

    /** The shared file's handle, for downloading it from its owner's drive. */
    fun sharedHandle(nodeId: Uuid): FilesyncHandle? = sharedHandles[nodeId]

    private suspend fun finishTurningOn() {
        val permissionsUrl = try {
            setup.missingPermissionsUrl(returnUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e, TAG) { "checking Filesync's permissions failed" }
            return failed(FilesyncError.SharingFailed)
        }
        if (permissionsUrl != null) {
            awaitingOwner = true
            send(FilesyncUiEvent.OpenOwnerConsole(permissionsUrl))
            return
        }
        if (guarded { sharing.openToMarkers() } == null) return failed(FilesyncError.SharingFailed)
        update { it.copy(on = true, turningOn = false) }
        loadOffered()
        loadRecipients()
        loadSharedIn()
        state().sharedWith?.nodeId?.let { loadSharedWith(it) }
    }

    private fun loadOffered() {
        scope.launch { guarded { sharing.offeredAccess() }?.let { offered -> update { it.copy(offered = offered) } } }
    }

    private fun loadSharedWith(nodeId: Uuid) {
        val fileId = handles()[nodeId]?.fileId ?: return
        sharedWithJob?.cancel()
        sharedWithJob = scope.launch {
            val recipients = guarded { sharing.sharedWith(fileId) } ?: return@launch
            update { s -> s.sharedWith?.takeIf { it.nodeId == nodeId }?.let { s.copy(sharedWith = it.copy(recipients = recipients)) } ?: s }
        }
    }

    private suspend fun failed(error: FilesyncError) {
        awaitingOwner = false
        update { it.copy(turningOn = false) }
        send(FilesyncUiEvent.Error(error))
    }

    // Null when [block] failed; logged, and the caller says what failed.
    private suspend fun <T> guarded(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e, TAG) { "sharing step failed" }
            null
        }

    private companion object {
        const val TAG = "FilesyncSharing"
    }
}

/** [nodes] and, for each folder among them, everything below it now; a shared folder shares what it holds. */
internal suspend fun withDescendants(
    nodes: List<FilesyncNode>,
    handles: Map<Uuid, FilesyncHandle>,
    listFolders: suspend (List<Uuid>) -> FilesyncNodes,
): Map<Uuid, FilesyncHandle> {
    val found = nodes.mapNotNull { node -> handles[node.nodeId]?.let { node.nodeId to it } }.toMap(LinkedHashMap())
    var folders = nodes.filter { it.isFolder }.map { it.nodeId }
    while (folders.isNotEmpty()) {
        val below = listFolders(folders)
        folders = below.nodes.values.filter { it.isFolder && it.nodeId !in found }.map { it.nodeId }
        below.handles.forEach { (id, handle) -> found.getOrPut(id) { handle } }
    }
    return found
}

/** What [parent] holds of [shared]; at the top (null), whatever's shared without the folder it's in. */
internal fun sharedLevel(shared: List<FilesyncNode>, parent: Uuid?): List<FilesyncNode> {
    val folders = shared.filter { it.isFolder }.mapTo(HashSet()) { it.nodeId }
    return shared.filter { if (parent == null) it.parentId !in folders else it.parentId == parent }
}
