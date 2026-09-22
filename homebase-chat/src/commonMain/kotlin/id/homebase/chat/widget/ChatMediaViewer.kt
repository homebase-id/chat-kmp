package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.ConversationListUiAction.CloseFullScreenOverlay
import id.homebase.chat.conversationlist.ConversationListUiAction.DeleteMessage
import id.homebase.chat.conversationlist.ConversationListUiAction.DownloadMedia
import id.homebase.chat.conversationlist.ConversationListUiAction.DownloadVideoMedia
import id.homebase.chat.conversationlist.ConversationListUiAction.ShareMedia
import id.homebase.chat.conversationlist.DecryptedFileKey
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.conversationlist.MessageListUiState

/**
 * Rendered either inside the messages pane (single-pane, where the shared-element scopes come
 * from the pane's own AnimatedContent) or lifted above the list/detail scaffold (two-pane, where
 * there is no bubble to pair with and the scopes only drive a fade).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatMediaViewer(
    data: FullScreenOverlay.MediaViewer,
    uiState: MessageListUiState,
    onUiAction: (ConversationListUiAction) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    when (data) {
        is FullScreenOverlay.ViewMessageData -> FullScreenMediaViewer(
            data = data,
            isDownloading = "${data.messageId}_${data.selectedPayloadKey}" in uiState.downloadingFiles,
            onShare = { id, key -> onUiAction(ShareMedia(id, key)) },
            onSave = { message, key -> onUiAction(DownloadMedia(message, key)) },
            onSaveSticker = { message, key ->
                onUiAction(ConversationListUiAction.SaveStickerFromMessage(message, key))
            },
            onDelete = { onUiAction(DeleteMessage(it)) },
            onDismiss = { onUiAction(CloseFullScreenOverlay) },
            animatedVisibilityScope = animatedVisibilityScope,
            sharedTransitionScope = sharedTransitionScope,
        )

        is FullScreenOverlay.VideoPlayerData -> FullScreenVideoPlayer(
            data = data,
            isDownloading = "${data.fileId}_${data.payloadKey}" in uiState.downloadingFiles,
            onDismiss = { onUiAction(CloseFullScreenOverlay) },
            onSave = {
                onUiAction(
                    DownloadVideoMedia(data.fileId, data.payloadKey, data.keyHeader, data.payload)
                )
            },
            uploadStatus = data.uploadMessageId?.let { uiState.uploadProgress[it] },
        )

        is FullScreenOverlay.PdfViewerData -> ChatPdfViewer(
            title = data.title,
            userDate = data.userDate,
            filePath = uiState.decryptedFiles[DecryptedFileKey(data.fileId, data.payloadKey)],
            isDownloading = "${data.messageId}_${data.payloadKey}" in uiState.downloadingFiles,
            onDownload = { onUiAction(DownloadMedia(data.messageId, data.payloadKey)) },
            onDismiss = { onUiAction(CloseFullScreenOverlay) },
        )
    }
}
