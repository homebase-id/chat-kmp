package id.homebase.core.ui.screens.feed.following

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.resources.MR
import id.homebase.resources.feed_following_row_add
import id.homebase.resources.feed_following_row_show_all
import id.homebase.resources.feed_following_row_show_posts
import id.homebase.resources.feed_following_row_view_contact
import id.homebase.resources.feed_following_unfollow
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val AvatarSize = 52.dp
private val ItemWidth = 68.dp

/**
 * Who you follow, along the top of the feed: the people your feed is made of. A tap shows only that person's
 * posts ([selected] is ringed); a long press offers their contact and Unfollow. The first item opens the
 * Following screen to find more. Shown behind the Advanced Feed developer flag.
 */
@Composable
fun FollowingAvatarRow(
    selected: String?,
    onSelect: (String) -> Unit,
    onViewContact: (String) -> Unit,
    onUnfollowed: (String) -> Unit,
    onFindPeople: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val followGraph = koinInject<FollowGraph>()
    val scope = rememberCoroutineScope()
    val contactService = koinInject<ContactService>()
    val graph by followGraph.state.collectAsStateWithLifecycle()
    val contacts by contactService.contacts.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { followGraph.ensureLoaded() }

    val names = remember(contacts) { contacts.associate { it.odinId.domainName.lowercase() to it.name } }

    Column(modifier = modifier) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "find-people") {
                RowItem(label = stringResource(MR.string.feed_following_row_add), onClick = onFindPeople) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(AvatarSize),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                        }
                    }
                }
            }
            items(items = graph.following, key = { it }) { domain ->
                val odin = remember(domain) { OdinId(domain) }
                val name = names[domain.lowercase()]?.takeIf { it.isNotBlank() } ?: domain.substringBefore('.')
                val isSelected = selected.equals(domain, ignoreCase = true)
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    RowItem(
                        label = name,
                        onClick = { onSelect(domain) },
                        onLongClick = { menuOpen = true },
                    ) {
                        // The avatar takes no clicks of its own, so the item's tap and long press both reach it
                        Box(
                            modifier = if (isSelected) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(2.dp)
                            } else {
                                Modifier.padding(2.dp)
                            },
                        ) {
                            IdentityAvatar(odinId = odin, displayName = name, options = AvatarOptions(size = AvatarSize - 4.dp))
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (isSelected) MR.string.feed_following_row_show_all
                                        else MR.string.feed_following_row_show_posts
                                    )
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onSelect(domain)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.string.feed_following_row_view_contact)) },
                            onClick = {
                                menuOpen = false
                                onViewContact(domain)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.string.feed_following_unfollow)) },
                            onClick = {
                                menuOpen = false
                                onUnfollowed(domain)
                                scope.launch {
                                    // The row puts them back if it fails; nothing else to say from here
                                    runCatching { followGraph.unfollow(domain) }
                                        .onFailure { Logger.w(throwable = it, tag = "FollowingAvatarRow") { "unfollow $domain failed" } }
                                }
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowItem(
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    avatar: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(ItemWidth)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        avatar()
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
