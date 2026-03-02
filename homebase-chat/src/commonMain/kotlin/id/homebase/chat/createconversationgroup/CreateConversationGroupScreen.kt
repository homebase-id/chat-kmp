package id.homebase.chat.createconversationgroup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import id.homebase.chat.createconversation.ContactItem
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.core.widget.DialogButtons
import id.homebase.core.widget.DialogCard
import id.homebase.core.widget.MinimalTextField
import id.homebase.core.widget.RoundedIcon
import id.homebase.core.widget.RoundedIconFromFile
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_group_name_placeholder
import id.homebase.resources.chat_group_name_screen_title
import id.homebase.resources.chat_group_remove_member
import id.homebase.resources.chat_group_remove_member_warning
import id.homebase.resources.create
import id.homebase.resources.members
import id.homebase.resources.menu_back
import id.homebase.resources.ok
import id.homebase.resources.remove
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
fun CreateConversationGroupScreen(
    viewModel: CreateConversationGroupViewModel,
    onNavigateBack: () -> Unit,
    onShowConversation: (conversationId: Uuid) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val galleryLauncher =
        rememberFilePickerLauncher(type = FileKitType.Image) { file ->
            file?.let {
                viewModel.onUiAction(CreateConversationGroupUiAction.AttachGroupImage(file))
            }
        }

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            is CreateConversationGroupUiEvent.Back -> {
                viewModel.eventConsumed()
                onNavigateBack()
            }

            is CreateConversationGroupUiEvent.ShowErrorMessage -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.message) }
            }

            is CreateConversationGroupUiEvent.LoadConversation -> {
                viewModel.eventConsumed()
                onShowConversation(event.conversationId)
            }

            is CreateConversationGroupUiEvent.PickGroupImage -> {
                viewModel.eventConsumed()
                galleryLauncher.launch()
            }
        }
    }

    when (val dialog = uiState.uiDialog) {
        null -> {}
        is CreateConversationGroupUiDialog.RemoveMemberWarning -> {
            Dialog(onDismissRequest = { viewModel.dialogClosed() }) {
                DialogCard(
                    buttons = {
                        DialogButtons(
                            primaryText = stringResource(MR.string.ok),
                            onPrimaryClick = { viewModel.dialogClosed() },
                        )
                    }) {
                    Text(
                        modifier = Modifier.padding(16.dp),
                        text = stringResource(MR.string.chat_group_remove_member_warning),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        is CreateConversationGroupUiDialog.RemoveMember -> {
            Dialog(onDismissRequest = { viewModel.dialogClosed() }) {
                DialogCard(
                    buttons = {
                        DialogButtons(
                            primaryText = stringResource(MR.string.remove),
                            onPrimaryClick = {
                                viewModel.onUiAction(
                                    CreateConversationGroupUiAction.RemoveMember(dialog.contact)
                                )
                                viewModel.dialogClosed()
                            },
                            secondaryText = stringResource(MR.string.cancel),
                            onSecondaryClick = { viewModel.dialogClosed() }
                        )
                    }) {
                    Text(
                        modifier = Modifier.padding(16.dp),
                        text = stringResource(
                            MR.string.chat_group_remove_member,
                            dialog.contact.name
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }

    CreateConversationGroupUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        groupNameTextState = viewModel.groupNameTextState,
        onUiAction = viewModel::onUiAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateConversationGroupUi(
    snackbarHostState: SnackbarHostState,
    uiState: CreateConversationGroupUiState,
    groupNameTextState: TextFieldState,
    onUiAction: (CreateConversationGroupUiAction) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(MR.string.chat_group_name_screen_title))
                },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(CreateConversationGroupUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            Button(
                onClick = { onUiAction(CreateConversationGroupUiAction.CreateGroup) },
                modifier = Modifier.defaultMinSize(minWidth = 56.dp),
                enabled = uiState.createAllowed && !uiState.isCreatingGroup,
                shape = CircleShape

            ) {
                Text(stringResource(MR.string.create))
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.padding(paddingValues)
        ) {
            if (uiState.isCreatingGroup) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.padding(end = 24.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GroupImage(
                        url = uiState.groupImage?.toString(),
                        onClickAdd = { onUiAction(CreateConversationGroupUiAction.AddGroupImage) },
                        onClickRemove = { onUiAction(CreateConversationGroupUiAction.RemoveGroupImage) },
                    )
                    MinimalTextField(
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                            .focusRequester(focusRequester),
                        state = groupNameTextState,
                        inputTransformation = InputTransformation.maxLength(100),
                        placeHolderText = stringResource(MR.string.chat_group_name_placeholder),
                    )
                }
            }
            HorizontalDivider()
            Text(
                modifier = Modifier.padding(horizontal = 24.dp).padding(top = 32.dp),
                text = stringResource(MR.string.members),
                style = MaterialTheme.typography.titleLarge
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(uiState.contacts) { contact ->
                    ContactItem(
                        name = contact.name,
                        subTitle = contact.odinId.domainName,
                        odinId = contact.odinId,
                        avatarInitials = contact.avatarInitials,
                        onContactClick = {
                            onUiAction(
                                CreateConversationGroupUiAction.RemoveClicked(contact)
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun GroupImage(
    modifier: Modifier = Modifier,
    avatarModel: ConversationAvatarModel? = null,
    url: String?,
    onClickAdd: () -> Unit,
    onClickRemove: () -> Unit,
) {
    Box(modifier = modifier.size(112.dp)) {
        if (avatarModel != null && avatarModel.type != ConversationAvatarModel.Type.GroupFallback) {
            ConversationAvatar(
                avatarModel = avatarModel,
                modifier = Modifier.align(Alignment.Center),
                options = AvatarOptions(
                    size = 72.dp,
                    fontSize = 24.sp,
                )
            )
            IconButton(
                onClick = onClickRemove,
                modifier = Modifier.align(Alignment.TopEnd),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(MR.string.remove),
                )
            }
        } else if (url != null) {
            RoundedIconFromFile(
                modifier = Modifier.align(Alignment.Center),
                path = url,
                size = 72.dp,
                onClick = onClickAdd,
            )
            IconButton(
                onClick = onClickRemove,
                modifier = Modifier.align(Alignment.TopEnd),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(MR.string.remove),
                )
            }
        } else {
            RoundedIcon(
                modifier = Modifier.align(Alignment.Center),
                imageVector = Icons.Outlined.PhotoCamera,
                size = 72.dp,
                onClick = onClickAdd,
            )
        }
    }

}