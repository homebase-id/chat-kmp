package id.homebase.auth.login

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import id.homebase.core.ui.theme.HomebaseFonts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import id.homebase.api.common.OdinId
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource

@Composable
fun LoginUi(
    uiState: LoginUiState,
    onAction: (LoginUiAction) -> Unit,
    pendingAuthUrl: String? = null,
    onContinueAuth: () -> Unit = {},
) {
    val errorText: String? = uiState.error?.let { error ->
        when (error) {
            is LoginError.Res ->
                if (error.arg != null) stringResource(error.resource, error.arg)
                else stringResource(error.resource)
            is LoginError.Message -> error.text
        }
    }
    MaterialTheme(typography = loginTypography()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLowest
        ) {
            LoginLayout(
                uiState = uiState,
                errorText = errorText,
                onAction = onAction,
                pendingAuthUrl = pendingAuthUrl,
                onContinueAuth = onContinueAuth,
            )
        }
    }
}

/** Manual pp.12-13, scoped to this screen: repointing [HomebaseTypography] restyles every screen. */
@Composable
private fun loginTypography(): Typography {
    val heading = HomebaseFonts.headline
    val body = HomebaseFonts.body
    val base = MaterialTheme.typography
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = heading),
        displayMedium = base.displayMedium.copy(fontFamily = heading),
        displaySmall = base.displaySmall.copy(fontFamily = heading),
        headlineLarge = base.headlineLarge.copy(fontFamily = heading),
        headlineMedium = base.headlineMedium.copy(fontFamily = heading),
        headlineSmall = base.headlineSmall.copy(fontFamily = heading),
        titleLarge = base.titleLarge.copy(fontFamily = body),
        titleMedium = base.titleMedium.copy(fontFamily = body),
        titleSmall = base.titleSmall.copy(fontFamily = body),
        bodyLarge = base.bodyLarge.copy(fontFamily = body),
        bodyMedium = base.bodyMedium.copy(fontFamily = body),
        bodySmall = base.bodySmall.copy(fontFamily = body),
        labelLarge = base.labelLarge.copy(fontFamily = body),
        labelMedium = base.labelMedium.copy(fontFamily = body),
        labelSmall = base.labelSmall.copy(fontFamily = body),
    )
}

@Preview(device = Devices.PIXEL_8)
@Composable
fun LoginUiFormPreview() {
    MaterialTheme {
        LoginUi(
            uiState = LoginUiState(
                homebaseId = "example.homebase.id",
                isLoading = false,
                isAuthenticated = false,
                error = null
            ),
            onAction = {}
        )
    }
}

@Preview(device = Devices.PIXEL_8)
@Composable
fun LoginUiLastIdentityPreview() {
    MaterialTheme {
        LoginUi(
            uiState = LoginUiState(
                homebaseId = "frodo.digital",
                lastIdentity = IdentityPreview(
                    odinId = OdinId("frodo.digital"),
                    displayName = "Frodo Baggins",
                    status = "Ring-bearer",
                ),
                showIdField = false,
            ),
            onAction = {}
        )
    }
}

@Preview(device = Devices.PIXEL_8)
@Composable
fun LoginUiLoadingPreview() {
    MaterialTheme {
        LoginUi(
            uiState = LoginUiState(
                homebaseId = "example.homebase.id",
                isLoading = true,
                isAuthenticated = false,
                error = null,
                driveProgresses = persistentListOf(
                    DriveProgress(driveId = "uuid-chat", name = "Chat", completed = true, total = 42, count = 42, progress = 1f),
                    DriveProgress(driveId = "uuid-feed", name = "Feed", count = 17, total = 17, progress = null),
                    DriveProgress(driveId = "uuid-contact", name = "Contact", error = "Network error"),
                )
            ),
            onAction = {}
        )
    }
}
