package id.homebase.core.ui.screens.feed.following

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.resources.MR
import id.homebase.resources.feed_following_row_add
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private val AvatarSize = 52.dp
private val ItemWidth = 68.dp

/**
 * Who you follow, along the top of the feed: the people your feed is made of, one tap from their contact.
 * The first item opens the Following screen to find more. Shown behind the Advanced Feed developer flag.
 */
@Composable
fun FollowingAvatarRow(
    onIdentityClick: (String) -> Unit,
    onFindPeople: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val followGraph = koinInject<FollowGraph>()
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
                RowItem(label = name, onClick = { onIdentityClick(domain) }) {
                    IdentityAvatar(
                        odinId = odin,
                        displayName = name,
                        options = AvatarOptions(size = AvatarSize, onClick = { onIdentityClick(domain) }),
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun RowItem(label: String, onClick: () -> Unit, avatar: @Composable () -> Unit) {
    Column(
        modifier = Modifier.width(ItemWidth).clickable(onClick = onClick).padding(vertical = 2.dp),
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
