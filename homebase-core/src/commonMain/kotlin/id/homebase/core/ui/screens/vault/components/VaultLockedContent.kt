package id.homebase.core.ui.screens.vault.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import id.homebase.resources.MR
import id.homebase.resources.vault_locked_subtitle
import id.homebase.resources.vault_locked_title
import id.homebase.resources.vault_locked_unlock
import org.jetbrains.compose.resources.stringResource

@Composable
fun VaultLockedContent(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BiometricLockedContent(
        title = stringResource(MR.string.vault_locked_title),
        subtitle = stringResource(MR.string.vault_locked_subtitle),
        unlockLabel = stringResource(MR.string.vault_locked_unlock),
        onUnlock = onUnlock,
        modifier = modifier,
    )
}
