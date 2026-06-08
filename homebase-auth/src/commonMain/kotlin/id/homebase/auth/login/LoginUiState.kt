package id.homebase.auth.login

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.StringResource

@Immutable
data class LoginUiState(
    val homebaseId: String = "",
    val isLoading: Boolean = false,
    val isPinging: Boolean = false,
    val isAuthenticated: Boolean = false,
    val error: LoginError? = null,
    val driveProgresses: ImmutableList<DriveProgress> = persistentListOf(),
    val uiEvent: LoginUiEvent? = null
)

/**
 * A login error to surface to the user. The ViewModel can't call `stringResource` (no Compose
 * context), so localizable errors travel as a [Res] (resolved in the Composable) and only genuinely
 * dynamic text (exception / server messages with no resource) travels as a [Message].
 */
@Immutable
sealed interface LoginError {
    data class Res(val resource: StringResource, val arg: String? = null) : LoginError
    data class Message(val text: String) : LoginError
}

@Immutable
data class DriveProgress(
    val driveId: String,
    val name: String,
    val error: String? = null,
    val completed: Boolean = false,
    val progress: Float? = null,
    val count: Int = 0,
    val total: Int = 0,
)
