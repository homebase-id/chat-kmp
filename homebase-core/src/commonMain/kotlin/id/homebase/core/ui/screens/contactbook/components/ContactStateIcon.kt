package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.ContactState
import id.homebase.resources.MR
import id.homebase.resources.contact_state_chat
import id.homebase.resources.contact_state_circle
import id.homebase.resources.contact_state_new
import org.jetbrains.compose.resources.stringResource

/**
 * The contact's state, in the row's fixed trailing slot.
 *
 * A tinted vector, not the 👋/💬/⭕ emoji the docs use as shorthand: colour fonts ignore text
 * colour, so an emoji here could not carry the theme. User-chosen circle emoji stay full-colour
 * elsewhere, which keeps the two visually distinct species.
 */
@Composable
fun ContactStateIcon(state: ContactState, modifier: Modifier = Modifier) {
    val (icon, label) = when (state) {
        ContactState.New -> Icons.Outlined.WavingHand to stringResource(MR.string.contact_state_new)
        ContactState.Chat -> Icons.Outlined.ChatBubbleOutline to stringResource(MR.string.contact_state_chat)
        ContactState.Circle -> Icons.Outlined.Workspaces to stringResource(MR.string.contact_state_circle)
    }
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(20.dp),
    )
}
