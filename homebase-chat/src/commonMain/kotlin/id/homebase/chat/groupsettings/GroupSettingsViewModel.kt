package id.homebase.chat.groupsettings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.RecipientTransferHistoryEntry
import id.homebase.api.client.drives.files.TransferStatus
import id.homebase.api.client.peer.PeerDriveQueryProvider
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.groupsettings.GroupSettingsUiEvent.Back
import id.homebase.chat.groupsettings.GroupSettingsUiEvent.Error
import id.homebase.chat.groupsettings.GroupSettingsUiEvent.ShowAddMembers
import id.homebase.chat.groupsettings.GroupSettingsUiEvent.ShowContactInfo
import id.homebase.chat.groupsettings.GroupSettingsUiEvent.ShowEditGroup
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.GroupHealService
import id.homebase.chat.services.convo.HealGroupResult
import id.homebase.chat.services.convo.HealPhase
import id.homebase.chat.services.convo.HealPlan
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import id.homebase.chat.services.convo.contact.ContactConnectionState
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.toErrorDetailRes
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.ui.navigation.Route
import id.homebase.core.util.buildConnectToIdentityUrl
import id.homebase.core.util.initials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class GroupSettingsViewModel(
    savedStateHandle: SavedStateHandle,
    private val conversationStream: ConversationStream,
    private val conversationService: ConversationService,
    private val groupHealService: GroupHealService,
    private val contactService: ContactService,
    private val credentialsManager: CredentialsManager,
    private val driveFileProvider: DriveFileProvider,
    private val peerDriveQueryProvider: PeerDriveQueryProvider,
) : ViewModel() {

    val route = savedStateHandle.toRoute<Route.GroupSettings>()
    private val _uiState = MutableStateFlow(GroupSettingsUiState())
    val uiState: StateFlow<GroupSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            conversationStream.start()
            conversationStream.conversations
                .filter { conversations ->
                    conversations.items.any { it.id.toString() == route.conversationId }
                }
                .collect { conversations ->
                    val conversation =
                        conversations.items.find { it.id.toString() == route.conversationId }
                    conversation?.let {
                        loadData(conversation)
                    }
                }
        }
    }

    fun onUiAction(action: GroupSettingsUiAction) {
        when (action) {
            is GroupSettingsUiAction.BackClicked -> {
                _uiState.update { it.copy(uiEvent = Back) }
            }

            is GroupSettingsUiAction.AddMembersClicked -> {
                _uiState.update { it.copy(uiEvent = ShowAddMembers(route.conversationId)) }
            }

            is GroupSettingsUiAction.EditGroupClicked -> {
                _uiState.update { it.copy(uiEvent = ShowEditGroup(route.conversationId)) }
            }

            is GroupSettingsUiAction.ShowContactInfo -> {
                _uiState.update {
                    it.copy(uiEvent = ShowContactInfo(action.contact.odinId.toString()))
                }
            }

            is GroupSettingsUiAction.ShowMemberSheet -> {
                _uiState.update {
                    it.copy(uiSheet = GroupSettingsUiSheet.Member(action.contact.id))
                }
            }

            is GroupSettingsUiAction.LeaveGroupConfirm -> {
                viewModelScope.launch {
                    // Show full-screen overlay while the leave is in flight. Cleared on
                    // error; on success the screen pops via the Back event below so
                    // explicit clearing is unnecessary.
                    _uiState.update { it.copy(isLeaving = true) }
                    try {
                        uiState.value.conversation?.let { conversation ->
                            uiState.value.currentOdinId?.let { currentUser ->
                                val isSoleAdmin = conversation.isCurrentUserAdmin(currentUser)
                                        && conversation.admins.size == 1
                                val forceLocalOnly =
                                    isSoleAdmin && !hasReachableNonAdmin(conversation)
                                conversationService.leaveGroup(
                                    conversationId = conversation.id,
                                    forceLocalOnly = forceLocalOnly
                                )
                                conversationStream.onConversationLeft(conversation.id)
                                _uiState.update { it.copy(uiEvent = Back) }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to leave group", e)
                        _uiState.update {
                            it.copy(
                                isLeaving = false,
                                uiEvent = Error("Failed to leave group"),
                            )
                        }
                    }
                }
            }

            is GroupSettingsUiAction.LeaveGroupClicked -> {
                uiState.value.conversation?.let { conversation ->
                    uiState.value.currentOdinId?.let { currentUser ->
                        val isAdmin = conversation.isCurrentUserAdmin(currentUser)
                        val isSoleAdmin = isAdmin
                                && conversation.admins.size == 1
                                && conversation.isGroupConversation
                        when {
                            // Legacy groups don't support admin management, so the
                            // "choose another admin first" path is a dead end for
                            // legacy admins. Surface a strong-warning dialog and let
                            // them proceed; service-side leaveGroup uses the local-
                            // only branch for legacy groups regardless.
                            conversation.isLegacyGroup && isAdmin -> {
                                _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.LeaveLegacyAdminWarning) }
                            }
                            // Only force the "choose new admin" dialog when there IS someone
                            // reachable to promote. If the caller has no connected non-admin,
                            // fall through to ConfirmLeave — leaveGroup will mark locally only.
                            isSoleAdmin && hasReachableNonAdmin(conversation) -> {
                                _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.LeaveChooseAdmin) }
                            }
                            else -> {
                                _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.ConfirmLeave) }
                            }
                        }
                    }
                }
            }

            is GroupSettingsUiAction.MakeAdmin -> {
                if (action.skipConfirmation) {
                    viewModelScope.launch {
                        runMemberOp(action.contact.odinId, "Failed to add an admin") { conversation ->
                            conversationService.updateAdmins(
                                conversationId = conversation.id,
                                add = listOf(action.contact.odinId)
                            )
                        }
                    }
                    return
                }
                _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.MakeAdmin(action.contact)) }
            }

            is GroupSettingsUiAction.RemoveAdmin -> {
                if (action.skipConfirmation) {
                    viewModelScope.launch {
                        runMemberOp(action.contact.odinId, "Failed to remove an admin") { conversation ->
                            conversationService.updateAdmins(
                                conversationId = conversation.id,
                                remove = listOf(action.contact.odinId)
                            )
                        }
                    }
                    return
                }
                _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.RemoveAdmin(action.contact)) }
            }

            is GroupSettingsUiAction.RemoveFromGroup -> {
                if (action.skipConfirmation) {
                    viewModelScope.launch {
                        val ok = runMemberOp(action.contact.odinId, "Failed to remove a member") { conversation ->
                            conversationService.updateGroupMembers(
                                conversationId = conversation.id,
                                remove = listOf(action.contact.odinId)
                            )
                        }
                        if (ok) {
                            // dismiss sheet on successful removal — the row in the
                            // participant list will also disappear once the conversation
                            // file refreshes.
                            _uiState.update { it.copy(uiSheet = null) }
                        }
                    }
                    return
                }
                _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.RemoveFromGroup(action.contact)) }
            }

            is GroupSettingsUiAction.ConnectToIdentity -> {
                viewModelScope.launch {
                    val currentUser = credentialsManager.requireActiveCredentials().domain
                    val url = currentUser.buildConnectToIdentityUrl(action.odinId)
                    _uiState.update { it.copy(uiEvent = GroupSettingsUiEvent.OpenUrl(url)) }
                }
            }

            is GroupSettingsUiAction.HealGroupClicked -> {
                val conversation = uiState.value.conversation ?: return
                if (uiState.value.isHealing) return
                if (!uiState.value.canHeal) return
                val plan = buildHealPlan(uiState.value)
                val initialItems = buildInitialProgressItems(plan)
                _uiState.update {
                    it.copy(
                        isHealing = true,
                        uiSheet = GroupSettingsUiSheet.HealProgress(initialItems, finished = false),
                    )
                }
                viewModelScope.launch {
                    try {
                        Logger.i("GroupSettings: heal requested for ${conversation.id} plan=$plan")
                        logTransferSnapshot("BEFORE heal", conversation.id, uiState.value)
                        val result = groupHealService.healGroupDistribution(
                            conversation.id,
                            plan,
                        ) { phase -> applyHealPhase(phase) }
                        Logger.i(
                            "GroupSettings: heal result for ${conversation.id} " +
                                "mainHealed=${result.mainHealed} adminHealed=${result.adminHealed} " +
                                "mainRecipients=${result.mainRecipientCount} " +
                                "adminRecipients=${result.adminRecipientCount} " +
                                "healMsgRecipients=${result.healMessageRecipientCount} " +
                                "mainFileId=${result.mainFileId} adminFileId=${result.adminFileId} " +
                                "healMessageUniqueId=${result.healMessageUniqueId}"
                        )
                        // Poll the server-side transfer-history for each
                        // enqueued file in parallel, flipping per-(peer, kind)
                        // rows from InFlight → Done/Failed/StillQueued as
                        // peer-transit results settle. Bounded window — the
                        // outbox keeps retrying for hours, but we stop
                        // watching after [HEAL_POLL_WINDOW_MS]. Polling runs
                        // INSIDE this launch so the dialog's `finished=true`
                        // flag only flips once watching is over.
                        pollHealDeliveryOutcomes(result)
                        // Re-load transfer history immediately. Skip the trailing
                        // peer-exists recheck — the heal just bumped our local
                        // vt; every peer is technically Stale until transit
                        // lands, and running the probe now would paint everyone
                        // red. Schedule a delayed recheck instead.
                        loadTransferHistory(conversation, recheckPeerExists = false)
                        logTransferSnapshot("AFTER heal", conversation.id, uiState.value)
                        scheduleDelayedPeerExistsRecheck(conversation)
                        _uiState.update { state ->
                            val sheet = state.uiSheet
                            state.copy(
                                isHealing = false,
                                uiSheet = if (sheet is GroupSettingsUiSheet.HealProgress) sheet.copy(finished = true) else sheet,
                                uiEvent = GroupSettingsUiEvent.HealCompleted(
                                    mainHealed = result.mainHealed,
                                    adminHealed = result.adminHealed,
                                    mainRecipientCount = result.mainRecipientCount,
                                    adminRecipientCount = result.adminRecipientCount,
                                    healMessageRecipientCount = result.healMessageRecipientCount,
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e("GroupSettings: heal failed for ${conversation.id}", e)
                        _uiState.update { state ->
                            val sheet = state.uiSheet
                            state.copy(
                                isHealing = false,
                                uiSheet = if (sheet is GroupSettingsUiSheet.HealProgress) sheet.copy(finished = true) else sheet,
                                uiEvent = Error("Failed to heal group: ${e.message ?: "unknown error"}")
                            )
                        }
                    }
                }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    fun dialogClosed() {
        _uiState.update { it.copy(uiDialog = null) }
    }

    fun bottomSheetDismissed() {
        _uiState.update { it.copy(uiSheet = null) }
    }

    /**
     * True when at least one loaded participant is both Connected (transit-reachable)
     * and not already an admin — i.e. someone the sole admin could promote and hand off to.
     */
    /**
     * Wraps a per-member service call with the in-flight tracking that drives the
     * spinner in the member-action sheet. Adds [odinId] to [GroupSettingsUiState.pendingMemberOps]
     * before invoking [block]; clears it in a finally block so the spinner always
     * unwinds even on cancellation. On exception, surfaces an error event with
     * [errorMessage] and returns false; otherwise returns true.
     *
     * If there is no current conversation in state, this is a no-op returning false.
     */
    private suspend fun runMemberOp(
        odinId: OdinId,
        errorMessage: String,
        block: suspend (ConversationUiModel) -> Unit,
    ): Boolean {
        val conversation = uiState.value.conversation ?: return false
        _uiState.update { it.copy(pendingMemberOps = it.pendingMemberOps + odinId) }
        return try {
            block(conversation)
            true
        } catch (e: Exception) {
            Logger.e(errorMessage, e)
            _uiState.update { it.copy(uiEvent = Error(errorMessage)) }
            false
        } finally {
            _uiState.update { it.copy(pendingMemberOps = it.pendingMemberOps - odinId) }
        }
    }

    private fun hasReachableNonAdmin(conversation: ConversationUiModel): Boolean {
        return uiState.value.contacts.any { contact ->
            contact.connectionState == ContactConnectionState.Connected &&
                    !conversation.isCurrentUserAdmin(contact.odinId)
        }
    }

    private fun loadData(conversation: ConversationUiModel) {
        viewModelScope.launch {
            try {
                val domain = credentialsManager.requireActiveCredentials().domain

                val contacts = conversation.participants
                    .filter { it != domain }
                    .map { odinId ->
                        contactService.resolveByOdinId(odinId) ?: ContactUiModel(
                            id = Uuid.random(),
                            odinId = odinId,
                            name = odinId.domainName,
                            avatarInitials = odinId.domainName.initials(),
                            connectionState = ContactConnectionState.NotConnected
                        )
                    }

                _uiState.update {
                    it.copy(
                        conversation = conversation,
                        contacts = contacts,
                        currentOdinId = domain,
                        isCurrentUserGroupAdmin = conversation.isCurrentUserAdmin(domain),
                        isLegacyGroup = conversation.isLegacyGroup,
                        isLoading = false,
                    )
                }

                if (conversation.isGroupConversation && !conversation.isLegacyGroup) {
                    loadTransferHistory(conversation)
                }
            } catch (e: Exception) {
                Logger.e("Failed to load conversation", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * For each of the two group files (main conversation + admin), fetches the per-recipient
     * transfer-history if (and only if) the current user is the original author of that file.
     * Mirrors the loader in [id.homebase.chat.messageinfo.MessageInfoViewModel] — same call,
     * same status mapping. Result is exposed in [GroupSettingsUiState.mainFileTransfer] /
     * [GroupSettingsUiState.adminFileTransfer]; null map = column hidden in the UI.
     */
    private suspend fun loadTransferHistory(
        conversation: ConversationUiModel,
        /**
         * When false, the trailing [loadPeerFileExists] is skipped. The heal
         * completion path uses this to avoid the "everyone instantly shows
         * Stale" flash that happens because the heal bumps our local vt and
         * the recheck races transit. The heal handler schedules its own
         * delayed recheck via [scheduleDelayedPeerExistsRecheck] instead.
         */
        recheckPeerExists: Boolean = true,
    ) {
        val domain = credentialsManager.requireActiveDomain()

        val mainFile = try {
            conversationService.getConversationHomebaseFile(conversation.id)
        } catch (e: Exception) {
            Logger.w(throwable = e) { "loadTransferHistory: failed to load main file ${conversation.id}" }
            null
        }
        val adminFile = try {
            conversationService.getConversationAdminHomebaseFile(conversation.id)
        } catch (e: Exception) {
            Logger.w(throwable = e) { "loadTransferHistory: failed to load admin file ${conversation.id}" }
            null
        }

        val mainTransfer = fetchTransferIfAuthor("main", domain, mainFile)
        val adminTransfer = fetchTransferIfAuthor("admin", domain, adminFile)

        // DB-vs-own-server diagnostic. Local rows come from the file reads
        // above (already in memory). Server rows are fetched directly from
        // the user's own drive — `getFileHeaderByUid` hits
        // `/drives/{driveId}/files/by-uid/{uniqueId}/header` and returns
        // null on 404, so we get the truth regardless of whether drive-sync
        // is current. The two GETs run in parallel; they're cheap (header
        // only, no payloads) and only fire when this screen is open.
        val dbGroup = toDbRow(mainFile)
        val dbAdmin = toDbRow(adminFile)
        val expectedMainUniqueId = conversation.id // main file's uniqueId == conversationId by construction
        val expectedAdminUniqueId = ChatProtocol.getAdminFileUniqueId(conversation.id)

        val (serverMainFile, serverAdminFile) = try {
            coroutineScope {
                val main = async {
                    runCatching {
                        driveFileProvider.getFileHeaderByUid(chatTargetDrive.alias, expectedMainUniqueId)
                    }.onFailure {
                        Logger.w(throwable = it) { "loadTransferHistory: getFileHeaderByUid(main) failed for ${conversation.id}" }
                    }.getOrNull()
                }
                val admin = async {
                    runCatching {
                        driveFileProvider.getFileHeaderByUid(chatTargetDrive.alias, expectedAdminUniqueId)
                    }.onFailure {
                        Logger.w(throwable = it) { "loadTransferHistory: getFileHeaderByUid(admin) failed for ${conversation.id}" }
                    }.getOrNull()
                }
                listOf(main, admin).awaitAll().let { it[0] to it[1] }
            }
        } catch (e: Exception) {
            Logger.w(throwable = e) { "loadTransferHistory: parallel server header fetch failed for ${conversation.id}" }
            null to null
        }

        val diagnostic = GroupFilesDiagnostic(
            conversationId = conversation.id,
            expectedMainUniqueId = expectedMainUniqueId,
            expectedAdminUniqueId = expectedAdminUniqueId,
            dbGroup = dbGroup,
            dbAdmin = dbAdmin,
            serverGroup = toServerRow(serverMainFile),
            serverAdmin = toServerRow(serverAdminFile),
        )

        Logger.d {
            "loadTransferHistory: ${conversation.id} mainAuthored=${mainTransfer != null} mainEntries=${mainTransfer?.size} " +
                    "adminAuthored=${adminTransfer != null} adminEntries=${adminTransfer?.size} " +
                    "main=${renderTransferMap(mainTransfer)} admin=${renderTransferMap(adminTransfer)}"
        }
        Logger.i {
            "GroupSettings: filesDiagnostic conversationId=${conversation.id} " +
                "dbGroup=${describeDb(diagnostic.dbGroup)} dbAdmin=${describeDb(diagnostic.dbAdmin)} " +
                "serverGroup=${describeServer(diagnostic.serverGroup)} serverAdmin=${describeServer(diagnostic.serverAdmin)} " +
                "expectedMainUid=${diagnostic.expectedMainUniqueId} expectedAdminUid=${diagnostic.expectedAdminUniqueId}"
        }

        _uiState.update {
            it.copy(
                mainFileTransfer = mainTransfer,
                adminFileTransfer = adminTransfer,
                filesDiagnostic = diagnostic,
            )
        }

        if (recheckPeerExists) {
            loadPeerFileExists(
                conversation = conversation,
                domain = domain,
                mainFile = mainFile,
                adminFile = adminFile,
            )
        } else {
            Logger.i(tag = "HealAudit") {
                "loadTransferHistory: skipping peer-exists recheck (recheckPeerExists=false) for ${conversation.id} — heal handler will schedule a delayed recheck"
            }
        }
    }

    /**
     * For each group recipient, asks the user's own server "does the peer hold
     * this group file right now, and at what versionTag?" via the V2 fileExists
     * endpoint. Fired once at screen-load (off the back of [loadTransferHistory]).
     *
     * Author-only — only the file's original author gets a meaningful versionTag
     * back from the server, which is what lets us tell InSync apart from Stale.
     * For non-authors the column is hidden, matching the existing transfer-history
     * gate in [fetchTransferIfAuthor].
     *
     * One coroutine per recipient × authored-file, gated by a [Semaphore] so we
     * never have more than 7 in-flight peer queries at once. Each completion
     * atomically merges its result into the per-file map on uiState.
     */
    private suspend fun loadPeerFileExists(
        conversation: ConversationUiModel,
        domain: OdinId,
        mainFile: HomebaseFile?,
        adminFile: HomebaseFile?,
        reason: String = "screen-open",
    ) {
        val recipients = conversation.participants.filter { it != domain }
        if (recipients.isEmpty()) {
            Logger.d(tag = "HealAudit") { "loadPeerFileExists($reason): conversationId=${conversation.id} no recipients — skipping" }
            return
        }

        val mainAuthored = mainFile != null && isAuthor(mainFile, domain)
        val adminAuthored = adminFile != null && isAuthor(adminFile, domain)
        if (!mainAuthored && !adminAuthored) {
            Logger.d(tag = "HealAudit") {
                "loadPeerFileExists($reason): conversationId=${conversation.id} caller=$domain not the author of either file — skipping " +
                    "(mainAuthor=${mainFile?.fileMetadata?.originalAuthor ?: mainFile?.fileMetadata?.senderOdinId}, " +
                    "adminAuthor=${adminFile?.fileMetadata?.originalAuthor ?: adminFile?.fileMetadata?.senderOdinId})"
            }
            return
        }

        val drive = chatTargetDrive.alias
        val mainUid = conversation.id
        val adminUid = ChatProtocol.getAdminFileUniqueId(conversation.id)
        val mainLocalVersion = mainFile?.fileMetadata?.versionTag
        val adminLocalVersion = adminFile?.fileMetadata?.versionTag

        Logger.i(tag = "HealAudit") {
            "loadPeerFileExists($reason): START conversationId=${conversation.id} " +
                "recipients=${recipients.map { it.domainName }} " +
                "mainAuthored=$mainAuthored mainLocalVersion=$mainLocalVersion " +
                "adminAuthored=$adminAuthored adminLocalVersion=$adminLocalVersion"
        }

        // Seed visible columns with Loading for every recipient so the UI shows
        // spinners immediately instead of empty cells.
        _uiState.update { state ->
            state.copy(
                mainFileExists = if (mainAuthored)
                    recipients.associateWith { MemberFileExistsStatus.Loading }
                else null,
                adminFileExists = if (adminAuthored)
                    recipients.associateWith { MemberFileExistsStatus.Loading }
                else null,
            )
        }

        val gate = Semaphore(permits = 7)

        for (recipient in recipients) {
            if (mainAuthored) {
                viewModelScope.launch {
                    val status = gate.withPermit {
                        queryPeerFileExists("main", recipient, drive, mainUid, mainLocalVersion)
                    }
                    Logger.i(tag = "HealAudit") {
                        "loadPeerFileExists($reason): main result peer=${recipient.domainName} → ${renderExistsStatus(status, mainLocalVersion)}"
                    }
                    _uiState.update { s ->
                        s.copy(mainFileExists = s.mainFileExists?.plus(recipient to status))
                    }
                }
            }
            if (adminAuthored) {
                viewModelScope.launch {
                    val status = gate.withPermit {
                        queryPeerFileExists("admin", recipient, drive, adminUid, adminLocalVersion)
                    }
                    Logger.i(tag = "HealAudit") {
                        "loadPeerFileExists($reason): admin result peer=${recipient.domainName} → ${renderExistsStatus(status, adminLocalVersion)}"
                    }
                    _uiState.update { s ->
                        s.copy(adminFileExists = s.adminFileExists?.plus(recipient to status))
                    }
                }
            }
        }
    }

    private fun renderExistsStatus(status: MemberFileExistsStatus, ourVersion: Uuid?): String = when (status) {
        MemberFileExistsStatus.Loading -> "Loading"
        MemberFileExistsStatus.Missing -> "Missing"
        is MemberFileExistsStatus.InSync -> "InSync(version=${status.versionTag})"
        is MemberFileExistsStatus.Stale -> "Stale(peerVersion=${status.peerVersionTag}, ourVersion=$ourVersion)"
        MemberFileExistsStatus.Error -> "Error"
    }

    private suspend fun queryPeerFileExists(
        label: String,
        peer: OdinId,
        drive: Uuid,
        uniqueId: Uuid,
        localVersionTag: Uuid?,
    ): MemberFileExistsStatus = try {
        val resp = peerDriveQueryProvider.fileExistsByUniqueId(peer, drive, uniqueId)
        val peerVersion = resp.versionTag
        when {
            !resp.exists -> MemberFileExistsStatus.Missing
            peerVersion != null && peerVersion == localVersionTag ->
                MemberFileExistsStatus.InSync(peerVersion)
            else -> MemberFileExistsStatus.Stale(peerVersion)
        }
    } catch (e: Exception) {
        Logger.w(throwable = e, tag = "HealAudit") {
            "queryPeerFileExists: fileExists($label, peer=${peer.domainName}, drive=$drive, uid=$uniqueId) failed"
        }
        MemberFileExistsStatus.Error
    }

    private fun isAuthor(file: HomebaseFile, currentUser: OdinId): Boolean {
        val author = file.fileMetadata.originalAuthor ?: file.fileMetadata.senderOdinId
        return author == currentUser
    }

    private fun describeDb(row: DbFileRow): String = when (row) {
        is DbFileRow.Present -> "Present(vt=${row.versionTag}, author=${row.originalAuthor.domainName}, fileId=${row.fileId})"
        is DbFileRow.Placeholder -> "Placeholder(fileId=${row.fileId})"
        DbFileRow.Absent -> "Absent"
    }

    private fun describeServer(row: ServerFileRow): String = when (row) {
        is ServerFileRow.Present -> "Present(vt=${row.versionTag}, author=${row.originalAuthor.domainName}, fileId=${row.fileId})"
        ServerFileRow.Absent -> "Absent"
    }

    private fun renderTransferMap(map: Map<OdinId, RecipientFileStatus>?): String {
        if (map == null) return "<column hidden — caller is not author>"
        if (map.isEmpty()) return "<no recipients in transfer history>"
        return map.entries.joinToString(prefix = "{", postfix = "}") { (recipient, status) ->
            val s = when (status) {
                RecipientFileStatus.Ok -> "OK"
                is RecipientFileStatus.Problem -> "PROBLEM(${status.rawStatus})"
            }
            "$recipient=$s"
        }
    }

    /**
     * Logs the per-recipient status of both group files in a single multi-line entry.
     * Called before and after a heal so the diff is easy to read in homebase.log when
     * diagnosing "I clicked heal but recipient X still doesn't have it".
     */
    private fun logTransferSnapshot(label: String, conversationId: Uuid, state: GroupSettingsUiState) {
        Logger.i {
            "GroupSettings: $label snapshot conversationId=$conversationId " +
                    "main=${renderTransferMap(state.mainFileTransfer)} admin=${renderTransferMap(state.adminFileTransfer)}"
        }
    }

    private suspend fun fetchTransferIfAuthor(
        label: String,
        currentUser: OdinId,
        file: HomebaseFile?
    ): Map<OdinId, RecipientFileStatus>? {
        if (file == null) return null
        val author = file.fileMetadata.originalAuthor ?: file.fileMetadata.senderOdinId
        if (author != currentUser) {
            Logger.d { "loadTransferHistory: skipping $label — caller=$currentUser is not author=$author" }
            return null
        }

        return try {
            val history = driveFileProvider.getTransferHistory(chatTargetDrive.alias, file.fileId)
            val results = history?.history?.results ?: emptyList()
            results.associate { entry: RecipientTransferHistoryEntry ->
                val odinId = OdinId(entry.recipient)
                // Match the per-message status mapping in
                // ChatDeliveryStatus.toChatDeliveryStatus: when the latest known
                // status is Delivered, that wins over `isInOutbox`. Right after
                // pressing "Heal group" we re-enqueue the file, so the server
                // briefly returns `latestTransferStatus = Delivered` (from the
                // prior successful send) AND `isInOutbox = true` (because we
                // just kicked it back into the outbox). Treating that combo as
                // a Problem produced a misleading "PROBLEM(Delivered)" log
                // entry and a transient red icon for ~180ms after each heal.
                // The recipient is fine — the file has been delivered before
                // and is currently being redistributed.
                val status = if (entry.latestTransferStatus == TransferStatus.Delivered) {
                    RecipientFileStatus.Ok
                } else {
                    RecipientFileStatus.Problem(
                        rawStatus = entry.latestTransferStatus,
                        detailRes = entry.latestTransferStatus.toErrorDetailRes()
                    )
                }
                odinId to status
            }
        } catch (e: Exception) {
            Logger.w(throwable = e) { "loadTransferHistory: getTransferHistory($label) failed for fileId=${file.fileId}" }
            null
        }
    }

    /**
     * Build the per-(recipient, file) [HealPlan] from the
     * currently-loaded [MemberFileExistsStatus] maps.
     *
     * - For each file, peers reported `Missing` go into the resend set; peers
     *   reported `Stale` go into the heal-message set; `InSync` peers are
     *   excluded entirely.
     * - `Error` peers are filtered out — they get skipped this round (we don't
     *   know whether resending or heal-messaging them is safe).
     * - The Heal button is gated on `canHeal`, which already blocks while any
     *   entry is `Loading`, so we never see Loading here.
     * - When a file's map is `null` (caller is not the author of that file) or
     *   the map exists but yields zero usable peers (all-Error), the plan's
     *   per-file slot becomes `null` — the service then falls back to its
     *   legacy "redistribute to every participant" behavior for that file.
     *
     * The heal-message slot is null only when neither file gave us any usable
     * signal at all; otherwise it's the (possibly empty) union of Stale peers
     * across both files. Empty means "skip the message — no Stale peers need
     * cleanup", non-empty means "address it to exactly this set".
     */
    private fun buildHealPlan(state: GroupSettingsUiState): HealPlan {
        // KNOWN ARCHITECTURAL LIMITATION — read before touching this logic:
        //
        // Heal redistribution goes through ConversationService.updateConversationInternal
        // which rewrites the file server-side and bumps its versionTag. The
        // upgrade-from-pre-bump-vt update is then transited to recipients.
        //
        // What the server's peer transit actually does (per Michael, owner of
        // the protocol): if the peer's existing file versionTag doesn't match
        // the incoming update's expected vt, the peer-side apply REJECTS the
        // push. So a "resend to previously-InSync peer" doesn't actually fix
        // them — their server-side vt stays at the pre-bump value forever,
        // they're now Stale relative to our new vt with no remediation path.
        //
        // This means heal as currently implemented CANNOT recover previously-
        // InSync peers when our vt bumps. The only honest move is to:
        //   - Resend to peers we CAN heal: Missing peers (create branch on
        //     peer's server) and peers who just cleaned up to vt=null via
        //     the heal-message.
        //   - Send heal-message to anyone non-InSync so they cleanup first;
        //     subsequent peer push lands on their now-empty drive.
        //
        // Peers that look InSync right now WILL desync the moment we press
        // Heal. There's no client-side fix for this — it needs either a
        // server endpoint that ships an existing file to a peer without
        // rewriting the local copy, or the ability to specify the new vt
        // client-side so the heal-message can announce the post-bump vt
        // (forcing all peers to cleanup). Both are out of scope here;
        // tracked for another pass.
        //
        // For the current cycle, the most honest plan is: resend only to
        // peers who can actually accept the push (Missing peers), and let
        // previously-InSync peers be picked up on the NEXT heal cycle after
        // a future event has re-classified them as Stale.
        val conversation = state.conversation ?: return HealPlan(null, null, null, null)
        val self = state.currentOdinId
        val recipients = conversation.participants.filter { it != self }.distinct()

        val mainAuthored = state.mainFileExists != null
        val adminAuthored = state.adminFileExists != null

        // Heal-message goes to every non-InSync peer (Missing/Stale/Error).
        // Cleanup leaves them at vt=null, ready to receive the resend as a
        // fresh create. InSync peers are excluded — sending them the heal-
        // message would be a no-op anyway (their vt matches canonical).
        fun nonInSync(map: Map<OdinId, MemberFileExistsStatus>?): List<OdinId> =
            map?.entries.orEmpty()
                .filter { it.value !is MemberFileExistsStatus.InSync }
                .map { it.key }

        // Resend goes only to peers the server's peer transit will actually
        // accept: Missing peers. Peers that JUST cleaned up via heal-message
        // are also Missing at the moment the resend lands, so they get it
        // too — that's handled implicitly because we re-poll peer-exists
        // after the cleanup writes the placeholder + hard-delete completes
        // server-side. (No, we don't actually re-poll between heal-message
        // and resend within one heal cycle — see TODO below.)
        //
        // TODO(heal-vt-rejection): the underlying primitive rejects pushes
        // to peers whose vt doesn't match the incoming envelope's expected
        // vt. So a peer who got heal-messaged in this same cycle and is now
        // at vt=null may still get rejected because the resend's envelope
        // was constructed with the pre-bump vt as "expected". Need to
        // investigate (a) UpdateLocale.Peer semantics, and (b) whether we
        // can ship a resend that the server accepts against any peer state.
        fun missingOnly(map: Map<OdinId, MemberFileExistsStatus>?): List<OdinId> =
            map?.entries.orEmpty()
                .filter { it.value is MemberFileExistsStatus.Missing }
                .map { it.key }

        return HealPlan(
            resendMainTo = if (mainAuthored) missingOnly(state.mainFileExists) else null,
            resendAdminTo = if (adminAuthored) missingOnly(state.adminFileExists) else null,
            healMessageMainTo = if (mainAuthored) nonInSync(state.mainFileExists) else null,
            healMessageAdminTo = if (adminAuthored) nonInSync(state.adminFileExists) else null,
        )
    }

    /**
     * Seed the progress sheet with one row per (peer, action) the heal plans to
     * perform. Rows start [HealProgressState.Pending]; the [applyHealPhase]
     * callback transitions them to InFlight / Done / Failed / Skipped as the
     * service emits [HealPhase] events.
     *
     * Order: heal-request rows first (those peers do nothing until they get the
     * request), then group-file resends, then admin-file resends. Within each
     * group rows are sorted by peer domain for stable display.
     *
     * A null per-file slot in the plan means "no per-recipient signal — fall
     * back to all-recipients legacy behavior". We still surface those rows
     * (one per peer in the conversation's participant list, minus self) so
     * the user sees what the heal is actually doing.
     */
    private fun buildInitialProgressItems(plan: HealPlan): List<HealProgressItem> {
        val conversation = uiState.value.conversation ?: return emptyList()
        val self = uiState.value.currentOdinId
        val fallback = conversation.participants.filter { it != self }.distinct()

        val mainResend = (plan.resendMainTo ?: fallback).sortedBy { it.domainName }
        val adminResend = (plan.resendAdminTo ?: fallback).sortedBy { it.domainName }
        // Split heal-request by which file is stale so a peer with both stale
        // gets two rows ("clean up group file" + "clean up admin file"). The
        // single underlying status message resolves both rows simultaneously
        // when applyHealPhase fires HealMessageSent for the union.
        val healMain = (plan.healMessageMainTo ?: fallback).sortedBy { it.domainName }
        val healAdmin = (plan.healMessageAdminTo ?: fallback).sortedBy { it.domainName }

        return buildList {
            healMain.forEach { add(HealProgressItem("healMain-${it.domainName}", HealActionKind.HealRequestGroupFile, it, HealProgressState.Pending)) }
            healAdmin.forEach { add(HealProgressItem("healAdmin-${it.domainName}", HealActionKind.HealRequestAdminFile, it, HealProgressState.Pending)) }
            mainResend.forEach { add(HealProgressItem("main-${it.domainName}", HealActionKind.GroupFile, it, HealProgressState.Pending)) }
            adminResend.forEach { add(HealProgressItem("admin-${it.domainName}", HealActionKind.AdminFile, it, HealProgressState.Pending)) }
        }
    }

    /**
     * Walk the current progress items and update any whose (kind, peer) matches
     * the given phase. The service's phase callback is invoked synchronously on
     * the same coroutine that called [ConversationService.healGroupDistribution]
     * (via [viewModelScope.launch]), so [_uiState.update] from here is safe.
     */
    private fun applyHealPhase(phase: HealPhase) {
        // HealMessage* phases map to BOTH heal-request kinds — the single status
        // message covers main AND admin file cleanup, so any (peer, file) row
        // whose peer is in the message audience transitions together.
        //
        // *Sent events deliberately do NOT flip rows to Done — they only mean
        // "successfully enqueued on our own server". The real per-recipient
        // outcome arrives via transfer-history polling in
        // [pollHealDeliveryOutcomes], which is what actually moves rows to
        // Done/Failed/StillQueued. We leave them at InFlight so the spinner
        // keeps running until polling resolves.
        val (targetKinds, newState) = when (phase) {
            is HealPhase.HealMessageSending -> HEAL_REQUEST_KINDS to HealProgressState.InFlight
            is HealPhase.HealMessageSent -> return  // polling owns Done/Failed
            is HealPhase.HealMessageFailed -> HEAL_REQUEST_KINDS to HealProgressState.Failed
            is HealPhase.HealMessageSkipped -> HEAL_REQUEST_KINDS to HealProgressState.Skipped
            is HealPhase.MainSending -> setOf(HealActionKind.GroupFile) to HealProgressState.InFlight
            is HealPhase.MainSent -> return        // polling owns Done/Failed
            is HealPhase.MainFailed -> setOf(HealActionKind.GroupFile) to HealProgressState.Failed
            is HealPhase.MainSkipped -> setOf(HealActionKind.GroupFile) to HealProgressState.Skipped
            is HealPhase.AdminSending -> setOf(HealActionKind.AdminFile) to HealProgressState.InFlight
            is HealPhase.AdminSent -> return       // polling owns Done/Failed
            is HealPhase.AdminFailed -> setOf(HealActionKind.AdminFile) to HealProgressState.Failed
            is HealPhase.AdminSkipped -> setOf(HealActionKind.AdminFile) to HealProgressState.Skipped
        }
        val recipients = phase.recipients.toSet()
        Logger.i(tag = "HealAudit") {
            "applyHealPhase: ${phase::class.simpleName} kinds=$targetKinds newState=$newState " +
                "recipients=${phase.recipients.map { it.domainName }}"
        }
        _uiState.update { state ->
            val sheet = state.uiSheet
            if (sheet !is GroupSettingsUiSheet.HealProgress) return@update state
            val updatedItems = sheet.items.map { item ->
                if (item.kind in targetKinds && item.peer in recipients) item.copy(state = newState)
                else item
            }
            state.copy(uiSheet = sheet.copy(items = updatedItems))
        }
    }

    /**
     * After heal enqueues, poll the server-side transfer-history for each
     * enqueued file in parallel, flipping per-(peer, kind) rows from InFlight
     * to a terminal state (Done / Failed / StillQueued) as peer-transit
     * results settle. Skips fileIds the heal didn't actually enqueue (null
     * fileId, empty target set).
     *
     * Three concurrent loops: main file, admin file, heal-request status
     * message. Each runs until either every targeted recipient has reached a
     * terminal transfer status OR the polling window expires. Any recipient
     * still unsettled at timeout transitions to StillQueued — the outbox
     * keeps retrying for hours in the background, but for this dialog we
     * stop watching.
     */
    private suspend fun pollHealDeliveryOutcomes(result: HealGroupResult) {
        // Resolve the heal-request status message's fileId from its uniqueId.
        // sendStatusMessage doesn't return a fileId; we look it up locally
        // after enqueue. May return null if the local DB write hasn't landed
        // yet — we retry a few times below.
        val healMessageFileId = result.healMessageUniqueId?.let { resolveStatusMessageFileId(it) }
        Logger.i(tag = "HealAudit") {
            "pollHealDeliveryOutcomes: starting parallel polls " +
                "mainFileId=${result.mainFileId} adminFileId=${result.adminFileId} " +
                "healMessageFileId=$healMessageFileId " +
                "mainTargets=${result.mainTargets.map { it.domainName }} " +
                "adminTargets=${result.adminTargets.map { it.domainName }} " +
                "healMessageTargets=${result.healMessageTargets.map { it.domainName }}"
        }
        coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Job>()
            if (result.mainFileId != null && result.mainTargets.isNotEmpty()) {
                jobs += launch {
                    pollOneFileDelivery(
                        fileId = result.mainFileId,
                        targets = result.mainTargets.toSet(),
                        kinds = setOf(HealActionKind.GroupFile),
                        label = "main",
                    )
                }
            }
            if (result.adminFileId != null && result.adminTargets.isNotEmpty()) {
                jobs += launch {
                    pollOneFileDelivery(
                        fileId = result.adminFileId,
                        targets = result.adminTargets.toSet(),
                        kinds = setOf(HealActionKind.AdminFile),
                        label = "admin",
                    )
                }
            }
            if (healMessageFileId != null && result.healMessageTargets.isNotEmpty()) {
                jobs += launch {
                    // Single status message covers BOTH heal-request kinds for
                    // a given peer, so any per-peer delivery outcome flips
                    // both rows together.
                    pollOneFileDelivery(
                        fileId = healMessageFileId,
                        targets = result.healMessageTargets.toSet(),
                        kinds = HEAL_REQUEST_KINDS,
                        label = "healMessage",
                    )
                }
            }
            jobs.forEach { it.join() }
        }
    }

    /**
     * Look up a freshly-enqueued status message's fileId from its uniqueId.
     * The local DB write happens during sendStatusMessage but the file
     * header lookup may briefly miss; retry a few times with a small backoff
     * before giving up.
     */
    private suspend fun resolveStatusMessageFileId(uniqueId: Uuid): Uuid? {
        repeat(HEAL_FILEID_LOOKUP_RETRIES) {
            try {
                val header = driveFileProvider.getFileHeaderByUid(chatTargetDrive.alias, uniqueId)
                if (header != null) return header.fileId
            } catch (e: Exception) {
                Logger.w(throwable = e, tag = "HealAudit") {
                    "resolveStatusMessageFileId: getFileHeaderByUid threw for uniqueId=$uniqueId"
                }
            }
            delay(HEAL_FILEID_LOOKUP_INTERVAL_MS)
        }
        Logger.w(tag = "HealAudit") {
            "resolveStatusMessageFileId: gave up resolving uniqueId=$uniqueId after $HEAL_FILEID_LOOKUP_RETRIES tries"
        }
        return null
    }

    /**
     * Poll the transfer-history endpoint for a single fileId until every
     * targeted recipient has reached a terminal status or the window
     * expires. Per-recipient row updates are pushed into the heal-progress
     * sheet as results settle.
     *
     * Termination rules per recipient (from RecipientTransferHistoryEntry):
     *   - latestTransferStatus == Delivered → Done
     *   - latestTransferStatus is in failedStatuses AND !isInOutbox → Failed
     *   - otherwise (server still retrying, or peer unresponsive but the
     *     outbox hasn't given up) → keep polling; at timeout → StillQueued
     *
     * We do NOT try to map specific failure codes to UI text — the user just
     * needs to know "got through" vs "didn't" per peer. A peer rejecting on
     * vt mismatch surfaces here as RecipientIdentityReturnedBadRequest, same
     * bucket as any other 400-class peer-side rejection.
     */
    private suspend fun pollOneFileDelivery(
        fileId: Uuid,
        targets: Set<OdinId>,
        kinds: Set<HealActionKind>,
        label: String,
    ) {
        val settled = mutableSetOf<OdinId>()
        withTimeoutOrNull(HEAL_POLL_WINDOW_MS) {
            while (settled.size < targets.size) {
                delay(HEAL_POLL_INTERVAL_MS)
                val history = try {
                    driveFileProvider.getTransferHistory(chatTargetDrive.alias, fileId)
                } catch (e: Exception) {
                    Logger.w(throwable = e, tag = "HealAudit") {
                        "pollOneFileDelivery($label): getTransferHistory threw for fileId=$fileId — will retry next tick"
                    }
                    continue
                }
                val entries = history?.history?.results ?: continue
                for (entry in entries) {
                    val peer = OdinId(entry.recipient)
                    if (peer !in targets || peer in settled) continue
                    val terminal = classifyDelivery(entry)
                    if (terminal != null) {
                        settled.add(peer)
                        Logger.i(tag = "HealAudit") {
                            "pollOneFileDelivery($label): peer=${peer.domainName} settled at $terminal " +
                                "(latestTransferStatus=${entry.latestTransferStatus} isInOutbox=${entry.isInOutbox})"
                        }
                        setRowState(kinds, peer, terminal)
                    }
                }
            }
        }
        // Anyone still unsettled at the window edge becomes StillQueued so
        // the dialog stops showing a spinner forever. The outbox may still
        // deliver to them in the background.
        val unsettled = targets - settled
        if (unsettled.isNotEmpty()) {
            Logger.i(tag = "HealAudit") {
                "pollOneFileDelivery($label): window expired with unsettled peers=${unsettled.map { it.domainName }} — marking StillQueued"
            }
            unsettled.forEach { peer -> setRowState(kinds, peer, HealProgressState.StillQueued) }
        }
    }

    private fun classifyDelivery(entry: RecipientTransferHistoryEntry): HealProgressState? {
        if (entry.latestTransferStatus == TransferStatus.Delivered) return HealProgressState.Done
        if (TransferStatus.isFailedStatus(entry.latestTransferStatus) && !entry.isInOutbox) {
            return HealProgressState.Failed
        }
        return null
    }

    private fun setRowState(kinds: Set<HealActionKind>, peer: OdinId, newState: HealProgressState) {
        _uiState.update { state ->
            val sheet = state.uiSheet
            if (sheet !is GroupSettingsUiSheet.HealProgress) return@update state
            val updatedItems = sheet.items.map { item ->
                if (item.kind in kinds && item.peer == peer) item.copy(state = newState) else item
            }
            state.copy(uiSheet = sheet.copy(items = updatedItems))
        }
    }

    /**
     * Schedule a peer-exists recheck a few seconds after a heal completes.
     * Heal redistribution server-side rewrites the file and hands us back a
     * new versionTag; transit then ships the new copy to each recipient async.
     * If we re-poll peer-exists immediately, every peer still has the OLD
     * versionTag → all classified as Stale → red flash everywhere.
     *
     * Waiting [POST_HEAL_RECHECK_DELAY_MS] gives transit time to land for
     * peers on healthy networks. Peers whose servers are slow or offline will
     * still show Stale/Missing afterwards — that's an accurate state, not the
     * versionTag race.
     */
    private fun scheduleDelayedPeerExistsRecheck(conversation: ConversationUiModel) {
        viewModelScope.launch {
            Logger.i(tag = "HealAudit") {
                "scheduleDelayedPeerExistsRecheck: waiting ${POST_HEAL_RECHECK_DELAY_MS}ms before re-querying peer-exists for ${conversation.id}"
            }
            delay(POST_HEAL_RECHECK_DELAY_MS)
            val domain = credentialsManager.requireActiveDomain()
            val mainFile = try { conversationService.getConversationHomebaseFile(conversation.id) } catch (e: Exception) { null }
            val adminFile = try { conversationService.getConversationAdminHomebaseFile(conversation.id) } catch (e: Exception) { null }
            Logger.i(tag = "HealAudit") {
                "scheduleDelayedPeerExistsRecheck: firing post-heal peer-exists for ${conversation.id} mainVt=${mainFile?.fileMetadata?.versionTag} adminVt=${adminFile?.fileMetadata?.versionTag}"
            }
            loadPeerFileExists(
                conversation = conversation,
                domain = domain,
                mainFile = mainFile,
                adminFile = adminFile,
                reason = "post-heal",
            )
        }
    }

    companion object {
        private val HEAL_REQUEST_KINDS = setOf(HealActionKind.HealRequestGroupFile, HealActionKind.HealRequestAdminFile)
        /** Pause between heal completion and the peer-exists recheck. Long enough
         *  for transit to plausibly land for healthy peers; short enough that
         *  the UI still feels responsive. Tunable; tracked under HealAudit. */
        private const val POST_HEAL_RECHECK_DELAY_MS = 30_000L
        /** Total time the dialog spends watching transfer-history for each
         *  enqueued file. Stops painting per-row results once expired (any
         *  unsettled rows become StillQueued). The outbox itself keeps
         *  retrying for hours regardless. */
        private const val HEAL_POLL_WINDOW_MS = 60_000L
        /** Gap between successive getTransferHistory polls. The endpoint is
         *  paginated and may briefly return stale results, so polling at
         *  2s gives the server time to actually try a delivery between
         *  ticks without flooding it. */
        private const val HEAL_POLL_INTERVAL_MS = 2_000L
        /** Retries when resolving a freshly-enqueued status message's fileId
         *  from its uniqueId. The local DB write happens during
         *  sendStatusMessage so usually one lookup succeeds; the retry is
         *  defensive against propagation delays. */
        private const val HEAL_FILEID_LOOKUP_RETRIES = 5
        private const val HEAL_FILEID_LOOKUP_INTERVAL_MS = 200L
    }
}