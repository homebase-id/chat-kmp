@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.webdrop

import id.homebase.core.ui.screens.webdrop.model.DropRow
import id.homebase.core.ui.screens.webdrop.model.PickedDropFile
import id.homebase.core.ui.screens.webdrop.model.WebDropTtlChoice
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class WebDropUiState(
    /** null while the drive mount state is still resolving. */
    val driveActivated: Boolean? = null,
    val setupInitiated: Boolean = false,
    val drops: List<DropRow> = emptyList(),
    val isLoaded: Boolean = false,
    val composeOpen: Boolean = false,
    val pickedFiles: List<PickedDropFile> = emptyList(),
    val ttlChoice: WebDropTtlChoice = WebDropTtlChoice.BurnAfterOpen,
    val introExpanded: Boolean = false,
    val recipientName: String = "",
    val conditions: Set<String> = emptySet(),
    val theme: String? = null,
    val isCreating: Boolean = false,
    /** Set when a drop was just created; the sheet flips to the share step. */
    val createdUrl: String? = null,
    val error: WebDropError? = null,
)

sealed interface WebDropUiAction {
    data object SetupClicked : WebDropUiAction
    data object OpenCompose : WebDropUiAction
    data object DismissOnboardingClicked : WebDropUiAction
    data class FilesPicked(val files: List<PickedDropFile>) : WebDropUiAction
    data class RemovePickedFile(val path: String) : WebDropUiAction
    data class TtlChosen(val choice: WebDropTtlChoice) : WebDropUiAction
    data object ToggleIntroSection : WebDropUiAction
    data class RecipientNameChanged(val name: String) : WebDropUiAction
    data class ConditionToggled(val id: String) : WebDropUiAction
    data class ThemeChosen(val theme: String?) : WebDropUiAction
    data object CreateClicked : WebDropUiAction
    data object ComposeDismissed : WebDropUiAction
    data class CopyLinkClicked(val url: String) : WebDropUiAction
    data class ShareClicked(val url: String) : WebDropUiAction
    data class RevokeClicked(val dropId: Uuid) : WebDropUiAction
    data class ClearClicked(val receiptFileId: Uuid) : WebDropUiAction
}

sealed interface WebDropUiEvent {
    data object CloseOnboarding : WebDropUiEvent
    data class ShareLink(val url: String) : WebDropUiEvent
    data class CopyLink(val url: String) : WebDropUiEvent
}

sealed interface WebDropError {
    data object CreateFailed : WebDropError
    data object TooManyFiles : WebDropError
}
