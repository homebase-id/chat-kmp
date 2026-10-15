package id.homebase.core.ui.screens.invite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.invitations.InvitationCode
import id.homebase.core.clipboard.rememberCopyToClipboard
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.util.getUriHandler
import id.homebase.core.util.isMobile
import id.homebase.core.widget.SettingsTopBar
import id.homebase.resources.MR
import id.homebase.resources.action_retry
import id.homebase.resources.invite_friends_empty
import id.homebase.resources.invite_friends_load_error
import id.homebase.resources.invite_friends_share_text
import id.homebase.resources.invite_friends_title
import id.homebase.resources.invite_friends_used_up
import id.homebase.resources.invite_friends_uses_left
import id.homebase.resources.share
import org.jetbrains.compose.resources.stringResource

@Composable
fun InviteFriendsScreen(
    viewModel: InviteFriendsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val copyToClipboard = rememberCopyToClipboard(snackbarHostState)
    val uriHandler = getUriHandler()
    // Desktop and web have no share sheet, so the link goes to the clipboard with a confirmation instead.
    val share: (String) -> Unit = if (isMobile()) {
        { text -> uriHandler.shareText(text) }
    } else {
        copyToClipboard
    }

    InviteFriendsUi(
        uiState = uiState,
        onAction = viewModel::onAction,
        onShare = share,
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteFriendsUi(
    uiState: InviteFriendsUiState,
    onAction: (InviteFriendsUiAction) -> Unit,
    onShare: (String) -> Unit,
    onBackClick: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = {
            SettingsTopBar(
                title = stringResource(MR.string.invite_friends_title),
                onBack = onBackClick,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding)
        when {
            uiState.isLoading -> Box(contentModifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            uiState.loadFailed -> CenteredMessage(
                message = stringResource(MR.string.invite_friends_load_error),
                modifier = contentModifier,
            ) {
                OutlinedButton(
                    onClick = { onAction(InviteFriendsUiAction.Retry) },
                    modifier = Modifier.testTag("inviteFriendsRetry"),
                ) {
                    Text(stringResource(MR.string.action_retry))
                }
            }

            uiState.hasNoCode -> CenteredMessage(
                message = stringResource(MR.string.invite_friends_empty),
                modifier = contentModifier,
            )

            else -> LazyColumn(
                modifier = contentModifier,
                contentPadding = PaddingValues(Dimens.Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Dimens.Spacing.gutter),
            ) {
                items(uiState.codes, key = { it.code }) { code ->
                    InvitationCodeCard(code = code, onShare = onShare)
                }
            }
        }
    }
}

@Composable
private fun InvitationCodeCard(
    code: InvitationCode,
    onShare: (String) -> Unit,
) {
    val shareText = stringResource(MR.string.invite_friends_share_text, code.link)
    ElevatedCard(modifier = Modifier.fillMaxWidth().testTag("invitationCode")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Dimens.Spacing.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing.item),
        ) {
            SelectionContainer {
                Text(
                    text = code.code,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = if (code.isUsedUp) {
                    stringResource(MR.string.invite_friends_used_up)
                } else {
                    stringResource(MR.string.invite_friends_uses_left, code.usesLeft, code.maxUses)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(Dimens.Spacing.label))
            Button(
                onClick = { onShare(shareText) },
                enabled = !code.isUsedUp,
                modifier = Modifier.testTag("invitationCodeShare"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(MR.string.share))
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(Dimens.Spacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing.gutter, Alignment.CenterVertically),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}
