package id.homebase.core.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.initials
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.OwnerAvatar
import id.homebase.core.avatars.OwnerAvatarState
import id.homebase.core.ui.theme.NavigationIndicatorShape
import id.homebase.resources.MR
import id.homebase.resources.settings
import org.jetbrains.compose.resources.stringResource

const val RailOwnerAvatarTag = "railOwnerAvatar"

// OwnerAvatar pads its box by this for the status dot, leaving the face off the rail's centre line.
private val StatusDotOverhang = 3.dp

@Composable
internal fun RailOwnerAvatar(
    session: OwnerSession?,
    state: OwnerAvatarState,
    onNavigate: (Route) -> Unit,
) {
    val slot = Modifier.padding(bottom = 8.dp).size(RailIndicatorSize)
    // Keeps its height until the session loads, so the tabs below don't jump.
    if (session == null) {
        Spacer(slot)
        return
    }
    val label = stringResource(MR.string.settings)
    Box(
        modifier = slot
            .testTag(RailOwnerAvatarTag)
            .clip(NavigationIndicatorShape)
            .clickable(role = Role.Button, onClick = { onNavigate(Route.Settings) })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        OwnerAvatar(
            odinId = session.odinId,
            profileImageData = null,
            initials = session.initials(),
            connectionStatus = state.connectionStatus,
            driveIsSyncing = state.driveIsSyncing,
            hasDriveError = state.hasDriveError,
            options = AvatarOptions(size = 32.dp, fontSize = 12.sp),
            modifier = Modifier
                .offset(StatusDotOverhang, StatusDotOverhang)
                .clearAndSetSemantics {},
            sharedTransitionScope = null,
            animatedVisibilityScope = null,
        )
    }
}
