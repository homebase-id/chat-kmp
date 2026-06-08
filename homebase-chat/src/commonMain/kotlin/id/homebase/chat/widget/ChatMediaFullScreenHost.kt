package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.conversationsettings.SharedMediaItem
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.core.localization.TranslationUtil
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.error_unknown
import id.homebase.resources.file_save_failed
import id.homebase.resources.file_saved_to
import id.homebase.resources.file_share_failed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

/**
 * Self-contained full-screen viewer for a single shared-media item. Reuses the
 * chat [FullScreenMediaViewer] (zoom/pan, video) and wires its Save/Share
 * actions to the same platform handlers the conversation uses — so any screen
 * with a [SharedMediaItem] (settings overview, media album) can open it with one
 * line. Renders nothing when [item] is null.
 */
@OptIn(
    ExperimentalSharedTransitionApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalEncodingApi::class,
)
@Composable
fun ChatMediaFullScreenHost(
    item: SharedMediaItem?,
    driveId: Uuid,
    title: String,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onNavigateToMessage: ((messageId: Uuid) -> Unit)? = null,
) {
    if (item == null) return

    val scope = rememberCoroutineScope()
    val actionService = org.koin.compose.koinInject<ChatMessageActionService>()
    val driveFileProvider = org.koin.compose.koinInject<DriveFileProvider>()
    val fileOps = org.koin.compose.koinInject<FileOperationsProvider>()
    val fileSystemHandler = getUriHandler()

    val ext = remember(item) {
        item.payload.contentType?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "bin"
    }
    val saveName = remember(item) { item.payload.filename() ?: "${item.payload.key}.$ext" }

    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
            FullScreenMediaViewer(
                data = FullScreenOverlay.ViewMessageData(
                    messageId = item.fileId,
                    title = title,
                    userDate = item.date,
                    content = "",
                    fileId = item.fileId,
                    driveId = driveId,
                    payloads = listOf(item.payload),
                    keyHeader = item.keyHeader,
                    selectedPayloadKey = item.payload.key,
                ),
                onShare = { _, _ ->
                    scope.launch {
                        try {
                            val iv = item.payload.iv?.let { Base64.decode(it) } ?: return@launch
                            val bytes = actionService.getPayloadBytes(
                                item.fileId, item.payload.key, KeyHeader(iv, item.keyHeader.aesKey)
                            )
                            if (bytes != null) {
                                val path = fileOps.writeBytesToShareOutboundFile(bytes, ".$ext")
                                fileSystemHandler.shareFile(
                                    file = Path(path),
                                    onError = { e ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                TranslationUtil.getString(
                                                    MR.string.file_share_failed,
                                                    e.message ?: TranslationUtil.getString(MR.string.error_unknown),
                                                )
                                            )
                                        }
                                    },
                                )
                            }
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar(
                                TranslationUtil.getString(
                                    MR.string.file_share_failed,
                                    e.message ?: TranslationUtil.getString(MR.string.error_unknown),
                                )
                            )
                        }
                    }
                },
                onSave = { _, _ ->
                    scope.launch {
                        try {
                            val iv = item.payload.iv?.let { Base64.decode(it) } ?: return@launch
                            val outPath = "${fileOps.getCacheDirectory()}/$saveName"
                            val ok = driveFileProvider.streamPayloadDecryptedToPath(
                                driveId = driveId,
                                fileId = item.fileId,
                                key = item.payload.key,
                                keyHeader = KeyHeader(iv, item.keyHeader.aesKey),
                                outputPath = outPath,
                                fileOps = fileOps,
                            )
                            if (ok) {
                                fileSystemHandler.saveFile(
                                    file = Path(outPath),
                                    suggestedName = saveName,
                                    onSuccess = { loc ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                TranslationUtil.getString(MR.string.file_saved_to, loc)
                                            )
                                        }
                                    },
                                    onError = { e ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                TranslationUtil.getString(
                                                    MR.string.file_save_failed,
                                                    e.message ?: TranslationUtil.getString(MR.string.error_unknown),
                                                )
                                            )
                                        }
                                    },
                                )
                            }
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar(
                                TranslationUtil.getString(
                                    MR.string.file_save_failed,
                                    e.message ?: TranslationUtil.getString(MR.string.error_unknown),
                                )
                            )
                        }
                    }
                },
                onDelete = { onDismiss() },
                onDismiss = onDismiss,
                onNavigateToMessage = onNavigateToMessage?.let { cb -> { cb(item.messageId) } },
                sharedTransitionScope = this@SharedTransitionLayout,
                animatedVisibilityScope = this@AnimatedVisibility,
            )
        }
    }
}

/**
 * Returns a "save this attachment to the device" action, wired to the same
 * decrypt-stream + platform save the chat uses. For non-viewable attachments
 * (files) where there's no full-screen viewer to host the Save button.
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
fun rememberSharedMediaSaver(
    driveId: Uuid,
    snackbarHostState: SnackbarHostState,
): (SharedMediaItem) -> Unit {
    val scope: CoroutineScope = rememberCoroutineScope()
    val driveFileProvider = org.koin.compose.koinInject<DriveFileProvider>()
    val fileOps = org.koin.compose.koinInject<FileOperationsProvider>()
    val fileSystemHandler = getUriHandler()
    return remember(driveId) {
        { item ->
            scope.launch {
                try {
                    val iv = item.payload.iv?.let { Base64.decode(it) } ?: return@launch
                    val ext = item.payload.contentType?.substringAfterLast('/')
                        ?.takeIf { it.isNotBlank() } ?: "bin"
                    val name = item.payload.filename() ?: "${item.payload.key}.$ext"
                    val outPath = "${fileOps.getCacheDirectory()}/$name"
                    val ok = driveFileProvider.streamPayloadDecryptedToPath(
                        driveId = driveId,
                        fileId = item.fileId,
                        key = item.payload.key,
                        keyHeader = KeyHeader(iv, item.keyHeader.aesKey),
                        outputPath = outPath,
                        fileOps = fileOps,
                    )
                    if (ok) {
                        fileSystemHandler.saveFile(
                            file = Path(outPath),
                            suggestedName = name,
                            onSuccess = { loc ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        TranslationUtil.getString(MR.string.file_saved_to, loc)
                                    )
                                }
                            },
                            onError = { e ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        TranslationUtil.getString(
                                            MR.string.file_save_failed,
                                            e.message ?: TranslationUtil.getString(MR.string.error_unknown),
                                        )
                                    )
                                }
                            },
                        )
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(
                        TranslationUtil.getString(
                            MR.string.file_save_failed,
                            e.message ?: TranslationUtil.getString(MR.string.error_unknown),
                        )
                    )
                }
            }
        }
    }
}
