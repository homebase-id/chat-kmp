package id.homebase.chat.contactinfo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.widget.AvatarNameDisplay
import id.homebase.chat.widget.ErrorInfoItem
import id.homebase.chat.widget.LoadingListItem
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactInfoScreen(
    viewModel: ContactInfoViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    when (uiState.uiEvent) {
        is ContactInfoUiEvent.Back -> {
            viewModel.eventConsumed()
            onNavigateBack()
        }
        null -> {}
    }

    ContactInfoUi(
        uiState = uiState,
        onUiAction = viewModel::onUiAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactInfoUi(
    uiState: ContactInfoUiState,
    onUiAction: (ContactInfoUiAction) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { onUiAction(ContactInfoUiAction.BackClicked)  }) {
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
            modifier = Modifier.padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.contact == null) {
                if (uiState.isLoading) {
                    LoadingListItem()
                } else {
                    ErrorInfoItem("No contact could be loaded")
                }
            }

            uiState.contact?.let { contact ->
                AvatarNameDisplay(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    displayName = contact.name,
                    avatarUrl = contact.avatarUrl,
                    avatarInitials = contact.avatarInitials,
                )
                Text(contact.odinId.domainName)
            }
        }
    }
}