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
    private val returnUrl: () -> String,
    private val state: () -> FilesyncSharingState,
    private val update: ((FilesyncSharingState) -> FilesyncSharingState) -> Unit,
    private val send: suspend (FilesyncUiEvent) -> Unit,
) {
    private var awaitingOwner = false
    private var sharedWithJob: Job? = null
    private var sharedFilesJob: Job? = null
    private val sharedHandles = mutableMapOf<Uuid, FilesyncHandle>()
    private var recipientFilesJob: Job? = null
    private val recipientHandles = mutableMapOf<Uuid, FilesyncHandle>()

    fun start() {
        scope.launch {
            if (guarded { setup.restore() } == true) {
                update { it.copy(on = true) }
                loadRecipients()
                // Keeps the circle every connection joins filled with the ones made before it.
                guarded { sharing.openToMarkers() }
            }
        }
        loadSharers()
    }

    /** Asks whom to share [nodes]' files with, turning sharing on first when it's off. */
    fun ask(nodes: List<FilesyncNode>) {
        val files = nodes.filter { !it.isFolder }.takeIf { it.isNotEmpty() } ?: return
        update { it.copy(shareWith = files) }
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

    fun share(recipient: OdinId, access: FilesyncAccess) {
        val files = state().shareWith ?: return
        val fileHandles = files.mapNotNull { handles()[it.nodeId] }
        update { it.copy(sending = true) }
        scope.launch {
            try {
                sharing.share(fileHandles, recipient, access)
                update { it.copy(sending = false, shareWith = null) }
                send(FilesyncUiEvent.Notice(FilesyncNotice.Shared))
                loadRecipients()
                state().sharedWith?.nodeId?.takeIf { id -> files.any { it.nodeId == id } }?.let { loadSharedWith(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "sharing ${fileHandles.size} files with $recipient failed" }
                update { it.copy(sending = false) }
                send(FilesyncUiEvent.Error(if (e is FilesyncSharingException.RecipientNotReady) FilesyncError.RecipientNotReady else FilesyncError.SharingFailed))
            }
        }
    }

    fun showSharedWith(nodeId: Uuid) {
        if (state().sharedWith?.nodeId == nodeId) return
        update { it.copy(sharedWith = FilesyncSharedWith(nodeId)) }
        if (state().on) loadSharedWith(nodeId)
    }

    fun unshare(nodeId: Uuid, recipient: OdinId) {
        scope.launch {
            try {
                sharing.withdraw(handles()[nodeId]?.fileId ?: return@launch, recipient)
                loadSharedWith(nodeId)
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

    fun loadRecipients() {
        if (!state().on) return
        scope.launch { guarded { sharing.recipients() }?.let { recipients -> update { it.copy(recipients = recipients) } } }
    }

    /** Shows what [recipient] is given, each file with a way to stop; null goes back to the folder. */
    fun openRecipient(recipient: OdinId?) {
        closeViews()
        recipient ?: return
        update { it.copy(recipient = recipient) }
        recipientFilesJob = scope.launch {
            val listed = guarded { sharing.sharedWithRecipient(recipient) }
            if (listed == null) {
                update { it.copy(recipientFilesFailed = true) }
                return@launch
            }
            listed.forEach { recipientHandles[it.node.nodeId] = it.handle }
            val byId = listed.associateBy { it.node.nodeId }
            update { it.copy(recipientFiles = listed.map { f -> f.node }.arranged(FilesyncSort()).mapNotNull { node -> byId[node.nodeId]?.let { FilesyncOutgoingShare(node, it.access) } }) }
        }
    }

    fun stopSharing(nodeId: Uuid) {
        val recipient = state().recipient ?: return
        val fileId = recipientHandles[nodeId]?.fileId ?: return
        scope.launch {
            try {
                sharing.withdraw(fileId, recipient)
                update { s -> s.copy(recipientFiles = s.recipientFiles?.filter { it.node.nodeId != nodeId }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "withdrawing $nodeId from $recipient failed" }
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
            update { it.copy(sharedFiles = listed.map { it.first }.arranged(FilesyncSort())) }
        }
    }

    private fun closeViews() {
        sharedFilesJob?.cancel()
        sharedHandles.clear()
        recipientFilesJob?.cancel()
        recipientHandles.clear()
        update { it.copy(sharer = null, sharedFiles = null, sharedFilesFailed = false, recipient = null, recipientFiles = null, recipientFilesFailed = false) }
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
