package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.detail.ContactCircleUi
import id.homebase.resources.MR
import id.homebase.resources.contact_review_body
import id.homebase.resources.contact_review_chat_only_hint
import id.homebase.resources.contact_review_introduced_by
import id.homebase.resources.contact_review_keep_new
import id.homebase.resources.contact_review_submit_chat_only
import id.homebase.resources.contact_review_submit_circles
import id.homebase.resources.contact_review_title
import org.jetbrains.compose.resources.stringResource

/**
 * The connection review: one sheet that records the owner's judgment and enrols whatever circles
 * they picked, in a single call.
 *
 * The submit button names the state the tap produces rather than passing judgment — it reads
 * "Add to circles" with a selection and "Chat only" without, so the relabel *is* the feedback that
 * deselecting the last circle changed the outcome. Dismissing leaves the contact New, which is why
 * the secondary action says so instead of "Cancel".
 *
 * Reviewing grants only; it never revokes, so circles the contact already holds are shown selected
 * and locked rather than offered as something to take away.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReviewConnectionSheet(
    displayName: String,
    introducedBy: String?,
    circles: List<ContactCircleUi>,
    alreadyHeldCircleIds: Set<String>,
    isSubmitting: Boolean,
    errorText: String?,
    onSubmit: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by rememberSaveable(displayName) { mutableStateOf(emptySet<String>()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(MR.string.contact_review_title, displayName),
                style = MaterialTheme.typography.titleLarge,
            )
            if (introducedBy != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(MR.string.contact_review_introduced_by, introducedBy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(MR.string.contact_review_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                circles.forEach { circle ->
                    val held = circle.id in alreadyHeldCircleIds
                    val isSelected = held || circle.id in selected
                    FilterChip(
                        selected = isSelected,
                        enabled = !held && !isSubmitting,
                        onClick = {
                            selected = if (circle.id in selected) selected - circle.id
                            else selected + circle.id
                        },
                        label = { CircleLabel(emoji = circle.emoji, name = circle.name) },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else null,
                    )
                }
            }

            if (selected.isEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(MR.string.contact_review_chat_only_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (errorText != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { onSubmit(selected) },
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                } else {
                    Text(
                        stringResource(
                            if (selected.isEmpty()) MR.string.contact_review_submit_chat_only
                            else MR.string.contact_review_submit_circles
                        )
                    )
                }
            }
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(MR.string.contact_review_keep_new))
            }
        }
    }
}
