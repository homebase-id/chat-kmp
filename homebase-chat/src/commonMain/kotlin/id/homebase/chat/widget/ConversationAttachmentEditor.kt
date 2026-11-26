package id.homebase.chat.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.image.SendQuality
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.ConversationListUiAction.SendFile
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.core.camera.CameraModes
import id.homebase.core.util.PlatformCameraManager
import id.homebase.core.util.rememberCameraManager
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.PickerResultLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlin.uuid.Uuid

internal class AttachmentLaunchers(
    val gallery: PickerResultLauncher,
    val file: PickerResultLauncher,
    val camera: PlatformCameraManager,
)

@Composable
internal fun rememberAttachmentLaunchers(
    conversationId: Uuid,
    onUiAction: (ConversationListUiAction) -> Unit,
): AttachmentLaunchers {
    val gallery = rememberFilePickerLauncher(
        type = FileKitType.ImageAndVideo,
        mode = FileKitMode.Multiple(),
    ) { files ->
        files?.takeIf { it.isNotEmpty() }?.let {
            onUiAction(
                ConversationListUiAction.AttachPlatformFile(
                    conversationId = conversationId,
                    files = it,
                    isImage = true,
                )
            )
        }
    }
    val file = rememberFilePickerLauncher { picked ->
        picked?.let {
            onUiAction(ConversationListUiAction.AttachPlatformFile(conversationId, listOf(it)))
        }
    }
    val camera = rememberCameraManager(
        modes = CameraModes.PhotoAndVideo,
        onOpenGallery = { gallery.launch() },
        awaitResultShown = true,
    ) { captured ->
        captured?.let {
            onUiAction(
                ConversationListUiAction.AttachPlatformFile(
                    conversationId = conversationId,
                    files = listOf(it),
                    isImage = true,
                )
            )
        }
    }
    return AttachmentLaunchers(gallery, file, camera)
}

@Composable
internal fun ConversationAttachmentEditor(
    data: FullScreenOverlay.AttachmentData,
    sendQuality: SendQuality,
    textFieldState: RichTextState,
    launchers: AttachmentLaunchers,
    revealed: Boolean,
    onUiAction: (ConversationListUiAction) -> Unit,
) {
    var currentPage by remember { mutableStateOf(0) }
    AttachmentEditorHost(
        data = data,
        sendQuality = sendQuality,
        textFieldState = textFieldState,
        revealed = revealed,
        currentPage = currentPage,
        onPageChanged = { currentPage = it },
        onAddFile = { launchers.file.launch() },
        onAddImage = { launchers.gallery.launch() },
        onCameraClick = { launchers.camera.launch() },
        onContentShown = launchers.camera::resultShown,
        onUiAction = onUiAction,
        onSend = { caption, viewOnce ->
            onUiAction(SendFile(data.conversationId, caption, data.attachments, viewOnce))
        },
    )
}
