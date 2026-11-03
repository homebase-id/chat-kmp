package id.homebase.core.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.initials
import id.homebase.core.avatars.AppConnectionStatus
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.OwnerAvatar
import id.homebase.core.avatars.OwnerAvatarState
import id.homebase.core.ui.theme.NavigationIndicatorShape
import id.homebase.resources.MR
import id.homebase.resources.not_connected
import id.homebase.resources.owner_status_connected
import id.homebase.resources.owner_status_connecting
import id.homebase.resources.owner_status_sync_problem
import id.homebase.resources.owner_status_syncing
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
    selected: Boolean = false,
) {
    val slot = Modifier.padding(bottom = 8.dp).size(RailIndicatorSize)
    // Keeps its height until the session loads, so the tabs below don't jump.
    if (session == null) {
        Spacer(slot)
        return
    }
    val label = stringResource(MR.string.settings)
    val status = stringResource(ownerStatusLabel(state))
    val indicatorColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    Box(
        modifier = slot
            .testTag(RailOwnerAvatarTag)
            .clip(NavigationIndicatorShape)
            .background(indicatorColor)
            .clickable(role = Role.Button, onClick = { onNavigate(Route.Settings) })
            .semantics {
                contentDescription = label
                stateDescription = status
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        OwnerAvatar(
            odinId = session.odinId,
            profileImageData = null,
            initials = session.initials(),
            connectionStatus = state.connectionStatus,
            driveIsSyncing = state.driveIsSyncing,
            hasDriveError = state.hasDriveError,
            statusRingColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            options = AvatarOptions(size = 32.dp, fontSize = 12.sp),
            modifier = Modifier
                .offset(StatusDotOverhang, StatusDotOverhang)
                .clearAndSetSemantics {},
            sharedTransitionScope = null,
            animatedVisibilityScope = null,
        )
    }
}

private fun ownerStatusLabel(state: OwnerAvatarState) = when {
    state.connectionStatus == AppConnectionStatus.Connected && state.hasDriveError -> MR.string.owner_status_sync_problem
    state.connectionStatus == AppConnectionStatus.Connected && state.driveIsSyncing -> MR.string.owner_status_syncing
    state.connectionStatus == AppConnectionStatus.Connected -> MR.string.owner_status_connected
    state.connectionStatus == AppConnectionStatus.Connecting -> MR.string.owner_status_connecting
    else -> MR.string.not_connected
}
