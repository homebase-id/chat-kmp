package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.ui.Alignment
import id.homebase.core.ui.screens.contactbook.ReviewCircleGroups
import id.homebase.resources.contact_review_group_apps
import id.homebase.resources.contact_review_group_apps_caption
import id.homebase.resources.contact_review_group_apps_collapse
import id.homebase.resources.contact_review_group_apps_expand
import id.homebase.resources.contact_review_group_apps_summary
import id.homebase.resources.contact_review_group_special
import id.homebase.resources.contact_review_group_special_caption
import id.homebase.resources.contact_review_group_yours
import id.homebase.resources.contact_review_group_yours_caption
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.detail.ContactCircleUi
import id.homebase.core.widget.AdaptiveSheet
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewConnectionSheet(
    displayName: String,
    introducedBy: String?,
    groups: ReviewCircleGroups,
    alreadyHeldCircleIds: Set<String>,
    isSubmitting: Boolean,
    errorText: String?,
    onSubmit: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // App defaults arrive checked: the owning app nominated them, and the review button applies
    // "the checked per-app defaults". They stay visible so any can be turned off deliberately.
    var selected by rememberSaveable(displayName) { mutableStateOf(groups.initialSelection()) }

    AdaptiveSheet(onDismiss = onDismiss, expandFully = true, maxWidth = 680.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 32.dp),
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
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(MR.string.contact_review_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val toggle: (String) -> Unit = { id -> selected = groups.toggleSelection(selected, id) }

            if (groups.yours.isNotEmpty()) {
                CircleGroup(
                    title = stringResource(MR.string.contact_review_group_yours),
                    caption = stringResource(MR.string.contact_review_group_yours_caption),
                    circles = groups.yours,
                    selected = selected,
                    alreadyHeldCircleIds = alreadyHeldCircleIds,
                    enabled = !isSubmitting,
                    onToggle = toggle,
                )
            }

            if (groups.special.isNotEmpty()) {
                CircleGroup(
                    title = stringResource(MR.string.contact_review_group_special),
                    caption = stringResource(MR.string.contact_review_group_special_caption),
                    circles = groups.special,
                    selected = selected,
                    alreadyHeldCircleIds = alreadyHeldCircleIds,
                    enabled = !isSubmitting,
                    onToggle = toggle,
                )
            }

            if (groups.appDefaults.isNotEmpty()) {
                var expanded by rememberSaveable { mutableStateOf(false) }
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isSubmitting) { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(MR.string.contact_review_group_apps),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (expanded) {
                                stringResource(MR.string.contact_review_group_apps_caption)
                            } else {
                                stringResource(
                                    MR.string.contact_review_group_apps_summary,
                                    groups.appDefaults.joinToString { it.name },
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) MR.string.contact_review_group_apps_collapse
                            else MR.string.contact_review_group_apps_expand
                        ),
                    )
                }
                if (expanded) {
                    Spacer(modifier = Modifier.height(10.dp))
                    CircleChips(
                        circles = groups.appDefaults,
                        selected = selected,
                        alreadyHeldCircleIds = alreadyHeldCircleIds,
                        enabled = !isSubmitting,
                        onToggle = toggle,
                    )
                }
            }

            if (selected.isEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(MR.string.contact_review_chat_only_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (errorText != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
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


/** One labelled group of circle chips. */
@Composable
private fun CircleGroup(
    title: String,
    circles: List<ContactCircleUi>,
    selected: Set<String>,
    alreadyHeldCircleIds: Set<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    caption: String? = null,
) {
    Spacer(modifier = Modifier.height(24.dp))
    Text(text = title, style = MaterialTheme.typography.labelLarge)
    if (caption != null) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(modifier = Modifier.height(10.dp))
    CircleChips(circles, selected, alreadyHeldCircleIds, enabled, onToggle)
}

/**
 * A circle the contact already holds renders selected and disabled: the review only ever grants,
 * so an unchecked box would offer a removal this call cannot perform.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CircleChips(
    circles: List<ContactCircleUi>,
    selected: Set<String>,
    alreadyHeldCircleIds: Set<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        circles.forEach { circle ->
            val held = circle.id in alreadyHeldCircleIds
            val isSelected = held || circle.id in selected
            FilterChip(
                selected = isSelected,
                enabled = !held && enabled,
                onClick = { onToggle(circle.id) },
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
}
