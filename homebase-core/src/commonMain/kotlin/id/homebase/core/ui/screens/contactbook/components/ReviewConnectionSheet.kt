package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.ReviewCircleGroups
import id.homebase.core.ui.screens.contactbook.detail.ContactCircleUi
import id.homebase.core.util.formatMomentDate
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.core.ui.screens.contactbook.detail.ReviewSheetState
import id.homebase.resources.MR
import id.homebase.resources.contact_review_accept_failed
import id.homebase.resources.contact_review_already_added
import id.homebase.resources.contactbook_circle_disabled
import id.homebase.resources.contactbook_detail_reject
import id.homebase.resources.contactbook_circle_members_count
import id.homebase.resources.contact_review_chat_only_hint
import id.homebase.resources.contact_review_connected_since
import id.homebase.resources.contact_review_emergency_desc
import id.homebase.resources.contact_review_emergency_row
import id.homebase.resources.contact_review_emergency_title
import id.homebase.resources.contact_review_group_apps
import id.homebase.resources.contact_review_group_apps_caption
import id.homebase.resources.contact_review_group_yours
import id.homebase.resources.contact_review_group_yours_caption
import id.homebase.resources.contact_review_introduced_by
import id.homebase.resources.contact_review_keep_new
import id.homebase.resources.contact_review_requested_on
import id.homebase.resources.contact_review_submit_chat_only
import id.homebase.resources.contact_review_submit_circles
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/**
 * The connection review: one sheet that records the owner's judgment and enrols whatever circles
 * they picked, in a single call.
 *
 * Dismissing leaves the contact New, which is why the secondary action says so instead of
 * "Cancel".
 */
@Composable
fun ReviewConnectionSheet(
    displayName: String,
    odinId: String?,
    avatar: (@Composable () -> Unit)?,
    introducedBy: String?,
    /** When the connection was made, epoch-millis. Null where it isn't known. */
    connectedAtMs: Long?,
    groups: ReviewCircleGroups,
    alreadyHeldCircleIds: Set<String>,
    isSubmitting: Boolean,
    errorText: String?,
    onSubmit: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismiss = onDismiss, expandFully = true, maxWidth = 680.dp) {
        ReviewConnectionContent(
            displayName = displayName,
            odinId = odinId,
            avatar = avatar,
            introducedBy = introducedBy,
            connectedAtMs = connectedAtMs,
            groups = groups,
            alreadyHeldCircleIds = alreadyHeldCircleIds,
            isSubmitting = isSubmitting,
            errorText = errorText,
            onSubmit = onSubmit,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp, bottom = 24.dp),
            secondaryAction = {
                TextButton(
                    onClick = { dismiss() },
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(MR.string.contact_review_keep_new))
                }
            },
        )
    }
}

/**
 * The review body, shared by the sheet (a New connection) and the pending-request screen, where
 * submitting accepts the request.
 *
 * Laid out as a settings screen — section header, card, switch rows — because that is what it is:
 * a list of things to turn on for one person. The chips it replaced looked like a filter.
 *
 * The submit button names the state the tap produces rather than passing judgment — "Add to
 * circles" with a selection, "Chat only" without — so the relabel *is* the feedback that
 * deselecting the last circle changed the outcome.
 */
@Composable
fun ReviewConnectionContent(
    displayName: String,
    odinId: String?,
    avatar: (@Composable () -> Unit)?,
    introducedBy: String?,
    /** When the connection was made, epoch-millis. Null where it isn't known. */
    connectedAtMs: Long?,
    groups: ReviewCircleGroups,
    alreadyHeldCircleIds: Set<String>,
    isSubmitting: Boolean,
    errorText: String?,
    onSubmit: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    incomingRequest: IncomingRequestSummary? = null,
    /** False where the host screen already shows the person's avatar, name and Homebase ID. */
    showIdentity: Boolean = true,
    details: (@Composable () -> Unit)? = null,
    secondaryAction: (@Composable () -> Unit)? = null,
) {
    // App defaults arrive checked: the owning app nominated them, and the review button applies
    // "the checked per-app defaults". They stay visible so any can be turned off deliberately.
    var selected by rememberSaveable(displayName) { mutableStateOf(groups.initialSelection()) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Both facts, not a choice between them: when the connection happened and who vouched
        // for it are each worth knowing, and neither implies the other.
        val subtitle = listOfNotNull(
            incomingRequest?.receivedAtMs?.takeIf { it > 0 }?.let {
                stringResource(
                    MR.string.contact_review_requested_on,
                    formatMomentDate(Instant.fromEpochMilliseconds(it)),
                )
            },
            connectedAtMs?.takeIf { it > 0 }?.let {
                stringResource(
                    MR.string.contact_review_connected_since,
                    formatMomentDate(Instant.fromEpochMilliseconds(it)),
                )
            },
            introducedBy?.let {
                stringResource(MR.string.contact_review_introduced_by, it)
            },
        ).joinToString(" · ")

        if (showIdentity) {
            // The person is the heading. A "Review connection with <odinId>" line above their own
            // name said the same thing twice and led with the least readable half.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    // The column's 12dp rhythm separates peer sections; the header is not one of
                    // them, so it gets its own gap before the first.
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                avatar?.invoke()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!odinId.isNullOrBlank() && !odinId.equals(displayName, ignoreCase = true)) {
                        Text(
                            text = odinId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (subtitle.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        incomingRequest?.message?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        details?.invoke()

        val toggle: (String) -> Unit = { id -> selected = groups.toggleSelection(selected, id) }

        if (groups.yours.isNotEmpty()) {
            Section(
                title = stringResource(MR.string.contact_review_group_yours),
                caption = stringResource(MR.string.contact_review_group_yours_caption),
            )
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

        // Its own section: one fixed circle granting a capability, not a pick from a set,
        // and the only choice here that shares something other than profile detail.
        groups.special.forEach { circle ->
            val held = circle.id in alreadyHeldCircleIds
            Section(title = stringResource(MR.string.contact_review_emergency_title))
            SettingsRow(
                icon = Icons.Outlined.MyLocation,
                title = stringResource(MR.string.contact_review_emergency_row),
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

        if (groups.appDefaults.isNotEmpty()) {
            Section(
                title = stringResource(MR.string.contact_review_group_apps),
                caption = stringResource(MR.string.contact_review_group_apps_caption),
            )
            groups.appDefaults.forEach { circle ->
                CircleToggleRow(
                    circle = circle,
                    held = circle.id in alreadyHeldCircleIds,
                    checked = circle.id in alreadyHeldCircleIds || circle.id in selected,
                    enabled = !isSubmitting,
                    onToggle = { toggle(circle.id) },
                )
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
        secondaryAction?.invoke()
    }
}

data class IncomingRequestSummary(
    val receivedAtMs: Long,
    val message: String?,
)

/**
 * [ReviewConnectionContent] wired for a pending incoming request, where submitting accepts it and
 * the secondary action rejects it. Shared by the contact-detail screen and the add-contact flow so
 * the two cannot drift on the failure message or the reject button.
 *
 * [avatar] is null on a host that already shows the person's identity above this block; the
 * identity header inside the content follows it.
 */
@Composable
fun PendingRequestReview(
    review: ReviewSheetState,
    displayName: String,
    odinId: String?,
    groups: ReviewCircleGroups,
    onSubmit: (Set<String>) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    avatar: (@Composable () -> Unit)? = null,
    details: (@Composable () -> Unit)? = null,
    rejectEnabled: Boolean = true,
) {
    ReviewConnectionContent(
        displayName = displayName,
        odinId = odinId,
        avatar = avatar,
        introducedBy = review.introducedBy,
        connectedAtMs = null,
        groups = groups,
        alreadyHeldCircleIds = review.alreadyHeldCircleIds,
        isSubmitting = review.isSubmitting,
        errorText = if (review.failed) {
            stringResource(MR.string.contact_review_accept_failed)
        } else null,
        onSubmit = onSubmit,
        modifier = modifier,
        incomingRequest = review.incomingRequest,
        showIdentity = avatar != null,
        details = details,
        secondaryAction = {
            OutlinedButton(
                onClick = onReject,
                enabled = !review.isSubmitting && rejectEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(MR.string.contactbook_detail_reject))
            }
        },
    )
}

/** Settings' section header, plus the line of helper text a section may need under it. */
@Composable
private fun Section(title: String, caption: String? = null) {
    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        SettingsSectionHeader(title = title)
        if (caption != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    val locked = held || circle.disabled
    val supporting = when {
        held -> stringResource(MR.string.contact_review_already_added)
        circle.disabled -> stringResource(MR.string.contactbook_circle_disabled)
        !circle.description.isNullOrBlank() -> circle.description
        circle.memberCount != null && circle.memberCount > 0 ->
            stringResource(MR.string.contactbook_circle_members_count, circle.memberCount)
        else -> null
    }
    ListItem(
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled && !locked,
            onValueChange = { onToggle() },
            role = Role.Switch,
        ),
        headlineContent = { Text(circle.name) },
        // Already-added outranks the blurb: it explains the inert switch, which is the more
        // pressing question. Otherwise the circle's own description, falling back to its size --
        // "4 members" is a poor description but a fair hint at what a circle is for.
        supportingContent = supporting?.let { { Text(it) } },
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
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = !locked) },
    )
}
