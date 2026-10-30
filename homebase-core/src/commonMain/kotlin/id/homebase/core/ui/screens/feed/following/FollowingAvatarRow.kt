package id.homebase.core.ui.screens.feed.following

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.core.util.isDesktopOrWeb
import id.homebase.resources.MR
import id.homebase.resources.feed_following_row_add
import id.homebase.resources.feed_following_row_next
import id.homebase.resources.feed_following_row_previous
import id.homebase.resources.feed_following_row_show_all
import id.homebase.resources.feed_following_row_show_posts
import id.homebase.resources.feed_following_row_view_contact
import id.homebase.resources.feed_following_unfollow
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val AvatarSize = 52.dp
private val ItemWidth = 68.dp
private val ArrowSize = 32.dp

// Per wheel notch; a trackpad sends fractions of one
private val WheelStep = 48.dp

// How much of the visible row an arrow click moves, leaving a little of the last page in view
private const val PageFraction = 0.8f

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

    val rowState = rememberLazyListState()
    // A mouse can't drag a row, and its wheel only scrolls vertically: the arrows and the wheel stand in
    val pointerNav = remember { isDesktopOrWeb() }

    Column(modifier = modifier) {
        Box {
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.pointerInput(rowState) {
                val step = WheelStep.toPx()
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val delta = event.changes.first().scrollDelta
                        // Only a purely vertical wheel: a sideways trackpad swipe already scrolls the row
                        if (delta.x == 0f && delta.y != 0f) {
                            rowState.dispatchRawDelta(delta.y * step)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
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
                val fullName = names[domain.lowercase()]?.takeIf { it.isNotBlank() }
                val name = fullName ?: domain.substringBefore('.')
                // A first name fits under an avatar where most full names don't
                val label = fullName?.trim()?.substringBefore(' ') ?: name
                val isSelected = selected.equals(domain, ignoreCase = true)
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    RowItem(
                        label = label,
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
        if (pointerNav) {
            val page = { sign: Float ->
                scope.launch { rowState.animateScrollBy(sign * rowState.layoutInfo.viewportSize.width * PageFraction) }
            }
            if (rowState.canScrollBackward) {
                RowArrow(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    description = stringResource(MR.string.feed_following_row_previous),
                    onClick = { page(-1f) },
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                )
            }
            if (rowState.canScrollForward) {
                RowArrow(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    description = stringResource(MR.string.feed_following_row_next),
                    onClick = { page(1f) },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                )
            }
        }
        }
        HorizontalDivider()
    }
}

@Composable
private fun RowArrow(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier.size(ArrowSize)) {
        Icon(icon, contentDescription = description)
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
        Spacer(modifier = Modifier.height(6.dp))
        // Still too long: start-aligned and faded at the end rather than cut with "…"
        var overflowing by remember(label) { mutableStateOf(false) }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            textAlign = if (overflowing) TextAlign.Start else TextAlign.Center,
            onTextLayout = { if (it.hasVisualOverflow) overflowing = true },
            modifier = Modifier
                .fillMaxWidth()
                .then(if (overflowing) Modifier.fadeEnd() else Modifier),
        )
    }
}

private val FadeWidth = 14.dp

// Fades the last [FadeWidth] to transparent; offscreen so the mask erases only this text
private fun Modifier.fadeEnd(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = FadeWidth.toPx()
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Black,
                ((size.width - fade) / size.width).coerceIn(0f, 1f) to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
