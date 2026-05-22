package id.homebase.core.ui.screens.vault.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.vault_add_entry
import id.homebase.resources.vault_add_note
import id.homebase.resources.vault_choose_file
import id.homebase.resources.vault_image_choose_gallery
import id.homebase.resources.vault_image_take_photo
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultAddEntrySheet(
    sheetState: SheetState,
    onTakePhoto: () -> Unit,
    onChooseGallery: () -> Unit,
    onChooseFile: () -> Unit,
    onAddNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(MR.string.vault_add_entry),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(modifier = Modifier.height(16.dp))

            SheetActionRow(
                icon = Icons.Outlined.CameraAlt,
                label = stringResource(MR.string.vault_image_take_photo),
                onClick = onTakePhoto,
            )
            SheetActionRow(
                icon = Icons.Outlined.Image,
                label = stringResource(MR.string.vault_image_choose_gallery),
                onClick = onChooseGallery,
            )
            SheetActionRow(
                icon = Icons.Outlined.Description,
                label = stringResource(MR.string.vault_choose_file),
                onClick = onChooseFile,
            )
            SheetActionRow(
                icon = Icons.AutoMirrored.Outlined.NoteAdd,
                label = stringResource(MR.string.vault_add_note),
                onClick = onAddNote,
            )
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
