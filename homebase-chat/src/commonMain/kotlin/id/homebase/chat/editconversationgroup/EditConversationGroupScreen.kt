package id.homebase.chat.editconversationgroup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.createconversationgroup.GroupImage
import id.homebase.core.widget.MinimalTextField
import id.homebase.resources.MR
import id.homebase.resources.chat_group_edit
import id.homebase.resources.chat_group_name_placeholder
import id.homebase.resources.menu_back
import id.homebase.resources.save
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun EditConversationGroupScreen(
    viewModel: EditConversationGroupViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val galleryLauncher =
        rememberFilePickerLauncher(type = FileKitType.Image) { file ->
            file?.let {
                viewModel.onUiAction(EditConversationGroupUiAction.AttachGroupImage(file))
            }
        }

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            is EditConversationGroupUiEvent.Back -> {
                viewModel.eventConsumed()
                onNavigateBack()
            }

            is EditConversationGroupUiEvent.Error -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.errorMessage) }
            }

            is EditConversationGroupUiEvent.PickGroupImage -> {
                viewModel.eventConsumed()
                galleryLauncher.launch()
            }
        }
    }

    EditConversationGroupUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        groupNameTextState = viewModel.groupNameTextState,
        onUiAction = viewModel::onUiAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditConversationGroupUi(
    snackbarHostState: SnackbarHostState,
    uiState: EditConversationGroupUiState,
    groupNameTextState: TextFieldState,
    onUiAction: (EditConversationGroupUiAction) -> Unit,
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
                    Text(stringResource(MR.string.chat_group_edit))
                },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(EditConversationGroupUiAction.BackClicked) }) {
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
                onClick = { onUiAction(EditConversationGroupUiAction.SaveGroup) },
                modifier = Modifier.defaultMinSize(minWidth = 56.dp),
                enabled = uiState.saveAllowed && !uiState.isLoading,
                shape = CircleShape

            ) {
                Text(stringResource(MR.string.save))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .consumeWindowInsets(padding)
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                uiState.conversation?.let { conversation ->
                    GroupImage(
                        url = uiState.groupImage?.toString(),
                        avatarModel = if (uiState.imageChanged) null else conversation.avatarModel,
                        onClickAdd = { onUiAction(EditConversationGroupUiAction.AddGroupImage) },
                        onClickRemove = { onUiAction(EditConversationGroupUiAction.RemoveGroupImage) },
                    )
                }
                MinimalTextField(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    state = groupNameTextState,
                    inputTransformation = InputTransformation.maxLength(100),
                    placeHolderText = stringResource(MR.string.chat_group_name_placeholder),
                )
            }
        }
    }
}