package id.homebase.chat.widget

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.permissions_extend
import id.homebase.resources.permissions_install
import id.homebase.resources.permissions_install_body
import id.homebase.resources.permissions_install_title
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
fun ExtendPermissionDialog(
    viewModel: ExtendPermissionViewModel,
    onCancel: (() -> Unit)? = null,
) {
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
            val installs = state.installs
            val title: String
            val body: String
            val confirm: String
            if (installs != null) {
                // Shown only while in front: a verdict from before leaving may be stale, and installing an installed app re-registers it.
                val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
                LaunchedEffect(lifecycle) { if (!lifecycle.isAtLeast(Lifecycle.State.RESUMED)) viewModel.dismissDialog() }
                title = stringResource(MR.string.permissions_install_title, installs)
                body = stringResource(MR.string.permissions_install_body, installs, state.appName)
                confirm = stringResource(MR.string.permissions_install)
            } else {
                title = stringResource(MR.string.permissions_missing_title)
                body = stringResource(MR.string.permissions_missing_body, state.appName)
                confirm = stringResource(MR.string.permissions_extend)
            }
            AlertDialog(
                onDismissRequest = {
                    viewModel.dismissDialog()
                    onCancel?.invoke()
                },
                title = { Text(title) },
                text = { Text(body) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.buildExtendPermissionUrl()?.let { uriHandler.openUrl(it) }
                        viewModel.dismissDialog()
                    }) {
                        Text(confirm)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.dismissDialog()
                        onCancel?.invoke()
                    }) {
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
