package id.homebase.auth.login

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class LoginUiState(
    val homebaseId: String = "",
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val errorMessage: String? = null,
    val driveProgresses: ImmutableList<DriveProgress> = persistentListOf(),
    val uiEvent: LoginUiEvent? = null
)

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
