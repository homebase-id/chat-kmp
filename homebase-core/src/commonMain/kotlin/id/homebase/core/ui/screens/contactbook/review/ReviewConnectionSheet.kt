package id.homebase.core.ui.screens.contactbook.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.review_app_pending
import id.homebase.resources.review_apps_header
import id.homebase.resources.review_block
import id.homebase.resources.review_chat_only_helper
import id.homebase.resources.review_circles_header
import id.homebase.resources.review_disconnect
import id.homebase.resources.review_explainer
import id.homebase.resources.review_failed
import id.homebase.resources.review_failed_circle_not_allowed
import id.homebase.resources.review_failed_not_connected
import id.homebase.resources.review_follow_feed
import id.homebase.resources.review_introduced_by
import id.homebase.resources.review_keep_as_new
import id.homebase.resources.review_no_circles
import id.homebase.resources.review_submit_chat_only
import id.homebase.resources.review_submit_circles
import id.homebase.resources.review_title
import org.jetbrains.compose.resources.stringResource

/**
 * The connection review modal. One button with two destinations — its label always names the
 * state the tap produces, so the label change *is* the feedback that deselecting the last circle
 * changed the outcome.
 *
 * Every exit names where it leaves the contact: Add to circles (⭕), Chat only (💬), Keep as new
 * (👋). Scrim-tap and back keep plain cancel behaviour, which is the same destination as
 * "Keep as new".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewConnectionSheet(
    uiState: ReviewConnectionUiState,
    sheetState: SheetState,
    onAction: (ReviewConnectionUiAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            val name = uiState.displayName.ifBlank { uiState.odinId }

            Text(
                text = stringResource(MR.string.review_title, uiState.odinId),
                style = MaterialTheme.typography.titleLarge,
            )

            uiState.introducerOdinId?.let { introducer ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(MR.string.review_introduced_by, introducer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(MR.string.review_explainer, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            if (uiState.circles.isEmpty()) {
                Text(
                    text = stringResource(MR.string.review_no_circles),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SectionLabel(stringResource(MR.string.review_circles_header))
                // Special-permission circles (Emergency Location Access) sit below a divider so
                // they read as a deliberate grant rather than another social circle.
                val (special, ordinary) = uiState.circles.partition { it.special }
                ordinary.forEach { circle ->
                    CircleCheckbox(
                        circle = circle,
                        checked = circle.id in uiState.selectedCircleIds,
                        enabled = !uiState.submitting,
                        onToggle = { onAction(ReviewConnectionUiAction.CircleToggled(circle.id)) },
                    )
                }
                if (special.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    special.forEach { circle ->
                        CircleCheckbox(
                            circle = circle,
                            checked = circle.id in uiState.selectedCircleIds,
                            enabled = !uiState.submitting,
                            onToggle = { onAction(ReviewConnectionUiAction.CircleToggled(circle.id)) },
                        )
                    }
                }
            }

            if (uiState.appToggles.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SectionLabel(stringResource(MR.string.review_apps_header))
                uiState.appToggles.forEach { app ->
                    AppToggleRow(
                        app = app,
                        checked = app.appId in uiState.checkedAppIds,
                        enabled = !uiState.submitting,
                        onToggle = { onAction(ReviewConnectionUiAction.AppToggled(app.appId)) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.string.review_follow_feed),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = uiState.followFeed,
                    enabled = !uiState.submitting,
                    onCheckedChange = { onAction(ReviewConnectionUiAction.FollowFeedToggled(it)) },
                )
            }

            if (!uiState.addsToCircles) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(MR.string.review_chat_only_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            uiState.error?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = when (error) {
                        ReviewError.NotConnected -> stringResource(MR.string.review_failed_not_connected)
                        ReviewError.CircleNotAllowed -> stringResource(MR.string.review_failed_circle_not_allowed)
                        ReviewError.Generic -> stringResource(MR.string.review_failed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onAction(ReviewConnectionUiAction.SubmitClicked) },
                enabled = !uiState.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = if (uiState.addsToCircles) {
                            Icons.Outlined.Circle
                        } else {
                            Icons.Outlined.ChatBubbleOutline
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = if (uiState.addsToCircles) {
                            stringResource(MR.string.review_submit_circles)
                        } else {
                            stringResource(MR.string.review_submit_chat_only)
                        },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(
                    onClick = { onAction(ReviewConnectionUiAction.KeepAsNewClicked) },
                    enabled = !uiState.submitting,
                ) { Text(stringResource(MR.string.review_keep_as_new)) }
                TextButton(
                    onClick = { onAction(ReviewConnectionUiAction.DisconnectClicked) },
                    enabled = !uiState.submitting,
                ) { Text(stringResource(MR.string.review_disconnect)) }
                TextButton(
                    onClick = { onAction(ReviewConnectionUiAction.BlockClicked) },
                    enabled = !uiState.submitting,
                ) { Text(stringResource(MR.string.review_block)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun CircleCheckbox(
    circle: ReviewCircleOption,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        // Full name, never the abbreviation — this is a roomy context.
        val label = circle.emoji?.let { "$it ${circle.name}" } ?: circle.name
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AppToggleRow(
    app: ReviewAppToggle,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyMedium)
            if (app.pending) {
                Text(
                    text = stringResource(MR.string.review_app_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
