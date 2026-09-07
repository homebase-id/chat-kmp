package id.homebase.core.ui.screens.location.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.util.formatTimestamp
import id.homebase.resources.MR
import id.homebase.resources.location_device_no_fix
import id.homebase.resources.location_device_this_device
import id.homebase.resources.location_device_unnamed
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

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
