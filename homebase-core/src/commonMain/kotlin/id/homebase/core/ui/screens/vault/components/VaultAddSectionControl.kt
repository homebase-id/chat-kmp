package id.homebase.core.ui.screens.vault.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import id.homebase.resources.MR
import id.homebase.resources.vault_new_section_add
import org.jetbrains.compose.resources.stringResource

@Composable
fun VaultAddSectionControl(
    onAddSection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onAddSection,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = stringResource(MR.string.vault_new_section_add),
        )
    }
}
