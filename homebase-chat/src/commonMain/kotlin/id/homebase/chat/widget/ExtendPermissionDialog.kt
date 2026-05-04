package id.homebase.chat.widget

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.permissions_extend
import id.homebase.resources.permissions_missing_body
import id.homebase.resources.permissions_missing_title
import org.jetbrains.compose.resources.stringResource

/**
 * A composable that observes the [ExtendPermissionViewModel] and shows an alert dialog when the app
 * is missing required permissions.
 *
 * This is the KMP equivalent of the React Native `ExtendPermissionDialog` component. Place this
 * once in the main authenticated screen (e.g. ConversationListScreen) so the check runs on app
 * startup.
 */
@Composable
fun ExtendPermissionDialog(viewModel: ExtendPermissionViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = getUriHandler()

    LaunchedEffect(uiState) {
        Logger.i(tag = "ExtendPermissionDialog") {
            "uiState observed: ${uiState::class.simpleName} (vm=${viewModel.hashCode()})"
        }
    }

    when (val state = uiState) {
        is ExtendPermissionUiState.ShowDialog -> {
            LaunchedEffect(state.appName) {
                Logger.i(tag = "ExtendPermissionDialog") {
                    "rendering AlertDialog for ${state.appName}"
                }
            }
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text(stringResource(MR.string.permissions_missing_title)) },
                text = {
                    Text(stringResource(MR.string.permissions_missing_body, state.appName))
                },
                confirmButton = {
                    TextButton(onClick = {
                        uriHandler.openUrl(state.extendPermissionUrl)
                        viewModel.dismissDialog()
                    }) {
                        Text(stringResource(MR.string.permissions_extend))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) {
                        Text(stringResource(MR.string.cancel))
                    }
                }
            )
        }

        else -> {
            /* No dialog shown */
        }
    }
}
