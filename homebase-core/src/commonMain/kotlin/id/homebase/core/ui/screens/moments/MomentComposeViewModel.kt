package id.homebase.core.ui.screens.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.readImageMetadata
import id.homebase.api.video.FFmpegUtils
import id.homebase.api.video.VideoThumbnailExtractor
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.core.clipboard.platformFileFromPath
import id.homebase.core.moments.services.MediaInfo
import id.homebase.core.moments.services.MomentCreateFlowState
import id.homebase.core.util.resolveContentType
import id.homebase.imageeditor.ui.CropResultBus
import id.homebase.imageeditor.ui.DrawResultBus
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "MomentComposeViewModel"

class MomentComposeViewModel(
    private val flowState: MomentCreateFlowState,
    private val fileOperationsProvider: FileOperationsProvider,
    private val cropResultBus: CropResultBus,
    private val drawResultBus: DrawResultBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(restoreFromDraft())
    val uiState: StateFlow<MomentComposeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MomentComposeUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MomentComposeUiEvent> = _events.asSharedFlow()

    /**
     * Rebuild the compose state from any draft left behind by a previous
     * Continue → Audience hop, so back-nav from the audience picker lands the
     * user on the same media + description they were editing.
     */
    private fun restoreFromDraft(): MomentComposeUiState {
        val draft = flowState.draft.value
            ?: return MomentComposeUiState(momentInstant = Clock.System.now())
        return MomentComposeUiState(
            attachments = draft.attachments,
            description = draft.description,
            // Drafts created before this field existed will deserialize as
            // null; treat that the same as a fresh compose and default to now.
            momentInstant = draft.momentInstant ?: Clock.System.now(),
            isMomentDateUserOverride = draft.isMomentDateUserOverride,
        )
    }

    fun onAction(action: MomentComposeUiAction) {
        when (action) {
            is MomentComposeUiAction.AttachmentsAdded -> {
                _uiState.update {
                    it.copy(attachments = it.attachments + action.attachments)
                        .withRecomputedDate()
                }
                // Kick off duration + poster-frame extraction for any newly-added videos
                // so the trim scrubber can render real frames once metadata lands.
                // Also kick off EXIF extraction for images so the photo-info chip
                // can render date/camera/GPS once the read finishes.
                action.attachments.forEach { f ->
                    when (f) {
                        is AttachmentPendingFile.FileVideo ->
                            extractVideoMetadata(f.attachmentId, f.file.toString())
                        is AttachmentPendingFile.FileImage ->
                            extractImageMetadata(f.attachmentId, f.file.toString())
                        else -> Unit
                    }
                }
            }

            is MomentComposeUiAction.AttachmentRemoved ->
                _uiState.update { state ->
                    val filtered = state.attachments.filterNot { it.attachmentId == action.attachmentId }
                    val newPage = state.currentPage.coerceAtMost(maxOf(0, filtered.size - 1))
                    state.copy(attachments = filtered, currentPage = newPage)
                        .withRecomputedDate()
                }

            is MomentComposeUiAction.DescriptionChanged ->
                _uiState.update { it.copy(description = action.text) }

            is MomentComposeUiAction.PageChanged ->
                _uiState.update { it.copy(currentPage = action.page) }

            MomentComposeUiAction.NextClicked -> {
                val s = _uiState.value
                if (!s.canContinue) return
                // Persist the editor's full-fidelity state (PlatformFile,
                // trim, etc.) so back-nav from the audience screen rehydrates
                // the composer with exactly what the user was editing. The
                // audience VM converts to `AttachmentInput` at post time.
                flowState.setDraft(
                    MomentCreateFlowState.Draft(
                        attachments = s.attachments,
                        description = s.description,
                        momentInstant = s.momentInstant,
                        isMomentDateUserOverride = s.isMomentDateUserOverride,
                    )
                )
                _events.tryEmit(MomentComposeUiEvent.NavigateToAudience)
            }

            is MomentComposeUiAction.RequestCrop -> handleRequestCrop(action.attachmentId)
            is MomentComposeUiAction.RequestDraw -> handleRequestDraw(action.attachmentId)
            is MomentComposeUiAction.ApplyCropResult ->
                handleApplyImageBytes(action.attachmentId, action.croppedBytes, "cropped_image")

            is MomentComposeUiAction.ApplyDrawResult ->
                handleApplyImageBytes(action.attachmentId, action.paintedBytes, "painted_image")

            is MomentComposeUiAction.ApplyTrim -> {
                _uiState.update { state ->
                    val updated = state.attachments.map { existing ->
                        if (existing.attachmentId == action.attachmentId &&
                            existing is AttachmentPendingFile.FileVideo
                        ) {
                            existing.copy(
                                trimStartMs = action.trimStartMs,
                                trimEndMs = action.trimEndMs,
                            )
                        } else existing
                    }
                    state.copy(attachments = updated)
                }
            }

            is MomentComposeUiAction.SaveFile -> {
                val (filePath, fileName) = when (val f = action.file) {
                    is AttachmentPendingFile.FileImage -> f.file.toString() to f.file.name
                    is AttachmentPendingFile.FileVideo -> f.file.toString() to f.file.name
                    is AttachmentPendingFile.File -> f.file.toString() to f.file.name
                    is AttachmentPendingFile.Gallery -> f.image.file.toString() to f.image.fileName
                    is AttachmentPendingFile.Audio -> f.audioFile.toString() to f.audioFile.name
                }
                _events.tryEmit(MomentComposeUiEvent.SaveFileToDevice(filePath, fileName))
            }

            is MomentComposeUiAction.VideoMetadataResolved -> {
                _uiState.update { state ->
                    val updated = state.attachments.map { a ->
                        if (a is AttachmentPendingFile.FileVideo && a.attachmentId == action.attachmentId) {
                            a.copy(
                                thumbnailBytes = action.thumbnailBytes ?: a.thumbnailBytes,
                                durationMs = action.durationMs?.takeIf { it > 0 } ?: a.durationMs,
                            )
                        } else a
                    }
                    state.copy(attachments = updated)
                }
            }

            is MomentComposeUiAction.ImageMetadataResolved -> {
                _uiState.update { state ->
                    val updated = state.attachments.map { a ->
                        if (a is AttachmentPendingFile.FileImage && a.attachmentId == action.attachmentId) {
                            a.copy(metadata = action.metadata)
                        } else a
                    }
                    // Late-arriving EXIF can earlier-bound the date; recompute
                    // unless the user has already locked in an override.
                    state.copy(attachments = updated).withRecomputedDate()
                }
            }

            is MomentComposeUiAction.OverrideMomentDate -> {
                val newInstant = action.epochMillis?.let { Instant.fromEpochMilliseconds(it) }
                _uiState.update { state ->
                    if (newInstant == null) {
                        // Clear override → resume auto-derivation. Photos may have
                        // arrived since the user opened the picker, so recompute.
                        state.copy(isMomentDateUserOverride = false).withRecomputedDate()
                    } else {
                        state.copy(
                            momentInstant = newInstant,
                            isMomentDateUserOverride = true,
                        )
                    }
                }
            }

            is MomentComposeUiAction.ToggleIncludeLocation -> {
                _uiState.update { state ->
                    val updated = state.attachments.map { a ->
                        if (a is AttachmentPendingFile.FileImage && a.attachmentId == action.attachmentId) {
                            // No GPS to share → no-op. Keeps the toggle a pure
                            // state flip and avoids leaking false positives if
                            // the user races the EXIF read.
                            val md = a.metadata
                            val hasGps = md?.latitude != null && md.longitude != null
                            if (hasGps) a.copy(includeLocation = !a.includeLocation) else a
                        } else a
                    }
                    state.copy(attachments = updated)
                }
            }
        }
    }

    private fun handleRequestCrop(attachmentId: Uuid) {
        viewModelScope.launch {
            try {
                val attachment = _uiState.value.attachments.firstOrNull { it.attachmentId == attachmentId }
                val sourcePath = when (attachment) {
                    is AttachmentPendingFile.FileImage -> attachment.file.toString()
                    is AttachmentPendingFile.Gallery -> attachment.image.file.toString()
                    else -> null
                }
                if (sourcePath == null) {
                    _events.tryEmit(MomentComposeUiEvent.ShowError("Cannot crop this attachment"))
                    return@launch
                }
                val bytes = fileOperationsProvider.readFileBytes(sourcePath)
                val requestId = Uuid.random()
                cropResultBus.postSource(requestId, bytes)

                viewModelScope.launch {
                    cropResultBus.resultsFor(requestId).collect { result ->
                        onAction(MomentComposeUiAction.ApplyCropResult(attachmentId, result.bytes))
                    }
                }

                _events.tryEmit(MomentComposeUiEvent.NavigateToCropper(requestId))
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "RequestCrop failed: ${e.message}" }
                _events.tryEmit(MomentComposeUiEvent.ShowError("Failed to open cropper: ${e.message}"))
            }
        }
    }

    private fun handleRequestDraw(attachmentId: Uuid) {
        viewModelScope.launch {
            try {
                val attachment = _uiState.value.attachments.firstOrNull { it.attachmentId == attachmentId }
                val sourcePath = when (attachment) {
                    is AttachmentPendingFile.FileImage -> attachment.file.toString()
                    is AttachmentPendingFile.Gallery -> attachment.image.file.toString()
                    else -> null
                }
                if (sourcePath == null) {
                    _events.tryEmit(MomentComposeUiEvent.ShowError("Cannot draw on this attachment"))
                    return@launch
                }
                val bytes = fileOperationsProvider.readFileBytes(sourcePath)
                val requestId = Uuid.random()
                drawResultBus.postSource(requestId, bytes)

                viewModelScope.launch {
                    drawResultBus.resultsFor(requestId).collect { result ->
                        onAction(MomentComposeUiAction.ApplyDrawResult(attachmentId, result.bytes))
                    }
                }

                _events.tryEmit(MomentComposeUiEvent.NavigateToDrawer(requestId))
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "RequestDraw failed: ${e.message}" }
                _events.tryEmit(MomentComposeUiEvent.ShowError("Failed to open draw editor: ${e.message}"))
            }
        }
    }

    private fun handleApplyImageBytes(attachmentId: Uuid, bytes: ByteArray, prefix: String) {
        viewModelScope.launch {
            try {
                val tempPath = fileOperationsProvider.writeBytesToTempFile(bytes, prefix, ".jpg")
                val newFile = AttachmentPendingFile.FileImage(
                    id = attachmentId,
                    file = platformFileFromPath(tempPath),
                )
                _uiState.update { state ->
                    val updated = state.attachments.map { existing ->
                        if (existing.attachmentId == attachmentId) newFile else existing
                    }
                    state.copy(attachments = updated)
                }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Apply $prefix failed: ${e.message}" }
                _events.tryEmit(MomentComposeUiEvent.ShowError("Failed to apply edit: ${e.message}"))
            }
        }
    }

    private fun extractImageMetadata(attachmentId: Uuid, imagePath: String) {
        viewModelScope.launch {
            val metadata = runCatching {
                val bytes = fileOperationsProvider.readFileBytes(imagePath)
                readImageMetadata(bytes)
            }.getOrElse { e ->
                Logger.w(throwable = e, tag = TAG) { "EXIF read failed for $attachmentId" }
                null
            }
            // Guard: user might have removed the attachment mid-read.
            if (_uiState.value.attachments.none { it.attachmentId == attachmentId }) return@launch
            onAction(MomentComposeUiAction.ImageMetadataResolved(attachmentId, metadata))
        }
    }

    /**
     * Recompute [MomentComposeUiState.momentInstant] from the current
     * attachments, unless the user has already locked in an override. The
     * default is "today" — only an EXIF-tagged photo can pull the date back
     * in time. So a moment with three screenshots stays at today; adding one
     * vacation photo with EXIF flips it to that capture date.
     */
    private fun MomentComposeUiState.withRecomputedDate(): MomentComposeUiState {
        if (isMomentDateUserOverride) return this
        return copy(momentInstant = deriveMomentInstant(attachments) ?: Clock.System.now())
    }

    private fun extractVideoMetadata(attachmentId: Uuid, videoPath: String) {
        val deferredBytes = viewModelScope.async {
            runCatching { VideoThumbnailExtractor.extractPosterFrame(videoPath) }.getOrNull()
        }
        val deferredDuration = viewModelScope.async {
            runCatching { FFmpegUtils.getDurationMs(videoPath) }.getOrNull()
        }
        viewModelScope.launch {
            val bytes = runCatching { deferredBytes.await() }.getOrNull()
            val durationMs = runCatching { deferredDuration.await() }.getOrNull()
            if (bytes == null && durationMs == null) return@launch
            // Guard: user might have removed the attachment while extraction was in flight.
            if (_uiState.value.attachments.none { it.attachmentId == attachmentId }) return@launch
            onAction(
                MomentComposeUiAction.VideoMetadataResolved(
                    attachmentId = attachmentId,
                    durationMs = durationMs,
                    thumbnailBytes = bytes,
                )
            )
        }
    }
}

/**
 * Earliest capture time across the photos that have EXIF. Photos without
 * a `capturedAt` are skipped entirely — a screenshot mixed in with three
 * vacation photos shouldn't yank the date forward to "now". Returns null
 * when no photo has a usable date, in which case the sender falls back to
 * `now()` at post time.
 *
 * If a photo has `captureUtcOffset` (newer iPhones / DSLRs write
 * `OffsetTimeOriginal`), we honor it. Otherwise we treat the wall-clock
 * value as device-local — wrong-by-a-few-hours for photos taken abroad,
 * but the user can override via the date chip.
 */
internal fun deriveMomentInstant(attachments: List<AttachmentPendingFile>): Instant? {
    val deviceTz = TimeZone.currentSystemDefault()
    return attachments
        .filterIsInstance<AttachmentPendingFile.FileImage>()
        .mapNotNull { att ->
            val md = att.metadata ?: return@mapNotNull null
            val captured = md.capturedAt ?: return@mapNotNull null
            val offset = md.captureUtcOffset
            if (offset != null) captured.toInstant(offset)
            else captured.toInstant(deviceTz)
        }
        .minOrNull()
}

/**
 * Convert the editor's per-attachment model into the post pipeline's
 * `AttachmentInput`. Mirrors the chat send path's mapping (see
 * `ConversationListViewModel.handleSendFile`) minus the HEIC→JPEG
 * pre-compression — moments' post sender does its own normalization.
 */
internal fun AttachmentPendingFile.toAttachmentInput(): AttachmentInput = when (this) {
    is AttachmentPendingFile.File -> AttachmentInput(
        filePath = file.toString(),
        contentType = resolveContentType(file.name, file.mimeType()?.toString()),
        displayName = file.name,
    )

    is AttachmentPendingFile.FileImage -> AttachmentInput(
        filePath = file.toString(),
        contentType = resolveContentType(file.name, file.mimeType()?.toString()),
        displayName = file.name,
    )

    is AttachmentPendingFile.FileVideo -> AttachmentInput(
        filePath = file.toString(),
        contentType = resolveContentType(file.name, file.mimeType()?.toString()),
        displayName = file.name,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
    )

    is AttachmentPendingFile.Gallery -> AttachmentInput(
        filePath = image.file.toString(),
        contentType = resolveContentType(image.fileName),
        displayName = image.fileName,
    )

    is AttachmentPendingFile.Audio -> AttachmentInput(
        filePath = audioFile.toString(),
        contentType = resolveContentType(audioFile.name, audioFile.mimeType()?.toString()),
        displayName = audioFile.name,
        waveformFile = waveformFile?.toString(),
        audioLengthSeconds = lengthSeconds,
    )
}

/**
 * Convert the editor's per-attachment model into the per-payload MediaInfo
 * sidecar. Only FileImage with extracted EXIF produces a value; GPS fields are
 * gated by the per-image opt-in. Returns null if the attachment isn't an
 * image, has no metadata, or has only fields that would all be null after
 * gating — saves bytes on the wire.
 */
internal fun AttachmentPendingFile.toMediaInfo(): MediaInfo? {
    if (this !is AttachmentPendingFile.FileImage) return null
    val md = metadata ?: return null

    val capturedAt = md.capturedAt?.toString()
    val offset = md.captureUtcOffset?.totalSeconds
    val lat = if (includeLocation) md.latitude else null
    val lon = if (includeLocation) md.longitude else null
    val alt = if (includeLocation) md.altitudeMeters else null

    val info = MediaInfo(
        capturedAtLocal = capturedAt,
        captureUtcOffsetSeconds = offset,
        latitude = lat,
        longitude = lon,
        altitudeMeters = alt,
        cameraMake = md.cameraMake,
        cameraModel = md.cameraModel,
        pixelWidth = md.pixelWidth,
        pixelHeight = md.pixelHeight,
    )
    // All-null after gating is equivalent to no metadata — drop it.
    val anyKnown = listOfNotNull(
        capturedAt, offset, lat, lon, alt,
        md.cameraMake, md.cameraModel, md.pixelWidth, md.pixelHeight,
    ).isNotEmpty()
    return if (anyKnown) info else null
}
