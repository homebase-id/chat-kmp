package id.homebase.core.ui.screens.location

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.ui.screens.location.devices.LocationDeviceInfo
import id.homebase.core.ui.screens.location.history.LocationTraceCanvas
import id.homebase.core.ui.screens.location.livelocation.AGE_LABEL_AFTER_MS
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.formatUntilTime
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.live_location_age_minutes
import id.homebase.resources.location_dashboard_empty_today
import id.homebase.resources.location_dashboard_history_section
import id.homebase.resources.location_dashboard_live_empty
import id.homebase.resources.location_dashboard_live_open
import id.homebase.resources.location_dashboard_live_section
import id.homebase.resources.location_dashboard_perm_banner
import id.homebase.resources.location_dashboard_share_until
import id.homebase.resources.location_dashboard_sharing_with
import id.homebase.resources.location_dashboard_sharing_with_you
import id.homebase.resources.location_dashboard_stop_confirm_body
import id.homebase.resources.location_dashboard_stop_confirm_title
import id.homebase.resources.location_dashboard_stop_everyone
import id.homebase.resources.location_dashboard_stop_one_cd
import id.homebase.resources.location_device_no_fix
import id.homebase.resources.location_device_this_device
import id.homebase.resources.location_device_unnamed
import id.homebase.resources.location_devices_section
import id.homebase.resources.location_emergency_access_manage
import id.homebase.resources.location_emergency_access_more
import id.homebase.resources.location_emergency_access_none
import id.homebase.resources.location_emergency_access_section
import id.homebase.resources.location_locatable_broken_cd
import id.homebase.resources.location_locatable_none
import id.homebase.resources.location_locatable_section
import id.homebase.resources.location_locatable_unreachable_cd
import id.homebase.resources.location_locate_age_days
import id.homebase.resources.location_locate_age_hours
import id.homebase.resources.location_locate_no_data
import id.homebase.resources.location_status_pending
import id.homebase.resources.location_status_points_today
import id.homebase.resources.stop_sharing
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.stringResource

/**
 * Everyday main screen once the Location add-on runs: map preview of today,
 * the device list (= the Find-device picker), and a compact status footer.
 * Future cards (Sentinel, live sharing) slot in below the device list.
 */
@Composable
fun LocationDashboardContent(
    uiState: LocationUiState,
    innerPadding: PaddingValues,
    fetchTile: suspend (zoom: Int, x: Int, y: Int) -> ByteArray?,
    onOpenHistory: () -> Unit,
    onOpenLiveMap: () -> Unit,
    onStopSharingWith: (String) -> Unit,
    onStopSharingWithEveryone: () -> Unit,
    onOpenDevice: (Uuid) -> Unit,
    onOpenSetup: () -> Unit,
    onManageEmergencyAccess: () -> Unit,
    onLocatableExpandedChange: (Boolean) -> Unit,
    onLocateContact: (ContactUiModel) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(0.dp))

        // ── Degraded-permission banner ──
        if (uiState.trackingAvailable && !uiState.whileInUseGranted) {
            Card(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSetup),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = stringResource(MR.string.location_dashboard_perm_banner),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // ── Live location sharing (title always shown; below it the map link + who's sharing) ──
        Text(
            text = stringResource(MR.string.location_dashboard_live_section),
            style = MaterialTheme.typography.titleMedium,
        )
        if (uiState.liveSharingVisible) {
            // Map link — opens the live map (Route.LocationLive).
            Card(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLiveMap),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(MR.string.location_dashboard_live_open),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // People I'm sharing with — one row per person (longest "until"), per-row + all stop.
            if (uiState.outgoingShares.isNotEmpty()) {
                var confirmStopAll by remember { mutableStateOf(false) }
                Text(
                    text = stringResource(MR.string.location_dashboard_sharing_with),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        uiState.outgoingShares.forEachIndexed { index, row ->
                            if (index > 0) HorizontalDivider()
                            OutgoingShareRowItem(row = row, onStop = { onStopSharingWith(row.odinId) })
                        }
                    }
                }
                TextButton(onClick = { confirmStopAll = true }) {
                    Text(text = stringResource(MR.string.location_dashboard_stop_everyone))
                }
                if (confirmStopAll) {
                    AlertDialog(
                        onDismissRequest = { confirmStopAll = false },
                        title = { Text(stringResource(MR.string.location_dashboard_stop_confirm_title)) },
                        text = { Text(stringResource(MR.string.location_dashboard_stop_confirm_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmStopAll = false
                                onStopSharingWithEveryone()
                            }) { Text(stringResource(MR.string.stop_sharing)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmStopAll = false }) {
                                Text(stringResource(MR.string.cancel))
                            }
                        },
                    )
                }
            }

            // People sharing with me — name + live-updating age (shown only past 2 minutes stale).
            if (uiState.incomingShares.isNotEmpty()) {
                Text(
                    text = stringResource(MR.string.location_dashboard_sharing_with_you),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        uiState.incomingShares.forEachIndexed { index, row ->
                            if (index > 0) HorizontalDivider()
                            IncomingShareRowItem(row = row)
                        }
                    }
                }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LocationSearching,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(MR.string.location_dashboard_live_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Location History (map preview, today, all devices) → History ──
        Text(
            text = stringResource(MR.string.location_dashboard_history_section),
            style = MaterialTheme.typography.titleMedium,
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clickable(onClick = onOpenHistory),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (uiState.todayTraces.isEmpty()) {
                    Text(
                        text = stringResource(MR.string.location_dashboard_empty_today),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                } else {
                    LocationTraceCanvas(
                        traces = uiState.todayTraces,
                        showMapTiles = uiState.showMapTiles,
                        fetchTile = fetchTile,
                        traceColors = dashboardTraceColors(),
                        interactive = false,
                    )
                }
            }
        }

        // ── My devices (= Find device picker) ──
        Text(
            text = stringResource(MR.string.location_devices_section),
            style = MaterialTheme.typography.titleMedium,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                uiState.devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    DeviceRow(device = device, onClick = { onOpenDevice(device.deviceId) })
                }
            }
        }

        // ── Who you can locate (contacts carrying our iCanLocate flag) ──
        DashboardSection(title = stringResource(MR.string.location_locatable_section)) {
            PeopleListBody(
                loaded = uiState.whoICanLocateLoaded,
                members = uiState.whoICanLocate,
                emptyText = stringResource(MR.string.location_locatable_none),
                onExpandedChange = onLocatableExpandedChange,
                rowTrailing = { member ->
                    LocateStatusTrailing(uiState.whoICanLocateStatus[member.odinId.domainName])
                },
                // Tap opens the emergency retrieval panel — only rows whose temporal access
                // verified Active; Broken/Unreachable/Loading rows stay inert (the trailing
                // icon is the explanation).
                memberClick = { member ->
                    val status = uiState.whoICanLocateStatus[member.odinId.domainName]
                    if (status is LocateVerifyStatus.Active) {
                        { onLocateContact(member) }
                    } else null
                },
            )
        }

        // ── Who can locate you (members of our emergency-location-access circle) ──
        DashboardSection(
            title = stringResource(MR.string.location_emergency_access_section),
            onManage = onManageEmergencyAccess,
        ) {
            PeopleListBody(
                loaded = uiState.whoCanLocateMeLoaded,
                members = uiState.whoCanLocateMe,
                emptyText = stringResource(MR.string.location_emergency_access_none),
            )
        }

        // ── Footer ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(MR.string.location_status_points_today) +
                    ": ${uiState.pointsToday}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(MR.string.location_status_pending) +
                    ": ${uiState.pendingUploadCount}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DeviceRow(device: LocationDeviceInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = when (device.platform) {
                "desktop" -> Icons.Outlined.Computer
                "web" -> Icons.Outlined.Language
                else -> Icons.Outlined.Smartphone
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name
                    ?: stringResource(MR.string.location_device_unnamed, device.shortId),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = device.lastFix?.let {
                    formatTimestamp(Instant.fromEpochMilliseconds(it.t))
                } ?: stringResource(MR.string.location_device_no_fix),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (device.isThisDevice) {
            SuggestionChip(
                onClick = onClick,
                label = { Text(stringResource(MR.string.location_device_this_device)) },
            )
        }
    }
}

/**
 * A titled dashboard section: a tight title row (with an optional compact manage
 * "+" affordance) hugging its content [Card]. Used for the two emergency lists so
 * their layout matches.
 */
@Composable
private fun DashboardSection(
    title: String,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (onManage != null) {
                // Compact tappable "+" (not a 48dp IconButton, which would balloon
                // the title row and push the card away).
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(MR.string.location_emergency_access_manage),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onManage)
                        .padding(4.dp)
                        .size(22.dp),
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/**
 * Body of a people-list dashboard section ("Who can locate you" / "Who you can locate"): a loading
 * spinner, an [emptyText] empty state, or an avatar stack that expands to a named list.
 */
@Composable
private fun PeopleListBody(
    loaded: Boolean,
    members: List<ContactUiModel>,
    emptyText: String,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    rowTrailing: (@Composable (ContactUiModel) -> Unit)? = null,
    /** Per-member tap action; return null for an inert row (no ripple, no handler). */
    memberClick: ((ContactUiModel) -> (() -> Unit)?)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    // Report every open/close so the (optional) per-entry preflight loop starts on each expand and
    // stops on collapse; successful data-bearing results younger than the TTL are skipped
    // downstream, so a quick re-expand is cheap — error/no-data rows re-verify on every expand
    // so a broken cloud can't stick (#985). Leaving composition (navigating away) counts as a close.
    LaunchedEffect(expanded) { onExpandedChange?.invoke(expanded) }
    DisposableEffect(Unit) { onDispose { onExpandedChange?.invoke(false) } }
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EmergencyAvatarStack(members = members, modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column {
                        members.forEach { member ->
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
        }
    }

/** Overlapping avatar stack with a "+N" overflow chip. */
@Composable
private fun EmergencyAvatarStack(
    members: List<ContactUiModel>,
    modifier: Modifier = Modifier,
    maxVisible: Int = 6,
) {
    val visible = members.take(maxVisible)
    val overflow = members.size - visible.size
    val avatarSize = 36.dp
    val ringSize = avatarSize + 4.dp
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(-(avatarSize / 3)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEach { member ->
            Box(
                modifier = Modifier
                    .size(ringSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                PublicAvatar(
                    odinId = member.odinId,
                    initials = member.avatarInitials,
                    options = AvatarOptions(size = avatarSize),
                )
            }
        }
        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .size(ringSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(MR.string.location_emergency_access_more, overflow),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** One row in the "Sharing with" list: avatar, name, "until …", and a per-person stop button. */
@Composable
private fun OutgoingShareRowItem(
    row: OutgoingShareRow,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PublicAvatar(
            odinId = OdinId(row.odinId),
            initials = row.avatarInitials,
            options = AvatarOptions(size = 40.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    MR.string.location_dashboard_share_until,
                    formatUntilTime(Instant.fromEpochMilliseconds(row.untilMs)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onStop) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(MR.string.location_dashboard_stop_one_cd, row.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One row in the "Sharing with you" list: avatar, name, and the last-fix age (only past 2 min). */
@Composable
private fun IncomingShareRowItem(row: IncomingShareRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PublicAvatar(
            odinId = OdinId(row.odinId),
            initials = row.avatarInitials,
            options = AvatarOptions(size = 40.dp),
        )
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (row.ageMs > AGE_LABEL_AFTER_MS) {
            Text(
                text = stringResource(MR.string.live_location_age_minutes, (row.ageMs / 60_000L).toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Trailing content for a "who I can locate" row: a spinner while an expand-triggered temporal-
 * access preflight is in flight, a broken-link icon when access is gone, a disconnected icon when the
 * peer's server couldn't be reached (inconclusive — not broken), or the compact age of the peer's
 * newest data (warning-orange past [LOCATE_AGE_WARN_MS]). Access with no data yet shows an explicit
 * "no data" label (the GPS-not-reporting case, #875). A null/absent status (section not yet
 * expanded) renders nothing.
 */
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
                // Access but no data yet — their GPS isn't reporting; warn rather than stay blank.
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

/** Compact "age since newest data" label: minutes, then hours (through 96 h), then days. */
@Composable
private fun formatLocateAge(ageMs: Long): String = when (val bucket = locateAgeBucket(ageMs)) {
    is LocateAgeBucket.Minutes -> stringResource(MR.string.live_location_age_minutes, bucket.minutes)
    is LocateAgeBucket.Hours -> stringResource(MR.string.location_locate_age_hours, bucket.hours)
    is LocateAgeBucket.Days -> stringResource(MR.string.location_locate_age_days, bucket.days)
}

@Composable
fun dashboardTraceColors() = id.homebase.core.ui.screens.location.history.mapTraceColors
