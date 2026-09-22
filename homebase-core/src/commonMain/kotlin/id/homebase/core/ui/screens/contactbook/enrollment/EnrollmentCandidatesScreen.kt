package id.homebase.core.ui.screens.contactbook.enrollment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.EnrollmentOutcomeKind
import id.homebase.core.util.formatMomentDate
import id.homebase.core.widget.SettingsTopBar
import id.homebase.resources.MR
import id.homebase.resources.enroll_circle_count
import id.homebase.resources.enroll_clear
import id.homebase.resources.enroll_confirm
import id.homebase.resources.enroll_empty
import id.homebase.resources.enroll_failed
import id.homebase.resources.enroll_grants
import id.homebase.resources.enroll_outcome_deposited
import id.homebase.resources.enroll_outcome_enrolled
import id.homebase.resources.enroll_outcome_skipped
import id.homebase.resources.enroll_read_warning
import id.homebase.resources.enroll_reviewed_on
import id.homebase.resources.enroll_select_all
import id.homebase.resources.enroll_subtitle_multi
import id.homebase.resources.enroll_title
import id.homebase.resources.enroll_undo_hint
import id.homebase.resources.enroll_why_connect
import id.homebase.resources.enroll_why_review
import id.homebase.resources.ok
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/**
 * The contacts who qualify for one of this app's circles but are not in it yet.
 *
 * Deliberately not a count with one button: this grants drive access, and for a read circle the
 * storage key is escrowed to each member — removing them later stops further reads but recalls
 * nothing already read. So every candidate is named, the review date that qualifies them is shown,
 * what the circle grants is stated before the confirm, and nothing is selected to begin with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollmentCandidatesScreen(
    viewModel: EnrollmentCandidatesViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                EnrollmentCandidatesUiEvent.Back -> onNavigateBack()
            }
        }
    }

    Scaffold(
        topBar = {
            SettingsTopBar(
                title = stringResource(MR.string.enroll_title),
                onBack = onNavigateBack,
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.failed && uiState.circles.isEmpty() -> CenteredMessage(
                text = stringResource(MR.string.enroll_failed),
                modifier = Modifier.padding(innerPadding),
            )

            // An empty response means nothing to offer — circles with no backlog are omitted
            // server-side, so there is no counting to do here.
            uiState.circles.isEmpty() -> CenteredMessage(
                text = stringResource(MR.string.enroll_empty),
                modifier = Modifier.padding(innerPadding),
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(modifier = Modifier.height(4.dp))
                // Only when it is ambiguous: with one circle the card header already says which.
                if (uiState.circles.size > 1) {
                    Text(
                        text = stringResource(
                            MR.string.enroll_subtitle_multi,
                            uiState.circles.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                uiState.circles.forEach { circle ->
                    CircleSection(
                        circle = circle,
                        selected = uiState.selectedIn(circle.circleId),
                        submitting = uiState.submittingCircleId == circle.circleId,
                        onAction = viewModel::onUiAction,
                    )
                }
                Text(
                    text = stringResource(MR.string.enroll_undo_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (uiState.outcomes.isNotEmpty()) {
        OutcomesDialog(
            outcomes = uiState.outcomes,
            circleName = uiState.outcomesCircleName,
            onDismiss = { viewModel.onUiAction(EnrollmentCandidatesUiAction.DismissOutcomes) },
        )
    }
}

/**
 * One circle and its backlog, boxed.
 *
 * Each circle owns its own card because the page can hold several: a run of section headings and
 * buttons down one scroll makes it unclear which circle a given name or Add button belongs to,
 * and the cost of getting that wrong is granting drive access to the wrong list of people.
 */
@Composable
private fun CircleSection(
    circle: CandidateCircleUi,
    selected: Set<String>,
    submitting: Boolean,
    onAction: (EnrollmentCandidatesUiAction) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = circle.circleName,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(MR.string.enroll_circle_count, circle.candidates.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            // Says why these people and not others — grantOn is the app's own declaration of when
            // it wants members, so it is the honest explanation rather than "some contacts".
            text = stringResource(
                if (circle.grantOn == CircleGrantOn.Review) MR.string.enroll_why_review
                else MR.string.enroll_why_connect
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (circle.driveGrants.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(MR.string.enroll_grants) + " " +
                    circle.driveGrants.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (circle.grantsRead) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(MR.string.enroll_read_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onAction(EnrollmentCandidatesUiAction.SelectAll(circle.circleId)) },
                enabled = !submitting,
            ) { Text(stringResource(MR.string.enroll_select_all)) }
            if (selected.isNotEmpty()) {
                TextButton(
                    onClick = {
                        onAction(EnrollmentCandidatesUiAction.ClearSelection(circle.circleId))
                    },
                    enabled = !submitting,
                ) { Text(stringResource(MR.string.enroll_clear)) }
            }
        }
    }

    circle.candidates.forEach { candidate ->
        val checked = candidate.odinId in selected
        ListItem(
            modifier = Modifier.toggleable(
                value = checked,
                enabled = !submitting,
                role = Role.Checkbox,
                onValueChange = {
                    onAction(
                        EnrollmentCandidatesUiAction.ToggleCandidate(
                            circle.circleId,
                            candidate.odinId,
                        )
                    )
                },
            ),
            headlineContent = { Text(candidate.displayName) },
            // No line at all when there is no date. Null means the circle grants on Connect, so
            // reviewing is not what qualifies them -- saying "not reviewed" would be a claim
            // about the contact rather than about the circle, and would be wrong for anyone the
            // owner has in fact reviewed. The card's own "why" line already covers it.
            supportingContent = candidate.reviewedAt?.let { reviewed ->
                {
                    Text(
                        stringResource(
                            MR.string.enroll_reviewed_on,
                            formatMomentDate(Instant.fromEpochMilliseconds(reviewed)),
                        )
                    )
                }
            },
            leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        )
    }

    Button(
        onClick = { onAction(EnrollmentCandidatesUiAction.Enroll(circle.circleId)) },
        enabled = selected.isNotEmpty() && !submitting,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
    ) {
        if (submitting) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
        } else {
            // Names the circle as well as the count: with several cards on screen, "Add 4" alone
            // would not say add them to what.
            Text(stringResource(MR.string.enroll_confirm, selected.size, circle.circleName))
        }
    }
    }
}

/**
 * Per contact, not totals. A deposit reads as pending rather than done, and a skip is named —
 * "which three were skipped" is the question an owner actually asks.
 */
@Composable
private fun OutcomesDialog(
    outcomes: List<OutcomeUi>,
    circleName: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(circleName.orEmpty().ifBlank { " " }) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                outcomes.forEach { outcome ->
                    Column {
                        Text(outcome.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(
                                when (outcome.kind) {
                                    EnrollmentOutcomeKind.Enrolled -> MR.string.enroll_outcome_enrolled
                                    EnrollmentOutcomeKind.Deposited -> MR.string.enroll_outcome_deposited
                                    EnrollmentOutcomeKind.Skipped -> MR.string.enroll_outcome_skipped
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.ok)) }
        },
    )
}

@Composable
private fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
