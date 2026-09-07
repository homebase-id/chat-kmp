package id.homebase.auth.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.login_continue_as
import org.jetbrains.compose.resources.stringResource

/** Renders from the saved domain alone and fills the name and avatar in later, or never — the user
 *  demonstrably signed in here before, so being offline is no reason to hide the shortcut. */
@Composable
internal fun LastIdentityCard(
    identity: IdentityPreview,
    // False when the brand panel is on screen: it is already showing this avatar and name at 96dp.
    showName: Boolean,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = identity.label()
    Button(
        onClick = onContinue,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("last_identity_card"),
    ) {
        if (showName) {
            IdentityAvatar(
                identity = identity,
                size = 48.dp,
                containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (showName) name else identity.odinId.domainName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("last_identity_name"),
            )
            if (showName && identity.isResolved) {
                Text(
                    text = identity.odinId.domainName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = stringResource(MR.string.login_continue_as, name),
        )
    }
}
