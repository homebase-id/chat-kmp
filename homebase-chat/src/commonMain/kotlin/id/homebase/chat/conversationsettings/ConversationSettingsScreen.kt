package id.homebase.chat.conversationsettings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConversationSettingsScreen(
    viewModel: ConversationSettingsViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    when (uiState.uiEvent) {
        is ConversationSettingsUiEvent.Back -> {
            viewModel.eventConsumed()
            onNavigateBack()
        }
        null -> {}
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
    ) { parameters ->
        Column(
            modifier = Modifier.padding(parameters)
        ) {
            Text("Conversation settings")
            Text(uiState.text)
        }
    }
}