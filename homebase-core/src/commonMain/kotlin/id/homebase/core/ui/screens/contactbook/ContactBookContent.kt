package id.homebase.core.ui.screens.contactbook

import id.homebase.chat.services.requests.RequestDirection
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import org.koin.compose.koinInject
import id.homebase.core.settings.DeveloperPreferences
import id.homebase.core.ui.screens.feed.following.FollowGraph
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import id.homebase.resources.contactbook_follow_filter_empty
import id.homebase.resources.contactbook_filter_following
import id.homebase.resources.contactbook_filter_followers
import id.homebase.resources.contactbook_follow_badge_mutual
import id.homebase.resources.contactbook_follow_badge_follows_you
import id.homebase.resources.contactbook_follow_badge_following
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.components.ContactBookEmptyState
import id.homebase.core.ui.screens.contactbook.components.ContactBookRow
import id.homebase.resources.MR
import id.homebase.resources.contactbook_filter_all
import id.homebase.resources.contactbook_no_results
import id.homebase.resources.contactbook_requests_header
import id.homebase.resources.contactbook_new_empty
import id.homebase.resources.contactbook_filter_circles
import id.homebase.resources.contactbook_circles_filter_empty
import id.homebase.resources.contactbook_filter_blocked
import id.homebase.resources.contactbook_blocked_filter_empty
import id.homebase.core.ui.screens.contactbook.components.ContactStateIcon
import id.homebase.core.ui.screens.contactbook.components.RequestDirectionIcon
import id.homebase.resources.contact_review_action
import org.jetbrains.compose.resources.stringResource

private enum class ContactBookContentPhase { LOADING, EMPTY, LIST }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactBookContent(
    uiState: ContactBookUiState,
    onAction: (ContactBookUiAction) -> Unit,
    modifier: Modifier = Modifier,
    /** New tab: unreviewed connections and incoming requests, the set awaiting a decision. */
    showNew: Boolean = false,
    listState: LazyListState,
) {
    // Developer flag: follow filters and badges, from the same lists the Following screen uses
    val advancedFeed by koinInject<DeveloperPreferences>().advancedFeedEnabled.collectAsStateWithLifecycle()
    val followGraph = koinInject<FollowGraph>()
    val follows by followGraph.state.collectAsStateWithLifecycle()
    LaunchedEffect(advancedFeed) { if (advancedFeed) followGraph.ensureLoaded() }
    // A follow filter left selected after the flag is turned off reads as All
    val filter = if (!advancedFeed && uiState.filter in FollowFilters) ContactFilter.ALL else uiState.filter

    Column(modifier = modifier.fillMaxSize()) {
        if (!showNew) FilterRow(filter, advancedFeed, onAction)

        // Incoming requests are the actionable set (outgoing has nothing to do here but Cancel,
        // already reachable from the resolved identity itself). They live with the unreviewed
        // connections because they are the same job: someone is waiting on a decision.
        val incomingRequests = if (showNew) {
            uiState.requests.filter { it.direction == RequestDirection.INCOMING }
        } else {
            emptyList()
        }

        val derivedFromStates = showNew || filter == ContactFilter.CIRCLES
        val list = when {
            showNew -> uiState.newContacts
            filter == ContactFilter.CIRCLES -> uiState.circleContacts
            filter == ContactFilter.BLOCKED -> uiState.blockedContacts
            filter == ContactFilter.FOLLOWING -> uiState.knownContacts.filter { it.odinId?.let(follows::isFollowing) == true }
            filter == ContactFilter.FOLLOWERS -> uiState.knownContacts.filter { it.odinId?.let(follows::followsYou) == true }
            else -> uiState.knownContacts
        }

        // Only the state-derived views wait on circles: they aren't cached, so gating All on
        // them would strand the whole list behind a spinner whenever circles fail to load.
        val phase = when {
            uiState.isLoading || (derivedFromStates && uiState.statesLoading) -> ContactBookContentPhase.LOADING
            list.isEmpty() && incomingRequests.isEmpty() -> ContactBookContentPhase.EMPTY
            else -> ContactBookContentPhase.LIST
        }
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
            },
        ) { targetPhase ->
            when (targetPhase) {
                ContactBookContentPhase.LOADING -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                ContactBookContentPhase.EMPTY -> when {
                    uiState.searchQuery.isNotBlank() -> CenterText(stringResource(MR.string.contactbook_no_results))

                    showNew -> CenterText(stringResource(MR.string.contactbook_new_empty))

                    filter in FollowFilters ->
                        CenterText(stringResource(MR.string.contactbook_follow_filter_empty))

                    filter == ContactFilter.CIRCLES ->
                        CenterText(stringResource(MR.string.contactbook_circles_filter_empty))

                    filter == ContactFilter.BLOCKED ->
                        CenterText(stringResource(MR.string.contactbook_blocked_filter_empty))

                    else -> ContactBookEmptyState(
                        onAddClick = { onAction(ContactBookUiAction.AddClicked) },
                    )
                }

                ContactBookContentPhase.LIST -> {
                    val (grouped, sections) = remember(list) {
                        val g = list.groupBy { it.sectionKey }
                        g to g.keys.sorted()
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        if (incomingRequests.isNotEmpty()) {
                            stickyHeader(key = "h_requests") {
                                Text(
                                    text = stringResource(MR.string.contactbook_requests_header),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .animateItem()
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            items(incomingRequests, key = { "req_${it.entry.uniqueId}" }) { request ->
                                ContactBookRow(
                                    entry = request.entry,
                                    onClick = { onAction(ContactBookUiAction.ContactClicked(request.entry)) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                        sections.forEach { section ->
                            val entries = grouped[section].orEmpty()
                            stickyHeader(key = "h_$section") {
                                Text(
                                    text = section,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .animateItem()
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            items(entries, key = { it.uniqueId.toString() }) { entry ->
                                val domain = entry.odinId?.lowercase()
                                val state = domain?.let { uiState.contactStates[it] }
                                val direction = domain?.let { uiState.requestDirections[it] }
                                ContactBookRow(
                                    entry = entry,
                                    onClick = { onAction(ContactBookUiAction.ContactClicked(entry)) },
                                    morphAvatar = true,
                                    modifier = Modifier.animateItem(),
                                    // Check shows whenever the identity is connected, in every
                                    // filter (a New contact is still a connection).
                                    connected = domain in uiState.connectedOdinIds,
                                    followBadge = if (advancedFeed && domain != null) {
                                        followBadgeText(follows.isFollowing(domain), follows.followsYou(domain))
                                    } else {
                                        null
                                    },
                                    trailing = when {
                                        direction != null -> {
                                            { RequestDirectionIcon(direction) }
                                        }

                                        state == null -> null
                                        // New is the one state with something to do, so it gets the
                                        // action rather than the icon that merely reports the state.
                                        state == ContactState.New -> {
                                            {
                                                TextButton(
                                                    onClick = {
                                                        onAction(ContactBookUiAction.ReviewClicked(entry))
                                                    },
                                                ) {
                                                    Text(stringResource(MR.string.contact_review_action))
                                                }
                                            }
                                        }

                                        else -> {
                                            { ContactStateIcon(state) }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterText(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilterRow(
    filter: ContactFilter,
    showFollowFilters: Boolean,
    onAction: (ContactBookUiAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filter == ContactFilter.ALL,
            onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.ALL)) },
            label = { Text(stringResource(MR.string.contactbook_filter_all)) },
        )
        FilterChip(
            selected = filter == ContactFilter.CIRCLES,
            onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.CIRCLES)) },
            label = { Text(stringResource(MR.string.contactbook_filter_circles)) },
        )
        FilterChip(
            selected = filter == ContactFilter.BLOCKED,
            onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.BLOCKED)) },
            label = { Text(stringResource(MR.string.contactbook_filter_blocked)) },
        )
        if (showFollowFilters) {
            FilterChip(
                selected = filter == ContactFilter.FOLLOWING,
                onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.FOLLOWING)) },
                label = { Text(stringResource(MR.string.contactbook_filter_following)) },
            )
            FilterChip(
                selected = filter == ContactFilter.FOLLOWERS,
                onClick = { onAction(ContactBookUiAction.FilterChanged(ContactFilter.FOLLOWERS)) },
                label = { Text(stringResource(MR.string.contactbook_filter_followers)) },
            )
        }
    }
}

private val FollowFilters = setOf(ContactFilter.FOLLOWING, ContactFilter.FOLLOWERS)

@Composable
private fun followBadgeText(following: Boolean, followsYou: Boolean): String? = when {
    following && followsYou -> stringResource(MR.string.contactbook_follow_badge_mutual)
    followsYou -> stringResource(MR.string.contactbook_follow_badge_follows_you)
    following -> stringResource(MR.string.contactbook_follow_badge_following)
    else -> null
}
