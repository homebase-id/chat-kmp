package id.homebase.chat.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.util.getUriHandler

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
    val uiState by viewModel.uiState.collectAsState()
    val uriHandler = getUriHandler()

    when (val state = uiState) {
        is ExtendPermissionUiState.ShowDialog -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text("Missing Permissions") },
                text = {
                    Text(
                        "The ${state.appName} app is missing permissions. " +
                                "Without the necessary permissions the functionality " +
                                "of ${state.appName} will be limited."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        uriHandler.openUrl(state.extendPermissionUrl)
                        viewModel.dismissDialog()
                    }) {
                        Text("Extend Permissions")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) { Text("Cancel") }
                }
            )
        }

        else -> {
            /* No dialog shown */
        }
    }
}
