@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package id.homebase.core.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.resources.MR
import id.homebase.resources.profile_edit_audience_circles_empty
import id.homebase.resources.profile_edit_audience_circles_hint
import id.homebase.resources.profile_edit_audience_circles_none
import id.homebase.resources.profile_edit_audience_only_me
import id.homebase.resources.profile_edit_audience_other_circles
import id.homebase.resources.profile_edit_audience_only_me_hint
import id.homebase.resources.profile_edit_audience_public_hint
import id.homebase.resources.profile_edit_audience_title
import id.homebase.resources.profile_edit_visibility_circles
import id.homebase.resources.profile_edit_visibility_public
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The one audience control every detail uses: Public | Circles | Only me, with a chip per Contacts circle when Circles. */
@Composable
internal fun AudiencePicker(
    audience: ProfileAudience,
    circles: List<CardCircle>,
    onChange: (ProfileAudience) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(MR.string.profile_edit_audience_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = audience == ProfileAudience.Public,
                onClick = { onChange(ProfileAudience.Public) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                label = { Text(stringResource(MR.string.profile_edit_visibility_public)) },
            )
            SegmentedButton(
                selected = audience is ProfileAudience.Circles,
                onClick = {
                    if (audience !is ProfileAudience.Circles) onChange(ProfileAudience.Circles(circles.map { it.id }.toSet()))
                },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                label = { Text(stringResource(MR.string.profile_edit_visibility_circles)) },
            )
            SegmentedButton(
                selected = audience == ProfileAudience.OnlyMe,
                onClick = { onChange(ProfileAudience.OnlyMe) },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                label = { Text(stringResource(MR.string.profile_edit_audience_only_me)) },
            )
        }
        if (audience is ProfileAudience.Circles) {
            if (circles.isEmpty()) {
                Text(
                    text = stringResource(MR.string.profile_edit_audience_circles_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    circles.forEach { circle ->
                        val selected = circle.id in audience.ids
                        FilterChip(
                            selected = selected,
                            onClick = {
                                onChange(audience.copy(ids = if (selected) audience.ids - circle.id else audience.ids + circle.id))
                            },
                            label = { Text(circle.name) },
                        )
                    }
                }
            }
        }
        if (audience is ProfileAudience.Circles && audience.otherIds.isNotEmpty()) {
            Text(
                text = pluralStringResource(MR.plurals.profile_edit_audience_other_circles, audience.otherIds.size, audience.otherIds.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val isEmptySelection = !audience.isSavable
        Text(
            text = stringResource(
                when {
                    isEmptySelection -> MR.string.profile_edit_audience_circles_empty
                    audience == ProfileAudience.Public -> MR.string.profile_edit_audience_public_hint
                    audience == ProfileAudience.OnlyMe -> MR.string.profile_edit_audience_only_me_hint
                    else -> MR.string.profile_edit_audience_circles_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (isEmptySelection) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The one-line form of an audience a collapsed row shows. */
@Composable
internal fun audienceSummary(audience: ProfileAudience, circles: List<CardCircle>): String = when (audience) {
    ProfileAudience.Public -> stringResource(MR.string.profile_edit_visibility_public)
    ProfileAudience.OnlyMe -> stringResource(MR.string.profile_edit_audience_only_me)
    is ProfileAudience.Circles -> circles.filter { it.id in audience.ids }.joinToString(", ") { it.name }
        .ifEmpty { stringResource(MR.string.profile_edit_visibility_circles) }
}
