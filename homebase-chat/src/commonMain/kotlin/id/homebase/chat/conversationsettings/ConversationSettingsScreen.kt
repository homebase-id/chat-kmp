package id.homebase.chat.conversationsettings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.widget.AvatarNameDisplay
import id.homebase.chat.widget.ErrorInfoItem
import id.homebase.chat.widget.LoadingListItem
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConversationSettingsScreen(
    viewModel: ConversationSettingsViewModel,
    onNavigateBack: () -> Unit,
    onShowContactInfo: (odinId: String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            is ConversationSettingsUiEvent.Back -> {
                viewModel.eventConsumed()
                onNavigateBack()
            }

            is ConversationSettingsUiEvent.ShowContactInfo -> {
                viewModel.eventConsumed()
                onShowContactInfo(event.odinId)
            }

            null -> {}
        }
    }

    ConversationSettingsUi(
        uiState = uiState,
        onUiAction = viewModel::onUiAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSettingsUi(
    uiState: ConversationSettingsUiState,
    onUiAction: (ConversationSettingsUiAction) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { onUiAction(ConversationSettingsUiAction.BackClicked)  }) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding)
        ) {
            if (uiState.conversation == null) {
                if (uiState.isLoading) {
                    LoadingListItem()
                } else {
                    ErrorInfoItem("No group could be loaded")
                }
            }

            uiState.conversation?.let { conversation ->
                AvatarNameDisplay(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    displayName = conversation.name,
                    avatarModel = conversation.avatarModel,
                    onClick = {
                        conversation.participants.firstOrNull()?.let {
                            onUiAction(ConversationSettingsUiAction.ShowContactInfo(it))
                        }
                    }
                )
            }
        }
    }
}