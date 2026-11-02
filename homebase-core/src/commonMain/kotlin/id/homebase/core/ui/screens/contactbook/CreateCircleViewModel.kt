package id.homebase.core.ui.screens.contactbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.OdinApiException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.errorCodeEnum
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether this app holds what creating a circle needs; the answer comes from the permission extension flow. */
interface CircleAccess : AutoCloseable {
    val granted: StateFlow<Boolean>
    val dismissed: Flow<Unit>
    val permission: ExtendPermissionViewModel?
    fun recheck()
}

class ExtendPermissionCircleAccess(override val permission: ExtendPermissionViewModel) : CircleAccess {
    override val granted: StateFlow<Boolean> get() = permission.permissionsGranted
    override val dismissed: Flow<Unit> =
        permission.uiState.filterIsInstance<ExtendPermissionUiState.Dismissed>().map { }

    override fun recheck() = permission.recheckPermissions()
    override fun close() = permission.close()
}

enum class CreateCircleError { Failed, NeedsPermission }

data class CreateCircleUiState(
    val open: Boolean = false,
    val checkingAccess: Boolean = false,
    val slug: String = "",
    val name: String = "",
    val emoji: String = "",
    val description: String = "",
    val isCreating: Boolean = false,
    val error: CreateCircleError? = null,
) {
    val canCreate: Boolean get() = open && name.isNotBlank() && !isCreating
}

sealed interface CreateCircleEvent {
    data class Created(val circleId: String) : CreateCircleEvent
}

class CreateCircleViewModel(
    private val circles: UserCircleService,
    private val access: CircleAccess,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CreateCircleUiState())
    val uiState: StateFlow<CreateCircleUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CreateCircleEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CreateCircleEvent> = _events.asSharedFlow()

    val permission: ExtendPermissionViewModel? get() = access.permission

    init {
        addCloseable(access)
        viewModelScope.launch {
            access.granted.filter { it }.collect { if (_uiState.value.checkingAccess) openSheet() }
        }
        viewModelScope.launch {
            access.dismissed.collect { _uiState.update { it.copy(checkingAccess = false) } }
        }
    }

    fun onNewCircleClicked() {
        val state = _uiState.value
        if (state.open || state.checkingAccess) return
        if (access.granted.value) {
            openSheet()
        } else {
            _uiState.update { it.copy(checkingAccess = true) }
            access.recheck()
        }
    }

    private fun openSheet() {
        _uiState.value = CreateCircleUiState(open = true, slug = newCircleSlug())
    }

    fun onNameChanged(name: String) = _uiState.update { it.copy(name = name.truncateToCodePoints(CIRCLE_NAME_MAX), error = null) }

    fun onEmojiChanged(emoji: String) = _uiState.update { it.copy(emoji = emoji.take(CIRCLE_EMOJI_MAX_CHARS), error = null) }

    fun onDescriptionChanged(description: String) = _uiState.update { it.copy(description = description.truncateToCodePoints(CIRCLE_DESCRIPTION_MAX), error = null) }

    fun onDismiss() {
        if (_uiState.value.isCreating) return
        _uiState.value = CreateCircleUiState()
    }

    fun onCreateClicked() {
        val state = _uiState.value
        if (!state.canCreate) return
        _uiState.update { it.copy(isCreating = true, error = null) }
        val draft = CircleDraft(state.slug, state.name, state.emoji, state.description)
        viewModelScope.launch {
            try {
                val id = circles.create(draft)
                _uiState.value = CreateCircleUiState()
                _events.emit(CreateCircleEvent.Created(id.toHexString()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, "CreateCircle") { "create failed (${e::class.simpleName})" }
                if (e is ForbiddenException) access.recheck()
                _uiState.update { current ->
                    current.copy(
                        isCreating = false,
                        error = if (e is ForbiddenException) CreateCircleError.NeedsPermission else CreateCircleError.Failed,
                        slug = if (e.isSlugTaken()) newCircleSlug() else current.slug,
                    )
                }
            }
        }
    }
}

private fun Throwable.isSlugTaken() =
    this is OdinApiException && problem?.errorCodeEnum() == OdinClientErrorCode.CircleSlugTaken
