@file:OptIn(ExperimentalMaterial3Api::class)

package id.homebase.core.ui.screens.card

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import id.homebase.core.localization.TranslationUtil
import id.homebase.core.ui.screens.profile.LoadFailedState
import id.homebase.core.ui.screens.profile.TierToggle
import id.homebase.core.util.getUriHandler
import id.homebase.core.util.isDesktopOrWeb
import id.homebase.core.widget.SettingsTopBar
import id.homebase.resources.MR
import id.homebase.resources.file_saved_to
import id.homebase.resources.profile_card_channel_access
import id.homebase.resources.profile_card_channel_access_allow
import id.homebase.resources.profile_card_design_board
import id.homebase.resources.profile_card_design_collage
import id.homebase.resources.profile_card_design_dossier
import id.homebase.resources.profile_card_design_poster
import id.homebase.resources.profile_card_error
import id.homebase.resources.profile_card_save
import id.homebase.resources.profile_card_share
import id.homebase.resources.profile_card_share_failed
import id.homebase.resources.profile_card_title
import id.homebase.resources.profile_card_unsupported
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// The portrait frame the card designs were drawn for.
private const val CARD_ASPECT_RATIO = 390f / 844f

// The page's own breakpoint for its full website layout; a CSS px is one dp in the host WebView.
private val PAGE_LAYOUT_MIN_WIDTH = 768.dp

@Composable
fun StartCardHostWhenSettled(viewModel: ProfileCardViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        // Building the host (first-use Chromium init + page load) blocks the main thread ~1s; a nav entry is RESUMED only once its enter transition ends.
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        withFrameNanos { }
        viewModel.startHost()
    }
}

@Composable
fun ProfileCardScreen(
    viewModel: ProfileCardViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val host by viewModel.host.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val fileSystemHandler = getUriHandler()
    // Desktop and web have no share sheet; saving is their way to get the image out.
    val saveInsteadOfShare = remember { isDesktopOrWeb() }
    val errCard = stringResource(MR.string.profile_card_error)
    val errShare = stringResource(MR.string.profile_card_share_failed)

    LaunchedEffect(viewModel) { viewModel.onScreenShown() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ProfileCardEvent.OpenLink -> fileSystemHandler.openUrl(event.url)
                is ProfileCardEvent.ShareImage -> {
                    val onError: (Throwable) -> Unit = { e ->
                        Logger.w(tag = "ProfileCard", throwable = e) { "handing the card image to the platform failed" }
                        launch { snackbarHostState.showSnackbar(errShare) }
                    }
                    if (saveInsteadOfShare) {
                        fileSystemHandler.saveFile(
                            file = Path(event.path),
                            suggestedName = event.fileName,
                            onSuccess = { location ->
                                launch {
                                    snackbarHostState.showSnackbar(
                                        TranslationUtil.getString(MR.string.file_saved_to, location),
                                    )
                                }
                            },
                            onError = onError,
                        )
                    } else {
                        fileSystemHandler.shareFile(Path(event.path), onError)
                    }
                }
                ProfileCardEvent.CardFailed -> launch { snackbarHostState.showSnackbar(errCard) }
                ProfileCardEvent.ShareFailed -> launch { snackbarHostState.showSnackbar(errShare) }
            }
        }
    }

    Scaffold(
        topBar = {
            SettingsTopBar(
                title = stringResource(MR.string.profile_card_title),
                onBack = onBack,
                actions = {
                    ShareAction(
                        isExporting = uiState.isExporting,
                        enabled = uiState.canShare,
                        saveInsteadOfShare = saveInsteadOfShare,
                        onClick = viewModel::onShareClicked,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TierToggle(
                selected = uiState.tier,
                onSelect = viewModel::onTierSelected,
                modifier = Modifier.padding(horizontal = 16.dp),
                reviewEnabled = uiState.reviewEnabled,
            )
            DesignChips(selected = uiState.design, onSelect = viewModel::onDesignSelected)
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState.loadFailed) {
                    LoadFailedState(modifier = Modifier, onRetry = viewModel::onRetry)
                } else {
                    val frame = if (maxWidth >= PAGE_LAYOUT_MIN_WIDTH) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.aspectRatio(CARD_ASPECT_RATIO, matchHeightConstraintsFirst = true)
                    }
                    Box(modifier = frame.clip(MaterialTheme.shapes.large)) {
                        // An unsupported server's /card is its public site, which desktop and web would float over the message.
                        if (!uiState.cardUnsupported) host?.let { CardHostView(it, Modifier.fillMaxSize()) }
                        if (!uiState.isCardReady) {
                            CardPlaceholder(failed = uiState.cardFailed, unsupported = uiState.cardUnsupported)
                        }
                    }
                }
                // Floats over the card: taking a row of the column would shrink the height-bound card and rewrap its text.
                if (uiState.showChannelAccessNotice) {
                    ChannelAccessNotice(
                        onAllow = viewModel::onAllowChannelAccess,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareAction(
    isExporting: Boolean,
    enabled: Boolean,
    saveInsteadOfShare: Boolean,
    onClick: () -> Unit,
) {
    if (isExporting) {
        Box(modifier = Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    } else {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = if (saveInsteadOfShare) Icons.Outlined.Download else Icons.Outlined.Share,
                contentDescription = stringResource(
                    if (saveInsteadOfShare) MR.string.profile_card_save else MR.string.profile_card_share,
                ),
            )
        }
    }
}

@Composable
private fun ChannelAccessNotice(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(MR.string.profile_card_channel_access),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            TextButton(onClick = onAllow) {
                Text(stringResource(MR.string.profile_card_channel_access_allow))
            }
        }
    }
}

@Composable
private fun DesignChips(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardDesign.all.forEach { design ->
            FilterChip(
                selected = design == selected,
                onClick = { onSelect(design) },
                label = { Text(stringResource(designLabel(design))) },
            )
        }
    }
}

private fun designLabel(design: String): StringResource = when (design) {
    CardDesign.POSTER -> MR.string.profile_card_design_poster
    CardDesign.COLLAGE -> MR.string.profile_card_design_collage
    CardDesign.DOSSIER -> MR.string.profile_card_design_dossier
    else -> MR.string.profile_card_design_board
}

@Composable
private fun CardPlaceholder(failed: Boolean, unsupported: Boolean) {
    val message = when {
        unsupported -> MR.string.profile_card_unsupported
        failed -> MR.string.profile_card_error
        else -> null
    }
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (message != null) {
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            CircularProgressIndicator()
        }
    }
}
