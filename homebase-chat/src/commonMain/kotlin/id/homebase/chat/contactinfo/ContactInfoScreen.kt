package id.homebase.chat.contactinfo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.widget.ErrorInfoItem
import id.homebase.chat.widget.LoadingListItem
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.resources.MR
import id.homebase.resources.error_no_contact_loaded
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactInfoScreen(
    viewModel: ContactInfoViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                    IconButton(onClick = { onUiAction(ContactInfoUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.contact == null) {
                if (uiState.isLoading) {
                    LoadingListItem()
                } else {
                    ErrorInfoItem(stringResource(MR.string.error_no_contact_loaded))
                }
            }

            uiState.contact?.let { contact ->
                ContactAvatar(
                    odinId = contact.odinId,
                    profileImageData = null,
                    initials = contact.avatarInitials,
                    options = AvatarOptions(
                        size = 72.dp,
                        fontSize = 24.sp,
                    ),
                    sharedTransitionScope = null,
                    animatedVisibilityScope = null
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = contact.odinId.domainName,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}