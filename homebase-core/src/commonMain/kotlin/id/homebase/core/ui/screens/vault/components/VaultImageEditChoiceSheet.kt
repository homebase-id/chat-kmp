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
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Draw
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
import id.homebase.resources.vault_edit_add_as_is
import id.homebase.resources.vault_edit_before_adding
import id.homebase.resources.vault_edit_crop
import id.homebase.resources.vault_edit_draw
import org.jetbrains.compose.resources.stringResource

/**
 * Optional editor gate shown after a single image is picked for the Vault.
 * Routes the picked image through the shared crop/draw editor (image-editor-ui)
 * before upload, or adds it unchanged. Keeps editing optional so a quick
 * "just add it" stays one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultImageEditChoiceSheet(
    sheetState: SheetState,
    onCrop: () -> Unit,
    onDraw: () -> Unit,
    onAddAsIs: () -> Unit,
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
                text = stringResource(MR.string.vault_edit_before_adding),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(modifier = Modifier.height(16.dp))

            EditChoiceRow(
                icon = Icons.Outlined.Crop,
                label = stringResource(MR.string.vault_edit_crop),
                onClick = onCrop,
            )
            EditChoiceRow(
                icon = Icons.Outlined.Draw,
                label = stringResource(MR.string.vault_edit_draw),
                onClick = onDraw,
            )
            EditChoiceRow(
                icon = Icons.Outlined.AddPhotoAlternate,
                label = stringResource(MR.string.vault_edit_add_as_is),
                onClick = onAddAsIs,
            )
        }
    }
}

@Composable
private fun EditChoiceRow(
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
