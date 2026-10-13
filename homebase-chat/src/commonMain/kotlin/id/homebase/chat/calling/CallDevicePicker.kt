package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.calling.MediaDeviceKind
import id.homebase.resources.MR
import id.homebase.resources.call_device_camera
import id.homebase.resources.call_device_microphone
import id.homebase.resources.call_device_speaker
import id.homebase.resources.call_device_system_default
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun MediaDeviceKind.label(): StringResource = when (this) {
    MediaDeviceKind.Mic -> MR.string.call_device_microphone
    MediaDeviceKind.Speaker -> MR.string.call_device_speaker
    MediaDeviceKind.Camera -> MR.string.call_device_camera
}

/** Radio sections of the call's devices; [kinds] limits the menu to the dock button's own kind. */
@Composable
internal fun CallDeviceMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    ui: CallUiState,
    kinds: List<MediaDeviceKind>,
    onSelect: (MediaDeviceKind, String) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) { CallDeviceMenuItems(onDismiss, ui, kinds, onSelect) }
}

internal fun currentDeviceName(ui: CallUiState, kind: MediaDeviceKind): String? =
    ui.devices.firstOrNull { it.kind == kind && it.id == ui.selectedDevices[kind] }?.name

private fun MediaDeviceKind.icon(): ImageVector = when (this) {
    MediaDeviceKind.Mic -> Icons.Default.Mic
    MediaDeviceKind.Speaker -> Icons.AutoMirrored.Filled.VolumeUp
    MediaDeviceKind.Camera -> Icons.Default.Videocam
}

@Composable
internal fun ColumnScope.CallDeviceMenuItems(
    onDismiss: () -> Unit,
    ui: CallUiState,
    kinds: List<MediaDeviceKind>,
    onSelect: (MediaDeviceKind, String) -> Unit,
) {
    val shown = kinds.filter { kind -> ui.devices.any { it.kind == kind } }
    shown.forEachIndexed { index, kind ->
        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(stringResource(kind.label()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        ui.devices.filter { it.kind == kind }.forEach { device ->
            val selected = ui.selectedDevices[kind] == device.id
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            device.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Its own line, so a long device name is not cut short by the label.
                        if (device.isDefault) {
                            Text(
                                stringResource(MR.string.call_device_system_default),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                leadingIcon = { RadioButton(selected = selected, onClick = null) },
                contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                modifier = Modifier
                    .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) else Modifier)
                    .semantics { role = Role.RadioButton; this.selected = selected },
                onClick = {
                    onDismiss()
                    onSelect(kind, device.id)
                },
            )
        }
    }
}

@Composable
internal fun CallDeviceSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data -> Snackbar(data) }
}
