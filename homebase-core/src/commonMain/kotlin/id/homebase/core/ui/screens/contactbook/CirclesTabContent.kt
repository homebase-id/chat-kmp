package id.homebase.core.ui.screens.contactbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.resources.MR
import id.homebase.resources.enroll_banner
import id.homebase.resources.enroll_banner_action
import id.homebase.resources.contactbook_circle_new
import id.homebase.resources.contactbook_circles_empty
import org.jetbrains.compose.resources.stringResource

@Composable
fun CirclesTabContent(
    circles: List<CircleWithMembers>,
    loading: Boolean,
    onAction: (ContactBookUiAction) -> Unit,
    modifier: Modifier = Modifier,
    /** Contacts who qualify for one of this app's circles and are not in it yet; 0 hides the row. */
    candidateCount: Int = 0,
) {
    when {
        loading && circles.isEmpty() -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        circles.isEmpty() -> Box(
            modifier = modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(MR.string.contactbook_circles_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> {
            // Client-side display override only — the auto-connected system circle keeps its
            // server-side name/id, we just relabel it "New" here.
            val newName = stringResource(MR.string.contactbook_circle_new)
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                // Only when there is something to offer: the server omits circles with no
                // backlog, so a zero here means nothing to say rather than nothing loaded.
                if (candidateCount > 0) {
                    item(key = "enrollment-candidates") {
                        ListItem(
                            modifier = Modifier.clickable {
                                onAction(ContactBookUiAction.EnrollmentCandidatesClicked)
                            },
                            leadingContent = {
                                Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                            },
                            headlineContent = {
                                Text(stringResource(MR.string.enroll_banner, candidateCount))
                            },
                            supportingContent = {
                                Text(stringResource(MR.string.enroll_banner_action))
                            },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
                items(circles, key = { it.circle.id }) { circle ->
                    val description = circle.circle.description
                    val displayName = if (circle.circle.id == AUTO_CONNECTIONS_CIRCLE_ID) {
                        newName
                    } else {
                        circle.circle.name
                    }
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAction(ContactBookUiAction.CircleClicked(circle)) },
                        leadingContent = {
                            val emoji = circle.circle.emoji
                            if (emoji.isNullOrBlank()) {
                                Icon(Icons.Outlined.Groups, contentDescription = null)
                            } else {
                                // Decorative: the headline right beside it already names the circle.
                                Text(
                                    text = emoji,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.clearAndSetSemantics { },
                                )
                            }
                        },
                        headlineContent = { Text(displayName) },
                        supportingContent = if (!description.isNullOrBlank()) {
                            { Text(description) }
                        } else null,
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }
}
