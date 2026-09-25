package id.homebase.core.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.uploadTempDirectory
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.TimeSource

@Composable
actual fun rememberCameraEngine(recordsVideo: Boolean, warm: CameraEngine?): CameraEngine {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val fileOps = koinInject<FileOperationsProvider>()
    val engine = remember(context, lifecycleOwner, warm) {
        warm as? AndroidCameraEngine ?: AndroidCameraEngine(context, lifecycleOwner, File(fileOps.uploadTempDirectory()))
    }
    DisposableEffect(engine) {
        engine.start()
        onDispose { engine.release() }
    }
    return engine
}

@Composable
actual fun rememberCameraWarmer(): CameraWarmer {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val fileOps = koinInject<FileOperationsProvider>()
    return remember(context, lifecycleOwner, fileOps) {
        CameraWarmer { _ ->
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                AndroidCameraEngine(context, lifecycleOwner, File(fileOps.uploadTempDirectory())).also { it.start() }
            } else {
                // Still worth starting: the provider's first init is most of a cold open, and it outlives the prompt.
                ProcessCameraProvider.getInstance(context)
                null
            }
        }
    }
}

/** Main-thread only: CameraX binding, LiveData observers and the capture callbacks all run on main. */
internal class AndroidCameraEngine(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val outputDir: File,
) : CameraEngine {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainExecutor = ContextCompat.getMainExecutor(this.context)

    private val _uiState = MutableStateFlow(CameraUiState())
    override val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _errors = MutableSharedFlow<CameraError>(extraBufferCapacity = 8)
    override val errors: SharedFlow<CameraError> = _errors.asSharedFlow()

    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest: StateFlow<SurfaceRequest?> = _surfaceRequest.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var boundUseCases: List<UseCase> = emptyList()
    private var anyCameraIsLegacy = true

    private var recording: Recording? = null
    private var recordingResult: CompletableDeferred<PlatformFile?>? = null

    private var captureRotation = QuarterTurn.R0
    private var displayRotation = 0
    private var zoomAnimation: Job? = null
    private var focusClear: Job? = null
    private var started = false
    private var released = false
    private var previewGeneration = 0
    // Open-latency breadcrumbs, from start() to the first preview frame.
    private var openedAt: TimeSource.Monotonic.ValueTimeMark? = null

    private fun logOpenStep(step: String) {
        val mark = openedAt ?: return
        Logger.d(tag = TAG) { "open: $step at ${mark.elapsedNow().inWholeMilliseconds} ms" }
    }

    private var observedZoom: LiveData<ZoomState>? = null
    private val zoomObserver = Observer<ZoomState> { zoom ->
        _uiState.update { it.copy(minZoom = zoom.minZoomRatio, maxZoom = zoom.maxZoomRatio) }
    }

    private var observedCameraState: LiveData<CameraState>? = null
    private var lastCameraErrorCode: Int? = null
    private val cameraStateObserver = Observer<CameraState> { state ->
        val code = state.error?.code
        if (code != null && code != lastCameraErrorCode) {
            Logger.w(tag = TAG) { "Camera state ${state.type} error=$code" }
            _errors.tryEmit(
                when (code) {
                    CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> CameraError.CameraInUse
                    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> CameraError.Interrupted
                    else -> CameraError.BindFailed
                }
            )
        }
        lastCameraErrorCode = code
        _uiState.update { it.copy(isBound = state.type == CameraState.Type.OPEN) }
    }

    private val displayManager = this.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onDisplayRotationChanged()
        }
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    fun start() {
        if (started) return
        started = true
        openedAt = TimeSource.Monotonic.markNow()
        displayRotation = currentDisplayRotation()
        displayManager.registerDisplayListener(displayListener, null)
        scope.launch {
            val cameraProvider = try {
                ProcessCameraProvider.awaitInstance(context)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(tag = TAG, throwable = e) { "ProcessCameraProvider unavailable" }
                _uiState.update { it.copy(isAvailable = false) }
                _errors.tryEmit(CameraError.NoCamera)
                return@launch
            }
            if (released) return@launch
            logOpenStep("provider ready")
            provider = cameraProvider
            // Only the first open in a process hops to IO: the hop hands main to the dialog's first frame, which then
            // runs ahead of the bind, and the camera can't open under that frame.
            anyCameraIsLegacy = CameraCapability.knownAnyCameraIsLegacy
                ?: withContext(Dispatchers.IO) { CameraCapability.anyCameraIsLegacy(context) }
            val hasBack = cameraProvider.hasCameraSafe(CameraSelector.DEFAULT_BACK_CAMERA)
            val hasFront = cameraProvider.hasCameraSafe(CameraSelector.DEFAULT_FRONT_CAMERA)
            if (!hasBack && !hasFront) {
                _uiState.update { it.copy(isAvailable = false, hasBackLens = false, hasFrontLens = false) }
                _errors.tryEmit(CameraError.NoCamera)
                return@launch
            }
            _uiState.update {
                it.copy(
                    awaitingFirstFrame = true,
                    hasBackLens = hasBack,
                    hasFrontLens = hasFront,
                    lens = when {
                        it.lens == CameraLens.Front && hasFront -> CameraLens.Front
                        hasBack -> CameraLens.Back
                        else -> CameraLens.Front
                    },
                )
            }
            bindWithRetry()
        }
    }

    private suspend fun bindWithRetry() {
        var backoff = BIND_RETRY_DELAY_MS
        repeat(BIND_MAX_ATTEMPTS) { attempt ->
            if (released) return
            if (bind()) return
            // Some HALs report zero cameras until the previous session's resources are released.
            if (attempt < BIND_MAX_ATTEMPTS - 1) {
                delay(backoff)
                backoff *= 2
            }
        }
        _errors.tryEmit(CameraError.BindFailed)
    }

    private fun bind(): Boolean {
        val cameraProvider = provider ?: return false
        val state = _uiState.value
        val selector = state.lens.selector
        val newPreview = buildPreview()
        val newImage = buildImageCapture()
        val newVideo = buildVideoCapture(state)

        val perMode: List<UseCase> =
            if (state.mode == CaptureMode.Photo) listOf(newPreview, newImage) else listOf(newPreview, newVideo)
        val simultaneous = listOf(newPreview, newImage, newVideo)
        val simultaneousSupported = CameraCapability.simultaneousSupport.getOrPut(state.lens) {
            canBindSimultaneously(cameraProvider, selector, simultaneous)
        }
        val attempts = if (simultaneousSupported) {
            listOf(simultaneous, perMode)
        } else {
            listOf(perMode)
        }

        for (useCases in attempts) {
            try {
                if (boundUseCases.isNotEmpty()) cameraProvider.unbind(*boundUseCases.toTypedArray())
                boundUseCases = emptyList()
                val bound = cameraProvider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
                boundUseCases = useCases
                logOpenStep("bound")
                onBound(bound, useCases, newPreview, newImage, newVideo)
                return true
            } catch (e: Exception) {
                Logger.w(tag = TAG, throwable = e) { "Binding ${useCases.size} use cases failed" }
            }
        }
        return false
    }

    private fun canBindSimultaneously(
        cameraProvider: ProcessCameraProvider,
        selector: CameraSelector,
        useCases: List<UseCase>,
    ): Boolean {
        if (anyCameraIsLegacy) return false
        return try {
            cameraProvider.getCameraInfo(selector).isSessionConfigSupported(SessionConfig(useCases))
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "isSessionConfigSupported failed; trusting hardware level" }
            true
        }
    }

    private fun onBound(
        bound: Camera,
        useCases: List<UseCase>,
        newPreview: Preview,
        newImage: ImageCapture,
        newVideo: VideoCapture<Recorder>,
    ) {
        camera = bound
        preview = newPreview
        imageCapture = newImage.takeIf { it in useCases }
        videoCapture = newVideo.takeIf { it in useCases }
        zoomAnimation?.cancel()
        observe(bound)
        val info = bound.cameraInfo
        val hasFlash = info.hasFlashUnit()
        val zoom = info.zoomState.value
        _uiState.update {
            it.copy(
                hasPhotoFlash = hasFlash,
                hasTorch = hasFlash,
                supportsSimultaneousVideo = useCases.size == 3,
                zoomRatio = zoom?.zoomRatio ?: 1f,
                minZoom = zoom?.minZoomRatio ?: 1f,
                maxZoom = zoom?.maxZoomRatio ?: 1f,
                focusPoint = null,
                focusLocked = false,
                exposureSupported = info.exposureState.isExposureCompensationSupported,
                exposureMinEv = info.exposureState.run { exposureCompensationRange.lower * exposureCompensationStep.toFloat() },
                exposureMaxEv = info.exposureState.run { exposureCompensationRange.upper * exposureCompensationStep.toFloat() },
                exposureBias = 0f,
            )
        }
        // A fresh ImageCapture defaults to FLASH_MODE_OFF, so every rebind re-seeds flash from state.
        imageCapture?.flashMode = FlashPolicy.effectivePhotoFlash(_uiState.value.flashMode, hasFlash).cameraXMode
        applyTorch()
    }

    private fun observe(bound: Camera) {
        val zoom = bound.cameraInfo.zoomState
        if (zoom !== observedZoom) {
            observedZoom?.removeObserver(zoomObserver)
            observedZoom = zoom
            zoom.observeForever(zoomObserver)
        }
        val cameraState = bound.cameraInfo.cameraState
        if (cameraState !== observedCameraState) {
            observedCameraState?.removeObserver(cameraStateObserver)
            observedCameraState = cameraState
            lastCameraErrorCode = null
            cameraState.observeForever(cameraStateObserver)
        }
    }

    // Target rotation is read once at build time by the Compose viewfinder, hence the rebuild on rotation.
    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildPreview(): Preview {
        val builder = Preview.Builder()
            .setResolutionSelector(RESOLUTION_16_9)
            .setTargetRotation(displayRotation)
        val generation = ++previewGeneration
        val delivered = AtomicBoolean(false)
        // Camera state OPEN precedes the first frame; this is the signal PreviewView's STREAMING state uses.
        Camera2Interop.Extender(builder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (delivered.compareAndSet(false, true)) mainExecutor.execute { onFirstFrame(generation) }
                }
            },
        )
        return builder.build().also { it.setSurfaceProvider { request ->
                logOpenStep("surface requested")
                _surfaceRequest.value = request
            } }
    }

    private fun onFirstFrame(generation: Int) {
        if (released || generation != previewGeneration) return
        logOpenStep("first frame")
        openedAt = null
        _uiState.update { it.copy(awaitingFirstFrame = false) }
    }

    private fun buildImageCapture(): ImageCapture =
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(RESOLUTION_16_9)
            .setTargetRotation(captureRotation.surfaceRotation)
            .build()

    // Mirror mode is build-time only on VideoCapture, so a mirror preference change needs a rebind.
    private fun buildVideoCapture(state: CameraUiState): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setAspectRatio(AspectRatio.RATIO_16_9)
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
                )
            )
            .build()
        return VideoCapture.Builder(recorder)
            .setMirrorMode(if (state.mirrorFront) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
            .setTargetRotation(captureRotation.surfaceRotation)
            .build()
    }

    private fun onDisplayRotationChanged() {
        val rotation = currentDisplayRotation()
        if (rotation == displayRotation) return
        displayRotation = rotation
        rebuildPreview()
    }

    /** Swaps only the Preview, leaving capture bound: a full unbind blacks out and can wedge older HALs. */
    private fun rebuildPreview() {
        val cameraProvider = provider ?: return
        val old = preview ?: return
        if (old !in boundUseCases) return
        val selector = _uiState.value.lens.selector
        val fresh = buildPreview()
        try {
            cameraProvider.unbind(old)
            camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, fresh)
            preview = fresh
            boundUseCases = boundUseCases.map { if (it === old) fresh else it }
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "Preview rebuild failed; restoring the old preview" }
            try {
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, old)
            } catch (restore: Exception) {
                Logger.e(tag = TAG, throwable = restore) { "Preview restore failed" }
                boundUseCases = boundUseCases - old
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0

    override fun setLens(lens: CameraLens) {
        val state = _uiState.value
        if (state.lens == lens || recording != null || !state.hasLens(lens)) return
        _uiState.update { it.copy(lens = lens, focusPoint = null, focusLocked = false, awaitingFirstFrame = true) }
        rebindOrReport()
    }

    override fun setMode(mode: CaptureMode) {
        if (_uiState.value.mode == mode || recording != null) return
        _uiState.update { it.copy(mode = mode) }
        val needsRebind = when (mode) {
            CaptureMode.Photo -> imageCapture == null
            CaptureMode.Video -> videoCapture == null
        }
        if (needsRebind) rebindOrReport()
        applyTorch()
    }

    override fun setMirrorFront(enabled: Boolean) {
        if (_uiState.value.mirrorFront == enabled) return
        _uiState.update { it.copy(mirrorFront = enabled) }
        if (_uiState.value.lens == CameraLens.Front && videoCapture != null && recording == null) rebindOrReport()
    }

    private fun rebindOrReport() {
        if (provider == null) return
        if (!bind()) {
            _uiState.update { it.copy(isBound = false) }
            _errors.tryEmit(CameraError.BindFailed)
        }
    }

    override fun setFlash(mode: FlashMode) {
        _uiState.update { it.copy(flashMode = mode) }
        imageCapture?.flashMode = FlashPolicy.effectivePhotoFlash(mode, _uiState.value.hasPhotoFlash).cameraXMode
    }

    override fun setTorch(on: Boolean) {
        _uiState.update { it.copy(torchOn = on) }
        applyTorch()
    }

    private fun applyTorch() {
        val state = _uiState.value
        val cam = camera ?: return
        if (!state.hasTorch) return
        cam.cameraControl.enableTorch(FlashPolicy.effectiveTorch(state.torchOn, state.mode, state.hasTorch))
    }

    override fun setCaptureRotation(rotation: QuarterTurn) {
        captureRotation = rotation
        imageCapture?.targetRotation = rotation.surfaceRotation
        if (recording == null) videoCapture?.targetRotation = rotation.surfaceRotation
    }

    // Log-space interpolation derived from Signal-Android CameraScreenViewModel.kt (AGPL-3.0, see NOTICE).
    override fun setZoomRatio(ratio: Float, animate: Boolean) {
        val cam = camera ?: return
        val state = _uiState.value
        val target = state.clampZoom(ratio)
        zoomAnimation?.cancel()
        val from = state.zoomRatio
        if (!animate || from <= 0f || target <= 0f || from == target) {
            applyZoom(cam, target)
            return
        }
        // AndroidUiDispatcher carries the Choreographer frame clock, so each step lands on a vsync.
        zoomAnimation = scope.launch(AndroidUiDispatcher.Main) {
            val fromLog = ln(from)
            val toLog = ln(target)
            val startNanos = withFrameNanos { it }
            while (true) {
                val elapsedMs = (withFrameNanos { it } - startNanos) / 1_000_000f
                val fraction = (elapsedMs / ZOOM_ANIMATION_MS).coerceAtMost(1f)
                applyZoom(cam, exp(fromLog + (toLog - fromLog) * FastOutSlowInEasing.transform(fraction)))
                if (fraction >= 1f) break
            }
        }
    }

    private fun applyZoom(cam: Camera, ratio: Float) {
        cam.cameraControl.setZoomRatio(ratio)
        _uiState.update { it.copy(zoomRatio = ratio) }
    }

    /** [lock] holds focus and exposure (AE/AF lock) until the next tap instead of releasing them after a few seconds. */
    fun focusAt(point: MeteringPoint, viewOffset: Offset, lock: Boolean = false) {
        val cam = camera ?: return
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .apply { if (lock) disableAutoCancel() else setAutoCancelDuration(FOCUS_AUTO_CANCEL_S, TimeUnit.SECONDS) }
            .build()
        // The EV bias stays: resetting it along with the new AE region darkened the preview for a few frames.
        cam.cameraControl.startFocusAndMetering(action)
        _uiState.update { it.copy(focusPoint = viewOffset, focusLocked = lock) }
        scheduleFocusClear()
    }

    private fun scheduleFocusClear() {
        focusClear?.cancel()
        if (_uiState.value.focusLocked) return
        focusClear = scope.launch {
            delay(TimeUnit.SECONDS.toMillis(FOCUS_AUTO_CANCEL_S))
            _uiState.update { it.copy(focusPoint = null) }
        }
    }

    override fun setExposureBias(bias: Float) {
        val cam = camera ?: return
        val clamped = bias.coerceIn(-1f, 1f)
        applyExposureBias(cam, clamped)
        _uiState.update { it.copy(exposureBias = clamped) }
        if (_uiState.value.focusPoint != null) scheduleFocusClear()
    }

    private fun applyExposureBias(cam: Camera, bias: Float) {
        val exposure = cam.cameraInfo.exposureState
        if (!exposure.isExposureCompensationSupported) return
        val range = exposure.exposureCompensationRange
        val index = if (bias >= 0f) (bias * range.upper).roundToInt() else (-bias * range.lower).roundToInt()
        if (index != exposure.exposureCompensationIndex) cam.cameraControl.setExposureCompensationIndex(index)
    }

    override suspend fun takePhoto(): PlatformFile? {
        val capture = imageCapture ?: run {
            _errors.tryEmit(CameraError.PhotoFailed("Photo capture is not bound"))
            return null
        }
        val state = _uiState.value
        val file = newOutputFile("IMG", "jpg") ?: return null
        val metadata = ImageCapture.Metadata().apply {
            // Set explicitly: CameraController and raw ImageCapture disagree on the front-lens default.
            isReversedHorizontal = MirrorPolicy.shouldMirror(state.lens, state.mirrorFront)
        }
        val options = ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build()
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                options,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(PlatformFile(file)) else file.delete()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Logger.e(tag = TAG, throwable = exception) { "Photo capture failed (${exception.imageCaptureError})" }
                        file.delete()
                        _errors.tryEmit(CameraError.PhotoFailed(exception.message))
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }
    }

    @SuppressLint("MissingPermission")
    override fun startRecording(withAudio: Boolean) {
        if (recording != null) return
        val capture = videoCapture ?: run {
            _errors.tryEmit(CameraError.RecordingFailed("Video capture is not bound"))
            return
        }
        val file = newOutputFile("VID", "mp4") ?: return
        val result = CompletableDeferred<PlatformFile?>()
        recordingResult = result
        val audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val pending = capture.output
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
            .let { if (withAudio && audioGranted) it.withAudioEnabled() else it }
        recording = try {
            pending.start(mainExecutor) { event -> onRecordEvent(event, file, result) }
        } catch (e: Exception) {
            Logger.e(tag = TAG, throwable = e) { "Recording failed to start" }
            file.delete()
            result.complete(null)
            _errors.tryEmit(CameraError.RecordingFailed(e.message))
            null
        }
    }

    private fun onRecordEvent(event: VideoRecordEvent, file: File, result: CompletableDeferred<PlatformFile?>) {
        when (event) {
            is VideoRecordEvent.Start -> _uiState.update { it.copy(isRecording = true) }

            // Start can precede the first recorded frame, so the timer counts from the recorded duration.
            is VideoRecordEvent.Status -> if (_uiState.value.recordingStartedAtMs == null) {
                val now = Clock.System.now().toEpochMilliseconds()
                recordingStartedAtMs(now, event.recordingStats.recordedDurationNanos)?.let { startedAt ->
                    _uiState.update { it.copy(recordingStartedAtMs = startedAt) }
                }
            }

            is VideoRecordEvent.Finalize -> {
                recording = null
                _uiState.update { it.copy(isRecording = false, recordingStartedAtMs = null) }
                val error = event.error
                val playable = error == VideoRecordEvent.Finalize.ERROR_NONE || error in PLAYABLE_FINALIZE_ERRORS
                if (error != VideoRecordEvent.Finalize.ERROR_NONE) {
                    Logger.w(tag = TAG, throwable = event.cause) { "Recording finalized with error=$error playable=$playable" }
                }
                // A playable stop (backgrounding stops the source) is delivered, not reported; storage still warns.
                if (!playable || error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE) {
                    _errors.tryEmit(
                        if (error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE) CameraError.InsufficientStorage
                        else CameraError.RecordingFailed(event.cause?.message)
                    )
                }
                if (playable && file.length() > 0L) {
                    result.complete(PlatformFile(file))
                } else {
                    file.delete()
                    result.complete(null)
                }
            }

            else -> Unit
        }
    }

    override suspend fun stopRecording(): PlatformFile? {
        val result = recordingResult ?: return null
        recording?.stop()
        return try {
            result.await()
        } finally {
            if (recordingResult === result) recordingResult = null
        }
    }

    override fun release() {
        if (released) return
        released = true
        displayManager.unregisterDisplayListener(displayListener)
        recording?.stop()
        recording = null
        observedZoom?.removeObserver(zoomObserver)
        observedZoom = null
        observedCameraState?.removeObserver(cameraStateObserver)
        observedCameraState = null
        if (boundUseCases.isNotEmpty()) provider?.unbind(*boundUseCases.toTypedArray())
        boundUseCases = emptyList()
        camera = null
        _surfaceRequest.value = null
        scope.cancel()
    }

    private fun newOutputFile(prefix: String, extension: String): File? {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            _errors.tryEmit(CameraError.InsufficientStorage)
            return null
        }
        return File(outputDir, "${prefix}_${System.currentTimeMillis()}.$extension")
    }

    private fun ProcessCameraProvider.hasCameraSafe(selector: CameraSelector): Boolean =
        try {
            hasCamera(selector)
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "hasCamera failed" }
            false
        }

    private companion object {
        const val TAG = "AndroidCameraEngine"
        const val BIND_MAX_ATTEMPTS = 4
        const val BIND_RETRY_DELAY_MS = 500L
        const val ZOOM_ANIMATION_MS = 250L
        const val FOCUS_AUTO_CANCEL_S = 3L

        val RESOLUTION_16_9: ResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()

        val PLAYABLE_FINALIZE_ERRORS = setOf(
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
        )
    }
}

private val CameraLens.selector: CameraSelector
    get() = when (this) {
        CameraLens.Back -> CameraSelector.DEFAULT_BACK_CAMERA
        CameraLens.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
    }

private val FlashMode.cameraXMode: Int
    get() = when (this) {
        FlashMode.Off -> ImageCapture.FLASH_MODE_OFF
        FlashMode.Auto -> ImageCapture.FLASH_MODE_AUTO
        FlashMode.On -> ImageCapture.FLASH_MODE_ON
    }
