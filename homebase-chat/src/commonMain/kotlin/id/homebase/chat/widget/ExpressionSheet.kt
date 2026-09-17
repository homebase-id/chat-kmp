@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.GifBox
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.services.sticker.SavedSticker
import id.homebase.chat.services.sticker.StickerStream
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.StickerFilled
import id.homebase.core.ui.assets.StickerOutlined
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import id.homebase.core.widget.EmojiSelection
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.cd_tab_emoji
import id.homebase.resources.cd_tab_stickers
import id.homebase.resources.chat_sticker_remove
import id.homebase.resources.chat_sticker_remove_confirm
import id.homebase.resources.remove
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The composer's keyboard-area expression panel. Replaces the old [EmojiSelectorSheet]
 * call in the chat composer: a centered icon tab row over the existing emoji picker and
 * the saved-stickers tray. GIFs slot in later via [expressionTabs].
 */
@Composable
fun ExpressionSheet(
    visible: Boolean,
    conversationId: Uuid,
    onUiAction: (ConversationListUiAction) -> Unit,
    onBackSpace: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        ExpressionPanel(
            conversationId = conversationId,
            onUiAction = onUiAction,
            onBackSpace = onBackSpace,
            onEmojiSelected = onEmojiSelected,
            modifier = modifier,
        )
    }
}

private val POPOVER_WIDTH = 360.dp
private val POPOVER_HEIGHT = 440.dp
private val POPOVER_MIN_HEIGHT = 200.dp
private val POPOVER_ANCHOR_GAP = 8.dp
private val POPOVER_WINDOW_MARGIN = 8.dp

// Compose it beside the emoji button: a Popup anchors to its parent layout.
@Composable
fun ExpressionPopover(
    anchorTopInWindow: Dp,
    conversationId: Uuid,
    onUiAction: (ConversationListUiAction) -> Unit,
    onBackSpace: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val density = LocalDensity.current
    val positionProvider = remember(density) {
        AboveAnchorPositionProvider(
            gapPx = with(density) { POPOVER_ANCHOR_GAP.roundToPx() },
            marginPx = with(density) { POPOVER_WINDOW_MARGIN.roundToPx() },
        )
    }
    // Read outside the Popup: on Android its content sits in a separate window with its own insets.
    val topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val height = (anchorTopInWindow - topInset - POPOVER_ANCHOR_GAP - POPOVER_WINDOW_MARGIN)
        .coerceIn(POPOVER_MIN_HEIGHT, POPOVER_HEIGHT)

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier.width(POPOVER_WIDTH).height(height),
            shape = MaterialTheme.shapes.large,
            color = MenuDefaults.containerColor,
            tonalElevation = MenuDefaults.TonalElevation,
            shadowElevation = MenuDefaults.ShadowElevation,
        ) {
            ExpressionPanel(
                conversationId = conversationId,
                onUiAction = onUiAction,
                onBackSpace = onBackSpace,
                onEmojiSelected = onEmojiSelected,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// Start-aligned: the button is at the composer's start, so the card grows over the conversation, not the list.
private class AboveAnchorPositionProvider(
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val preferredX = when (layoutDirection) {
            LayoutDirection.Ltr -> anchorBounds.left
            LayoutDirection.Rtl -> anchorBounds.right - popupContentSize.width
        }
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val y = anchorBounds.top - gapPx - popupContentSize.height
        return IntOffset(preferredX.coerceIn(marginPx, maxX), y.coerceAtLeast(marginPx))
    }
}

@Composable
private fun ExpressionPanel(
    conversationId: Uuid,
    onUiAction: (ConversationListUiAction) -> Unit,
    onBackSpace: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ephemeral panel state (rememberSaveable with a plain enum isn't reliably saveable
    // on iOS/Desktop; the tab choice needn't survive process death).
    var selected by remember { mutableStateOf(ExpressionTab.Default) }
    val tabs = remember { expressionTabs(gifsEnabled = false) }

    Column(modifier = modifier) {
        ExpressionTabRow(tabs = tabs, selected = selected, onSelect = { selected = it })
        when (selected) {
            // weight(1f) fills the remaining panel height (the panel is floored at 300.dp
            // and the keyboard can be shorter); a fixed height would clip the picker. The
            // emoji grid scrolls internally.
            ExpressionTab.Emoji -> EmojiSelection(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                messageInputMode = true,
                onBackSpace = onBackSpace,
                onEmojiSelected = onEmojiSelected,
            )
            ExpressionTab.Stickers -> StickersTabContent(
                conversationId = conversationId,
                onUiAction = onUiAction,
            )
            ExpressionTab.Gifs -> Unit // reserved
        }
    }
}

/**
 * Centered icon tab row. The active tab is tinted with the primary color and uses the
 * filled icon variant; inactive tabs use the outlined variant. Emoji = a smiley, Stickers =
 * the Material Symbols sticker glyph, Gifs = a GIF box (reserved tab).
 */
@Composable
private fun ExpressionTabRow(
    tabs: List<ExpressionTab>,
    selected: ExpressionTab,
    onSelect: (ExpressionTab) -> Unit,
) {
    val emojiLabel = stringResource(MR.string.cd_tab_emoji)
    val stickersLabel = stringResource(MR.string.cd_tab_stickers)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        tabs.forEach { tab ->
            val isSel = tab == selected
            IconButton(onClick = { onSelect(tab) }) {
                Icon(
                    imageVector = when (tab) {
                        ExpressionTab.Emoji ->
                            if (isSel) Icons.Filled.EmojiEmotions else Icons.Outlined.EmojiEmotions
                        ExpressionTab.Stickers ->
                            if (isSel) HomebaseIcons.StickerFilled else HomebaseIcons.StickerOutlined
                        ExpressionTab.Gifs ->
                            if (isSel) Icons.Filled.GifBox else Icons.Outlined.GifBox
                    },
                    contentDescription = when (tab) {
                        ExpressionTab.Emoji -> emojiLabel
                        else -> stickersLabel
                    },
                    tint = if (isSel) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Stickers tab body: the saved-sticker grid + the import picker + the long-press
 * remove-confirm dialog. (All moved off the deleted StickerTraySheet.)
 */
@Composable
private fun StickersTabContent(
    conversationId: Uuid,
    onUiAction: (ConversationListUiAction) -> Unit,
) {
    val stickerStream: StickerStream = koinInject()
    val stickers by stickerStream.stickers.collectAsStateWithLifecycle()
    val isLoaded by stickerStream.isLoaded.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<SavedSticker?>(null) }

    LaunchedEffect(Unit) { onUiAction(ConversationListUiAction.EnsureStickerDriveMounted) }

    val importPicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        if (file != null) {
            scope.launch {
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@launch
                val contentType = detectContentTypeFromExtensionOrHint(file.name)
                onUiAction(ConversationListUiAction.CreateStickerFromImage(conversationId, bytes, contentType))
            }
        }
    }

    StickerTray(
        stickers = stickers,
        isLoaded = isLoaded,
        onStickerSelected = { sticker ->
            onUiAction(ConversationListUiAction.SendSavedSticker(conversationId, sticker))
        },
        onStickerLongPress = { sticker -> pendingDelete = sticker },
        onImportClick = { importPicker.launch() },
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
    )

    pendingDelete?.let { sticker ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = stringResource(MR.string.chat_sticker_remove)) },
            text = { Text(text = stringResource(MR.string.chat_sticker_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onUiAction(ConversationListUiAction.RemoveSticker(sticker))
                    pendingDelete = null
                }) { Text(text = stringResource(MR.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(text = stringResource(MR.string.cancel)) }
            },
        )
    }
}
