package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
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
 * Full-screen viewer for a shared-media item, laid over [content]. Reuses the chat
 * [FullScreenMediaViewer] (zoom/pan, video) and wires its Save/Share actions to the same
 * platform handlers the conversation uses. [content] wraps its tiles in [SharedMediaHero.Tile]
 * so the opened tile morphs into the viewer and back.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ChatMediaFullScreenHost(
    item: SharedMediaItem?,
    driveId: Uuid,
    title: String,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onNavigateToMessage: ((messageId: Uuid) -> Unit)? = null,
    content: @Composable (SharedMediaHero) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        SharedMediaOverlay(item, Modifier.fillMaxSize(), content) { shown, sharedTransitionScope, animatedVisibilityScope ->
            SharedMediaViewer(
                item = shown,
                driveId = driveId,
                title = title,
                snackbarHostState = snackbarHostState,
                onDismiss = onDismiss,
                onNavigateToMessage = onNavigateToMessage,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
        // The only host for [snackbarHostState]: a second one under the fading viewer would draw it twice.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedMediaOverlay(
    item: SharedMediaItem?,
    modifier: Modifier,
    content: @Composable (SharedMediaHero) -> Unit,
    viewer: @Composable (SharedMediaItem, SharedTransitionScope, AnimatedVisibilityScope) -> Unit,
) {
    val transition = updateTransition(item, label = "sharedMediaViewer")
    SharedTransitionLayout(modifier = modifier) {
        val hero = remember(transition) { SharedMediaHero(this, transition) }
        content(hero)
        transition.AnimatedVisibility(
            visible = { it != null },
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        ) {
            // Exiting, the target is already null; keep showing the item being closed.
            val shown = transition.targetState ?: transition.currentState ?: return@AnimatedVisibility
            viewer(shown, this@SharedTransitionLayout, this)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Stable
class SharedMediaHero internal constructor(
    private val sharedTransitionScope: SharedTransitionScope,
    private val transition: Transition<SharedMediaItem?>,
) {
    // Hidden while its item is open so tile and viewer swap on one transition; [modifier] keeps the slot sized.
    @Composable
    fun Tile(
        item: SharedMediaItem,
        modifier: Modifier,
        content: @Composable (SharedTransitionScope, AnimatedVisibilityScope) -> Unit,
    ) {
        Box(modifier) {
            transition.AnimatedVisibility(
                visible = { it?.fileId != item.fileId || it.payload.key != item.payload.key },
                enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
            ) {
                content(sharedTransitionScope, this)
            }
        }
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalEncodingApi::class,
)
@Composable
private fun SharedMediaViewer(
    item: SharedMediaItem,
    driveId: Uuid,
    title: String,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onNavigateToMessage: ((messageId: Uuid) -> Unit)?,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val scope = rememberCoroutineScope()
    val actionService = org.koin.compose.koinInject<ChatMessageActionService>()
    val fileSystemHandler = getUriHandler()
    val saveItem = rememberSharedMediaSaver(driveId, snackbarHostState)

    val ext = remember(item) {
        item.payload.contentType?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "bin"
    }

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
                    // Stream-decrypt straight into share_outbound (#845) —
                    // bounded RAM for any payload size; the old byte path
                    // buffered the whole payload (~2×) in memory.
                    val path = actionService.streamPayloadToShareOutbound(
                        item.fileId, item.payload.key, KeyHeader(iv, item.keyHeader.aesKey), ".$ext"
                    )
                    if (path != null) {
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
        onSave = { _, _ -> saveItem(item) },
        onDelete = { onDismiss() },
        onDismiss = onDismiss,
        onNavigateToMessage = onNavigateToMessage?.let { cb -> { cb(item.messageId) } },
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
    )
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
