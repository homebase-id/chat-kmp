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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
                        val result = conversationService.healGroupDistribution(
                            conversation.id,
                            plan,
                        ) { phase -> applyHealPhase(phase) }
                        Logger.i(
                            "GroupSettings: heal result for ${conversation.id} " +
                                "mainHealed=${result.mainHealed} adminHealed=${result.adminHealed} " +
                                "mainRecipients=${result.mainRecipientCount} " +
                                "adminRecipients=${result.adminRecipientCount} " +
                                "healMsgRecipients=${result.healMessageRecipientCount}"
                        )
                        // Re-load transfer history so the user sees outbox/sending state immediately
                        loadTransferHistory(conversation)
                        logTransferSnapshot("AFTER heal", conversation.id, uiState.value)
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
    private suspend fun loadTransferHistory(conversation: ConversationUiModel) {
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

        loadPeerFileExists(
            conversation = conversation,
            domain = domain,
            mainFile = mainFile,
            adminFile = adminFile,
        )
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
     * Build the per-(recipient, file) [ConversationService.HealPlan] from the
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
    private fun buildHealPlan(state: GroupSettingsUiState): ConversationService.HealPlan {
        fun resendSet(map: Map<OdinId, MemberFileExistsStatus>?): List<OdinId>? {
            if (map == null) return null
            val anyUsable = map.values.any { it !is MemberFileExistsStatus.Error }
            if (!anyUsable) return null
            return map.entries
                .filter { it.value is MemberFileExistsStatus.Missing }
                .map { it.key }
        }

        fun staleSet(map: Map<OdinId, MemberFileExistsStatus>?): List<OdinId> =
            map?.entries.orEmpty()
                .filter { it.value is MemberFileExistsStatus.Stale }
                .map { it.key }

        val resendMain = resendSet(state.mainFileExists)
        val resendAdmin = resendSet(state.adminFileExists)
        val noUsableSignal = resendMain == null && resendAdmin == null
        val healMessage: List<OdinId>? = if (noUsableSignal) {
            null
        } else {
            (staleSet(state.mainFileExists) + staleSet(state.adminFileExists)).distinct()
        }

        return ConversationService.HealPlan(
            resendMainTo = resendMain,
            resendAdminTo = resendAdmin,
            healMessageTo = healMessage,
        )
    }

    /**
     * Seed the progress sheet with one row per (peer, action) the heal plans to
     * perform. Rows start [HealProgressState.Pending]; the [applyHealPhase]
     * callback transitions them to InFlight / Done / Failed / Skipped as the
     * service emits [ConversationService.HealPhase] events.
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
    private fun buildInitialProgressItems(plan: ConversationService.HealPlan): List<HealProgressItem> {
        val conversation = uiState.value.conversation ?: return emptyList()
        val self = uiState.value.currentOdinId
        val fallback = conversation.participants.filter { it != self }.distinct()

        val mainSet = (plan.resendMainTo ?: fallback).sortedBy { it.domainName }
        val adminSet = (plan.resendAdminTo ?: fallback).sortedBy { it.domainName }
        val healSet = (plan.healMessageTo ?: fallback).sortedBy { it.domainName }

        return buildList {
            healSet.forEach { add(HealProgressItem("heal-${it.domainName}", HealActionKind.HealRequest, it, HealProgressState.Pending)) }
            mainSet.forEach { add(HealProgressItem("main-${it.domainName}", HealActionKind.GroupFile, it, HealProgressState.Pending)) }
            adminSet.forEach { add(HealProgressItem("admin-${it.domainName}", HealActionKind.AdminFile, it, HealProgressState.Pending)) }
        }
    }

    /**
     * Walk the current progress items and update any whose (kind, peer) matches
     * the given phase. The service's phase callback is invoked synchronously on
     * the same coroutine that called [ConversationService.healGroupDistribution]
     * (via [viewModelScope.launch]), so [_uiState.update] from here is safe.
     */
    private fun applyHealPhase(phase: ConversationService.HealPhase) {
        val (targetKind, newState) = when (phase) {
            is ConversationService.HealPhase.HealMessageSending -> HealActionKind.HealRequest to HealProgressState.InFlight
            is ConversationService.HealPhase.HealMessageSent -> HealActionKind.HealRequest to HealProgressState.Done
            is ConversationService.HealPhase.HealMessageFailed -> HealActionKind.HealRequest to HealProgressState.Failed
            is ConversationService.HealPhase.HealMessageSkipped -> HealActionKind.HealRequest to HealProgressState.Skipped
            is ConversationService.HealPhase.MainSending -> HealActionKind.GroupFile to HealProgressState.InFlight
            is ConversationService.HealPhase.MainSent -> HealActionKind.GroupFile to HealProgressState.Done
            is ConversationService.HealPhase.MainFailed -> HealActionKind.GroupFile to HealProgressState.Failed
            is ConversationService.HealPhase.MainSkipped -> HealActionKind.GroupFile to HealProgressState.Skipped
            is ConversationService.HealPhase.AdminSending -> HealActionKind.AdminFile to HealProgressState.InFlight
            is ConversationService.HealPhase.AdminSent -> HealActionKind.AdminFile to HealProgressState.Done
            is ConversationService.HealPhase.AdminFailed -> HealActionKind.AdminFile to HealProgressState.Failed
            is ConversationService.HealPhase.AdminSkipped -> HealActionKind.AdminFile to HealProgressState.Skipped
        }
        val recipients = phase.recipients.toSet()
        Logger.i(tag = "HealAudit") {
            "applyHealPhase: ${phase::class.simpleName} kind=$targetKind newState=$newState " +
                "recipients=${phase.recipients.map { it.domainName }}"
        }
        _uiState.update { state ->
            val sheet = state.uiSheet
            if (sheet !is GroupSettingsUiSheet.HealProgress) return@update state
            val updatedItems = sheet.items.map { item ->
                if (item.kind == targetKind && item.peer in recipients) item.copy(state = newState)
                else item
            }
            state.copy(uiSheet = sheet.copy(items = updatedItems))
        }
    }
}