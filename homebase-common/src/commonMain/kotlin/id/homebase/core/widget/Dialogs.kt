package id.homebase.core.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.composer_discard_confirm
import id.homebase.resources.composer_discard_keep
import id.homebase.resources.composer_discard_message
import id.homebase.resources.composer_discard_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun DialogCard(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 16.dp,
    buttons: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    Card(
        modifier = modifier
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = bottomPadding),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState)
            ) {
                content()
            }
            if (buttons != null) {
                Spacer(modifier = Modifier.height(16.dp))
                buttons()
            }
        }
    }
}

@Composable
fun DialogButtons(
    onPrimaryClick: (() -> Unit)? = null,
    primaryText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    secondaryText: String? = null,
    onTertiaryClick: (() -> Unit)? = null,
    tertiaryText: String? = null,
    showButtonsVertically: Boolean = false,
) {
    if (showButtonsVertically) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (onPrimaryClick != null && primaryText != null) {
                TextButton(onClick = onPrimaryClick) {
                    Text(primaryText)
                }
            }
            if (onSecondaryClick != null && secondaryText != null) {
                TextButton(onClick = onSecondaryClick) {
                    Text(secondaryText)
                }
            }
            if (onTertiaryClick != null && tertiaryText != null) {
                TextButton(onClick = onTertiaryClick) {
                    Text(tertiaryText)
                }
            }
        }
    } else {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onTertiaryClick != null && tertiaryText != null) {
                TextButton(onClick = onTertiaryClick) {
                    Text(tertiaryText)
                }
            }
            if (onSecondaryClick != null && secondaryText != null) {
                TextButton(onClick = onSecondaryClick) {
                    Text(secondaryText)
                }
            }
            if (onPrimaryClick != null && primaryText != null) {
                TextButton(onClick = onPrimaryClick) {
                    Text(primaryText)
                }
            }
        }
    }
}

@Composable
fun DialogTitle(modifier: Modifier = Modifier.padding(16.dp), text: String) {
    Text(
        modifier = modifier,
        text = text,
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
fun DialogText(modifier: Modifier = Modifier.padding(16.dp), text: String) {
    Text(
        modifier = modifier,
        text = text,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
fun DiscardChangesDialog(onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(MR.string.composer_discard_title)) },
        text = { Text(stringResource(MR.string.composer_discard_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(MR.string.composer_discard_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) { Text(stringResource(MR.string.composer_discard_keep)) }
        },
    )
}

/** A yes/no question; [destructive] paints the confirm button in the error colour. */
@Composable
fun ConfirmDialog(
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
    )
}

/** Asks for a name; [onConfirm] gets it trimmed, and can't be tapped while it's blank or unchanged. */
@Composable
fun NameDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank() && name.trim() != initial) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
    )
}
