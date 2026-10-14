package id.homebase.chat.calling

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.services.calling.MediaDeviceKind
import id.homebase.resources.MR
import id.homebase.resources.call_device_camera
import id.homebase.resources.call_device_dismiss
import id.homebase.resources.call_device_input_level
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
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.widthIn(min = 264.dp, max = 360.dp),
    ) { CallDeviceMenuItems(onDismiss, ui, kinds, onSelect) }
}

internal fun currentDeviceName(ui: CallUiState, kind: MediaDeviceKind): String? =
    ui.devices.firstOrNull { it.kind == kind && it.id == ui.selectedDevices[kind] }?.name

internal fun MediaDeviceKind.icon(): ImageVector = when (this) {
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
        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp).semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(stringResource(kind.label()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        ui.devices.filter { it.kind == kind }.forEach { device ->
            val selected = ui.selectedDevices[kind] == device.id
            // Radio plus an inset tint: weight on top of both would be a third signal for the same state.
            DropdownMenuItem(
                text = {
                    Column {
                        Text(device.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                contentPadding = PaddingValues(start = 8.dp, end = 12.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                    .semantics { role = Role.RadioButton; this.selected = selected },
                onClick = {
                    onDismiss()
                    onSelect(kind, device.id)
                },
            )
        }
        if (kind == MediaDeviceKind.Mic && !ui.muted && ui.micLevelSeen) InputLevelRow()
    }
}

/** Live proof that the newly picked mic is hearing the user, before the menu closes. */
@Composable
private fun InputLevelRow() {
    val level by LocalMicLevel.current.collectAsStateWithLifecycle()
    val shown by animateFloatAsState(level.coerceIn(0f, 1f), tween(90), label = "menuMicLevel")
    val description = stringResource(MR.string.call_device_input_level)
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp).semantics(mergeDescendants = true) {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/** A device notice: names the kind with its icon and offers the one action that answers it. */
internal class DeviceSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
    val kind: MediaDeviceKind,
) : SnackbarVisuals {
    override val withDismissAction: Boolean get() = true
    override val duration: SnackbarDuration get() = SnackbarDuration.Long
}

@Composable
internal fun CallDeviceSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data -> DeviceSnackbar(data) }
}

@Composable
private fun DeviceSnackbar(data: SnackbarData) {
    val kind = (data.visuals as? DeviceSnackbarVisuals)?.kind
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 480.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (kind != null) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                }
            }
            Text(
                data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = 12.dp, vertical = 8.dp),
            )
            data.visuals.actionLabel?.let { label ->
                TextButton(onClick = data::performAction) { Text(label) }
            }
            if (data.visuals.withDismissAction) {
                IconButton(onClick = data::dismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.call_device_dismiss), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
