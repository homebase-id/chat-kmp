@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.webdrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.ui.screens.webdrop.components.WebDropComposeSheet
import id.homebase.core.ui.screens.webdrop.components.WebDropRowCard
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.webdrop_empty_body
import id.homebase.resources.webdrop_empty_title
import id.homebase.resources.webdrop_label
import id.homebase.resources.webdrop_new_drop
import kotlin.uuid.ExperimentalUuidApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDropScreen(
    viewModel: WebDropViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val uriHandler = getUriHandler()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                WebDropUiEvent.CloseOnboarding -> onNavigateBack()
                is WebDropUiEvent.ShareLink -> {
                    // Desktop/web shareText is a no-op; the sheet's copy button is the fallback.
                    clipboard.setText(AnnotatedString(event.url))
                    uriHandler.shareText(event.url)
                }
                is WebDropUiEvent.CopyLink -> clipboard.setText(AnnotatedString(event.url))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Redeem,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(MR.string.webdrop_label))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.onAction(WebDropUiAction.OpenCompose) }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(MR.string.webdrop_new_drop),
                )
            }
        },
    ) { innerPadding ->
        when {
            !uiState.isLoaded -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.drops.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Redeem,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(64.dp).height(64.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(MR.string.webdrop_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(MR.string.webdrop_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.drops, key = { it.receiptFileId.toString() }) { row ->
                    WebDropRowCard(
                        row = row,
                        onCopyLink = { viewModel.onAction(WebDropUiAction.CopyLinkClicked(row.receipt.url)) },
                        onRevoke = { viewModel.onAction(WebDropUiAction.RevokeClicked(row.dropId)) },
                        onClear = { viewModel.onAction(WebDropUiAction.ClearClicked(row.receiptFileId)) },
                    )
                }
            }
        }
    }

    if (uiState.composeOpen) {
        WebDropComposeSheet(
            uiState = uiState,
            onAction = viewModel::onAction,
        )
    }

}
