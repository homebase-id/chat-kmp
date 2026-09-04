package id.homebase.core.ui.screens.location

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.data.ContactUiModel
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.contactbook.LocateVerifyStatus
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.live_location_age_minutes
import id.homebase.resources.location_emergency_access_manage
import id.homebase.resources.location_emergency_access_none
import id.homebase.resources.location_emergency_access_section
import id.homebase.resources.location_emergency_helper
import id.homebase.resources.location_emergency_remove_cd
import id.homebase.resources.location_emergency_remove_confirm_body
import id.homebase.resources.location_emergency_remove_confirm_title
import id.homebase.resources.location_emergency_status_pending
import id.homebase.resources.location_locatable_broken_cd
import id.homebase.resources.location_locatable_none
import id.homebase.resources.location_locatable_section
import id.homebase.resources.location_locatable_unreachable_cd
import id.homebase.resources.location_locate_age_days
import id.homebase.resources.location_locate_age_hours
import id.homebase.resources.location_locate_no_data
import id.homebase.resources.location_tile_emergency_title
import id.homebase.resources.menu_back
import id.homebase.resources.remove
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationEmergencyScreen(
    viewModel: LocationViewModel,
    onNavigateBack: () -> Unit,
    onManageEmergencyAccess: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }
    // The per-contact verify loop runs only while this screen is in composition.
    DisposableEffect(Unit) {
        viewModel.onAction(LocationUiAction.SetEmergencyScreenVisible(true))
        onDispose { viewModel.onAction(LocationUiAction.SetEmergencyScreenVisible(false)) }
    }

    // The contact whose emergency-locate panel is open; closed on dismiss and on the VM's
    // terminal events (navigate-to-viewer / fetch-failed snackbar, both handled in AppNavHost).
    var pendingLocate by remember { mutableStateOf<ContactUiModel?>(null) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is LocationUiEvent.OpenPeerHistory || event is LocationUiEvent.LocateFetchFailed) {
                pendingLocate = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.location_tile_emergency_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(0.dp))
            Text(
                text = stringResource(MR.string.location_emergency_helper),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingsSectionHeader(
                title = stringResource(MR.string.location_locatable_section),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                PeopleListBody(
                    loaded = uiState.whoICanLocateLoaded,
                    members = uiState.whoICanLocate,
                    emptyText = stringResource(MR.string.location_locatable_none),
                    rowTrailing = { member ->
                        LocateStatusTrailing(uiState.whoICanLocateStatus[member.odinId.domainName])
                    },
                    // Only a verified-Active row opens the retrieval panel; Broken/Unreachable/
                    // Loading rows stay inert — the trailing icon is the explanation.
                    memberClick = { member ->
                        val status = uiState.whoICanLocateStatus[member.odinId.domainName]
                        if (status is LocateVerifyStatus.Active) {
                            { pendingLocate = member }
                        } else null
                    },
                )
            }

            SettingsSectionHeader(
                title = stringResource(MR.string.location_emergency_access_section),
                modifier = Modifier.padding(horizontal = 4.dp),
                trailing = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(MR.string.location_emergency_access_manage),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onManageEmergencyAccess)
                            .padding(4.dp)
                            .size(22.dp),
                    )
                },
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                var confirmRemove by remember { mutableStateOf<ContactUiModel?>(null) }
                val pendingIds = remember(uiState.whoCanLocateMePending) {
                    uiState.whoCanLocateMePending.map { it.odinId }.toSet()
                }
                PeopleListBody(
                    loaded = uiState.whoCanLocateMeLoaded,
                    members = (uiState.whoCanLocateMe + uiState.whoCanLocateMePending)
                        .distinctBy { it.odinId },
                    emptyText = stringResource(MR.string.location_emergency_access_none),
                    rowTrailing = { member ->
                        EmergencyContactTrailing(
                            name = member.name,
                            pending = pendingIds.contains(member.odinId),
                            removing = uiState.removingEmergencyContacts.contains(member.odinId.domainName),
                            onRemoveClick = { confirmRemove = member },
                        )
                    },
                )
                confirmRemove?.let { contact ->
                    AlertDialog(
                        onDismissRequest = { confirmRemove = null },
                        title = { Text(stringResource(MR.string.location_emergency_remove_confirm_title)) },
                        text = {
                            Text(
                                stringResource(MR.string.location_emergency_remove_confirm_body, contact.name)
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmRemove = null
                                viewModel.onAction(
                                    LocationUiAction.RemoveEmergencyContact(contact.odinId.domainName)
                                )
                            }) { Text(stringResource(MR.string.remove)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmRemove = null }) {
                                Text(stringResource(MR.string.cancel))
                            }
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    pendingLocate?.let { contact ->
        val status = uiState.whoICanLocateStatus[contact.odinId.domainName]
        val newestMs = (status as? LocateVerifyStatus.Active)?.newestModifiedMs
        EmergencyLocateSheet(
            contact = contact,
            lastPointAgeMs = newestMs?.let {
                (Clock.System.now().toEpochMilliseconds() - it).coerceAtLeast(0)
            },
            submitting = uiState.locateSubmitInFlight,
            onDismiss = { pendingLocate = null },
            onConfirm = { explanation, windowHours, ambush ->
                viewModel.onAction(
                    LocationUiAction.ConfirmEmergencyLocate(
                        odinId = contact.odinId.domainName,
                        name = contact.name,
                        explanation = explanation,
                        windowHours = windowHours,
                        ambush = ambush,
                    )
                )
            },
        )
    }
}

@Composable
private fun PeopleListBody(
    loaded: Boolean,
    members: List<ContactUiModel>,
    emptyText: String,
    rowTrailing: (@Composable (ContactUiModel) -> Unit)? = null,
    /** Per-member tap action; return null for an inert row (no ripple, no handler). */
    memberClick: ((ContactUiModel) -> (() -> Unit)?)? = null,
) {
    when {
        !loaded -> Box(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }

        members.isEmpty() -> Text(
            text = emptyText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )

        else -> Column {
            members.forEachIndexed { index, member ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                val onClick = memberClick?.invoke(member)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (onClick != null) Modifier.clickable(onClick = onClick)
                            else Modifier
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PublicAvatar(
                        odinId = member.odinId,
                        initials = member.avatarInitials,
                        options = AvatarOptions(size = 40.dp),
                    )
                    Text(
                        text = member.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    rowTrailing?.invoke(member)
                }
            }
        }
    }
}

@Composable
private fun LocateStatusTrailing(status: LocateVerifyStatus?) {
    when (status) {
        null -> Unit

        LocateVerifyStatus.Loading ->
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)

        is LocateVerifyStatus.Broken ->
            Icon(
                imageVector = Icons.Default.LinkOff,
                contentDescription = stringResource(MR.string.location_locatable_broken_cd),
                tint = MaterialTheme.colorScheme.error,
            )

        is LocateVerifyStatus.Unreachable ->
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = stringResource(MR.string.location_locatable_unreachable_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        is LocateVerifyStatus.Active -> {
            val ms = status.newestModifiedMs
            if (ms == null) {
                Text(
                    text = stringResource(MR.string.location_locate_no_data),
                    style = MaterialTheme.typography.labelMedium,
                    color = HomebaseTheme.extendedColors.warning,
                )
            } else {
                val ageMs = Clock.System.now().toEpochMilliseconds() - ms
                Text(
                    text = formatLocateAge(ageMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (locateAgeWarn(ageMs)) HomebaseTheme.extendedColors.warning
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmergencyContactTrailing(
    name: String,
    pending: Boolean,
    removing: Boolean,
    onRemoveClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (pending && !removing) {
            Text(
                text = stringResource(MR.string.location_emergency_status_pending),
                style = MaterialTheme.typography.labelMedium,
                color = HomebaseTheme.extendedColors.warning,
            )
        }
        if (removing) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp).padding(4.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = onRemoveClick) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(MR.string.location_emergency_remove_cd, name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun formatLocateAge(ageMs: Long): String = when (val bucket = locateAgeBucket(ageMs)) {
    is LocateAgeBucket.Minutes -> stringResource(MR.string.live_location_age_minutes, bucket.minutes)
    is LocateAgeBucket.Hours -> stringResource(MR.string.location_locate_age_hours, bucket.hours)
    is LocateAgeBucket.Days -> stringResource(MR.string.location_locate_age_days, bucket.days)
}
