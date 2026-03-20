package id.homebase.chat.messageinfo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.widget.ReceivedMessageBubble
import id.homebase.chat.widget.SentMessageBubble
import id.homebase.core.util.formateDateTime
import id.homebase.resources.MR
import id.homebase.resources.chat_message_info
import id.homebase.resources.details
import id.homebase.resources.menu_back
import id.homebase.resources.sent
import id.homebase.resources.updated
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource

@Composable
fun MessageInfoScreen(
    viewModel: MessageInfoViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    when (uiState.uiEvent) {
        is MessageInfoUiEvent.Back -> {
            viewModel.eventConsumed()
            onNavigateBack()
        }

        null -> {}
    }

    MessageInfoUi(uiState = uiState, onUiAction = viewModel::onUiAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInfoUi(
    uiState: MessageInfoUiState,
    onUiAction: (MessageInfoUiAction) -> Unit,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.chat_message_info)) },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(MessageInfoUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
            )
        }) { padding ->
        Column(modifier = Modifier
            .consumeWindowInsets(padding)
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(scrollState)
        ) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
                uiState.message?.let { message ->
                    if (message.isAuthoredBy(uiState.ownerSession?.odinId)) {
                        SentMessageBubble(
                            message = message,
                            decryptedFiles = persistentMapOf(),
                            onEdit = {},
                            onShare = {},
                            onDelete = {},
                            onMediaClick = {},
                            onShowReactions = {},
                            downloadingFiles = emptySet(),
                        )
                    } else {
                        ReceivedMessageBubble(
                            message = message,
                            decryptedFiles = persistentMapOf(),
                            onShare = {},
                            onDelete = {},
                            onMarkAsRead = {},
                            onShowReactions = {},
                            onMediaClick = {},
                            downloadingFiles = emptySet(),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = stringResource(MR.string.details),
                    style = MaterialTheme.typography.titleLarge
                )
                Row(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = stringResource(MR.string.sent) + ": ",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = uiState.message?.created?.let { formateDateTime(it) } ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Row(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = stringResource(MR.string.updated) + ": ",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = uiState.message?.modified?.let { formateDateTime(it) }
                            ?: uiState.message?.created?.let { formateDateTime(it) } ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
