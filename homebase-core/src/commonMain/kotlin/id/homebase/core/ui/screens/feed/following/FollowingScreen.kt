package id.homebase.core.ui.screens.feed.following

import androidx.compose.foundation.clickable
import id.homebase.api.client.follow.FollowerSuggestion
import androidx.compose.foundation.layout.size
import id.homebase.resources.feed_following_pymk_title
import id.homebase.resources.feed_following_pymk_basis
import id.homebase.resources.feed_following_pymk_refresh
import id.homebase.resources.feed_following_pymk_via_connections
import id.homebase.resources.feed_following_pymk_via_follows
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import org.jetbrains.compose.resources.StringResource
import id.homebase.resources.feed_following_tab_suggested
import id.homebase.resources.feed_following_follow_back
import id.homebase.resources.feed_following_follow_all
import id.homebase.resources.feed_following_suggested_hint
import id.homebase.resources.feed_following_empty_suggested
import id.homebase.resources.feed_following_add
import id.homebase.resources.feed_following_address_label
import id.homebase.resources.feed_following_address_placeholder
import id.homebase.resources.feed_following_invalid_address
import id.homebase.resources.feed_following_cancel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.core.avatars.AvatarOptions
import id.homebase.resources.MR
import id.homebase.resources.feed_following_back
import id.homebase.resources.feed_following_empty_followers
import id.homebase.resources.feed_following_empty_following
import id.homebase.resources.feed_following_error
import id.homebase.resources.feed_following_follow
import id.homebase.resources.feed_following_follows_you
import id.homebase.resources.feed_following_retry
import id.homebase.resources.feed_following_tab_followers
import id.homebase.resources.feed_following_tab_following
import id.homebase.resources.feed_following_title
import id.homebase.resources.feed_following_unfollow
import id.homebase.resources.feed_following_followed
import id.homebase.resources.feed_following_followed_many
import id.homebase.resources.feed_following_following
import id.homebase.resources.feed_following_undo
import id.homebase.resources.feed_following_close
import id.homebase.resources.feed_following_pymk_via_title_connections
import id.homebase.resources.feed_following_pymk_via_title_follows
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.layout.heightIn
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowingScreen(
    viewModel: FollowingViewModel = koinViewModel(),
    onBack: () -> Unit,
    onIdentityClick: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val displayNames by viewModel.displayNames.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val invalidAddress = stringResource(MR.string.feed_following_invalid_address)
    // Read inside the collector, which outlives any one composition of the name map
    val currentNames by rememberUpdatedState(displayNames)
    var viaShown by remember { mutableStateOf<FollowerSuggestion?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is FollowingEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is FollowingEvent.Followed -> {
                    val name = currentNames[OdinId(event.odinId)]?.takeIf { it.isNotBlank() } ?: event.odinId
                    val result = snackbarHostState.showSnackbar(
                        message = getString(MR.string.feed_following_followed, name),
                        actionLabel = getString(MR.string.feed_following_undo),
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.unfollow(event.odinId)
                }
                is FollowingEvent.FollowedMany ->
                    snackbarHostState.showSnackbar(getString(MR.string.feed_following_followed_many, event.count))
                FollowingEvent.InvalidAddress -> snackbarHostState.showSnackbar(invalidAddress)
                is FollowingEvent.NavigateToIdentity -> onIdentityClick(event.odinId)
            }
        }
    }

    viaShown?.let { suggestion ->
        ViaDialog(
            suggestion = suggestion,
            nameOf = { domain -> displayNames[OdinId(domain)]?.takeIf { it.isNotBlank() } ?: domain },
            onIdentityClick = {
                viaShown = null
                viewModel.onIdentityClick(it)
            },
            onDismiss = { viaShown = null },
        )
    }

    if (uiState.isFollowByAddressOpen) {
        FollowByAddressDialog(onFollow = viewModel::followByAddress, onDismiss = viewModel::closeFollowByAddress)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.feed_following_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.feed_following_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::openFollowByAddress) {
                        Icon(
                            imageVector = Icons.Filled.PersonAdd,
                            contentDescription = stringResource(MR.string.feed_following_add),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            PrimaryTabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                Tab(
                    selected = uiState.selectedTab == FollowTab.Following,
                    onClick = { viewModel.selectTab(FollowTab.Following) },
                    text = { Text(stringResource(MR.string.feed_following_tab_following)) },
                )
                Tab(
                    selected = uiState.selectedTab == FollowTab.Followers,
                    onClick = { viewModel.selectTab(FollowTab.Followers) },
                    text = { Text(stringResource(MR.string.feed_following_tab_followers)) },
                )
                Tab(
                    selected = uiState.selectedTab == FollowTab.Suggested,
                    onClick = { viewModel.selectTab(FollowTab.Suggested) },
                    text = { Text(stringResource(MR.string.feed_following_tab_suggested)) },
                )
            }

            // Lower-cased once per list change, so each row's "follows you" check is a set lookup
            val followerSet = remember(uiState.followers) { uiState.followers.mapTo(HashSet()) { it.lowercase() } }

            val identities = when (uiState.selectedTab) {
                FollowTab.Following -> uiState.following
                FollowTab.Followers -> uiState.followers
                FollowTab.Suggested -> uiState.suggested
            }

            // People you may know shows under your own suggestions, and keeps the tab from reading as empty
            val showPeopleYouMayKnow = uiState.selectedTab == FollowTab.Suggested && uiState.advancedFeed &&
                (uiState.peopleYouMayKnowLoading || uiState.peopleYouMayKnowNotFollowed.isNotEmpty())

            when {
                uiState.isLoading && identities.isEmpty() -> LoadingState()
                // A failed load is not an empty list: say so, rather than "You're not following anyone yet."
                uiState.errorMessage != null && identities.isEmpty() -> ErrorState(onRetry = viewModel::load)
                identities.isEmpty() && !showPeopleYouMayKnow -> EmptyState(tab = uiState.selectedTab)
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (uiState.selectedTab == FollowTab.Suggested && identities.isNotEmpty()) {
                        item(key = "suggested-header") {
                            SuggestedHeader(onFollowAll = { viewModel.followAll(identities) })
                        }
                    }
                    items(items = identities, key = { it }) { odinId ->
                        val isFollowed = remember(odinId, uiState.following) {
                            uiState.following.any { it.equals(odinId, ignoreCase = true) }
                        }
                        val odin = remember(odinId) { OdinId(odinId) }
                        val displayName = displayNames[odin]?.takeIf { it.isNotBlank() } ?: odinId
                        IdentityRow(
                            odinId = odinId,
                            displayName = displayName,
                            isFollowed = isFollowed,
                            followedHere = uiState.selectedTab != FollowTab.Following &&
                                odinId.lowercase() in uiState.followedHere,
                            // Only on the Following tab: everyone on the Followers tab follows you
                            followsYou = uiState.selectedTab == FollowTab.Following &&
                                odinId.lowercase() in followerSet,
                            // They follow you and you don't follow them: say it as following back
                            followLabel = if (odinId.lowercase() in followerSet) {
                                MR.string.feed_following_follow_back
                            } else {
                                MR.string.feed_following_follow
                            },
                            onRowClick = { viewModel.onIdentityClick(odinId) },
                            onFollow = { viewModel.follow(odinId) },
                            onUnfollow = { viewModel.unfollow(odinId) },
                        )
                    }
                    if (showPeopleYouMayKnow) {
                        item(key = "pymk-header") {
                            PeopleYouMayKnowHeader(
                                basis = uiState.peopleYouMayKnowBasis,
                                isLoading = uiState.peopleYouMayKnowLoading,
                                onRefresh = { viewModel.loadPeopleYouMayKnow(refresh = true) },
                            )
                        }
                        items(items = uiState.peopleYouMayKnowNotFollowed, key = { "pymk-" + it.odinId }) { suggestion ->
                            val odin = remember(suggestion.odinId) { OdinId(suggestion.odinId) }
                            val displayName = displayNames[odin]?.takeIf { it.isNotBlank() } ?: suggestion.odinId
                            PeopleYouMayKnowRow(
                                suggestion = suggestion,
                                displayName = displayName,
                                nameOf = { domain ->
                                    displayNames[OdinId(domain)]?.takeIf { it.isNotBlank() } ?: domain
                                },
                                isFollowed = uiState.following.any { it.equals(suggestion.odinId, ignoreCase = true) },
                                onRowClick = { viewModel.onIdentityClick(suggestion.odinId) },
                                onFollow = { viewModel.follow(suggestion.odinId) },
                                onUnfollow = { viewModel.unfollow(suggestion.odinId) },
                                onShowVia = { viaShown = suggestion },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeopleYouMayKnowHeader(basis: Pair<Int, Int>?, isLoading: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(MR.string.feed_following_pymk_title),
                style = MaterialTheme.typography.titleSmall,
            )
            basis?.let { (answered, asked) ->
                Text(
                    text = stringResource(MR.string.feed_following_pymk_basis, answered, asked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onRefresh) { Text(stringResource(MR.string.feed_following_pymk_refresh)) }
        }
    }
}

// "Connected to Sam and Merry" / "Followed by Sam": who led here, by name where the contact book knows them
@Composable
private fun PeopleYouMayKnowRow(
    suggestion: FollowerSuggestion,
    displayName: String,
    nameOf: (String) -> String,
    isFollowed: Boolean,
    onRowClick: () -> Unit,
    onFollow: () -> Unit,
    onUnfollow: () -> Unit,
    onShowVia: () -> Unit,
) {
    val odin = remember(suggestion.odinId) { OdinId(suggestion.odinId) }
    // "+N" hides names, so the line opens the whole list
    val hasMore = suggestion.viaConnections.size + suggestion.viaFollows.size > 2
    val via = when {
        suggestion.viaConnections.isNotEmpty() ->
            stringResource(MR.string.feed_following_pymk_via_connections, viaNames(suggestion.viaConnections, nameOf))
        else -> stringResource(MR.string.feed_following_pymk_via_follows, viaNames(suggestion.viaFollows, nameOf))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IdentityAvatar(
            odinId = odin,
            displayName = displayName,
            options = AvatarOptions(size = 44.dp, onClick = onRowClick),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = via,
                style = MaterialTheme.typography.bodySmall,
                color = if (hasMore) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (hasMore) Modifier.clickable(onClick = onShowVia) else Modifier,
            )
        }
        if (isFollowed) FollowingButton(onClick = onUnfollow)
        else Button(onClick = onFollow) { Text(stringResource(MR.string.feed_following_follow)) }
    }
}

// Followed from this screen: says so where the Follow button was; tapping it again unfollows
@Composable
private fun FollowingButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(MR.string.feed_following_following), modifier = Modifier.padding(start = 6.dp))
    }
}

// Everyone behind a suggestion, by name where the contact book knows them; a name opens that contact
@Composable
private fun ViaDialog(
    suggestion: FollowerSuggestion,
    nameOf: (String) -> String,
    onIdentityClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(nameOf(suggestion.odinId), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                for ((title, domains) in listOf(
                    MR.string.feed_following_pymk_via_title_connections to suggestion.viaConnections,
                    MR.string.feed_following_pymk_via_title_follows to suggestion.viaFollows,
                )) {
                    if (domains.isEmpty()) continue
                    item(key = "title-$title") {
                        Text(
                            stringResource(title, domains.size),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(items = domains, key = { "$title-$it" }) { domain ->
                        Text(
                            nameOf(domain),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onIdentityClick(domain) }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.feed_following_close)) }
        },
    )
}

// Two names, then "and N more"
private fun viaNames(domains: List<String>, nameOf: (String) -> String): String {
    val names = domains.take(2).map(nameOf)
    val more = domains.size - names.size
    return when {
        more > 0 -> names.joinToString(", ") + " +$more"
        names.size == 2 -> names[0] + " & " + names[1]
        else -> names.firstOrNull().orEmpty()
    }
}

@Composable
private fun SuggestedHeader(onFollowAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(MR.string.feed_following_suggested_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onFollowAll) {
            Text(stringResource(MR.string.feed_following_follow_all))
        }
    }
}

@Composable
private fun FollowByAddressDialog(onFollow: (String) -> Unit, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.string.feed_following_add)) },
        text = {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                label = { Text(stringResource(MR.string.feed_following_address_label)) },
                placeholder = { Text(stringResource(MR.string.feed_following_address_placeholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (address.isNotBlank()) onFollow(address) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onFollow(address) }, enabled = address.isNotBlank()) {
                Text(stringResource(MR.string.feed_following_follow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.feed_following_cancel)) }
        },
    )
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(MR.string.feed_following_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(MR.string.feed_following_retry))
        }
    }
}

@Composable
private fun EmptyState(tab: FollowTab) {
    val message = when (tab) {
        FollowTab.Following -> stringResource(MR.string.feed_following_empty_following)
        FollowTab.Followers -> stringResource(MR.string.feed_following_empty_followers)
        FollowTab.Suggested -> stringResource(MR.string.feed_following_empty_suggested)
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IdentityRow(
    odinId: String,
    displayName: String,
    isFollowed: Boolean,
    followedHere: Boolean,
    followsYou: Boolean,
    followLabel: StringResource,
    onRowClick: () -> Unit,
    onFollow: () -> Unit,
    onUnfollow: () -> Unit,
) {
    // OdinId(...) recomputes a SHA-256 hash on construction, so keep it behind
    // remember keyed on the domain string rather than rebuilding every recompose.
    val odin = remember(odinId) { OdinId(odinId) }

    // The whole row opens the contact (a dialog on desktop and web), not just the avatar
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IdentityAvatar(
            odinId = odin,
            displayName = displayName,
            options = AvatarOptions(size = 44.dp, onClick = onRowClick),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (displayName != odinId) {
                Text(
                    text = odinId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (followsYou) {
                Text(
                    text = stringResource(MR.string.feed_following_follows_you),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (isFollowed && followedHere) {
            FollowingButton(onClick = onUnfollow)
        } else if (isFollowed) {
            OutlinedButton(onClick = onUnfollow) {
                Text(stringResource(MR.string.feed_following_unfollow))
            }
        } else {
            Button(onClick = onFollow) {
                Text(stringResource(followLabel))
            }
        }
    }
}
