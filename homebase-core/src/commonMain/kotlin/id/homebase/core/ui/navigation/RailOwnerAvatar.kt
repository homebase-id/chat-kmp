package id.homebase.core.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.initials
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.OwnerAvatar
import id.homebase.core.avatars.OwnerAvatarState
import id.homebase.resources.MR
import id.homebase.resources.settings
import org.jetbrains.compose.resources.stringResource

const val RailOwnerAvatarTag = "railOwnerAvatar"

@Composable
internal fun RailOwnerAvatar(
    session: OwnerSession?,
    state: OwnerAvatarState,
    onNavigate: (Route) -> Unit,
) {
    if (session == null) return
    val label = stringResource(MR.string.settings)
    OwnerAvatar(
        odinId = session.odinId,
        profileImageData = null,
        initials = session.initials(),
        connectionStatus = state.connectionStatus,
        driveIsSyncing = state.driveIsSyncing,
        hasDriveError = state.hasDriveError,
        options = AvatarOptions(size = 32.dp, fontSize = 12.sp, onClick = { onNavigate(Route.Settings) }),
        modifier = Modifier
            .padding(bottom = 8.dp)
            .testTag(RailOwnerAvatarTag)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
            },
        sharedTransitionScope = null,
        animatedVisibilityScope = null,
    )
}
