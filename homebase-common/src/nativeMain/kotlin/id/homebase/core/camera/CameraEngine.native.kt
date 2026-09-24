@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.uploadTempDirectory
import id.homebase.core.audio.AudioSession
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
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
import org.koin.compose.koinInject
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceFormat
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePosition
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceRotationCoordinator
import platform.AVFoundation.AVCaptureDeviceSubjectAreaDidChangeNotification
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInDualCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInDualWideCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTripleCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTrueDepthCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureExposureModeAutoExpose
import platform.AVFoundation.AVCaptureExposureModeContinuousAutoExposure
import platform.AVFoundation.AVCaptureFlashMode
import platform.AVFoundation.AVCaptureFlashModeAuto
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCaptureFocusModeAutoFocus
import platform.AVFoundation.AVCaptureFocusModeContinuousAutoFocus
import platform.AVFoundation.AVCaptureMovieFileOutput
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionInterruptionEndedNotification
import platform.AVFoundation.AVCaptureSessionInterruptionReasonKey
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureSessionPresetInputPriority
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureSessionRuntimeErrorNotification
import platform.AVFoundation.AVCaptureSessionWasInterruptedNotification
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVFrameRateRange
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoCodecTypeJPEG
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.cancelVideoZoomRamp
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.AVFoundation.displayVideoZoomFactorMultiplier
import platform.AVFoundation.exposureMode
import platform.AVFoundation.maxExposureTargetBias
import platform.AVFoundation.minExposureTargetBias
import platform.AVFoundation.setExposureTargetBias
import platform.AVFoundation.exposurePointOfInterest
import platform.AVFoundation.exposurePointOfInterestSupported
import platform.AVFoundation.focusMode
import platform.AVFoundation.focusPointOfInterest
import platform.AVFoundation.focusPointOfInterestSupported
import platform.AVFoundation.hasFlash
import platform.AVFoundation.hasTorch
import platform.AVFoundation.isExposureModeSupported
import platform.AVFoundation.isFocusModeSupported
import platform.AVFoundation.isTorchModeSupported
import platform.AVFoundation.maxAvailableVideoZoomFactor
import platform.AVFoundation.minAvailableVideoZoomFactor
import platform.AVFoundation.rampToVideoZoomFactor
import platform.AVFoundation.CMVideoDimensionsValue
import platform.CoreMedia.CMFormatDescriptionGetMediaSubType
import platform.CoreMedia.CMVideoDimensions
import platform.CoreMedia.CMVideoFormatDescriptionGetDimensions
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.QuartzCore.CATransaction
import platform.Foundation.NSValue
import platform.AVFoundation.subjectAreaChangeMonitoringEnabled
import platform.AVFoundation.torchMode
import platform.AVFoundation.videoZoomFactor
import platform.AVFoundation.virtualDeviceSwitchOverVideoZoomFactors
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGPointMake
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.writeToURL
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.coroutines.resume
import kotlin.time.Clock

@Composable
actual fun rememberCameraEngine(recordsVideo: Boolean, warm: CameraEngine?): CameraEngine {
    val fileOps = koinInject<FileOperationsProvider>()
    val engine = remember(warm) { warm as? IosCameraEngine ?: IosCameraEngine(fileOps.uploadTempDirectory(), recordsVideo) }
    DisposableEffect(engine) {
        engine.start()
        onDispose { engine.release() }
    }
    return engine
}

@Composable
actual fun rememberCameraWarmer(): CameraWarmer {
    val fileOps = koinInject<FileOperationsProvider>()
    return remember(fileOps) {
        CameraWarmer { recordsVideo ->
            val granted = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized
            if (granted) IosCameraEngine(fileOps.uploadTempDirectory(), recordsVideo).also { it.start() } else null
        }
    }
}

/**
 * Public methods run on main. Everything touching the session, inputs, outputs or device configuration
 * runs on [sessionQueue]: startRunning/commitConfiguration block for hundreds of ms.
 */
internal class IosCameraEngine(private val outputDir: String, private val recordsVideo: Boolean) : CameraEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessionQueue = dispatch_queue_create("id.homebase.camera.session", null)

    private val _uiState = MutableStateFlow(CameraUiState())
    override val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _errors = MutableSharedFlow<CameraError>(extraBufferCapacity = 8)
    override val errors: SharedFlow<CameraError> = _errors.asSharedFlow()

    private val session = AVCaptureSession()
    private val photoOutput = AVCapturePhotoOutput()
    private val movieOutput = AVCaptureMovieFileOutput()

    val previewLayer: AVCaptureVideoPreviewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    // Session-queue state.
    private var backDevice: AVCaptureDevice? = null
    private var frontDevice: AVCaptureDevice? = null
    private var videoInput: AVCaptureDeviceInput? = null
    private var audioInput: AVCaptureDeviceInput? = null
    private var zoomMultiplier = 1.0

    // Main-thread state.
    private var rotationCoordinator: AVCaptureDeviceRotationCoordinator? = null
    private val photoDelegates = mutableSetOf<PhotoCaptureDelegate>()
    private var recordingDelegate: RecordingDelegate? = null
    private var recordingResult: CompletableDeferred<PlatformFile?>? = null
    private val observers = mutableListOf<NSObjectProtocol>()
    private var subjectAreaObserver: NSObjectProtocol? = null
    private var focusClear: Job? = null
    private var started = false
    private var released = false

    fun start() {
        if (started) return
        started = true
        observeSession()
        _uiState.update { it.copy(awaitingFirstFrame = true) }
        onSessionQueue {
            backDevice = firstDevice(BACK_DEVICE_TYPES, AVCaptureDevicePositionBack)
            frontDevice = firstDevice(FRONT_DEVICE_TYPES, AVCaptureDevicePositionFront)
            val hasBack = backDevice != null
            val hasFront = frontDevice != null
            if (!hasBack && !hasFront) {
                Logger.i(tag = TAG) { "No capture device (simulator?)" }
                _uiState.update { it.copy(isAvailable = false, hasBackLens = false, hasFrontLens = false) }
                _errors.tryEmit(CameraError.NoCamera)
                return@onSessionQueue
            }
            val lens = if (hasBack) CameraLens.Back else CameraLens.Front
            _uiState.update { it.copy(hasBackLens = hasBack, hasFrontLens = hasFront, lens = lens) }

            session.beginConfiguration()
            // A mic attached while framing must not stop the user's music; iOS Camera keeps it playing too.
            session.configuresApplicationAudioSessionToMixWithOthers = true
            val attached = attachVideoInput(lens)
            if (session.canAddOutput(photoOutput)) session.addOutput(photoOutput)
            if (session.canAddOutput(movieOutput)) session.addOutput(movieOutput)
            attachGrantedMic()
            session.commitConfiguration()
            if (!attached) {
                _errors.tryEmit(CameraError.BindFailed)
                return@onSessionQueue
            }
            configureOutputsForDevice()
            session.startRunning()
            _uiState.update { it.copy(isBound = session.running, supportsSimultaneousVideo = true) }
        }
    }

    private fun firstDevice(types: List<String?>, position: AVCaptureDevicePosition): AVCaptureDevice? =
        types.firstNotNullOfOrNull { AVCaptureDevice.defaultDeviceWithDeviceType(it, AVMediaTypeVideo, position) }

    /** Session queue, after any commit that changed the device, format or preset. */
    private fun configureOutputsForDevice() {
        val format = currentDevice()?.activeFormat ?: return
        val fourByThree = format.dimensions().let { (w, h) -> w * 3 == h * 4 }
        dispatch_async(dispatch_get_main_queue()) {
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            previewLayer.videoGravity = if (fourByThree) AVLayerVideoGravityResizeAspect else AVLayerVideoGravityResizeAspectFill
            CATransaction.commit()
            _uiState.update { it.copy(previewAspectRatio = if (fourByThree) 3f / 4f else null) }
        }
        choosePhotoSize(format.photoSizes())?.let { (width, height) ->
            photoOutput.maxPhotoDimensions = cValue<CMVideoDimensions> {
                this.width = width
                this.height = height
            }
        }
        // The front camera flashes stills with the screen (Retina Flash) and has no hasFlash LED to ask about.
        val photoFlash = photoOutput.supportedFlashModes.any { (it as? NSNumber)?.longValue == AVCaptureFlashModeOn }
        _uiState.update { it.copy(hasPhotoFlash = photoFlash) }
        val connection = movieOutput.connectionWithMediaType(AVMediaTypeVideo) ?: return
        // Match the old system picker and Android receivers rather than HEVC.
        if (movieOutput.availableVideoCodecTypes.contains(AVVideoCodecTypeH264)) {
            movieOutput.setOutputSettings(mapOf<Any?, Any?>(AVVideoCodecKey to AVVideoCodecTypeH264), forConnection = connection)
        }
    }

    /** Session queue, inside begin/commitConfiguration. */
    private fun attachVideoInput(lens: CameraLens): Boolean {
        val device = (if (lens == CameraLens.Back) backDevice else frontDevice) ?: return false
        videoInput?.let { session.removeInput(it) }
        videoInput = null
        val input = memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            AVCaptureDeviceInput.deviceInputWithDevice(device, err.ptr).also {
                err.value?.let { e -> Logger.e(tag = TAG) { "Device input failed: ${e.localizedDescription}" } }
            }
        } ?: return false
        if (!session.canAddInput(input)) return false
        session.addInput(input)
        videoInput = input
        applyModeFormat(device, _uiState.value.mode)
        configureNewDevice(device)
        dispatch_async(dispatch_get_main_queue()) { onDeviceActivated(device) }
        return true
    }

    /**
     * Session queue, inside begin/commitConfiguration with [device] attached; false when nothing changed. Photo gets a
     * recordable 4:3 format so a hold from Photo records without reconfiguring, as the Camera app's QuickTake does.
     */
    private fun applyModeFormat(device: AVCaptureDevice, mode: CaptureMode): Boolean {
        val format = if (mode == CaptureMode.Photo) photoModeFormat(device) else null
        if (format != null) {
            if (session.sessionPreset == AVCaptureSessionPresetInputPriority && device.activeFormat == format) return false
            device.withConfigurationLock { activeFormat = format }
            return true
        }
        val preset = listOfNotNull(AVCaptureSessionPresetPhoto.takeIf { mode == CaptureMode.Photo }, AVCaptureSessionPresetHigh)
            .firstOrNull { session.canSetSessionPreset(it) } ?: return false
        if (session.sessionPreset == preset) return false
        session.sessionPreset = preset
        return true
    }

    private fun photoModeFormat(device: AVCaptureDevice): AVCaptureDeviceFormat? {
        // 10-bit formats can't be recorded as H.264.
        val formats = device.formats.filterIsInstance<AVCaptureDeviceFormat>()
            .filter { CMFormatDescriptionGetMediaSubType(it.formatDescription) in EIGHT_BIT_420 }
        val specs = formats.map { format ->
            val (width, height) = format.dimensions()
            val maxFps = format.videoSupportedFrameRateRanges.maxOfOrNull { (it as? AVFrameRateRange)?.maxFrameRate ?: 0.0 } ?: 0.0
            VideoFormat(width, height, maxFps, format.photoSizes())
        }
        return choosePhotoModeFormat(specs)?.let(formats::get)
    }

    /** Virtual (multi-lens) devices start on their widest lens; move to the main wide lens so "1×" is the default. */
    private fun configureNewDevice(device: AVCaptureDevice) {
        zoomMultiplier = device.displayVideoZoomFactorMultiplier.takeIf { it > 0.0 } ?: 1.0
        val minFactor = device.minAvailableVideoZoomFactor
        val maxFactor = minOf(device.maxAvailableVideoZoomFactor, MAX_DISPLAY_ZOOM / zoomMultiplier)
        val startFactor = (1.0 / zoomMultiplier).coerceIn(minFactor, maxOf(minFactor, maxFactor))
        device.withConfigurationLock {
            videoZoomFactor = startFactor
            if (isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus)) focusMode = AVCaptureFocusModeContinuousAutoFocus
            subjectAreaChangeMonitoringEnabled = false
        }
        val switchRatios = device.virtualDeviceSwitchOverVideoZoomFactors
            .mapNotNull { (it as? NSNumber)?.doubleValue?.times(zoomMultiplier)?.toFloat() }
        val hasTorch = device.hasTorch
        _uiState.update {
            it.copy(
                zoomRatio = (startFactor * zoomMultiplier).toFloat(),
                minZoom = (minFactor * zoomMultiplier).toFloat(),
                maxZoom = (maxOf(minFactor, maxFactor) * zoomMultiplier).toFloat(),
                lensSwitchRatios = switchRatios,
                hasTorch = hasTorch,
                focusPoint = null,
                focusLocked = false,
                exposureSupported = true,
                exposureBias = 0f,
            )
        }
        applyTorchOnQueue(device)
    }

    private fun onDeviceActivated(device: AVCaptureDevice) {
        if (released) return
        rotationCoordinator = AVCaptureDeviceRotationCoordinator(device = device, previewLayer = previewLayer)
        applyPreviewRotation()
        subjectAreaObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        subjectAreaObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVCaptureDeviceSubjectAreaDidChangeNotification,
            `object` = device,
            queue = NSOperationQueue.mainQueue,
        ) { _: NSNotification? -> resetFocusToContinuous(device) }
    }

    /** Main thread, once [previewLayer] reports it is showing frames: startRunning returns before it does. */
    fun onPreviewShowing() {
        _uiState.update { it.copy(awaitingFirstFrame = false) }
    }

    /** Main thread; also called from the preview view's layoutSubviews when the interface rotates. */
    fun applyPreviewRotation() {
        val angle = rotationCoordinator?.videoRotationAngleForHorizonLevelPreview ?: return
        val connection = previewLayer.connection ?: return
        if (connection.isVideoRotationAngleSupported(angle)) connection.videoRotationAngle = angle
    }

    private fun observeSession() {
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(AVCaptureSessionWasInterruptedNotification, session, NSOperationQueue.mainQueue) { note ->
            val reason = (note?.userInfo?.get(AVCaptureSessionInterruptionReasonKey) as? NSNumber)?.longValue
            Logger.w(tag = TAG) { "Session interrupted reason=$reason" }
            _uiState.update { it.copy(isBound = false) }
            _errors.tryEmit(
                if (reason == AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient) CameraError.CameraInUse
                else CameraError.Interrupted
            )
        }
        observers += center.addObserverForName(AVCaptureSessionInterruptionEndedNotification, session, NSOperationQueue.mainQueue) { _ ->
            _uiState.update { it.copy(isBound = true) }
        }
        observers += center.addObserverForName(AVCaptureSessionRuntimeErrorNotification, session, NSOperationQueue.mainQueue) { note ->
            Logger.e(tag = TAG) { "Session runtime error: ${note?.userInfo}" }
            _uiState.update { it.copy(isBound = false) }
            _errors.tryEmit(CameraError.BindFailed)
            onSessionQueue {
                if (!session.running) session.startRunning()
                _uiState.update { it.copy(isBound = session.running) }
            }
        }
    }

    override fun setLens(lens: CameraLens) {
        val state = _uiState.value
        if (state.lens == lens || recordingDelegate != null || !state.hasLens(lens)) return
        _uiState.update { it.copy(lens = lens, focusPoint = null, focusLocked = false) }
        onSessionQueue {
            session.beginConfiguration()
            val ok = attachVideoInput(lens)
            attachGrantedMic()
            session.commitConfiguration()
            if (ok) configureOutputsForDevice() else _errors.tryEmit(CameraError.BindFailed)
        }
    }

    override fun setMode(mode: CaptureMode) {
        if (_uiState.value.mode == mode || recordingDelegate != null) return
        _uiState.update { it.copy(mode = mode) }
        onSessionQueue {
            val device = currentDevice() ?: return@onSessionQueue
            if (_uiState.value.mode == mode) reconfigureForMode(device, mode)
            applyTorchOnQueue(device)
        }
    }

    private fun reconfigureForMode(device: AVCaptureDevice, mode: CaptureMode) {
        session.beginConfiguration()
        val changed = applyModeFormat(device, mode)
        if (changed) {
            _uiState.update { it.copy(awaitingFirstFrame = true) }
            val factor = (_uiState.value.zoomRatio / zoomMultiplier)
                .coerceIn(device.minAvailableVideoZoomFactor, device.maxAvailableVideoZoomFactor)
            device.withConfigurationLock { videoZoomFactor = factor }
            attachGrantedMic()
        }
        session.commitConfiguration()
        if (!changed) return
        configureOutputsForDevice()
        dispatch_async(dispatch_get_main_queue()) { _uiState.update { it.copy(awaitingFirstFrame = false) } }
    }

    override fun setMirrorFront(enabled: Boolean) {
        _uiState.update { it.copy(mirrorFront = enabled) }
    }

    override fun setFlash(mode: FlashMode) {
        _uiState.update { it.copy(flashMode = mode) }
    }

    override fun setTorch(on: Boolean) {
        _uiState.update { it.copy(torchOn = on) }
        onSessionQueue { currentDevice()?.let { applyTorchOnQueue(it) } }
    }

    private fun applyTorchOnQueue(device: AVCaptureDevice) {
        val state = _uiState.value
        if (!device.hasTorch) return
        val mode = if (FlashPolicy.effectiveTorch(state.torchOn, state.mode, device.hasTorch)) AVCaptureTorchModeOn else AVCaptureTorchModeOff
        if (device.torchMode == mode || !device.isTorchModeSupported(mode)) return
        device.withConfigurationLock { torchMode = mode }
    }

    /** The rotation coordinator already tracks physical orientation, including with the UI locked to portrait. */
    override fun setCaptureRotation(rotation: QuarterTurn) = Unit

    override fun setZoomRatio(ratio: Float, animate: Boolean) {
        val state = _uiState.value
        val target = state.clampZoom(ratio)
        _uiState.update { it.copy(zoomRatio = target) }
        onSessionQueue {
            val device = currentDevice() ?: return@onSessionQueue
            val factor = (target / zoomMultiplier).coerceIn(device.minAvailableVideoZoomFactor, device.maxAvailableVideoZoomFactor)
            device.withConfigurationLock {
                if (animate) {
                    rampToVideoZoomFactor(factor, withRate = ZOOM_RAMP_RATE)
                } else {
                    cancelVideoZoomRamp()
                    videoZoomFactor = factor
                }
            }
        }
    }

    /** AutoFocus/AutoExpose settle once and then hold; [lock] also stops a subject-area change from releasing them. */
    fun focusAt(layerPoint: CValue<CGPoint>, viewOffset: Offset, lock: Boolean = false) {
        // A tap on the Photo letterbox maps outside the frame.
        val devicePoint = previewLayer.captureDevicePointOfInterestForPoint(layerPoint)
            .useContents { CGPointMake(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0)) }
        _uiState.update { it.copy(focusPoint = viewOffset, focusLocked = lock, exposureBias = 0f) }
        scheduleFocusClear()
        onSessionQueue {
            val device = currentDevice() ?: return@onSessionQueue
            device.withConfigurationLock {
                if (focusPointOfInterestSupported && isFocusModeSupported(AVCaptureFocusModeAutoFocus)) {
                    focusPointOfInterest = devicePoint
                    focusMode = AVCaptureFocusModeAutoFocus
                }
                if (exposurePointOfInterestSupported && isExposureModeSupported(AVCaptureExposureModeAutoExpose)) {
                    exposurePointOfInterest = devicePoint
                    exposureMode = AVCaptureExposureModeAutoExpose
                }
                setExposureTargetBias(0f, completionHandler = null)
                subjectAreaChangeMonitoringEnabled = !lock
            }
        }
    }

    private fun scheduleFocusClear() {
        focusClear?.cancel()
        if (_uiState.value.focusLocked) return
        focusClear = scope.launch {
            delay(FOCUS_INDICATOR_MS)
            _uiState.update { it.copy(focusPoint = null) }
        }
    }

    override fun setExposureBias(bias: Float) {
        val clamped = bias.coerceIn(-1f, 1f)
        _uiState.update { it.copy(exposureBias = clamped) }
        if (_uiState.value.focusPoint != null) scheduleFocusClear()
        onSessionQueue {
            val device = currentDevice() ?: return@onSessionQueue
            val target = if (clamped >= 0f) clamped * device.maxExposureTargetBias else -clamped * device.minExposureTargetBias
            device.withConfigurationLock { setExposureTargetBias(target, completionHandler = null) }
        }
    }

    private fun resetFocusToContinuous(device: AVCaptureDevice) {
        onSessionQueue {
            device.withConfigurationLock {
                val centre = CGPointMake(0.5, 0.5)
                if (focusPointOfInterestSupported && isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus)) {
                    focusPointOfInterest = centre
                    focusMode = AVCaptureFocusModeContinuousAutoFocus
                }
                if (exposurePointOfInterestSupported && isExposureModeSupported(AVCaptureExposureModeContinuousAutoExposure)) {
                    exposurePointOfInterest = centre
                    exposureMode = AVCaptureExposureModeContinuousAutoExposure
                }
                subjectAreaChangeMonitoringEnabled = false
            }
        }
    }

    override suspend fun takePhoto(): PlatformFile? {
        val state = _uiState.value
        if (!state.isBound) {
            _errors.tryEmit(CameraError.PhotoFailed("Camera is not running"))
            return null
        }
        val mirror = MirrorPolicy.shouldMirror(state.lens, state.mirrorFront)
        val angle = rotationCoordinator?.videoRotationAngleForHorizonLevelCapture
        val flash = FlashPolicy.effectivePhotoFlash(state.flashMode, state.hasPhotoFlash).avMode
        val url = newOutputUrl("IMG", "jpg") ?: return null
        return suspendCancellableCoroutine { cont ->
            val delegate = PhotoCaptureDelegate { self, data, error ->
                val written = data != null && data.writeToURL(url, atomically = true)
                dispatch_async(dispatch_get_main_queue()) {
                    photoDelegates.remove(self)
                    if (!written) {
                        Logger.e(tag = TAG) { "Photo capture failed: ${error?.localizedDescription}" }
                        _errors.tryEmit(CameraError.PhotoFailed(error?.localizedDescription))
                    }
                    if (cont.isActive) cont.resume(if (written) PlatformFile(url) else null)
                }
            }
            photoDelegates += delegate
            onSessionQueue {
                photoOutput.connectionWithMediaType(AVMediaTypeVideo)?.configure(mirror, angle)
                val settings = AVCapturePhotoSettings.photoSettingsWithFormat(mapOf<Any?, Any?>(AVVideoCodecKey to AVVideoCodecTypeJPEG))
                settings.maxPhotoDimensions = photoOutput.maxPhotoDimensions
                if (photoOutput.supportedFlashModes.any { (it as? NSNumber)?.longValue == flash }) settings.flashMode = flash
                photoOutput.capturePhotoWithSettings(settings, delegate)
            }
        }
    }

    override fun startRecording(withAudio: Boolean) {
        if (recordingDelegate != null) return
        val state = _uiState.value
        val mirror = MirrorPolicy.shouldMirror(state.lens, state.mirrorFront)
        val angle = rotationCoordinator?.videoRotationAngleForHorizonLevelCapture
        val url = newOutputUrl("VID", "mov") ?: return
        val result = CompletableDeferred<PlatformFile?>()
        recordingResult = result
        val audio = withAudio && AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeAudio) == AVAuthorizationStatusAuthorized
        val delegate = RecordingDelegate(
            onStarted = {
                dispatch_async(dispatch_get_main_queue()) {
                    _uiState.update { it.copy(isRecording = true, recordingStartedAtMs = Clock.System.now().toEpochMilliseconds()) }
                }
            },
            onFinished = { fileUrl, usable, error -> onRecordingFinished(fileUrl, usable, error, result) },
        )
        recordingDelegate = delegate
        onSessionQueue {
            // Only reached when the mic was granted after the session came up; this commit blanks the preview for a frame.
            if (audio && audioInput == null) {
                session.beginConfiguration()
                attachMic()
                session.commitConfiguration()
            }
            movieOutput.connectionWithMediaType(AVMediaTypeAudio)?.enabled = audio
            movieOutput.connectionWithMediaType(AVMediaTypeVideo)?.configure(mirror, angle)
            movieOutput.startRecordingToOutputFileURL(url, recordingDelegate = delegate)
        }
    }

    /** Session queue, inside a commit the session makes anyway, so the mic never costs a reconfiguration of its own. */
    private fun attachGrantedMic() {
        if (recordsVideo && AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeAudio) == AVAuthorizationStatusAuthorized) attachMic()
    }

    /** Session queue, inside begin/commitConfiguration. */
    private fun attachMic() {
        if (audioInput != null) return
        val mic = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeAudio) ?: return
        val input = AVCaptureDeviceInput.deviceInputWithDevice(mic, null) ?: return
        if (session.canAddInput(input)) {
            session.addInput(input)
            audioInput = input
        }
    }

    private fun onRecordingFinished(url: NSURL, usable: Boolean, error: NSError?, result: CompletableDeferred<PlatformFile?>) {
        dispatch_async(dispatch_get_main_queue()) {
            recordingDelegate = null
            _uiState.update { it.copy(isRecording = false, recordingStartedAtMs = null) }
            if (error != null) {
                Logger.w(tag = TAG) { "Recording finished with error usable=$usable: ${error.localizedDescription}" }
                _errors.tryEmit(CameraError.RecordingFailed(error.localizedDescription))
            }
            if (!usable) NSFileManager.defaultManager.removeItemAtURL(url, null)
            result.complete(if (usable) PlatformFile(url) else null)
        }
    }

    override suspend fun stopRecording(): PlatformFile? {
        val result = recordingResult ?: return null
        onSessionQueue { if (movieOutput.recording) movieOutput.stopRecording() }
        return try {
            result.await()
        } finally {
            if (recordingResult === result) recordingResult = null
        }
    }

    override fun release() {
        if (released) return
        released = true
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers.clear()
        subjectAreaObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        subjectAreaObserver = null
        rotationCoordinator = null
        focusClear?.cancel()
        dispatch_async(sessionQueue) {
            if (movieOutput.recording) movieOutput.stopRecording()
            currentDevice()?.let { device ->
                if (device.hasTorch && device.torchMode != AVCaptureTorchModeOff) device.withConfigurationLock { torchMode = AVCaptureTorchModeOff }
            }
            if (session.running) session.stopRunning()
            if (audioInput != null) AudioSession.releaseAfterRecording()
        }
        scope.cancel()
    }

    private fun currentDevice(): AVCaptureDevice? = videoInput?.device

    private fun onSessionQueue(block: () -> Unit) {
        if (released) return
        dispatch_async(sessionQueue, block)
    }

    private fun newOutputUrl(prefix: String, extension: String): NSURL? {
        if (!NSFileManager.defaultManager.createDirectoryAtPath(outputDir, withIntermediateDirectories = true, attributes = null, error = null)) {
            _errors.tryEmit(CameraError.InsufficientStorage)
            return null
        }
        val name = "${prefix}_${Clock.System.now().toEpochMilliseconds()}.$extension"
        return NSURL.fileURLWithPath("$outputDir/$name")
    }

    private companion object {
        const val TAG = "IosCameraEngine"
        const val MAX_DISPLAY_ZOOM = 10.0
        const val ZOOM_RAMP_RATE = 8f
        const val FOCUS_INDICATOR_MS = 3_000L

        val BACK_DEVICE_TYPES = listOf(
            AVCaptureDeviceTypeBuiltInTripleCamera,
            AVCaptureDeviceTypeBuiltInDualWideCamera,
            AVCaptureDeviceTypeBuiltInDualCamera,
            AVCaptureDeviceTypeBuiltInWideAngleCamera,
        )
        val FRONT_DEVICE_TYPES = listOf(
            AVCaptureDeviceTypeBuiltInTrueDepthCamera,
            AVCaptureDeviceTypeBuiltInWideAngleCamera,
        )
    }
}

private fun AVCaptureConnection.configure(mirror: Boolean, rotationAngle: Double?) {
    if (supportsVideoMirroring) {
        automaticallyAdjustsVideoMirroring = false
        videoMirrored = mirror
    }
    if (rotationAngle != null && isVideoRotationAngleSupported(rotationAngle)) videoRotationAngle = rotationAngle
}

private fun AVCaptureDeviceFormat.dimensions(): Pair<Int, Int> =
    CMVideoFormatDescriptionGetDimensions(formatDescription).useContents { width to height }

private fun AVCaptureDeviceFormat.photoSizes(): List<Pair<Int, Int>> =
    supportedMaxPhotoDimensions.mapNotNull { value -> (value as? NSValue)?.CMVideoDimensionsValue?.useContents { width to height } }

private val EIGHT_BIT_420 = setOf(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange)

private inline fun AVCaptureDevice.withConfigurationLock(block: AVCaptureDevice.() -> Unit) {
    if (lockForConfiguration(null)) {
        try {
            block()
        } finally {
            unlockForConfiguration()
        }
    }
}

private val FlashMode.avMode: AVCaptureFlashMode
    get() = when (this) {
        FlashMode.Off -> AVCaptureFlashModeOff
        FlashMode.Auto -> AVCaptureFlashModeAuto
        FlashMode.On -> AVCaptureFlashModeOn
    }
