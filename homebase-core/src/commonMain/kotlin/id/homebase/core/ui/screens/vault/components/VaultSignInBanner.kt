package id.homebase.core.ui.screens.vault.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.vault_sign_in_again_body
import id.homebase.resources.vault_sign_in_again_button
import id.homebase.resources.vault_sign_in_again_continue
import id.homebase.resources.vault_sign_in_again_title
import org.jetbrains.compose.resources.stringResource

/** [blocked]: the browser blocked the sign-in window, so the button re-opens it from a fresh click. */
@Composable
fun VaultSignInBanner(
    blocked: Boolean,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = Icons.Outlined.Lock, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(MR.string.vault_sign_in_again_title), style = MaterialTheme.typography.titleSmall)
                Text(text = stringResource(MR.string.vault_sign_in_again_body), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onSignIn) {
                Text(
                    stringResource(if (blocked) MR.string.vault_sign_in_again_continue else MR.string.vault_sign_in_again_button)
                )
            }
        }
    }
}
