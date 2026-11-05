package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import id.homebase.core.clipboard.clipEntryOf
import id.homebase.core.util.getUriHandler
import id.homebase.core.util.isDesktopOrWeb
import id.homebase.resources.MR
import id.homebase.resources.contactbook_empty_invite
import id.homebase.resources.contactbook_invite_body
import id.homebase.resources.contactbook_invite_copied
import id.homebase.resources.contactbook_invite_copy
import id.homebase.resources.contactbook_invite_email
import id.homebase.resources.contactbook_invite_email_failed
import id.homebase.resources.contactbook_invite_email_subject
import id.homebase.resources.share
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private const val COPIED_CONFIRMATION_MS = 2000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteFriendSheet(message: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val shareHandler = getUriHandler()
    val scope = rememberCoroutineScope()
    val subject = stringResource(MR.string.contactbook_invite_email_subject)
    val canShare = remember { !isDesktopOrWeb() }
    var copied by remember { mutableStateOf(false) }
    var emailFailed by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_CONFIRMATION_MS)
            copied = false
        }
    }

    val copy = {
        scope.launch {
            clipboard.setClipEntry(clipEntryOf(message))
            copied = true
        }
        Unit
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(MR.string.contactbook_empty_invite),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(MR.string.contactbook_invite_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            val copyIcon = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy
            val copyLabel = stringResource(
                if (copied) MR.string.contactbook_invite_copied else MR.string.contactbook_invite_copy,
            )
            if (canShare) {
                Button(
                    onClick = {
                        // Close first: iOS presents the share sheet from the root view controller.
                        onDismiss()
                        shareHandler.shareText(message)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    InviteActionLabel(Icons.Filled.Share, stringResource(MR.string.share))
                }
                OutlinedButton(onClick = copy, modifier = Modifier.fillMaxWidth()) {
                    InviteActionLabel(copyIcon, copyLabel)
                }
            } else {
                Button(onClick = copy, modifier = Modifier.fillMaxWidth()) {
                    InviteActionLabel(copyIcon, copyLabel)
                }
            }
            OutlinedButton(
                onClick = {
                    val mailto = "mailto:?subject=${subject.encodeURLParameter()}" +
                        "&body=${message.encodeURLParameter()}"
                    // A device with no mail client throws out of openUri.
                    emailFailed = runCatching { uriHandler.openUri(mailto) }.isFailure
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                InviteActionLabel(Icons.Filled.Email, stringResource(MR.string.contactbook_invite_email))
            }
            if (emailFailed) {
                Text(
                    text = stringResource(MR.string.contactbook_invite_email_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun InviteActionLabel(icon: ImageVector, label: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
    Text(label)
}
