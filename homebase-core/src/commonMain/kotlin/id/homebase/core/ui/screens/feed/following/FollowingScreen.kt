package id.homebase.core.ui.screens.feed.following

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.util.initials
import id.homebase.resources.MR
import id.homebase.resources.feed_following_back
import id.homebase.resources.feed_following_empty_followers
import id.homebase.resources.feed_following_empty_following
import id.homebase.resources.feed_following_follow
import id.homebase.resources.feed_following_tab_followers
import id.homebase.resources.feed_following_tab_following
import id.homebase.resources.feed_following_title
import id.homebase.resources.feed_following_unfollow
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

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is FollowingEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is FollowingEvent.NavigateToIdentity -> onIdentityClick(event.odinId)
            }
        }
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
            }

            val identities = when (uiState.selectedTab) {
                FollowTab.Following -> uiState.following
                FollowTab.Followers -> uiState.followers
            }

            when {
                uiState.isLoading && identities.isEmpty() -> LoadingState()
                identities.isEmpty() -> EmptyState(tab = uiState.selectedTab)
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = identities, key = { it }) { odinId ->
                        val isFollowed = remember(odinId, uiState.following) {
                            uiState.following.any { it.equals(odinId, ignoreCase = true) }
                        }
                        // Resolve to a saved contact/connection name, else the raw domain.
                        val odin = remember(odinId) { OdinId(odinId) }
                        val displayName = displayNames[odin]?.takeIf { it.isNotBlank() } ?: odinId
                        IdentityRow(
                            odinId = odinId,
                            displayName = displayName,
                            isFollowed = isFollowed,
                            onRowClick = { viewModel.onIdentityClick(odinId) },
                            onFollow = { viewModel.follow(odinId) },
                            onUnfollow = { viewModel.unfollow(odinId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(tab: FollowTab) {
    val message = when (tab) {
        FollowTab.Following -> stringResource(MR.string.feed_following_empty_following)
        FollowTab.Followers -> stringResource(MR.string.feed_following_empty_followers)
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
    onRowClick: () -> Unit,
    onFollow: () -> Unit,
    onUnfollow: () -> Unit,
) {
    // OdinId(...) recomputes a SHA-256 hash on construction, so keep it behind
    // remember keyed on the domain string rather than rebuilding every recompose.
    val odin = remember(odinId) { OdinId(odinId) }
    val rowInitials = remember(displayName) { displayName.initials() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PublicAvatar(
            odinId = odin,
            initials = rowInitials,
            options = AvatarOptions(size = 44.dp, onClick = onRowClick),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Show the domain as a secondary line only when it differs from the resolved name,
            // so a known contact reads "Alice / alice.dotyou.cloud" and an unknown shows just
            // the domain once.
            if (displayName != odinId) {
                Text(
                    text = odinId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isFollowed) {
            OutlinedButton(onClick = onUnfollow) {
                Text(stringResource(MR.string.feed_following_unfollow))
            }
        } else {
            Button(onClick = onFollow) {
                Text(stringResource(MR.string.feed_following_follow))
            }
        }
    }
}
