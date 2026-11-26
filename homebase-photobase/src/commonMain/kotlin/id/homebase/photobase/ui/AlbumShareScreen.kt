package id.homebase.photobase.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.core.widget.SettingsTopBar
import id.homebase.photobase.sharing.PhotoAlbumAccess
import id.homebase.photobase.sharing.key
import id.homebase.photobase.sharing.label
import id.homebase.resources.MR
import id.homebase.resources.photobase_share_access_add
import id.homebase.resources.photobase_share_access_view
import id.homebase.resources.photobase_share_failed
import id.homebase.resources.photobase_share_identity
import id.homebase.resources.photobase_share_identity_invalid
import id.homebase.resources.photobase_share_none
import id.homebase.resources.photobase_share_not_added
import id.homebase.resources.photobase_share_not_told
import id.homebase.resources.photobase_share_send
import id.homebase.resources.photobase_share_shared_with
import id.homebase.resources.photobase_share_sign_in
import id.homebase.resources.photobase_share_sign_in_body
import id.homebase.resources.photobase_share_stop
import id.homebase.resources.photobase_share_title
import id.homebase.resources.photobase_share_waiting
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private val PhotoAlbumAccess.title: StringResource
    get() = when (this) {
        PhotoAlbumAccess.View -> MR.string.photobase_share_access_view
        PhotoAlbumAccess.Add -> MR.string.photobase_share_access_add
    }

@Composable
fun AlbumShareScreen(viewModel: AlbumShareViewModel, onOpenSignIn: (String) -> Unit, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    LifecycleResumeEffect(viewModel) {
        viewModel.resumed()
        onPauseOrDispose {}
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AlbumShareEvent.OpenUrl -> uriHandler.openUri(event.url)
                is AlbumShareEvent.OpenSignIn -> onOpenSignIn(event.url)
                AlbumShareEvent.Failed -> snackbar.showSnackbar(getString(MR.string.photobase_share_failed))
                is AlbumShareEvent.Waiting -> snackbar.showSnackbar(getString(MR.string.photobase_share_waiting, event.who))
                is AlbumShareEvent.NotAdded -> snackbar.showSnackbar(getString(MR.string.photobase_share_not_added, event.who))
                is AlbumShareEvent.NotTold -> snackbar.showSnackbar(getString(MR.string.photobase_share_not_told, event.who))
            }
        }
    }
    Scaffold(
        topBar = { SettingsTopBar(stringResource(MR.string.photobase_share_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        if (!uiState.signedIn) {
            Column(Modifier.padding(innerPadding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(MR.string.photobase_share_sign_in_body), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { viewModel.onAction(AlbumShareUiAction.SignIn) }) { Text(stringResource(MR.string.photobase_share_sign_in)) }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(innerPadding)) {
            item(key = "add") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.identity,
                        onValueChange = { viewModel.onAction(AlbumShareUiAction.Identity(it)) },
                        label = { Text(stringResource(MR.string.photobase_share_identity)) },
                        isError = uiState.identityInvalid,
                        supportingText = if (uiState.identityInvalid) {
                            { Text(stringResource(MR.string.photobase_share_identity_invalid)) }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        PhotoAlbumAccess.entries.forEachIndexed { index, access ->
                            SegmentedButton(
                                selected = uiState.access == access,
                                onClick = { viewModel.onAction(AlbumShareUiAction.Access(access)) },
                                shape = SegmentedButtonDefaults.itemShape(index, PhotoAlbumAccess.entries.size),
                            ) { Text(stringResource(access.title)) }
                        }
                    }
                    Button(
                        onClick = { viewModel.onAction(AlbumShareUiAction.Share) },
                        enabled = !uiState.busy && uiState.identity.isNotBlank(),
                    ) { Text(stringResource(MR.string.photobase_share_send)) }
                }
            }
            item(key = "header") { SettingsSectionHeader(stringResource(MR.string.photobase_share_shared_with), Modifier.padding(start = 16.dp, top = 8.dp)) }
            val recipients = uiState.recipients
            if (recipients?.isEmpty() == true) {
                item(key = "none") {
                    Text(
                        stringResource(MR.string.photobase_share_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(recipients.orEmpty(), key = { it.audience.key }) { recipient ->
                ListItem(
                    headlineContent = { Text(recipient.audience.label) },
                    supportingContent = { Text(stringResource(recipient.access.title)) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.onAction(AlbumShareUiAction.Withdraw(recipient)) }, enabled = !uiState.busy) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.string.photobase_share_stop, recipient.audience.label))
                        }
                    },
                )
            }
        }
    }
}
