package id.homebase.core.ui.screens.contactbook.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.follow.FollowNotificationType
import id.homebase.api.client.follow.FollowProvider
import id.homebase.api.client.follow.FollowRequest
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.core.ui.screens.contactbook.appDefaultToggles
import id.homebase.core.ui.screens.contactbook.countsAsOwnerCircle
import id.homebase.core.ui.screens.contactbook.reviewEnrollment
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val TAG = "ReviewConnectionViewModel"

/**
 * Drives the connection review modal for one identity.
 *
 * The payload sent to `/connections/review` is computed here rather than by the server: it must
 * carry the owner's chosen circles *and* the `Connect` circle of every checked app the contact
 * isn't already in. See [reviewEnrollment] for why omitting the latter can leave an approved
 * contact unable to chat.
 */
class ReviewConnectionViewModel(
    private val odinIdArg: String,
    private val connectionService: ConnectionService,
    private val connectionNetworkProvider: ConnectionNetworkProvider,
    private val followProvider: FollowProvider,
    private val securityContextProvider: SecurityContextProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewConnectionUiState(odinId = odinIdArg))
    val uiState: StateFlow<ReviewConnectionUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ReviewConnectionUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ReviewConnectionUiEvent> = _events.asSharedFlow()

    private val odinId = OdinId(odinIdArg)
    private var circleSnapshot: List<CircleWithMembers> = emptyList()

    /** Follow state when the modal opened — the switch only calls out when it actually changed. */
    private var followedAtOpen = false

    /**
     * Normalized aliases of drives this app token holds a grant on. Null = the security context
     * couldn't be read; every circle then renders selectable and the server's rejection is the
     * backstop, rather than disabling circles on a guess.
     */
    private var appDriveAliases: Set<String>? = null

    init {
        viewModelScope.launch {
            // Resolve grantability first so circles never render selectable and then flip to
            // disabled under the user's finger.
            appDriveAliases = runCatching { securityContextProvider.getSecurityContext() }
                .getOrNull()
                ?.permissionContext
                ?.permissionGroups
                ?.flatMap { it.driveGrants.orEmpty() }
                ?.mapTo(mutableSetOf()) { it.permissionedDrive.drive.alias.normalizedGuid() }
            if (appDriveAliases == null) {
                Logger.w(TAG) { "security context unavailable — circle grantability unchecked" }
            }
            connectionService.circles.collect { state -> applyCircles(state.circles) }
        }
        viewModelScope.launch {
            runCatching { followProvider.isFollowing(odinId) }
                .onSuccess { following ->
                    followedAtOpen = following
                    _uiState.update { it.copy(followFeed = following) }
                }
                .onFailure { Logger.w(it, TAG) { "isFollowing check failed for $odinIdArg" } }
        }
        viewModelScope.launch {
            connectionService.connections.collect { state ->
                val reg = state.map.entries
                    .firstOrNull { it.key.domainName.equals(odinIdArg, ignoreCase = true) }?.value
                _uiState.update { it.copy(introducerOdinId = reg?.introducerOdinId?.domainName) }
            }
        }
    }

    private fun applyCircles(circles: List<CircleWithMembers>) {
        circleSnapshot = circles
        val options = circles
            .map { it.circle }
            .filter { it.countsAsOwnerCircle() && !it.disabled && it.name.isNotBlank() }
            .sortedBy { it.name.lowercase() }
            .map {
                ReviewCircleOption(
                    id = it.id,
                    name = it.name,
                    emoji = it.emoji?.takeIf { e -> e.isNotBlank() },
                    grantable = it.isGrantableBy(appDriveAliases),
                )
            }
        val toggles = appDefaultToggles(circles).map { app ->
            ReviewAppToggle(
                appId = app.appId,
                label = app.reviewCircles.firstOrNull()?.name
                    ?: app.connectCircles.firstOrNull()?.name.orEmpty(),
                circleIds = (app.reviewCircles + app.connectCircles).map { it.id },
            )
        }
        // ReviewDiag: temporary.
        Logger.d {
            "ReviewDiag/modal raw=${circles.size} shown=${options.map { it.name }} " +
                "ungrantable=${options.filterNot { it.grantable }.map { it.name }} " +
                "appDrives=${appDriveAliases?.size} " +
                "toggles=${toggles.map { "${it.label}:${it.appId}" }} " +
                "rejected=" + circles.map { it.circle }
                    .filterNot { it.countsAsOwnerCircle() && !it.disabled && it.name.isNotBlank() }
                    .map {
                        "${it.name}(owner=${it.countsAsOwnerCircle()},disabled=${it.disabled}," +
                            "blankName=${it.name.isBlank()})"
                    }
        }
        _uiState.update { state ->
            state.copy(
                circles = options,
                appToggles = toggles,
                // App defaults start checked: they are the app's own recommendation, and the
                // chat-only path explicitly clears them (see CircleToggled).
                checkedAppIds = if (state.checkedAppIds.isEmpty() && state.selectedCircleIds.isEmpty()) {
                    toggles.map { it.appId }.toSet()
                } else {
                    state.checkedAppIds
                },
            )
        }
    }

    fun setDisplayName(name: String) {
        _uiState.update { it.copy(displayName = name) }
    }

    fun onAction(action: ReviewConnectionUiAction) {
        when (action) {
            is ReviewConnectionUiAction.CircleToggled -> _uiState.update { state ->
                if (state.circles.none { it.id == action.circleId && it.grantable }) return@update state
                val next = if (action.circleId in state.selectedCircleIds) {
                    state.selectedCircleIds - action.circleId
                } else {
                    state.selectedCircleIds + action.circleId
                }
                // Deselecting the last circle turns the app defaults off too, so "Chat only"
                // really grants nothing — they stay visible so either can be re-enabled.
                state.copy(
                    selectedCircleIds = next,
                    checkedAppIds = if (next.isEmpty()) emptySet() else state.checkedAppIds,
                    error = null,
                )
            }

            is ReviewConnectionUiAction.AppToggled -> _uiState.update { state ->
                state.copy(
                    checkedAppIds = if (action.appId in state.checkedAppIds) {
                        state.checkedAppIds - action.appId
                    } else {
                        state.checkedAppIds + action.appId
                    },
                    error = null,
                )
            }

            is ReviewConnectionUiAction.FollowFeedToggled ->
                _uiState.update { it.copy(followFeed = action.follow) }

            ReviewConnectionUiAction.SubmitClicked -> submit()
            ReviewConnectionUiAction.KeepAsNewClicked ->
                _events.tryEmit(ReviewConnectionUiEvent.Dismissed)
            ReviewConnectionUiAction.DisconnectClicked -> disconnect()
            ReviewConnectionUiAction.BlockClicked -> block()
        }
    }

    private fun submit() {
        val state = _uiState.value
        if (state.submitting) return
        _uiState.update { it.copy(submitting = true, error = null) }

        viewModelScope.launch {
            val heldIds = connectionService.circles.value.circles
                .filter { cwm -> cwm.members.any { it.domainName.equals(odinIdArg, ignoreCase = true) } }
                .map { it.circle.id }
                .toSet()

            val ids = reviewEnrollment(
                selectedPersonalCircleIds = state.selectedCircleIds,
                checkedApps = appDefaultToggles(circleSnapshot)
                    .filter { it.appId in state.checkedAppIds },
                alreadyHeldCircleIds = heldIds,
            )

            try {
                connectionService.review(odinId, ids.map { Uuid.parse(it) })
                // Following is orthogonal to the review — it grants them nothing and is not a
                // rung on the ladder, so it is its own call and its failure must not fail the
                // review that already succeeded.
                applyFollow(state.followFeed)
                _events.tryEmit(ReviewConnectionUiEvent.Completed(state.addsToCircles))
            } catch (e: ClientException) {
                Logger.w(e, TAG) { "review failed for $odinIdArg code=${e.errorCode}" }
                _uiState.update { it.copy(submitting = false, error = e.errorCode.toReviewError()) }
            } catch (e: Exception) {
                Logger.w(e, TAG) { "review failed for $odinIdArg" }
                _uiState.update { it.copy(submitting = false, error = ReviewError.Generic) }
            }
        }
    }

    private suspend fun applyFollow(follow: Boolean) {
        if (follow == followedAtOpen) return
        runCatching {
            if (follow) {
                followProvider.follow(
                    FollowRequest(odinId, FollowNotificationType.AllNotifications),
                )
                followProvider.syncFeedHistory(odinId)
            } else {
                followProvider.unfollow(odinId)
            }
        }.onFailure { Logger.w(it, TAG) { "follow toggle failed for $odinIdArg (review succeeded)" } }
    }

    private fun disconnect() = runTerminal(ReviewConnectionUiEvent.Disconnected) {
        connectionNetworkProvider.disconnect(odinId)
        connectionService.refresh()
    }

    private fun block() = runTerminal(ReviewConnectionUiEvent.Blocked) {
        connectionNetworkProvider.block(odinId)
        connectionService.refresh()
    }

    private fun runTerminal(event: ReviewConnectionUiEvent, call: suspend () -> Unit) {
        if (_uiState.value.submitting) return
        _uiState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                call()
                _events.tryEmit(event)
            } catch (e: Exception) {
                Logger.w(e, TAG) { "terminal review action failed for $odinIdArg" }
                _uiState.update { it.copy(submitting = false, error = ReviewError.Generic) }
            }
        }
    }
}

private fun OdinClientErrorCode.toReviewError(): ReviewError = when (this) {
    OdinClientErrorCode.IdentityMustBeConnected -> ReviewError.NotConnected
    OdinClientErrorCode.CircleNotOwnedByApp -> ReviewError.CircleNotAllowed
    else -> ReviewError.Generic
}

private fun String.normalizedGuid(): String = replace("-", "").lowercase()

/**
 * The server needs a storage key for every drive a circle grants (rejecting otherwise with
 * CannotSourceDriveStorageKeyForGrant). This app can only source keys for drives it holds a grant
 * on, so an owner circle over another app's drive — Emergency Location Access over the Location
 * drive — cannot be granted from here. A null [appDriveAliases] means "unknown", so allow it and
 * let the server decide.
 */
internal fun RedactedCircleDefinition.isGrantableBy(appDriveAliases: Set<String>?): Boolean {
    if (appDriveAliases == null) return true
    return driveGrants.orEmpty().all { grant ->
        val alias = grant.permissionedDrive?.drive?.alias ?: return@all true
        alias.normalizedGuid() in appDriveAliases
    }
}

/** Test seam: [isGrantableBy] is the production path and must stay the only implementation. */
internal fun RedactedCircleDefinition.isGrantableByForTest(appDriveAliases: Set<String>?): Boolean =
    isGrantableBy(appDriveAliases)
