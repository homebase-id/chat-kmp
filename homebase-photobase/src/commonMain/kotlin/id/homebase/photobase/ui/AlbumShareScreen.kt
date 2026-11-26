package id.homebase.photobase.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.homebase.chat.widget.AudienceGroup
import id.homebase.chat.widget.AudienceGroupSheet
import id.homebase.chat.widget.AudiencePicker
import id.homebase.chat.widget.GroupFaces
import id.homebase.chat.widget.PersonRow
import id.homebase.core.widget.ButtonProgressContent
import id.homebase.chat.widget.rememberAudiencePickerState
import id.homebase.photobase.sharing.AlbumAudience
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import id.homebase.photobase.sharing.AlbumShareRecipient
import id.homebase.resources.audience_group_people
import id.homebase.resources.photobase_group_gone
import id.homebase.resources.photobase_group_pending
import id.homebase.resources.photobase_group_saved
import id.homebase.resources.photobase_share_left_out
import id.homebase.resources.photobase_group_short_of_access
import id.homebase.resources.photobase_share_failed_group
import id.homebase.resources.photobase_share_recipient_detail
import org.jetbrains.compose.resources.pluralStringResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.core.widget.SettingsTopBar
import id.homebase.api.youauth.DrivePermission
import id.homebase.photobase.sharing.PhotoAlbumAccess
import id.homebase.photobase.sharing.key
import id.homebase.photobase.sharing.label
import id.homebase.resources.MR
import id.homebase.resources.photobase_share_access_add
import id.homebase.resources.photobase_share_access_view
import id.homebase.resources.ok
import id.homebase.resources.photobase_permission_comment
import id.homebase.resources.photobase_permission_none
import id.homebase.resources.photobase_permission_react
import id.homebase.resources.photobase_permission_read
import id.homebase.resources.photobase_permission_write
import id.homebase.resources.photobase_share_circle_id
import id.homebase.resources.photobase_share_circle_too_narrow
import id.homebase.resources.details
import id.homebase.resources.photobase_share_details_body
import id.homebase.resources.photobase_share_details_none
import id.homebase.resources.photobase_share_failed_read
import id.homebase.resources.photobase_share_failed_share
import id.homebase.resources.photobase_share_failed_sign_in
import id.homebase.resources.photobase_share_failed_sign_in_unfinished
import id.homebase.resources.photobase_share_failed_withdraw
import id.homebase.resources.photobase_share_device_error
import id.homebase.resources.photobase_share_server_status
import id.homebase.resources.photobase_share_sign_in_blocked
import id.homebase.resources.photobase_share_unreachable
import id.homebase.resources.sign_in_again_continue
import id.homebase.resources.photobase_share_none
import id.homebase.resources.photobase_share_done
import id.homebase.resources.photobase_share_not_added
import id.homebase.resources.photobase_share_not_connected
import id.homebase.resources.photobase_share_not_reviewed
import id.homebase.resources.photobase_share_pending
import id.homebase.resources.photobase_share_sharing
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

/** One person by name and avatar, their Homebase ID below; a group by its faces, name and size, which shows its members. */
@Composable
private fun RecipientRow(recipient: AlbumShareRecipient, enabled: Boolean, onMembers: (AlbumAudience.Group) -> Unit, onStop: () -> Unit) {
    val access = stringResource(recipient.access.title)
    val stop: @Composable () -> Unit = {
        IconButton(onClick = onStop, enabled = enabled) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.string.photobase_share_stop, recipient.audience.label))
        }
    }
    when (val audience = recipient.audience) {
        is AlbumAudience.Person -> PersonRow(audience.odinId, stringResource(MR.string.photobase_share_recipient_detail, audience.odinId.domainName, access), stop)
        is AlbumAudience.Group -> ListItem(
            leadingContent = { GroupFaces(audience.members) },
            headlineContent = { Text(audience.name) },
            supportingContent = {
                val people = pluralStringResource(MR.plurals.audience_group_people, audience.members.size, audience.members.size)
                Text(stringResource(MR.string.photobase_share_recipient_detail, people, access))
            },
            trailingContent = stop,
            modifier = Modifier.clickable(enabled = enabled, role = Role.Button) { onMembers(audience) },
        )
    }
}

private suspend fun ShareFailure.detailsText(): String {
    deviceError?.let { return it }
    val none = getString(MR.string.photobase_share_details_none)
    return getString(MR.string.photobase_share_details_body, status?.toString() ?: none, code ?: none, correlationId ?: none)
}

private suspend fun List<DrivePermission>.names(): String =
    if (isEmpty()) getString(MR.string.photobase_permission_none)
    else map {
        getString(
            when (it) {
                DrivePermission.Read -> MR.string.photobase_permission_read
                DrivePermission.Write -> MR.string.photobase_permission_write
                DrivePermission.React -> MR.string.photobase_permission_react
                DrivePermission.Comment -> MR.string.photobase_permission_comment
            }
        )
    }.joinToString(", ")

private suspend fun AlbumShareEvent.Failed.failureMessage(): String {
    if (reason.unreachable) return getString(MR.string.photobase_share_unreachable)
    val why = reason.reason
        ?: reason.status?.let { getString(MR.string.photobase_share_server_status, it) }
        ?: if (step == AlbumShareStep.SignIn && reason.deviceError == null) return getString(MR.string.photobase_share_failed_sign_in_unfinished)
        else getString(MR.string.photobase_share_device_error)
    return getString(
        when (step) {
            AlbumShareStep.SignIn -> MR.string.photobase_share_failed_sign_in
            AlbumShareStep.Read -> MR.string.photobase_share_failed_read
            AlbumShareStep.Share -> MR.string.photobase_share_failed_share
            AlbumShareStep.Withdraw -> MR.string.photobase_share_failed_withdraw
            AlbumShareStep.EditGroup -> MR.string.photobase_share_failed_group
        },
        why,
    )
}

@Composable
fun AlbumShareScreen(
    viewModel: AlbumShareViewModel,
    onOpenSignIn: (String) -> Unit,
    onBack: () -> Unit,
    signInBlocked: Boolean = false,
    onContinueSignIn: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val picker = rememberAudiencePickerState()
    val shortOfAccess = stringResource(MR.string.photobase_group_short_of_access)
    val groups = remember(uiState.groups, shortOfAccess) {
        uiState.groups.map {
            AudienceGroup(it.circleId.toString(), it.name, it.members, unavailable = shortOfAccess.takeIf { _ -> it.missing.isNotEmpty() }, pending = it.pending)
        }
    }
    // The group whose members are shown, by id, so a save's reload shows it as it is now.
    var sheetGroup by rememberSaveable { mutableStateOf<String?>(null) }
    sheetGroup?.let { id ->
        val group = uiState.groups.firstOrNull { it.circleId.toString() == id }
        val shown = group?.let { g -> groups.firstOrNull { it.id == id } }
        if (group != null && shown != null) {
            AudienceGroupSheet(
                shown,
                onDismiss = { sheetGroup = null },
                onSave = { members -> viewModel.onAction(AlbumShareUiAction.EditGroup(group, members)) },
                saving = uiState.busy,
            )
        }
    }
    // The people it's shared with already come first.
    val first = remember(uiState.recipients) { uiState.recipients.orEmpty().mapNotNullTo(HashSet()) { (it.audience as? AlbumAudience.Person)?.odinId?.domainName } }
    val choice = picker.choice(groups)
    var details by remember { mutableStateOf<String?>(null) }
    details?.let { body ->
        AlertDialog(
            onDismissRequest = { details = null },
            confirmButton = { TextButton(onClick = { details = null }) { Text(stringResource(MR.string.ok)) } },
            title = { Text(stringResource(MR.string.details)) },
            text = { Text(body) },
        )
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.resumed()
        onPauseOrDispose {}
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AlbumShareEvent.OpenUrl -> uriHandler.openUri(event.url)
                is AlbumShareEvent.OpenSignIn -> onOpenSignIn(event.url)
                is AlbumShareEvent.GroupSaved -> {
                    sheetGroup = null
                    snackbar.showSnackbar(getString(MR.string.photobase_group_saved, event.name))
                }
                is AlbumShareEvent.GroupPending -> snackbar.showSnackbar(getString(MR.string.photobase_group_pending, event.who, event.group))
                AlbumShareEvent.GroupGone -> snackbar.showSnackbar(getString(MR.string.photobase_group_gone))
                is AlbumShareEvent.LeftOut -> snackbar.showSnackbar(getString(MR.string.photobase_share_left_out, event.who))
                is AlbumShareEvent.Shared -> {
                    picker.clear()
                    snackbar.showSnackbar(getString(MR.string.photobase_share_done, event.who))
                }
                is AlbumShareEvent.Failed -> {
                    val message = event.failureMessage()
                    val shown = snackbar.showSnackbar(message, actionLabel = event.reason.takeIf { it.hasDetails }?.let { getString(MR.string.details) })
                    if (shown == SnackbarResult.ActionPerformed) details = event.reason.detailsText()
                }
                is AlbumShareEvent.CircleTooNarrow -> {
                    val message = getString(MR.string.photobase_share_circle_too_narrow, event.circleName, event.granted.names(), event.missing.names())
                    val shown = snackbar.showSnackbar(message, actionLabel = getString(MR.string.details), duration = SnackbarDuration.Long)
                    if (shown == SnackbarResult.ActionPerformed) details = getString(MR.string.photobase_share_circle_id, event.circleName, event.circleId.toString())
                }
                is AlbumShareEvent.Waiting -> snackbar.showSnackbar(getString(MR.string.photobase_share_waiting, event.who))
                is AlbumShareEvent.NotAdded -> snackbar.showSnackbar(getString(MR.string.photobase_share_not_added, event.who))
                is AlbumShareEvent.NotConnected -> snackbar.showSnackbar(getString(MR.string.photobase_share_not_connected, event.who))
                is AlbumShareEvent.NotReviewed -> snackbar.showSnackbar(getString(MR.string.photobase_share_not_reviewed, event.who))
                is AlbumShareEvent.Pending -> snackbar.showSnackbar(getString(MR.string.photobase_share_pending, event.who))
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
                if (signInBlocked) {
                    // A browser opens a window only from a click: the first one came after the sign-in link was made.
                    Text(stringResource(MR.string.photobase_share_sign_in_blocked), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onContinueSignIn) { Text(stringResource(MR.string.sign_in_again_continue)) }
                } else {
                    Button(onClick = { viewModel.onAction(AlbumShareUiAction.SignIn) }) { Text(stringResource(MR.string.photobase_share_sign_in)) }
                }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(innerPadding)) {
            item(key = "add") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AudiencePicker(picker, groups, first, typedFallback = true, enabled = !uiState.busy, onGroupMembers = { sheetGroup = it.id })
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
                        onClick = { choice?.let { viewModel.onAction(AlbumShareUiAction.Share(it)) } },
                        enabled = !uiState.busy && choice != null,
                    ) { ButtonProgressContent(uiState.busy, stringResource(MR.string.photobase_share_sharing), stringResource(MR.string.photobase_share_send)) }
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
                RecipientRow(
                    recipient,
                    enabled = !uiState.busy,
                    onMembers = { sheetGroup = it.circleId.toString() },
                    onStop = { viewModel.onAction(AlbumShareUiAction.Withdraw(recipient)) },
                )
            }
        }
    }
}
