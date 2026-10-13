package id.homebase.chat.calling

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.Modifier
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

@Composable
internal fun ColumnScope.CallDeviceMenuItems(
    onDismiss: () -> Unit,
    ui: CallUiState,
    kinds: List<MediaDeviceKind>,
    onSelect: (MediaDeviceKind, String) -> Unit,
) {
    run {
        val shown = kinds.filter { kind -> ui.devices.any { it.kind == kind } }
        shown.forEachIndexed { index, kind ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                stringResource(kind.label()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            ui.devices.filter { it.kind == kind }.forEach { device ->
                val selected = ui.selectedDevices[kind] == device.id
                val name = if (device.isDefault) stringResource(MR.string.call_device_system_default, device.name) else device.name
                DropdownMenuItem(
                    text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { RadioButton(selected = selected, onClick = null) },
                    trailingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.semantics { role = Role.RadioButton; this.selected = selected },
                    onClick = {
                        onDismiss()
                        onSelect(kind, device.id)
                    },
                )
            }
        }
    }
}

@Composable
internal fun CallDeviceSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data -> Snackbar(data) }
}
