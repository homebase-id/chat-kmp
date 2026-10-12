package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import kotlin.uuid.Uuid

/** One step of the breadcrumb trail; [name] is null for the top level. */
@Immutable
data class FilesyncCrumb(val nodeId: Uuid, val name: String?)

@Immutable
data class FilesyncUiState(
    /** Null until the drive grant has been checked. */
    val installed: Boolean? = null,
    val setupInitiated: Boolean = false,
    val path: List<FilesyncCrumb> = emptyList(),
    val nodes: List<FilesyncNode> = emptyList(),
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val transferNodeId: Uuid? = null,
    val transferProgress: Float = 0f,
)

sealed interface FilesyncUiAction {
    data object SetupClicked : FilesyncUiAction
    data object DismissOnboardingClicked : FilesyncUiAction
    data object Refresh : FilesyncUiAction
    data object NavigateUp : FilesyncUiAction
    data class NavigateToCrumb(val index: Int) : FilesyncUiAction
    data class NodeClicked(val node: FilesyncNode) : FilesyncUiAction
    data class SaveClicked(val node: FilesyncNode) : FilesyncUiAction
    data object CancelTransfer : FilesyncUiAction
}

enum class FilesyncError { DownloadFailed, Damaged }

sealed interface FilesyncUiEvent {
    data object CloseOnboarding : FilesyncUiEvent
    data class OpenFile(val path: String) : FilesyncUiEvent
    data class SaveFile(val path: String, val name: String) : FilesyncUiEvent
    data class Error(val error: FilesyncError) : FilesyncUiEvent
}
