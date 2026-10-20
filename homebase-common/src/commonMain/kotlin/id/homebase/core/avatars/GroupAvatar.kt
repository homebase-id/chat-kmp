package id.homebase.core.avatars

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A group or circle has no identity photo, so every surface draws it the same way. */
@Composable
fun GroupAvatar(
    initials: String?,
    options: AvatarOptions,
    modifier: Modifier = Modifier,
) {
    FallbackAvatar(initials = initials, options = options, modifier = modifier, imageVector = Icons.Default.People)
}
