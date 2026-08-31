package id.homebase.core.ui.screens.webdrop.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.webdrop.WebDropUiAction
import id.homebase.core.ui.screens.webdrop.WebDropUiState
import id.homebase.core.ui.screens.webdrop.WebDropError
import id.homebase.core.ui.screens.webdrop.model.PickedDropFile
import id.homebase.core.ui.screens.webdrop.model.WebDropTtlChoice
import id.homebase.core.ui.screens.vault.pathCompat
import id.homebase.core.util.contentType
import id.homebase.api.util.truncateToCodePoints
import id.homebase.core.webdrop.WebDropProtocol
import id.homebase.resources.MR
import id.homebase.resources.webdrop_add_files
import id.homebase.resources.webdrop_compose_title
import id.homebase.resources.webdrop_copy
import id.homebase.resources.webdrop_create
import id.homebase.resources.webdrop_error_create
import id.homebase.resources.webdrop_error_too_many
import id.homebase.resources.webdrop_for_someone
import id.homebase.resources.webdrop_recipient_name
import id.homebase.resources.webdrop_condition_recipient_only
import id.homebase.resources.webdrop_condition_no_retention
import id.homebase.resources.webdrop_condition_personal_data
import id.homebase.resources.webdrop_theme_mission
import id.homebase.resources.webdrop_theme_clean
import id.homebase.resources.webdrop_theme_choplifter
import id.homebase.resources.webdrop_link_ready
import id.homebase.resources.webdrop_share
import id.homebase.resources.webdrop_ttl_burn
import id.homebase.resources.webdrop_ttl_one_day
import id.homebase.resources.webdrop_ttl_seven_days
import id.homebase.resources.webdrop_ttl_thirty_days
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import org.jetbrains.compose.resources.stringResource

private const val MAX_NAME_CHARS = 40

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDropComposeSheet(
    uiState: WebDropUiState,
    onAction: (WebDropUiAction) -> Unit,
) {
    val filePicker = rememberFilePickerLauncher(
        type = FileKitType.File(),
        mode = FileKitMode.Multiple(),
    ) { files ->
        if (!files.isNullOrEmpty()) {
            onAction(
                WebDropUiAction.FilesPicked(
                    files.map { file ->
                        PickedDropFile(
                            path = file.pathCompat,
                            name = file.name,
                            contentType = file.contentType(),
                            size = 0,
                        )
                    }
                )
            )
        }
    }

    ModalBottomSheet(onDismissRequest = { onAction(WebDropUiAction.ComposeDismissed) }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(
                    if (uiState.createdUrl != null) MR.string.webdrop_link_ready
                    else MR.string.webdrop_compose_title
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.createdUrl != null) {
                Text(
                    text = uiState.createdUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { onAction(WebDropUiAction.CopyLinkClicked(uiState.createdUrl)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = null)
                        Text(stringResource(MR.string.webdrop_copy))
                    }
                    OutlinedButton(
                        onClick = { onAction(WebDropUiAction.ShareClicked(uiState.createdUrl)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(imageVector = Icons.Outlined.Share, contentDescription = null)
                        Text(stringResource(MR.string.webdrop_share))
                    }
                }
                return@Column
            }

            uiState.pickedFiles.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = file.name.truncateToCodePoints(MAX_NAME_CHARS),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onAction(WebDropUiAction.RemovePickedFile(file.path)) }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null)
                    }
                }
            }

            OutlinedButton(
                onClick = { filePicker.launch() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Text(stringResource(MR.string.webdrop_add_files))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TtlChip(uiState, WebDropTtlChoice.BurnAfterOpen, MR.string.webdrop_ttl_burn, onAction)
                TtlChip(uiState, WebDropTtlChoice.OneDay, MR.string.webdrop_ttl_one_day, onAction)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TtlChip(uiState, WebDropTtlChoice.SevenDays, MR.string.webdrop_ttl_seven_days, onAction)
                TtlChip(uiState, WebDropTtlChoice.ThirtyDays, MR.string.webdrop_ttl_thirty_days, onAction)
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = { onAction(WebDropUiAction.ToggleIntroSection) }) {
                Text(stringResource(MR.string.webdrop_for_someone))
            }

            if (uiState.introExpanded) {
                OutlinedTextField(
                    value = uiState.recipientName,
                    onValueChange = { onAction(WebDropUiAction.RecipientNameChanged(it)) },
                    label = { Text(stringResource(MR.string.webdrop_recipient_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                ConditionRow(uiState, WebDropProtocol.ConditionRecipientOnly, MR.string.webdrop_condition_recipient_only, onAction)
                ConditionRow(uiState, WebDropProtocol.ConditionNoRetention, MR.string.webdrop_condition_no_retention, onAction)
                ConditionRow(uiState, WebDropProtocol.ConditionPersonalData, MR.string.webdrop_condition_personal_data, onAction)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip(uiState, WebDropProtocol.ThemeMission, MR.string.webdrop_theme_mission, onAction)
                    ThemeChip(uiState, WebDropProtocol.ThemeClean, MR.string.webdrop_theme_clean, onAction)
                    ThemeChip(uiState, WebDropProtocol.ThemeChoplifter, MR.string.webdrop_theme_choplifter, onAction)
                }
            }

            uiState.error?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (error) {
                        WebDropError.CreateFailed -> stringResource(MR.string.webdrop_error_create)
                        WebDropError.TooManyFiles ->
                            stringResource(MR.string.webdrop_error_too_many, WebDropProtocol.MaxFilesPerDrop)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            FilledTonalButton(
                onClick = { onAction(WebDropUiAction.CreateClicked) },
                enabled = uiState.pickedFiles.isNotEmpty() && !uiState.isCreating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isCreating) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).fillMaxWidth(0.1f))
                } else {
                    Text(stringResource(MR.string.webdrop_create))
                }
            }
        }
    }
}

@Composable
private fun ConditionRow(
    uiState: WebDropUiState,
    id: String,
    label: org.jetbrains.compose.resources.StringResource,
    onAction: (WebDropUiAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = id in uiState.conditions,
            onCheckedChange = { onAction(WebDropUiAction.ConditionToggled(id)) },
        )
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ThemeChip(
    uiState: WebDropUiState,
    id: String,
    label: org.jetbrains.compose.resources.StringResource,
    onAction: (WebDropUiAction) -> Unit,
) {
    FilterChip(
        selected = uiState.theme == id,
        onClick = { onAction(WebDropUiAction.ThemeChosen(if (uiState.theme == id) null else id)) },
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun TtlChip(
    uiState: WebDropUiState,
    choice: WebDropTtlChoice,
    label: org.jetbrains.compose.resources.StringResource,
    onAction: (WebDropUiAction) -> Unit,
) {
    FilterChip(
        selected = uiState.ttlChoice == choice,
        onClick = { onAction(WebDropUiAction.TtlChosen(choice)) },
        label = { Text(stringResource(label)) },
    )
}
