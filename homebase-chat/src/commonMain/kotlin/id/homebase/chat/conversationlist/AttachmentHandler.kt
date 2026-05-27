package id.homebase.chat.conversationlist

import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.video.VideoThumbnailService
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowErrorMessage
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowInfoMessage
import id.homebase.core.audio.AudioFileInfo
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.audio.AudioWaveFormGenerator
import id.homebase.core.clipboard.platformFileFromPath
import id.homebase.core.localization.TranslationUtil
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import id.homebase.imageeditor.ui.CropResultBus
import id.homebase.imageeditor.ui.DrawResultBus
import id.homebase.resources.MR
import id.homebase.resources.chat_attach_file_failed
import id.homebase.resources.chat_message_audio_recording_help
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Handles attachment-editor and audio-recording action arms (attach / unattach
 * platform files, gallery items, clipboard images; crop / draw / trim editors;
 * record / stop / cancel audio) extracted from `ConversationListViewModel.onAction`.
 *
 * Owns the in-flight `pendingThumbnails` map for video poster extraction so the
 * editor can open instantly (Signal-style) while FFmpeg / MediaMetadataRetriever
 * runs in the background. The send path (still in the VM, via
 * `addMessageWithFiles`) calls back into [ensureThumbnail] so the message envelope
 * still ships a poster frame.
 *
 * Behavior is byte-identical to the previous in-VM implementation; the move is
 * purely structural — see PR 4 in `lucky-chasing-valley.md` for context.
 */
internal class AttachmentHandler(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ConversationListUiState>,
    private val messagesUiState: MutableStateFlow<MessageListUiState>,
    private val fileOperationsProvider: FileOperationsProvider,
    private val cropResultBus: CropResultBus,
    private val drawResultBus: DrawResultBus,
    private val audioRecorder: AudioRecorder,
    private val audioWaveFormGenerator: AudioWaveFormGenerator,
    private val sendEvent: (ConversationListUiEvent) -> Unit,
    private val dispatch: (ConversationListUiAction) -> Unit,
    private val addMessageWithFiles: (conversationId: Uuid, content: String, files: List<AttachmentPendingFile>) -> Unit,
) {

    // Tracks in-flight video thumbnail extraction per pending attachment so the editor can
    // open instantly (Signal-style) while the FFmpeg/MediaMetadataRetriever poster work
    // happens in the background. The send path awaits these so the message envelope still
    // ships a poster frame.
    private val pendingThumbnails = mutableMapOf<Uuid, Deferred<ByteArray?>>()

    internal fun extractThumbnailAsync(attachmentId: Uuid, videoPath: String) {
        val deferred = scope.async {
            runCatching { VideoThumbnailService.extractPosterFrame(videoPath) }.getOrNull()
        }
        pendingThumbnails[attachmentId] = deferred
        // Duration is needed by the trim screen and is cheap to read; kick it off in
        // parallel with the poster extraction.
        val durationDeferred = scope.async {
            runCatching { id.homebase.api.video.FFmpegUtils.getDurationMs(videoPath) }
                .getOrNull()
        }
        scope.launch {
            val bytes = try {
                deferred.await()
            } catch (_: CancellationException) {
                null
            }
            val durationMs = try {
                durationDeferred.await()
            } catch (_: CancellationException) {
                null
            }
            pendingThumbnails.remove(attachmentId)
            if (bytes == null && durationMs == null) return@launch
            messagesUiState.update { state ->
                val overlay = state.fullScreenOverlay as? FullScreenOverlay.AttachmentData
                    ?: return@update state
                if (overlay.attachments.none { it.attachmentId == attachmentId }) return@update state
                val updated = overlay.attachments.map { a ->
                    if (a is AttachmentPendingFile.FileVideo && a.attachmentId == attachmentId) {
                        a.copy(
                            thumbnailBytes = bytes ?: a.thumbnailBytes,
                            durationMs = durationMs?.takeIf { it > 0 } ?: a.durationMs,
                        )
                    } else a
                }
                state.copy(fullScreenOverlay = overlay.copy(attachments = updated))
            }
        }
    }

    internal suspend fun ensureThumbnail(file: AttachmentPendingFile.FileVideo): AttachmentPendingFile.FileVideo {
        if (file.thumbnailBytes != null) return file
        val pending = pendingThumbnails.remove(file.attachmentId) ?: return file
        val bytes = runCatching { pending.await() }.getOrNull()
        return if (bytes != null) file.copy(thumbnailBytes = bytes) else file
    }

    fun handleAttachPlatformFile(action: ConversationListUiAction.AttachPlatformFile) {
        scope.launch {
            try {
                val newFiles = action.files.map {
                    val ct = it.mimeType()?.toString()
                        ?: detectContentTypeFromExtensionOrHint(it.name)
                    when {
                        ct.startsWith("video/") -> AttachmentPendingFile.FileVideo(
                            Uuid.generateV7(),
                            it,
                            thumbnailBytes = null,
                        )

                        action.isImage || ct.startsWith("image/") -> AttachmentPendingFile.FileImage(
                            Uuid.generateV7(),
                            it
                        )

                        else -> AttachmentPendingFile.File(Uuid.generateV7(), it)
                    }
                }
                val conversation = uiState.value.activeConversations.find {
                    it.conversation.id == action.conversationId
                }
                if (newFiles.isEmpty() || conversation == null) return@launch

                val overlay = messagesUiState.value.fullScreenOverlay
                val newOverlay = if (overlay is FullScreenOverlay.AttachmentData) {
                    overlay.copy(
                        attachments = overlay.attachments + newFiles,
                    )
                } else {
                    FullScreenOverlay.AttachmentData(
                        conversationTitle = conversation.getDisplayName(),
                        conversationId = action.conversationId,
                        selected = newFiles.last().attachmentId,
                        attachments = newFiles,
                    )
                }

                messagesUiState.update {
                    it.copy(
                        fullScreenOverlay = newOverlay,
                    )
                }

                // Editor is now visible — extract thumbnails in the background and
                // patch the pending FileVideo entries when they complete.
                newFiles.forEach { f ->
                    if (f is AttachmentPendingFile.FileVideo) {
                        // A web-picked PlatformFile has no path, so materialize its bytes into
                        // okio first and hand the extractor that readable path (native actuals
                        // return toString() unchanged — no copy). Best-effort: a failure here
                        // must not abort the attach, it just leaves the poster blank.
                        // Plain try/catch rather than runCatching — the latter triggers a
                        // Kotlin/Native link-time `Lowering ReturnsInsertion: phases [Autobox]
                        // required, but not satisfied` compiler bug on iOS when its inline lambda
                        // wraps a suspend call. Re-throw CancellationException so the surrounding
                        // coroutine still cancels cleanly.
                        val path = try {
                            f.file.toUploadPath(fileOperationsProvider)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            null
                        }
                        if (path != null) extractThumbnailAsync(f.attachmentId, path)
                    }
                }
            } catch (e: Exception) {
                Logger.e("Failed to attach file(s)", e)
                sendEvent(
                    ShowErrorMessage(
                        TranslationUtil.getString(
                            MR.string.chat_attach_file_failed,
                            e.message ?: ""
                        )
                    )
                )
            }
        }
    }

    fun handleAttachGalleryItem(action: ConversationListUiAction.AttachGalleryItem) {
        scope.launch {
            try {
                val newFiles = action.files.map {
                    if (it.mimeType.startsWith("video/")) {
                        AttachmentPendingFile.FileVideo(
                            Uuid.generateV7(),
                            it.file,
                            thumbnailBytes = null,
                        )
                    } else {
                        AttachmentPendingFile.Gallery(Uuid.generateV7(), it)
                    }
                }
                val conversation = uiState.value.activeConversations.find {
                    it.conversation.id == action.conversationId
                }
                if (newFiles.isEmpty() || conversation == null) return@launch

                val overlay = messagesUiState.value.fullScreenOverlay
                val newOverlay = if (overlay is FullScreenOverlay.AttachmentData) {
                    overlay.copy(
                        attachments = overlay.attachments + newFiles,
                    )
                } else {
                    FullScreenOverlay.AttachmentData(
                        conversationTitle = conversation.getDisplayName(),
                        conversationId = action.conversationId,
                        selected = newFiles.last().attachmentId,
                        attachments = newFiles,
                    )
                }

                messagesUiState.update {
                    it.copy(
                        fullScreenOverlay = newOverlay,
                    )
                }

                // Editor visible — kick off thumbnail extraction in parallel. As in
                // handleAttachPlatformFile, materialize web-picked bytes into okio first so the
                // extractor can read them (native: toString() unchanged, no copy). See
                // handleAttachPlatformFile for why this isn't runCatching (K/N link-time bug).
                newFiles.zip(action.files).forEach { (pending, gallery) ->
                    if (pending is AttachmentPendingFile.FileVideo) {
                        val path = try {
                            gallery.file.toUploadPath(fileOperationsProvider)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            null
                        }
                        if (path != null) extractThumbnailAsync(pending.attachmentId, path)
                    }
                }
            } catch (e: Exception) {
                Logger.e("Failed to attach file(s)", e)
                sendEvent(
                    ShowErrorMessage(
                        TranslationUtil.getString(
                            MR.string.chat_attach_file_failed,
                            e.message ?: ""
                        )
                    )
                )
            }
        }
    }

    fun handleUnAttachFile(action: ConversationListUiAction.UnAttachFile) {
        scope.launch {
            try {
                val fullScreenOverlay = messagesUiState.value.fullScreenOverlay
                if (fullScreenOverlay == null || fullScreenOverlay !is FullScreenOverlay.AttachmentData) return@launch

                val newFiles = fullScreenOverlay.attachments.filter {
                    it.attachmentId != action.id
                }
                messagesUiState.update {
                    it.copy(
                        fullScreenOverlay = fullScreenOverlay.copy(attachments = newFiles),
                    )
                }
            } catch (e: Exception) {
                Logger.e("Failed to unattach file", e)
                sendEvent(
                    ShowErrorMessage(
                        "Failed to unattach file: ${e.message}"
                    )
                )
            }
        }
    }

    fun handleAttachClipboardImage(action: ConversationListUiAction.AttachClipboardImage) {
        scope.launch {
            try {
                val tempPath = fileOperationsProvider.writeBytesToTempFile(
                    action.imageBytes,
                    "clipboard_image",
                    ".png"
                )
                val platformFile = platformFileFromPath(tempPath)
                val newFile = AttachmentPendingFile.FileImage(
                    Uuid.generateV7(),
                    platformFile
                )
                val conversation = uiState.value.activeConversations.find {
                    it.conversation.id == action.conversationId
                }
                if (conversation == null) return@launch

                val overlay = messagesUiState.value.fullScreenOverlay
                val newOverlay = if (overlay is FullScreenOverlay.AttachmentData) {
                    overlay.copy(
                        attachments = overlay.attachments + newFile,
                    )
                } else {
                    FullScreenOverlay.AttachmentData(
                        conversationTitle = conversation.getDisplayName(),
                        conversationId = action.conversationId,
                        selected = newFile.attachmentId,
                        attachments = listOf(newFile),
                    )
                }

                messagesUiState.update {
                    it.copy(fullScreenOverlay = newOverlay)
                }
            } catch (e: Exception) {
                Logger.e("Failed to attach clipboard image", e)
                sendEvent(ShowErrorMessage("Failed to paste image: ${e.message}"))
            }
        }
    }

    /* Crop attachment */
    fun handleRequestCropAttachment(action: ConversationListUiAction.RequestCropAttachment) {
        scope.launch {
            try {
                val overlay = messagesUiState.value.fullScreenOverlay as? FullScreenOverlay.AttachmentData
                val attachment = overlay?.attachments?.firstOrNull {
                    it.attachmentId == action.attachmentId
                }
                val sourcePath = when (attachment) {
                    is AttachmentPendingFile.FileImage -> attachment.file.toString()
                    is AttachmentPendingFile.Gallery -> attachment.image.file.toString()
                    else -> null
                }
                if (sourcePath == null) {
                    sendEvent(ShowErrorMessage("Cannot crop this attachment"))
                    return@launch
                }
                val bytes = fileOperationsProvider.readFileBytes(sourcePath)
                val requestId = Uuid.random()
                cropResultBus.postSource(requestId, bytes)

                scope.launch {
                    cropResultBus.resultsFor(requestId).collect { result ->
                        dispatch(
                            ConversationListUiAction.ApplyCropResult(
                                action.conversationId,
                                action.attachmentId,
                                result.bytes,
                            )
                        )
                    }
                }

                sendEvent(ConversationListUiEvent.NavigateToCropper(requestId))
            } catch (e: Exception) {
                Logger.e("RequestCropAttachment failed", e)
                sendEvent(ShowErrorMessage("Failed to open cropper: ${e.message}"))
            }
        }
    }

    fun handleApplyCropResult(action: ConversationListUiAction.ApplyCropResult) {
        scope.launch {
            try {
                val tempPath = fileOperationsProvider.writeBytesToTempFile(
                    action.croppedBytes,
                    "cropped_image",
                    ".jpg",
                )
                val newFile = AttachmentPendingFile.FileImage(
                    id = action.attachmentId,
                    file = id.homebase.core.clipboard.platformFileFromPath(tempPath),
                )
                val overlay = messagesUiState.value.fullScreenOverlay
                if (overlay !is FullScreenOverlay.AttachmentData) return@launch
                val newAttachments = overlay.attachments.map { existing ->
                    if (existing.attachmentId == action.attachmentId) newFile else existing
                }
                messagesUiState.update {
                    it.copy(fullScreenOverlay = overlay.copy(attachments = newAttachments))
                }
            } catch (e: Exception) {
                Logger.e("ApplyCropResult failed", e)
                sendEvent(ShowErrorMessage("Failed to apply crop: ${e.message}"))
            }
        }
    }

    /* Draw on attachment — same shape as crop, different result bus. */
    fun handleRequestDrawAttachment(action: ConversationListUiAction.RequestDrawAttachment) {
        scope.launch {
            try {
                val overlay = messagesUiState.value.fullScreenOverlay as? FullScreenOverlay.AttachmentData
                val attachment = overlay?.attachments?.firstOrNull {
                    it.attachmentId == action.attachmentId
                }
                val sourcePath = when (attachment) {
                    is AttachmentPendingFile.FileImage -> attachment.file.toString()
                    is AttachmentPendingFile.Gallery -> attachment.image.file.toString()
                    else -> null
                }
                if (sourcePath == null) {
                    sendEvent(ShowErrorMessage("Cannot draw on this attachment"))
                    return@launch
                }
                val bytes = fileOperationsProvider.readFileBytes(sourcePath)
                val requestId = Uuid.random()
                drawResultBus.postSource(requestId, bytes)

                scope.launch {
                    drawResultBus.resultsFor(requestId).collect { result ->
                        dispatch(
                            ConversationListUiAction.ApplyDrawResult(
                                action.conversationId,
                                action.attachmentId,
                                result.bytes,
                            )
                        )
                    }
                }

                sendEvent(ConversationListUiEvent.NavigateToDrawer(requestId))
            } catch (e: Exception) {
                Logger.e("RequestDrawAttachment failed", e)
                sendEvent(ShowErrorMessage("Failed to open draw editor: ${e.message}"))
            }
        }
    }

    fun handleApplyDrawResult(action: ConversationListUiAction.ApplyDrawResult) {
        scope.launch {
            try {
                val tempPath = fileOperationsProvider.writeBytesToTempFile(
                    action.paintedBytes,
                    "painted_image",
                    ".jpg",
                )
                val newFile = AttachmentPendingFile.FileImage(
                    id = action.attachmentId,
                    file = id.homebase.core.clipboard.platformFileFromPath(tempPath),
                )
                val overlay = messagesUiState.value.fullScreenOverlay
                if (overlay !is FullScreenOverlay.AttachmentData) return@launch
                val newAttachments = overlay.attachments.map { existing ->
                    if (existing.attachmentId == action.attachmentId) newFile else existing
                }
                messagesUiState.update {
                    it.copy(fullScreenOverlay = overlay.copy(attachments = newAttachments))
                }
            } catch (e: Exception) {
                Logger.e("ApplyDrawResult failed", e)
                sendEvent(ShowErrorMessage("Failed to apply drawing: ${e.message}"))
            }
        }
    }

    /* Inline trim scrubber result. */
    fun handleApplyTrimResult(action: ConversationListUiAction.ApplyTrimResult) {
        val overlay = messagesUiState.value.fullScreenOverlay
        if (overlay !is FullScreenOverlay.AttachmentData) return
        val newAttachments = overlay.attachments.map { existing ->
            if (existing.attachmentId == action.attachmentId &&
                existing is AttachmentPendingFile.FileVideo
            ) {
                existing.copy(
                    trimStartMs = action.trimStartMs,
                    trimEndMs = action.trimEndMs,
                )
            } else existing
        }
        messagesUiState.update {
            it.copy(fullScreenOverlay = overlay.copy(attachments = newAttachments))
        }
    }

    /* Audio recording */
    fun handleShowRecordingHelp() {
        sendEvent(ShowInfoMessage(MR.string.chat_message_audio_recording_help))
    }

    fun handleStartRecording(action: ConversationListUiAction.StartRecording) {
        scope.launch {
            try {
                val file = newRecordingFile(
                    "recording-${Uuid.random()}.${audioRecorder.getAudioFileExtension()}"
                )
                audioRecorder.startRecording(file.toString())
                messagesUiState.update {
                    it.copy(
                        recordingData = RecordingData(
                            file = file,
                            conversationId = action.conversationId
                        )
                    )
                }
            } catch (e: Exception) {
                Logger.e("Failed to start recording", e)
                sendEvent(ShowErrorMessage("Failed to start recording: $e"))
            }
        }
    }

    fun handleStopRecording(action: ConversationListUiAction.StopRecording) {
        scope.launch {
            try {
                val recordingData = messagesUiState.value.recordingData
                messagesUiState.update {
                    it.copy(recordingData = recordingData?.copy(isProcessing = true))
                }

                audioRecorder.stopRecording()
                recordingData?.let { recordingData ->
                    var waveFormImageFile: PlatformFile? = null
                    var audioInfo: AudioFileInfo? = null
                    try {
                        audioInfo =
                            audioWaveFormGenerator.generateWaveForm(recordingData.file)
                        val waveFormImageBytes = audioWaveFormGenerator.saveWaveformToPng(
                            audioInfo.waveForm,
                            1000,
                            200
                        )
                        waveFormImageFile = newWaveformCacheFile(
                            "waveform-${Uuid.generateV4()}.png"
                        )
                        waveFormImageFile.writeBytesCompat(waveFormImageBytes)
                    } catch (e: Exception) {
                        Logger.e("Failed to generate waveform", e)
                    }

                    addMessageWithFiles(
                        recordingData.conversationId,
                        "",
                        listOf(
                            AttachmentPendingFile.Audio(
                                id = Uuid.random(),
                                audioFile = recordingData.file,
                                waveformFile = waveFormImageFile,
                                lengthSeconds = audioInfo?.getDuration()?.inWholeSeconds?.toInt()
                                    ?: 0
                            )
                        ),
                    )
                }
            } catch (e: Exception) {
                Logger.e("Failed to send recording", e)
                sendEvent(ShowErrorMessage("Failed to send recording: ${e.message}"))
            }
            messagesUiState.update { it.copy(recordingData = null) }
        }
    }

    fun handleCancelRecording() {
        scope.launch {
            try {
                audioRecorder.stopRecording()
                messagesUiState.value.recordingData?.file?.deleteCompat(mustExist = false)
            } catch (_: Exception) {
                // ignore
            }
            messagesUiState.update { it.copy(recordingData = null) }
        }
    }
}
