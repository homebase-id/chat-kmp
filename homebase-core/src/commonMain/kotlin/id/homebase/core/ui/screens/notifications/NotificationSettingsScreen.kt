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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.notifications.rememberOpenSystemNotificationSettings
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.createPermissionsManager
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.settings_notifications
import org.jetbrains.compose.resources.stringResource

@Composable
fun NotificationSettingsScreen(
    viewModel: NotificationSettingsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val openSystemSettings = rememberOpenSystemNotificationSettings()

    val permissionManager = createPermissionsManager { type, status, _ ->
        if (type == PermissionType.NOTIFICATION) {
            viewModel.updatePermissionStatus(status == PermissionStatus.GRANTED)
        }
    }

    LaunchedEffect(Unit) {
        val granted = permissionManager.isPermissionGranted(PermissionType.NOTIFICATION)
        viewModel.updatePermissionStatus(granted)
    }

    NotificationSettingsUi(
        uiState = uiState, onAction = { action ->
            if (action is NotificationSettingsUiAction.RequestPermission) {
                permissionManager.askPermission(PermissionType.NOTIFICATION)
            } else {
                viewModel.onAction(action)
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
                title = { Text(stringResource(MR.string.settings_notifications)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
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
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Permission Section ──
            if (!uiState.isPermissionGranted) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Push Notifications are disabled",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Enable notifications to receive messages when the app is closed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                onAction(NotificationSettingsUiAction.RequestPermission)
                            }) { Text("Enable Notifications") }
                    }
                }
            }

            // ── Sounds Section ──
            SectionHeader(title = "Sounds")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    // Message Sound row — opens system notification settings
                    SettingsClickableRow(
                        label = "Message Sound",
                        value = "System Default",
                        onClick = onOpenSystemSettings
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    // Play While App is Open toggle
                    SettingsToggleRow(
                        label = "Play While App is Open",
                        checked = uiState.playWhileAppOpen,
                        onCheckedChange = {
                            onAction(NotificationSettingsUiAction.SetPlayWhileAppOpen(it))
                        })
                }
            }

            // ── Notification Content Section ──
            SectionHeader(title = "Notification Content")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsClickableRow(
                        label = "Show",
                        value = uiState.notificationContentLevel.displayName,
                        onClick = {
                            onAction(NotificationSettingsUiAction.ToggleContentLevelPicker)
                        })

                    if (uiState.showContentLevelPicker) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        NotificationContentLevel.entries.forEach { level ->
                            ContentLevelOption(
                                level = level,
                                isSelected = level == uiState.notificationContentLevel,
                                onClick = {
                                    onAction(
                                        NotificationSettingsUiAction.SetContentLevel(level)
                                    )
                                })
                        }
                    }
                }
            }
            Text(
                text = "Call and Message notifications can appear while your phone is locked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // ── Badge Count Section ──
            SectionHeader(title = "Badge Count")
            Card(modifier = Modifier.fillMaxWidth()) {
                SettingsToggleRow(
                    label = "Include Muted Chats",
                    checked = uiState.includeMutedChatsInBadge,
                    onCheckedChange = {
                        onAction(NotificationSettingsUiAction.SetIncludeMutedChatsInBadge(it))
                    })
            }


            // ── Re-register Push Notifications ──
            Card(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !uiState.isReRegistering) {
                    onAction(NotificationSettingsUiAction.ReRegisterPushNotifications)
                }) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (uiState.isReRegistering) "Re-registering..."
                        else "Re-register Push Notifications",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (uiState.isReRegistering) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// ── Reusable Setting Components ──

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun SettingsToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsClickableRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ContentLevelOption(
    level: NotificationContentLevel, isSelected: Boolean, onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = level.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
