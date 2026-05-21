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
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.moments.services.MomentsRecipient
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.moments_audience_create_group
import id.homebase.resources.moments_audience_post
import id.homebase.resources.moments_audience_post_failed
import id.homebase.resources.moments_audience_save_privately
import id.homebase.resources.moments_audience_section_contacts
import id.homebase.resources.moments_audience_section_groups
import id.homebase.resources.moments_audience_section_recent
import id.homebase.resources.moments_audience_title
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
                isPosting = uiState.isPosting,
                enabled = uiState.canPost,
                onPost = { viewModel.onAction(MomentAudienceUiAction.PostClicked) },
            )
        },
    ) { innerPadding ->
        val recent = uiState.filteredRecent
        val groupsSection = uiState.filteredGroups
        val contactsSection = uiState.filteredContacts

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
                )
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
                    )
                }
            }
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
                imageVector = when (recipient) {
                    is MomentsRecipient.Group -> Icons.Outlined.Group
                    is MomentsRecipient.Individual -> Icons.Outlined.Person
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recipient.displayName,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (recipient is MomentsRecipient.Group) {
                Text(
                    text = stringResource(
                        MR.string.number_of_members,
                        recipient.memberCount.toString(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
                            if (selectedCount == 0) MR.string.moments_audience_save_privately
                            else MR.string.moments_audience_post,
                        ),
                    )
                }
            }
        }
    }
}
