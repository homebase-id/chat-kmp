package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.fileshare.FileShareDescriptor
import id.homebase.api.youauth.PermissionCheckResult
import id.homebase.api.youauth.PermissionExtensionConfig
import id.homebase.api.youauth.PermissionExtensionManager
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.config.filesyncAppRegistration
import io.ktor.client.HttpClient
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Turning sharing on: signing in to Filesync too ([FilesyncSession]), and the permission its registration may lack. */
class FilesyncSharingSetup(
    private val flow: YouAuthFlowManager,
    private val httpClient: HttpClient,
    private val appCredentials: CredentialsManager,
) {
    suspend fun identity(): OdinId? = appCredentials.getActiveCredentials()?.domain

    suspend fun restore(): Boolean = FilesyncSession.restoreFor(appCredentials)

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
            is PermissionCheckResult.AppSlugTaken -> error("this config has no install step")
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
    /** Tells the people a share reaches about it; the ones it couldn't. */
    private val tell: suspend (List<OdinId>, FileShareDescriptor) -> List<OdinId>,
    private val state: () -> FilesyncSharingState,
    private val update: ((FilesyncSharingState) -> FilesyncSharingState) -> Unit,
    private val send: suspend (FilesyncUiEvent) -> Unit,
) {
    private var awaitingOwner = false
    private var awaitingSignIn = false
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
            }
        }
        loadSharers()
    }

    /** Asks whom to share [nodes]' files with, turning sharing on first when it's off. */
    fun ask(nodes: List<FilesyncNode>) {
        val live = nodes.filter { it.removedMs == null }.takeIf { it.isNotEmpty() } ?: return
        update { it.copy(shareWith = live) }
        if (state().on) loadOffered()
    }

    fun dismiss() = update { it.copy(shareWith = null) }

    // Pressable again while the browser is out: a sign-in or owner's page left unfinished sends nothing back.
    fun turnOn() {
        if (state().turningOn && !awaitingOwner && !awaitingSignIn) return
        update { it.copy(turningOn = true) }
        scope.launch {
            if (FilesyncSession.signedIn.value) {
                finishTurningOn()
                return@launch
            }
            val url = guarded {
                setup.signInUrl { ok ->
                    awaitingSignIn = false
                    if (ok) finishTurningOn() else failed(FilesyncError.SharingFailed)
                }
            } ?: return@launch failed(FilesyncError.SharingFailed)
            awaitingSignIn = true
            send(FilesyncUiEvent.OpenSignIn(url))
        }
    }

    /** Back from the owner's page: the permission may be granted now; if not, the user turns it on again. */
    fun resumed() {
        if (!awaitingOwner) return
        awaitingOwner = false
        scope.launch { finishTurningOn(openOwnerPage = false) }
    }

    fun share(audience: FilesyncAudience, access: FilesyncAccess, expiry: FilesyncExpiry, note: String) = share(access, expiry, note) { audience }

    fun shareWithNewGroup(name: String, members: List<OdinId>, access: FilesyncAccess, expiry: FilesyncExpiry, note: String) =
        share(access, expiry, note) { sharing.createGroup(name, members) }

    private fun share(access: FilesyncAccess, expiry: FilesyncExpiry, note: String, audience: suspend () -> FilesyncAudience) {
        val files = state().shareWith ?: return
        update { it.copy(sending = true) }
        scope.launch {
            val expiresAt = expiry.expiresAt(Clock.System.now().toEpochMilliseconds())
            val shared = attempt("sharing ${files.size} nodes") {
                val tree = treeOf(files, handles(), listFolders)
                val to = audience()
                val made = sharing.share(tree.handles.values.toList(), to, access, expiresAt)
                Triple(to, made, describe(files, tree.nodes.values, made.circleId, to, access, expiresAt, note))
            }
            update { if (shared != null) it.copy(sending = false, shareWith = null) else it.copy(sending = false) }
            val (to, made, description) = shared ?: return@launch
            send(if (made.waiting.isEmpty()) FilesyncUiEvent.Notice(FilesyncNotice.Shared) else FilesyncUiEvent.Waiting(made.waiting))
            tell(to.members, description).takeIf { it.isNotEmpty() }?.let { send(FilesyncUiEvent.NotTold(it)) }
            loadRecipients()
            loadSharedIn()
            state().sharedWith?.nodeId?.takeIf { id -> files.any { it.nodeId == id } }?.let { loadSharedWith(it) }
        }
    }

    /**
     * Moves [nodes] into [into] with [move], which says which of them moved, and shares those like their new folder;
     * asks first when someone would lose access. Nothing moves when their sharing can't be read.
     */
    fun move(nodes: List<FilesyncNode>, into: Uuid, move: suspend () -> List<Uuid>) {
        scope.launch {
            val going = nodes.filter { it.parentId != into && it.removedMs == null }
            val plan = if (going.isEmpty()) FilesyncMoveSharing.None else attempt("reading the sharing of ${going.size} nodes moving to $into", FilesyncError.MoveCheckFailed) {
                sharing.planMove(going, into) { node -> withDescendants(listOf(node)) }
            } ?: return@launch
            val go: suspend () -> Unit = {
                val moved = move()
                if (moved.isNotEmpty() && plan !== FilesyncMoveSharing.None) {
                    attempt("sharing ${moved.size} moved nodes like $into", FilesyncError.MoveSharingFailed) { sharing.applyMove(plan, moved) }?.let { loadSharedIn() }
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
        // Loaded already; one that failed is asked for again.
        if (state().sharedWith?.takeIf { it.nodeId == nodeId }?.recipients != null) return
        update { it.copy(sharedWith = FilesyncSharedWith(nodeId)) }
        if (state().on) loadSharedWith(nodeId)
    }

    fun unshare(node: FilesyncNode, recipient: FilesyncAudience) {
        scope.launch {
            attempt("withdrawing ${node.nodeId} from $recipient") { sharing.withdraw(withDescendants(listOf(node)), recipient) } ?: return@launch
            loadSharedWith(node.nodeId)
            loadSharedIn()
        }
    }

    private fun loadSharers() {
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

    fun setGroupMembers(group: FilesyncAudience.Group, members: List<OdinId>) {
        scope.launch {
            val added = attempt("changing ${group.circleId}'s members") { sharing.setGroupMembers(group, members) }
            if (!added.isNullOrEmpty()) tellAboutGroup(group, added)
            // Read back, so a change that only partly went through shows as it is.
            val recipients = guarded { sharing.audiences() } ?: return@launch
            update { s ->
                val shown = (s.recipient as? FilesyncAudience.Group)?.takeIf { it.circleId == group.circleId }
                s.copy(recipients = recipients, recipient = shown?.let { recipients.firstOrNull { r -> r is FilesyncAudience.Group && r.circleId == group.circleId } ?: it } ?: s.recipient)
            }
        }
    }

    // A new member sees everything shared with the group, so they're told what that is.
    private suspend fun tellAboutGroup(group: FilesyncAudience.Group, added: List<OdinId>) {
        val shared = guarded { sharing.sharedWithAudience(group) }?.takeIf { it.isNotEmpty() } ?: return
        val top = sharedLevel(shared.map { it.node }, parent = null)
        val access = if (shared.all { it.access == FilesyncAccess.Edit }) FilesyncAccess.Edit else FilesyncAccess.View
        val description = describe(top, shared.map { it.node }, group.circleId, group, access, expiresAt = null, note = "")
        tell(added, description).takeIf { it.isNotEmpty() }?.let { send(FilesyncUiEvent.NotTold(it)) }
    }

    /** Opened from a chat message: keeps [sharer] among the sharers, then shows their files. */
    fun openFromChat(sharer: OdinId) {
        openSharer(sharer)
        if (sharer in state().sharers) return
        scope.launch {
            guarded { sharing.keepSharer(sharer) }
            loadSharers()
        }
    }

    /** Opened from this identity's own chat message: shows what the share's circle is given. */
    fun openOwnShare(circleId: Uuid) {
        scope.launch {
            guarded { sharing.audienceOf(circleId) }?.let(::openRecipient)
        }
    }

    /** Shows what [recipient] is given, each file with a way to stop. */
    fun openRecipient(recipient: FilesyncAudience) {
        closeViews()
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
        val node = state().recipientFiles?.firstOrNull { it.node.nodeId == nodeId }?.node ?: return
        scope.launch {
            val stopped = attempt("withdrawing $nodeId from $recipient") { sharing.withdraw(withDescendants(listOf(node), recipientHandles, listFolders), recipient) } ?: return@launch
            update { s -> s.copy(recipientFiles = s.recipientFiles?.filter { it.node.nodeId !in stopped }) }
            loadSharedIn()
        }
    }

    private suspend fun withDescendants(nodes: List<FilesyncNode>) = withDescendants(nodes, handles(), listFolders)

    /** Shares the file with the shown audience again, for [expiry] from now; the new row replaces the old. */
    fun changeExpiry(nodeId: Uuid, expiry: FilesyncExpiry) {
        val recipient = state().recipient ?: return
        val handle = recipientHandles[nodeId] ?: return
        val shown = state().recipientFiles?.firstOrNull { it.node.nodeId == nodeId } ?: return
        scope.launch {
            val expiresAt = expiry.expiresAt(Clock.System.now().toEpochMilliseconds())
            attempt("changing $nodeId's expiry for $recipient") { sharing.renew(handle, recipient, shown.access, expiresAt) } ?: return@launch
            update { s -> s.copy(recipientFiles = s.recipientFiles?.map { if (it.node.nodeId == nodeId) it.copy(expiresAt = expiresAt) else it }) }
        }
    }

    fun openSharer(sharer: OdinId) {
        closeViews()
        update { it.copy(sharer = sharer) }
        sharedFilesJob = scope.launch {
            val listed = guarded {
                try {
                    sharing.sharedBy(sharer)
                } catch (e: FilesyncSharingException.NotReviewedByOwner) {
                    send(FilesyncUiEvent.NotReviewedBy(e.owner))
                    null
                } catch (e: FilesyncSharingException.NotSealedYet) {
                    send(FilesyncUiEvent.NotSealedYet(e.owner))
                    null
                }
            }
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

    fun closeShareView() = closeViews()

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

    private suspend fun finishTurningOn(openOwnerPage: Boolean = true) {
        val permissionsUrl = try {
            setup.missingPermissionsUrl(returnUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e, TAG) { "checking Filesync's permissions failed" }
            return failed(FilesyncError.SharingFailed)
        }
        if (permissionsUrl != null) {
            if (!openOwnerPage) return failed(FilesyncError.SharingFailed)
            awaitingOwner = true
            send(FilesyncUiEvent.OpenOwnerConsole(permissionsUrl))
            return
        }
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

    // Null when [block] failed: logged as [what], and the user hears [error], or who isn't ready.
    private suspend fun <T : Any> attempt(what: String, error: FilesyncError = FilesyncError.SharingFailed, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e, TAG) { "$what failed" }
            forgetIfRevoked(e)
            send(
                when (e) {
                    is FilesyncSharingException.RecipientNotAdded -> FilesyncUiEvent.NotAdded(e.recipients)
                    else -> FilesyncUiEvent.Error(error)
                }
            )
            null
        }

    // Null when [block] failed, which is logged.
    private suspend fun <T> guarded(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e, TAG) { "sharing step failed" }
            forgetIfRevoked(e)
            null
        }

    // The owner revoked Filesync or its token ran out: sharing is off until the user turns it on and signs in again.
    private suspend fun forgetIfRevoked(e: Exception) {
        if (!sharing.signOutIfRefused(e)) return
        update { it.copy(on = false, turningOn = false) }
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
): Map<Uuid, FilesyncHandle> = treeOf(nodes, handles, listFolders).handles

/** [withDescendants], with the nodes too. */
internal suspend fun treeOf(
    nodes: List<FilesyncNode>,
    handles: Map<Uuid, FilesyncHandle>,
    listFolders: suspend (List<Uuid>) -> FilesyncNodes,
): FilesyncNodes {
    val found = nodes.mapNotNull { node -> handles[node.nodeId]?.let { node.nodeId to it } }.toMap(LinkedHashMap())
    val foundNodes = nodes.filter { it.nodeId in found }.associateByTo(LinkedHashMap()) { it.nodeId }
    var folders = nodes.filter { it.isFolder }.map { it.nodeId }
    while (folders.isNotEmpty()) {
        val below = listFolders(folders)
        folders = below.nodes.values.filter { it.isFolder && it.nodeId !in found }.map { it.nodeId }
        below.handles.forEach { (id, handle) -> found.getOrPut(id) { handle } }
        below.nodes.forEach { (id, node) -> foundNodes.getOrPut(id) { node } }
    }
    return FilesyncNodes(foundNodes, found)
}

/** What [parent] holds of [shared]; at the top (null), whatever's shared without the folder it's in. */
internal fun sharedLevel(shared: List<FilesyncNode>, parent: Uuid?): List<FilesyncNode> {
    val folders = shared.filter { it.isFolder }.mapTo(HashSet()) { it.nodeId }
    return shared.filter { if (parent == null) it.parentId !in folders else it.parentId == parent }
}

/** What a share's chat message says: its top folder, or first file, and how much it holds outside Recently deleted. */
internal fun describe(
    top: List<FilesyncNode>,
    tree: Collection<FilesyncNode>,
    circleId: Uuid,
    audience: FilesyncAudience,
    access: FilesyncAccess,
    expiresAt: Long?,
    note: String,
): FileShareDescriptor {
    // As the browser lists them: folders first, then by name.
    val first = top.arranged(FilesyncSort()).first()
    val live = tree.filter { it.removedMs == null }.totals()
    return FileShareDescriptor(
        circleId = circleId.toString(),
        title = first.name.truncateToCodePoints(FileShareDescriptor.MAX_TITLE_CODEPOINTS),
        titleIsFolder = first.isFolder,
        files = live.files,
        bytes = live.bytes,
        access = if (access == FilesyncAccess.Edit) FileShareDescriptor.ACCESS_EDIT else FileShareDescriptor.ACCESS_VIEW,
        group = (audience as? FilesyncAudience.Group)?.name?.truncateToCodePoints(FileShareDescriptor.MAX_GROUP_CODEPOINTS),
        note = note.trim().ifEmpty { null }?.truncateToCodePoints(FileShareDescriptor.MAX_NOTE_CODEPOINTS),
        expiresAt = expiresAt,
    )
}
