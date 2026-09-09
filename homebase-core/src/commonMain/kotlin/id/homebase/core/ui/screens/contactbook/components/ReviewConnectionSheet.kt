package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.ReviewCircleGroups
import id.homebase.core.ui.screens.contactbook.detail.ContactCircleUi
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.resources.MR
import id.homebase.resources.contact_review_already_added
import id.homebase.resources.contact_review_chat_only_hint
import id.homebase.resources.contact_review_emergency_desc
import id.homebase.resources.contact_review_emergency_title
import id.homebase.resources.contact_review_group_apps
import id.homebase.resources.contact_review_group_apps_caption
import id.homebase.resources.contact_review_group_apps_summary
import id.homebase.resources.contact_review_group_yours
import id.homebase.resources.contact_review_group_yours_caption
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
 * Laid out as a settings screen — section header, card, switch rows — because that is what it is:
 * a list of things to turn on for one person. The chips it replaced looked like a filter.
 *
 * The submit button names the state the tap produces rather than passing judgment — "Add to
 * circles" with a selection, "Chat only" without — so the relabel *is* the feedback that
 * deselecting the last circle changed the outcome. Dismissing leaves the contact New, which is
 * why the secondary action says so instead of "Cancel".
 */
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
    var appDefaultsExpanded by rememberSaveable { mutableStateOf(false) }

    AdaptiveSheet(onDismiss = onDismiss, expandFully = true, maxWidth = 680.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(MR.string.contact_review_title, displayName),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (introducedBy != null) {
                Text(
                    text = stringResource(MR.string.contact_review_introduced_by, introducedBy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            val toggle: (String) -> Unit = { id -> selected = groups.toggleSelection(selected, id) }

            if (groups.yours.isNotEmpty()) {
                SectionCaption(
                    title = stringResource(MR.string.contact_review_group_yours),
                    caption = stringResource(MR.string.contact_review_group_yours_caption),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    groups.yours.forEach { circle ->
                        CircleToggleRow(
                            circle = circle,
                            held = circle.id in alreadyHeldCircleIds,
                            checked = circle.id in alreadyHeldCircleIds || circle.id in selected,
                            enabled = !isSubmitting,
                            onToggle = { toggle(circle.id) },
                        )
                    }
                }
            }

            // Its own card and its own words: one fixed circle granting a capability, not a pick
            // from a set, and the only choice here that shares something other than profile detail.
            groups.special.forEach { circle ->
                val held = circle.id in alreadyHeldCircleIds
                Card(modifier = Modifier.fillMaxWidth()) {
                    SettingsRow(
                        icon = Icons.Outlined.MyLocation,
                        title = stringResource(MR.string.contact_review_emergency_title),
                        supportingText = stringResource(
                            MR.string.contact_review_emergency_desc,
                            displayName,
                        ),
                        action = SettingsRowAction.Toggle(
                            checked = held || circle.id in selected,
                            onCheckedChange = { if (!held && !isSubmitting) toggle(circle.id) },
                        ),
                    )
                }
            }

            if (groups.appDefaults.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SettingsRow(
                        icon = Icons.Outlined.Groups,
                        title = stringResource(MR.string.contact_review_group_apps),
                        supportingText = if (appDefaultsExpanded) {
                            stringResource(MR.string.contact_review_group_apps_caption)
                        } else {
                            stringResource(
                                MR.string.contact_review_group_apps_summary,
                                groups.appDefaults.joinToString { it.name },
                            )
                        },
                        action = SettingsRowAction.Expand(
                            expanded = appDefaultsExpanded,
                            onExpandedChange = { appDefaultsExpanded = it },
                        ),
                    )
                    AnimatedVisibility(visible = appDefaultsExpanded) {
                        Column {
                            groups.appDefaults.forEach { circle ->
                                CircleToggleRow(
                                    circle = circle,
                                    held = circle.id in alreadyHeldCircleIds,
                                    checked = circle.id in alreadyHeldCircleIds ||
                                        circle.id in selected,
                                    enabled = !isSubmitting,
                                    onToggle = { toggle(circle.id) },
                                )
                            }
                        }
                    }
                }
            }

            if (selected.isEmpty()) {
                Text(
                    text = stringResource(MR.string.contact_review_chat_only_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
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

/** Settings' section header, plus the line of helper text this sheet needs under it. */
@Composable
private fun SectionCaption(title: String, caption: String) {
    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        SettingsSectionHeader(title = title)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One circle as a settings row.
 *
 * A circle the contact already holds is checked and inert: the review only ever grants, so an
 * unchecked box would offer a removal this call cannot perform, and the supporting line says why
 * rather than leaving a dead switch unexplained.
 */
@Composable
private fun CircleToggleRow(
    circle: ContactCircleUi,
    held: Boolean,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val emoji = circle.emoji
    ListItem(
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled && !held,
            onValueChange = { onToggle() },
            role = Role.Switch,
        ),
        headlineContent = { Text(circle.name) },
        supportingContent = if (held) {
            { Text(stringResource(MR.string.contact_review_already_added)) }
        } else null,
        leadingContent = {
            if (emoji.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.Outlined.Groups,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                // Full colour and cleared from semantics: emoji ignore tint, and the circle name
                // beside it is the accessible label.
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Unspecified,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = !held) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
