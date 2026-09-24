package id.homebase.chat.selectmembers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.chat.data.ContactUiModel
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.resources.MR
import id.homebase.resources.remove
import org.jetbrains.compose.resources.stringResource

@Composable
fun SelectedMemberChipRow(
    contacts: List<ContactUiModel>,
    onRemove: (ContactUiModel) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val listState = rememberLazyListState()
    var previousCount by remember { mutableIntStateOf(contacts.size) }
    LaunchedEffect(contacts.size) {
        if (contacts.size > previousCount) listState.animateScrollToItem(contacts.lastIndex)
        previousCount = contacts.size
    }

    AnimatedVisibility(
        visible = contacts.isNotEmpty(),
        enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
    ) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 16.dp),
        ) {
            items(contacts, key = { it.odinId.domainName }) { contact ->
                InputChip(
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = motion.defaultEffectsSpec(),
                            placementSpec = motion.defaultSpatialSpec(),
                            fadeOutSpec = motion.fastEffectsSpec(),
                        )
                        .widthIn(max = 200.dp)
                        .padding(end = 8.dp),
                    onClick = { onRemove(contact) },
                    label = {
                        Text(
                            text = contact.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    selected = true,
                    leadingIcon = {
                        ContactAvatar(
                            odinId = contact.odinId,
                            profileImageData = null,
                            initials = contact.avatarInitials,
                            options = AvatarOptions(
                                size = 28.dp,
                                fontSize = 12.sp,
                            ),
                            sharedTransitionScope = null,
                            animatedVisibilityScope = null
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(MR.string.remove),
                        )
                    }
                )
            }
        }
    }
}
