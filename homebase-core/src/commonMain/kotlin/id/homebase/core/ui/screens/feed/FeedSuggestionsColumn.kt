package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.core.ui.screens.feed.following.FollowingEvent
import id.homebase.core.ui.screens.feed.following.FollowingViewModel
import id.homebase.resources.MR
import id.homebase.resources.feed_following_follow
import id.homebase.resources.feed_following_following
import id.homebase.resources.feed_wide_channels_title
import id.homebase.resources.feed_wide_people_title
import id.homebase.resources.feed_wide_see_all
import id.homebase.resources.feed_wide_suggestions_empty
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

internal data class SuggestedPerson(val odinId: String, val isFollowed: Boolean)

internal const val SUGGESTED_PEOPLE_LIMIT = 5

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
        modifier = modifier.padding(top = 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(FeedColumnGap),
    ) {
        item(key = "people") {
            SuggestionCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(MR.string.feed_wide_people_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    )
                    TextButton(onClick = onSeeAll) { Text(stringResource(MR.string.feed_wide_see_all)) }
                }
                if (people.isEmpty()) {
                    Text(
                        text = stringResource(MR.string.feed_wide_suggestions_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                }
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
        if (channelNames.isNotEmpty()) {
            item(key = "channels") {
                SuggestionCard {
                    Text(
                        text = stringResource(MR.string.feed_wide_channels_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    channelNames.forEach { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(bottom = 6.dp)) { content() }
    }
}

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
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IdentityAvatar(odinId = odin, displayName = displayName, options = AvatarOptions(size = 40.dp, onClick = onClick))
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (person.isFollowed) {
            OutlinedButton(onClick = onUnfollow) { Text(stringResource(MR.string.feed_following_following)) }
        } else {
            Button(onClick = onFollow) { Text(stringResource(MR.string.feed_following_follow)) }
        }
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
        val followedHere = uiState.followedHere
        val ids = (uiState.peopleYouMayKnowNotFollowed.map { it.odinId } + uiState.suggested)
            .distinctBy { it.lowercase() }
            .take(SUGGESTED_PEOPLE_LIMIT)
        ids.map { SuggestedPerson(it, isFollowed = it.lowercase() in followedHere) }
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
