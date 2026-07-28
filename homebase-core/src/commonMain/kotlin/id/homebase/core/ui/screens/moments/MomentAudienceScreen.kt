package id.homebase.core.ui.screens.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.FallbackAvatar
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.moments.services.MomentsRecipient
import id.homebase.core.ui.screens.contactbook.components.CircleMembersSheet
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.moments_audience_create_group
import id.homebase.resources.moments_audience_post
import id.homebase.resources.moments_audience_post_failed
import id.homebase.resources.moments_audience_save_privately
import id.homebase.resources.moments_audience_section_circles
import id.homebase.resources.moments_audience_section_contacts
import id.homebase.resources.moments_audience_section_groups
import id.homebase.resources.moments_audience_section_recent
import id.homebase.resources.moments_audience_title
import id.homebase.resources.moments_audience_to_myself_only
import id.homebase.resources.moments_audience_view_circle_members
import id.homebase.resources.moments_compose_comments_enabled
import id.homebase.resources.moments_create_search_hint
import id.homebase.resources.moments_create_selected_count
import id.homebase.resources.number_of_members
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentAudienceScreen(
    viewModel: MomentAudienceViewModel,
    onNavigateBack: () -> Unit,
    onPosted: () -> Unit,
    onCreateGroup: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val postFailedMessage = stringResource(MR.string.moments_audience_post_failed)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                MomentAudienceUiEvent.Posted -> onPosted()
                is MomentAudienceUiEvent.PostFailed ->
                    snackbarHostState.showSnackbar(postFailedMessage)
            }
        }
    }

    val searchHint = stringResource(MR.string.moments_create_search_hint)
    val recentLabel = stringResource(MR.string.moments_audience_section_recent)
    val groupsLabel = stringResource(MR.string.moments_audience_section_groups)
    val circlesLabel = stringResource(MR.string.moments_audience_section_circles)
    val contactsLabel = stringResource(MR.string.moments_audience_section_contacts)
    val createGroupLabel = stringResource(MR.string.moments_audience_create_group)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.moments_audience_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            AudienceBottomBar(
                selectedCount = uiState.selected.size,
                selfOnly = uiState.selfOnly,
                isPosting = uiState.isPosting,
                enabled = uiState.canPost,
                onPost = { viewModel.onAction(MomentAudienceUiAction.PostClicked) },
            )
        },
    ) { innerPadding ->
        val recent = uiState.filteredRecent
        val groupsSection = uiState.filteredGroups
        val circlesSection = uiState.filteredCircles
        val contactsSection = uiState.filteredContacts

        // Only circle rows expose a "view members" affordance; everything else gets null.
        val infoClickFor: (MomentsRecipient) -> (() -> Unit)? = { recipient ->
            if (recipient is MomentsRecipient.Circle) {
                { viewModel.onAction(MomentAudienceUiAction.ShowCircleMembers(recipient.id)) }
            } else {
                null
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = {
                        viewModel.onAction(MomentAudienceUiAction.QueryChanged(it))
                    },
                    placeholder = { Text(searchHint) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Comments toggle — moved here from the compose screen so the
            // user makes the recipient + reactions decision together.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(MR.string.moments_compose_comments_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = uiState.commentsEnabled,
                        onCheckedChange = {
                            viewModel.onAction(MomentAudienceUiAction.CommentsEnabledChanged(it))
                        },
                    )
                }
                HorizontalDivider()
            }

            // Static "To myself only" option — explicit private save. Mutually
            // exclusive with any recipient selection (see ViewModel).
            item(key = "self-only") {
                SelfOnlyRow(
                    selected = uiState.selfOnly,
                    onClick = {
                        viewModel.onAction(MomentAudienceUiAction.ToggleSelfOnly)
                    },
                )
            }

            // Recent (MRU) — preserves the order returned by the lookup service
            // (most recent first). Hidden when there's no MRU history yet, or
            // when the search query filters every recent recipient out.
            if (recent.isNotEmpty()) {
                section(recentLabel)
                items(recent, key = { "r-${it.id.raw}" }) { recipient ->
                    RecipientRow(
                        recipient = recipient,
                        selected = recipient.id in uiState.selected,
                        onClick = {
                            viewModel.onAction(MomentAudienceUiAction.ToggleRecipient(recipient.id))
                        },
                        onInfoClick = infoClickFor(recipient),
                    )
                }
            }

            // Groups section is always shown so the "Create new group" entry
            // is reachable even when the user has no groups yet.
            section(groupsLabel)
            item(key = "create-group") {
                CreateGroupRow(label = createGroupLabel, onClick = onCreateGroup)
            }
            items(groupsSection, key = { "g-${it.id.raw}" }) { recipient ->
                RecipientRow(
                    recipient = recipient,
                    selected = recipient.id in uiState.selected,
                    onClick = {
                        viewModel.onAction(MomentAudienceUiAction.ToggleRecipient(recipient.id))
                    },
                    onInfoClick = infoClickFor(recipient),
                )
            }

            // Circles — share to everyone in a user-defined circle in one tap (#1087). Empty
            // circles are shown greyed-out + non-selectable (RecipientRow gates on member count);
            // the "i" affordance opens the view-only roster.
            if (circlesSection.isNotEmpty()) {
                section(circlesLabel)
                items(circlesSection, key = { "ci-${it.id.raw}" }) { recipient ->
                    RecipientRow(
                        recipient = recipient,
                        selected = recipient.id in uiState.selected,
                        onClick = {
                            viewModel.onAction(MomentAudienceUiAction.ToggleRecipient(recipient.id))
                        },
                        onInfoClick = infoClickFor(recipient),
                    )
                }
            }

            if (contactsSection.isNotEmpty()) {
                section(contactsLabel)
                items(contactsSection, key = { "c-${it.id.raw}" }) { recipient ->
                    RecipientRow(
                        recipient = recipient,
                        selected = recipient.id in uiState.selected,
                        onClick = {
                            viewModel.onAction(MomentAudienceUiAction.ToggleRecipient(recipient.id))
                        },
                        onInfoClick = infoClickFor(recipient),
                    )
                }
            }
        }

        // View-only roster for a tapped circle — reuses the contact-book sheet with management
        // affordances disabled (manageable = false). Members are the circle's snapshot audience.
        uiState.circleDetail?.let { detail ->
            CircleMembersSheet(
                state = detail,
                onDismiss = { viewModel.onAction(MomentAudienceUiAction.DismissCircleMembers) },
                onMemberClick = {},
                onAddMemberClick = {},
                onRemoveMemberClick = {},
            )
        }
    }
}

private fun LazyListScope.section(title: String) {
    item {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun RecipientRow(
    recipient: MomentsRecipient,
    selected: Boolean,
    onClick: () -> Unit,
    onInfoClick: (() -> Unit)? = null,
) {
    // A circle with no members can't be a share target — show it, greyed, but don't let it be
    // selected (odinIds is the exact fan-out set, and it's empty). Everything else is selectable.
    val selectable = recipient !is MomentsRecipient.Circle || recipient.odinIds.isNotEmpty()
    val contentAlpha = if (selectable) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = selectable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Avatar + label are dimmed together when the row isn't selectable; the info affordance
        // and selection indicator keep full opacity so the "view members" tap stays clear.
        Row(
            modifier = Modifier.weight(1f).alpha(contentAlpha),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Render avatars the same way chat does: individuals resolve their
            // public profile image from the odinId via PublicAvatar; groups fall
            // back to chat's group glyph (mirrors ConversationAvatar's
            // GroupFallback). See ConversationAvatar.kt / PublicAvatar.kt.
            val avatarOptions = AvatarOptions(size = 40.dp)
            when (recipient) {
                is MomentsRecipient.Individual -> PublicAvatar(
                    odinId = recipient.odinId,
                    initials = recipient.avatarInitials,
                    options = avatarOptions,
                )

                is MomentsRecipient.Group -> FallbackAvatar(
                    initials = recipient.avatarInitials,
                    options = avatarOptions,
                    imageVector = Icons.Outlined.Group,
                )

                is MomentsRecipient.Circle -> FallbackAvatar(
                    initials = recipient.avatarInitials,
                    options = avatarOptions,
                    imageVector = Icons.Outlined.Groups,
                )
            }
            Column {
                Text(
                    text = recipient.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                val memberCount = when (recipient) {
                    is MomentsRecipient.Group -> recipient.memberCount
                    is MomentsRecipient.Circle -> recipient.memberCount
                    is MomentsRecipient.Individual -> null
                }
                if (memberCount != null) {
                    Text(
                        text = stringResource(
                            MR.string.number_of_members,
                            memberCount.toString(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (onInfoClick != null) {
            IconButton(onClick = onInfoClick) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(MR.string.moments_audience_view_circle_members),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Always shown so the row keeps a consistent trailing column; dimmed (and never
        // "selected") for a non-selectable empty circle so it reads as unavailable, not checkable.
        Box(modifier = Modifier.alpha(contentAlpha)) {
            SelectionIndicator(selected = selectable && selected)
        }
    }
}

@Composable
private fun SelfOnlyRow(
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = stringResource(MR.string.moments_audience_to_myself_only),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        SelectionIndicator(selected = selected)
    }
}

@Composable
private fun CreateGroupRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SelectionIndicator(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun AudienceBottomBar(
    selectedCount: Int,
    selfOnly: Boolean,
    isPosting: Boolean,
    enabled: Boolean,
    onPost: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.string.moments_create_selected_count, selectedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (isPosting) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(20.dp),
                    )
                }
                Button(
                    onClick = onPost,
                    enabled = enabled,
                ) {
                    Text(
                        stringResource(
                            if (selfOnly) MR.string.moments_audience_save_privately
                            else MR.string.moments_audience_post,
                        ),
                    )
                }
            }
        }
    }
}
