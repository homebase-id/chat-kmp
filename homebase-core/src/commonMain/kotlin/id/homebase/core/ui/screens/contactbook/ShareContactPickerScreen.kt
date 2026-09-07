package id.homebase.core.ui.screens.contactbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.ui.screens.contactbook.components.ContactBookAvatar
import id.homebase.core.ui.screens.contactbook.components.ContactBookRow
import id.homebase.core.widget.StyledSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.chat_contact_share_no_contacts
import id.homebase.resources.chat_contact_share_review_count
import id.homebase.resources.chat_contact_share_review_photo
import id.homebase.resources.chat_contact_share_review_title
import id.homebase.resources.chat_contact_share_title
import id.homebase.resources.chat_contact_share_unshareable
import id.homebase.resources.chat_new_conversation_search_placeholder
import id.homebase.resources.contactbook_detail_name
import id.homebase.resources.contactbook_edit_email
import id.homebase.resources.contactbook_edit_odinid
import id.homebase.resources.contactbook_edit_organization
import id.homebase.resources.contactbook_edit_phone
import id.homebase.resources.menu_back
import id.homebase.resources.send
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun ShareContactPickerScreen(
    viewModel: ShareContactPickerViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ShareContactPickerUiEvent.Back,
                ShareContactPickerUiEvent.MessageSent -> onNavigateBack()

                is ShareContactPickerUiEvent.ShowError -> {
                    val message = getString(event.res)
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
            }
        }
    }

    ShareContactPickerUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        searchTextState = viewModel.searchTextState,
        onUiAction = viewModel::onUiAction,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ShareContactPickerUi(
    snackbarHostState: SnackbarHostState,
    uiState: ShareContactPickerUiState,
    searchTextState: TextFieldState,
    onUiAction: (ShareContactPickerUiAction) -> Unit,
) {
    val review = uiState.review
    BackHandler(enabled = review != null) {
        onUiAction(ShareContactPickerUiAction.BackClicked)
    }
    if (review == null) {
        ContactPickerList(
            snackbarHostState = snackbarHostState,
            uiState = uiState,
            searchTextState = searchTextState,
            onUiAction = onUiAction,
        )
    } else {
        ContactCardReviewUi(
            snackbarHostState = snackbarHostState,
            review = review,
            isSending = uiState.isSending,
            onUiAction = onUiAction,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactPickerList(
    snackbarHostState: SnackbarHostState,
    uiState: ShareContactPickerUiState,
    searchTextState: TextFieldState,
    onUiAction: (ShareContactPickerUiAction) -> Unit,
) {
    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.chat_contact_share_title)) },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(ShareContactPickerUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            StyledSearchTextField(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                textFieldState = searchTextState,
                showSearchIcon = false,
                placeHolderText = stringResource(MR.string.chat_new_conversation_search_placeholder),
            )
            if (uiState.candidates.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 16.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(text = stringResource(MR.string.chat_contact_share_no_contacts))
                }
            } else {
                val unshareableReason = stringResource(MR.string.chat_contact_share_unshareable)
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(uiState.candidates, key = { it.entry.uniqueId.toString() }) { candidate ->
                        val entry = candidate.entry
                        ContactBookRow(
                            entry = entry,
                            onClick = { onUiAction(ShareContactPickerUiAction.ContactClicked(entry)) },
                            disabledReason = if (!candidate.shareable) unshareableReason else null,
                            trailing = if (uiState.selectedId == entry.uniqueId) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactCardReviewUi(
    snackbarHostState: SnackbarHostState,
    review: ContactCardReview,
    isSending: Boolean,
    onUiAction: (ShareContactPickerUiAction) -> Unit,
) {
    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(MR.string.chat_contact_share_review_title)) },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(ShareContactPickerUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { onUiAction(ShareContactPickerUiAction.SendClicked) },
                        modifier = Modifier.padding(end = 8.dp),
                        enabled = !isSending && review.canSend,
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current,
                            )
                        } else {
                            Text(text = stringResource(MR.string.send))
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            ContactCardReviewHeader(review = review, onUiAction = onUiAction)
            if (review.fields.isNotEmpty()) {
                Text(
                    text = stringResource(
                        MR.string.chat_contact_share_review_count,
                        review.selectedCount,
                        review.fields.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    review.fields.forEachIndexed { index, field ->
                        if (index > 0) HorizontalDivider()
                        ContactCardReviewFieldRow(
                            field = field,
                            onToggle = {
                                onUiAction(ShareContactPickerUiAction.ReviewFieldToggled(index))
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ContactCardReviewHeader(
    review: ContactCardReview,
    onUiAction: (ShareContactPickerUiAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (review.hasPhoto) {
            val photoLabel = stringResource(MR.string.chat_contact_share_review_photo)
            Checkbox(
                checked = review.includePhoto,
                onCheckedChange = { onUiAction(ShareContactPickerUiAction.ReviewPhotoToggled) },
                modifier = Modifier.semantics { contentDescription = photoLabel },
            )
        }
        ContactBookAvatar(entry = review.entry, size = 56.dp)
        Spacer(modifier = Modifier.width(16.dp))
        OutlinedTextField(
            value = review.displayName,
            onValueChange = { onUiAction(ShareContactPickerUiAction.ReviewNameChanged(it)) },
            label = { Text(stringResource(MR.string.contactbook_detail_name)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ContactCardReviewFieldRow(field: ContactCardField, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = field.selected, onCheckedChange = null)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contactCardFieldLabel(field.kind),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(text = field.value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun contactCardFieldLabel(kind: ContactCardFieldKind): String = when (kind) {
    ContactCardFieldKind.Phone -> stringResource(MR.string.contactbook_edit_phone)
    ContactCardFieldKind.Email -> stringResource(MR.string.contactbook_edit_email)
    ContactCardFieldKind.Organization -> stringResource(MR.string.contactbook_edit_organization)
    ContactCardFieldKind.Identity -> stringResource(MR.string.contactbook_edit_odinid)
}
