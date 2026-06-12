package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.avatars.FallbackAvatar
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry

/**
 * Avatar for a contact-book entry. Identity contacts (with an odinId) render the
 * published public avatar via [ContactAvatar]; plain phone/email contacts fall
 * back to coloured initials via [FallbackAvatar].
 */
@Composable
fun ContactBookAvatar(
    entry: ContactBookEntry,
    size: Dp = 44.dp,
) {
    val options = AvatarOptions(size = size, fontSize = (size.value * 0.4f).sp)
    val odinId = entry.odinId
    if (!odinId.isNullOrBlank()) {
        val parsed = remember(odinId) { runCatching { OdinId(odinId) }.getOrNull() }
        if (parsed != null) {
            ContactAvatar(
                odinId = parsed,
                profileImageData = null,
                initials = entry.avatarInitials,
                options = options,
            )
            return
        }
    }
    FallbackAvatar(initials = entry.avatarInitials, options = options)
}
