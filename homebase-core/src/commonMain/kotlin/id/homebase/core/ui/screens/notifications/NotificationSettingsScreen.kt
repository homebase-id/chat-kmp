package id.homebase.core.ui.screens.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.clipboard.clipEntryOf
import id.homebase.core.notifications.rememberOpenSystemNotificationSettings
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.createPermissionsManager
import id.homebase.core.ui.theme.ExtendedColors
import id.homebase.core.widget.SettingsOptionRow
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.not_available
import id.homebase.resources.settings_badge_count
import id.homebase.resources.settings_copy_token
import id.homebase.resources.settings_device_token
import id.homebase.resources.settings_enable_notifications
import id.homebase.resources.settings_friendly_name
import id.homebase.resources.settings_include_muted_chats
import id.homebase.resources.settings_message_sound
import id.homebase.resources.settings_notification_content
import id.homebase.resources.settings_notification_locked_screen_note
import id.homebase.resources.settings_notification_show
import id.homebase.resources.settings_notifications
import id.homebase.resources.settings_notifications_denied_body
import id.homebase.resources.settings_notifications_disabled_body
import id.homebase.resources.settings_notifications_disabled_title
import id.homebase.resources.settings_open_settings
import id.homebase.resources.settings_play_while_app_open
import id.homebase.resources.settings_push_notification_status
import id.homebase.resources.settings_re_register_failure
import id.homebase.resources.settings_re_register_push
import id.homebase.resources.settings_re_register_success
import id.homebase.resources.settings_re_registering
import id.homebase.resources.settings_registration_error
import id.homebase.resources.settings_registration_not_registered
import id.homebase.resources.settings_registration_registered
import id.homebase.resources.settings_registration_status
import id.homebase.resources.settings_registration_unknown
import id.homebase.resources.settings_server_token
import id.homebase.resources.settings_server_verification
import id.homebase.resources.settings_sound_system_default
import id.homebase.resources.settings_sounds
import id.homebase.resources.settings_status
import id.homebase.resources.settings_verifying
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun NotificationSettingsScreen(
    viewModel: NotificationSettingsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val openSystemSettings = rememberOpenSystemNotificationSettings()

    val permissionManager = createPermissionsManager { type, status, isPermanentlyDenied ->
        if (type == PermissionType.NOTIFICATION) {
            viewModel.updatePermissionStatus(
                isGranted = status == PermissionStatus.GRANTED,
                isPermanentlyDenied = isPermanentlyDenied
            )
        }
    }

    LaunchedEffect(Unit) {
        val granted = permissionManager.isPermissionGranted(PermissionType.NOTIFICATION)
        viewModel.updatePermissionStatus(granted)
    }

    NotificationSettingsUi(
        uiState = uiState, onAction = { action ->
            when (action) {
                is NotificationSettingsUiAction.RequestPermission ->
                    permissionManager.askPermission(PermissionType.NOTIFICATION)
                is NotificationSettingsUiAction.OpenSystemNotificationSettings ->
                    permissionManager.launchSettings()
                else -> viewModel.onAction(action)
            }
        }, onBackClick = onBackClick, onOpenSystemSettings = openSystemSettings
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsUi(
    uiState: NotificationSettingsUiState,
    onAction: (NotificationSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.settings_notifications), modifier = Modifier.testTag("notificationsTitle")) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                })
        }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(scrollState),
        ) {
            // ── Permission Section ──
            if (!uiState.isPermissionGranted) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            modifier = Modifier.testTag("pushNotificationsDisabled"),
                            text = stringResource(MR.string.settings_notifications_disabled_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (uiState.isPermissionPermanentlyDenied)
                                stringResource(MR.string.settings_notifications_denied_body)
                            else
                                stringResource(MR.string.settings_notifications_disabled_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        if (uiState.isPermissionPermanentlyDenied) {
                            Button(onClick = {
                                onAction(NotificationSettingsUiAction.OpenSystemNotificationSettings)
                            }) {
                                Text(stringResource(MR.string.settings_open_settings))
                            }
                        } else {
                            Button(
                                modifier = Modifier.testTag("enableNotificationsButton"),
                                onClick = {
                                    onAction(NotificationSettingsUiAction.RequestPermission)
                                }) { Text(stringResource(MR.string.settings_enable_notifications)) }
                        }
                    }
                }
            }

            // ── Sounds Section ──
            NotificationSectionHeader(
                title = stringResource(MR.string.settings_sounds),
                modifier = Modifier.testTag("soundsTitle"),
            )
            SettingsRow(
                modifier = Modifier.testTag("messageSound"),
                icon = Icons.Outlined.MusicNote,
                title = stringResource(MR.string.settings_message_sound),
                supportingText = stringResource(MR.string.settings_sound_system_default),
                action = SettingsRowAction.External(onOpenSystemSettings),
            )
            SettingsRow(
                modifier = Modifier.testTag("playWhileAppIsOpen"),
                icon = Icons.AutoMirrored.Outlined.VolumeUp,
                title = stringResource(MR.string.settings_play_while_app_open),
                action = SettingsRowAction.Toggle(
                    checked = uiState.playWhileAppOpen,
                    onCheckedChange = {
                        onAction(NotificationSettingsUiAction.SetPlayWhileAppOpen(it))
                    },
                ),
            )

            // ── Notification Content Section ──
            NotificationSectionHeader(
                title = stringResource(MR.string.settings_notification_content),
                modifier = Modifier.testTag("notificationContent"),
            )
            SettingsRow(
                modifier = Modifier.testTag(uiState.notificationContentLevel.code),
                icon = Icons.Outlined.Visibility,
                title = stringResource(MR.string.settings_notification_show),
                supportingText = uiState.notificationContentLevel.displayName,
                action = SettingsRowAction.Expand(
                    expanded = uiState.showContentLevelPicker,
                    onExpandedChange = {
                        onAction(NotificationSettingsUiAction.ToggleContentLevelPicker)
                    },
                ),
            )
            if (uiState.showContentLevelPicker) {
                NotificationContentLevel.entries.forEach { level ->
                    SettingsOptionRow(
                        label = level.displayName,
                        selected = level == uiState.notificationContentLevel,
                        onClick = {
                            onAction(NotificationSettingsUiAction.SetContentLevel(level))
                        },
                    )
                }
            }
            Text(
                text = stringResource(MR.string.settings_notification_locked_screen_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // ── Badge Count Section ──
            NotificationSectionHeader(
                title = stringResource(MR.string.settings_badge_count),
                modifier = Modifier.testTag("badgeCount"),
            )
            SettingsRow(
                modifier = Modifier.testTag("includeMutedChats"),
                icon = Icons.Outlined.NotificationsOff,
                title = stringResource(MR.string.settings_include_muted_chats),
                action = SettingsRowAction.Toggle(
                    checked = uiState.includeMutedChatsInBadge,
                    onCheckedChange = {
                        onAction(NotificationSettingsUiAction.SetIncludeMutedChatsInBadge(it))
                    },
                ),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Re-register Push Notifications ──
            // Kept as a Card, not a SettingsRow: it must go inert while a re-registration is in
            // flight, and SettingsRow has no disabled state by design.
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clickable(enabled = !uiState.isReRegistering) {
                        onAction(NotificationSettingsUiAction.ReRegisterPushNotifications)
                    }) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        modifier = Modifier.testTag("reRegisterPushNotifications"),
                        text = if (uiState.isReRegistering) stringResource(MR.string.settings_re_registering)
                        else stringResource(MR.string.settings_re_register_push),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (uiState.isReRegistering) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ── Re-register Result Feedback ──
            uiState.reRegisterResult?.let { result ->
                LaunchedEffect(result) {
                    delay(5000)
                    onAction(NotificationSettingsUiAction.DismissReRegisterResult)
                }

                val isSuccess = result is ReRegisterResult.Success
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSuccess) ExtendedColors.Success.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = when (result) {
                            is ReRegisterResult.Success -> stringResource(MR.string.settings_re_register_success)
                            is ReRegisterResult.Failure -> stringResource(MR.string.settings_re_register_failure, result.message)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSuccess) ExtendedColors.Success
                        else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // ── Push Notification Status (Debug — tap header 5 times to reveal) ──
            NotificationSectionHeader(
                title = stringResource(MR.string.settings_push_notification_status),
                modifier = Modifier
                    .testTag("pushNotificationStatusHeader")
                    .clickable { onAction(NotificationSettingsUiAction.DebugHeaderTapped) },
            )

            if (uiState.showDebugInfo) {
                val clipboardManager = LocalClipboard.current
                val scope = rememberCoroutineScope()

                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column {
                        // Token row
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(MR.string.settings_device_token),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = uiState.deviceToken?.let { token ->
                                        if (token.length > 12) "${token.take(8)}...${token.takeLast(4)}"
                                        else token
                                    } ?: stringResource(MR.string.not_available),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            uiState.deviceToken?.let { token ->
                                IconButton(onClick = {
                                    scope.launch {
                                        clipboardManager.setClipEntry(clipEntryOf(token))
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = stringResource(MR.string.settings_copy_token),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        // Status row
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(MR.string.settings_registration_status),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = when (uiState.registrationStatus) {
                                    RegistrationStatus.UNKNOWN -> stringResource(MR.string.settings_registration_unknown)
                                    RegistrationStatus.REGISTERED -> stringResource(MR.string.settings_registration_registered)
                                    RegistrationStatus.NOT_REGISTERED -> stringResource(MR.string.settings_registration_not_registered)
                                    RegistrationStatus.ERROR -> stringResource(MR.string.settings_registration_error)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = when (uiState.registrationStatus) {
                                    RegistrationStatus.REGISTERED -> ExtendedColors.Success
                                    RegistrationStatus.ERROR -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        // Server verification section
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = stringResource(MR.string.settings_server_verification),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            if (uiState.isVerifyingSubscription) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = stringResource(MR.string.settings_verifying),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            uiState.subscriptionVerification?.let { result ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(MR.string.settings_status),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = result.status,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (result.isOk) ExtendedColors.Success
                                        else MaterialTheme.colorScheme.error
                                    )
                                }
                                result.friendlyName?.let { name ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(MR.string.settings_friendly_name),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                                result.serverToken?.let { token ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(MR.string.settings_server_token),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = if (token.length > 12) "${token.take(8)}...${token.takeLast(4)}"
                                            else token,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NotificationSectionHeader(title: String, modifier: Modifier = Modifier) {
    SettingsSectionHeader(
        title = title,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}
