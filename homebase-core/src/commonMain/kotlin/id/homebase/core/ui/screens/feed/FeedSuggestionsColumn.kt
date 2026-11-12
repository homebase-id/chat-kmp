package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.core.ui.screens.feed.following.FollowingEvent
import id.homebase.core.ui.screens.feed.following.FollowingUiState
import id.homebase.core.ui.screens.feed.following.FollowingViewModel
import id.homebase.resources.MR
import id.homebase.resources.feed_following_follow
import id.homebase.resources.feed_following_following
import id.homebase.resources.feed_wide_channels_empty
import id.homebase.resources.feed_wide_channels_title
import id.homebase.resources.feed_wide_people_title
import id.homebase.resources.feed_wide_see_all
import id.homebase.resources.feed_wide_suggestions_empty
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

internal data class SuggestedPerson(val odinId: String, val isFollowed: Boolean)

internal const val SUGGESTED_PEOPLE_LIMIT = 5

// followedHere only keeps a row in place; the toggle reads the follow list so an unfollow flips it back.
internal fun FollowingUiState.suggestedPeople(): List<SuggestedPerson> {
    val followed = following.mapTo(HashSet()) { it.lowercase() }
    return (peopleYouMayKnowNotFollowed.map { it.odinId } + suggested)
        .distinctBy { it.lowercase() }
        .take(SUGGESTED_PEOPLE_LIMIT)
        .map { SuggestedPerson(it, isFollowed = it.lowercase() in followed) }
}

// Right-hand column of the wide feed: people to follow and the channels you post to, as on the web feed.
@Composable
internal fun FeedSuggestionsColumn(
    people: List<SuggestedPerson>,
    channelNames: List<String>,
    displayNameFor: (String) -> String,
    onFollow: (String) -> Unit,
    onUnfollow: (String) -> Unit,
    onPersonClick: (String) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = feedListPadding(wide = true),
        verticalArrangement = Arrangement.spacedBy(FeedColumnGap),
    ) {
        item(key = "people") {
            SuggestionCard {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle(stringResource(MR.string.feed_wide_people_title), Modifier.weight(1f))
                    if (people.isNotEmpty()) {
                        TextButton(onClick = onSeeAll) { Text(stringResource(MR.string.feed_wide_see_all)) }
                    }
                }
                if (people.isEmpty()) EmptyLine(stringResource(MR.string.feed_wide_suggestions_empty))
                people.forEach { person ->
                    SuggestedPersonRow(
                        person = person,
                        displayName = displayNameFor(person.odinId),
                        onFollow = { onFollow(person.odinId) },
                        onUnfollow = { onUnfollow(person.odinId) },
                        onClick = { onPersonClick(person.odinId) },
                    )
                }
            }
        }
        item(key = "channels") {
            SuggestionCard {
                SectionTitle(
                    stringResource(MR.string.feed_wide_channels_title),
                    Modifier.heightIn(min = 56.dp).padding(horizontal = 20.dp).wrapContentHeight(Alignment.CenterVertically),
                )
                if (channelNames.isEmpty()) EmptyLine(stringResource(MR.string.feed_wide_channels_empty))
                channelNames.forEach { name -> ChannelRow(name) }
            }
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

@Composable
private fun SuggestionCard(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) { content() }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SuggestedPersonRow(
    person: SuggestedPerson,
    displayName: String,
    onFollow: () -> Unit,
    onUnfollow: () -> Unit,
    onClick: () -> Unit,
) {
    // OdinId construction hashes, so it is kept out of the recomposition path.
    val odin = remember(person.odinId) { OdinId(person.odinId) }
    // Without a profile name the first domain label stands in, so every row keeps the same two-line shape.
    val title = displayName.takeUnless { it.equals(person.odinId, ignoreCase = true) } ?: person.odinId.substringBefore('.')
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IdentityAvatar(odinId = odin, displayName = displayName, options = AvatarOptions(size = 40.dp, onClick = onClick))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = person.odinId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val label = stringResource(if (person.isFollowed) MR.string.feed_following_following else MR.string.feed_following_follow)
        FilledTonalIconToggleButton(
            checked = person.isFollowed,
            onCheckedChange = { follow -> if (follow) onFollow() else onUnfollow() },
            shapes = IconButtonDefaults.toggleableShapes(),
            colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                checkedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                checkedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                if (person.isFollowed) Icons.Filled.Check else Icons.Outlined.PersonAdd,
                contentDescription = label,
            )
        }
    }
}

@Composable
private fun ChannelRow(name: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Tag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun FeedSuggestionsPane(
    channelNames: List<String>,
    onPersonClick: (OdinId) -> Unit,
    onSeeAll: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: FollowingViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val displayNames by viewModel.displayNames.collectAsStateWithLifecycle()
    val namesByDomain = remember(displayNames) { displayNames.mapKeys { it.key.domainName.lowercase() } }
    val people = remember(uiState.peopleYouMayKnow, uiState.following, uiState.connections, uiState.followedHere) {
        uiState.suggestedPeople()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is FollowingEvent.ShowSnackbar) snackbarHostState.showSnackbar(event.message)
        }
    }

    FeedSuggestionsColumn(
        people = people,
        channelNames = channelNames,
        displayNameFor = { id -> namesByDomain[id.lowercase()]?.takeIf { it.isNotBlank() } ?: id },
        onFollow = viewModel::follow,
        onUnfollow = viewModel::unfollow,
        onPersonClick = { onPersonClick(OdinId(it)) },
        onSeeAll = onSeeAll,
        modifier = modifier,
    )
}
