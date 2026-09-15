package id.homebase.core.ui.screens.email.thunderbird

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.api.client.mail.MailClientSettings

/**
 * The values and actions every Thunderbird setup page draws on, so a page can offer the exact
 * button its instruction needs instead of sending the user to another screen to fetch it.
 *
 * [onSaveKey] is null until the identity has a published key — there is nothing to save before
 * then, and a button that cannot work is worse than no button.
 */
internal data class ThunderbirdActions(
    val address: String?,
    val password: String?,
    val settings: MailClientSettings?,
    val onCopy: (String) -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onSaveKey: (() -> Unit)?,
    val onCopyKey: (() -> Unit)?,
    /** No confirmation on this one: a public key is meant to be handed out. */
    val onCopyPublicKey: (() -> Unit)?,
)

@Composable
internal fun SetupCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
internal fun Body(text: String) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * FlowRow, not Row: on a narrow screen a fixed Row squeezes each button to its minimum width and
 * the labels wrap one character per line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}

@Composable
internal fun StepAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = label)
    }
}
