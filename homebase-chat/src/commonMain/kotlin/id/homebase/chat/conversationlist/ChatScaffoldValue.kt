package id.homebase.chat.conversationlist

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import kotlin.uuid.Uuid

/**
 * The chat pane scaffold's layout, derived from the open conversation and the window width alone,
 * so a compact window owned by an empty detail pane — no list, no back, no bottom bar — has no
 * input that produces it.
 *
 * One destination is enough for a two-pane window: the library expands the destination's own pane,
 * then walks Primary/Secondary/Tertiary to fill whatever partition is left over.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun chatScaffoldValue(
    directive: PaneScaffoldDirective,
    selectedConversationId: Uuid?,
): ThreePaneScaffoldValue = calculateThreePaneScaffoldValue(
    maxHorizontalPartitions = directive.maxHorizontalPartitions,
    adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
    currentDestination = ThreePaneScaffoldDestinationItem(
        pane = if (selectedConversationId == null) {
            ListDetailPaneScaffoldRole.List
        } else {
            ListDetailPaneScaffoldRole.Detail
        },
        contentKey = selectedConversationId,
    ),
    maxVerticalPartitions = directive.maxVerticalPartitions,
)
