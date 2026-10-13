import AVFoundation
import CallKit
import ComposeApp
import AVKit
import Foundation
import PushKit
import WebRTC

/// PushKit + CallKit for calling. Server-side VoIP delivery needs the Apple key on the relay; until then
/// rings arrive as time-sensitive alerts and CallKit is only used for outgoing calls.
///
/// Apple's rule: every VoIP push must be reported to CallKit immediately, or iOS stops delivering them.
final class CallKitManager: NSObject, CXProviderDelegate, PKPushRegistryDelegate, CallKitBridge {
    static let shared = CallKitManager()

    private let provider: CXProvider
    private let controller = CXCallController()
    private var registry: PKPushRegistry?
    // CallKit identifies a call by UUID; ours is the callId string.
    private var uuidForCall: [String: UUID] = [:]
    private var callForUuid: [UUID: String] = [:]
    // Calls CallKit has answered (or been asked to): stops an in-app accept from looping back through CXAnswerCallAction.
    private var answeredCalls: Set<String> = []

    private override init() {
        let config = CXProviderConfiguration()
        config.supportsVideo = true
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.generic]
        config.includesCallsInRecents = false
        config.ringtoneSound = nil
        provider = CXProvider(configuration: config)
        super.init()
        provider.setDelegate(self, queue: nil)
    }

    func start() {
        CallKitBridgeHolder.shared.bridge = self
        CallPictureInPictureBridgeHolder.shared.bridge = PictureInPictureManager.shared
        let registry = PKPushRegistry(queue: .main)
        registry.delegate = self
        registry.desiredPushTypes = [.voIP]
        self.registry = registry
    }

    // While CallKit owns a call WebRTC must not start its audio unit until CallKit activates the session
    // (didActivate), else audio is silent; with no CallKit call (simulator, ring without VoIP push) it runs freely.
    private func syncManualAudio() {
        let rtc = RTCAudioSession.sharedInstance()
        let manual = !uuidForCall.isEmpty
        rtc.useManualAudio = manual
        if !manual { rtc.isAudioEnabled = true }
    }

    private func uuid(for callId: String) -> UUID {
        if let existing = uuidForCall[callId] { return existing }
        let created = UUID(uuidString: callId) ?? UUID()
        uuidForCall[callId] = created
        callForUuid[created] = callId
        syncManualAudio()
        return created
    }

    // MARK: PKPushRegistryDelegate

    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        let token = pushCredentials.token.map { String(format: "%02x", $0) }.joined()
        VoipTokenRelay.shared.onToken(token: token)
    }

    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        let dict = payload.dictionaryPayload
        var data: [String: String] = [:]
        for (k, v) in dict { if let key = k as? String, let value = v as? String { data[key] = value } }

        // Parse just enough to name the caller; the full payload also goes through the shared router.
        var callId = UUID().uuidString
        var caller = "Homebase"
        if let json = data["data"]?.data(using: .utf8),
           let obj = try? JSONSerialization.jsonObject(with: json) as? [String: Any] {
            if let sender = obj["senderId"] as? String { caller = sender }
            if let options = obj["options"] as? [String: Any], let tag = options["tagId"] as? String { callId = tag }
        }

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: caller)
        update.localizedCallerName = caller
        update.hasVideo = false // the ring push does not say; Kotlin calls reportVideo() once the offer arrives
        // A self-addressed hangup push reuses the live call's id: it must not drop that call's mapping.
        let alreadyKnown = uuidForCall[callId] != nil
        provider.reportNewIncomingCall(with: uuid(for: callId), update: update) { error in
            if error != nil && !alreadyKnown { self.forget(callId) }
            completion()
        }
        let routed = CallPushRouter.shared.handler != nil && CallPushRouter.shared.tryHandle(data: data)
        if !routed && !alreadyKnown { endUnrouted(callId) }
    }

    // A VoIP push must be reported to CallKit, but one nothing will handle (not a call, or the handler is not set yet) must not keep ringing.
    private func endUnrouted(_ callId: String) {
        guard let id = uuidForCall[callId] else { return }
        provider.reportCall(with: id, endedAt: Date(), reason: .failed)
        forget(callId)
    }

    // MARK: CallKitBridge (Kotlin -> CallKit)

    var hostsOutgoingCalls: Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        return true
        #endif
    }

    // Kotlin calls these from background threads; every piece of state below is main-queue only.
    private func onMain(_ work: @escaping () -> Void) {
        DispatchQueue.main.async(execute: work)
    }

    func reportOutgoing(callId: String, handle: String, video: Bool) {
        onMain {
            let id = self.uuid(for: callId)
            let action = CXStartCallAction(call: id, handle: CXHandle(type: .generic, value: handle))
            action.isVideo = video
            self.controller.request(CXTransaction(action: action)) { error in
                NSLog("[Call] CallKit start request call=%@ error=%@", callId, String(describing: error))
            }
        }
    }

    func reportConnected(callId: String) {
        onMain {
            guard let id = self.uuidForCall[callId] else { return }
            self.provider.reportOutgoingCall(with: id, connectedAt: Date())
        }
    }

    func reportVideo(callId: String, video: Bool) {
        onMain {
            guard let id = self.uuidForCall[callId] else { return }
            let update = CXCallUpdate()
            update.hasVideo = video
            self.provider.reportCall(with: id, updated: update)
        }
    }

    func reportEnded(callId: String, remoteEnded: Bool) {
        onMain {
            guard let id = self.uuidForCall[callId] else { return }
            self.provider.reportCall(with: id, endedAt: Date(), reason: remoteEnded ? .remoteEnded : .failed)
            self.forget(callId)
        }
    }

    func reportEndedElsewhere(callId: String, declined: Bool) {
        onMain {
            guard let id = self.uuidForCall[callId] else { return }
            self.provider.reportCall(with: id, endedAt: Date(), reason: declined ? .declinedElsewhere : .answeredElsewhere)
            self.forget(callId)
        }
    }

    func requestAnswer(callId: String) {
        onMain {
            guard let id = self.uuidForCall[callId], !self.answeredCalls.contains(callId) else { return }
            self.answeredCalls.insert(callId)
            self.controller.request(CXTransaction(action: CXAnswerCallAction(call: id))) { error in
                NSLog("[Call] CallKit answer request call=%@ error=%@", callId, String(describing: error))
            }
        }
    }

    func requestEnd(callId: String) {
        onMain {
            guard let id = self.uuidForCall[callId] else { return }
            self.controller.request(CXTransaction(action: CXEndCallAction(call: id))) { error in
                if error != nil { self.forgetEndedCall(callId, id) }
            }
        }
    }

    private func forgetEndedCall(_ callId: String, _ id: UUID) {
        guard uuidForCall[callId] == id else { return }
        provider.reportCall(with: id, endedAt: Date(), reason: .remoteEnded)
        forget(callId)
    }

    private func forget(_ callId: String) {
        if let id = uuidForCall.removeValue(forKey: callId) { callForUuid.removeValue(forKey: id) }
        answeredCalls.remove(callId)
        syncManualAudio()
    }

    // MARK: CXProviderDelegate (CallKit -> Kotlin)

    func providerDidReset(_ provider: CXProvider) {
        NSLog("[Call] CallKit provider reset")
        uuidForCall.removeAll()
        callForUuid.removeAll()
        answeredCalls.removeAll()
        syncManualAudio()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        NSLog("[Call] CXStartCallAction call=%@", action.callUUID.uuidString)
        provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: Date())
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        if let callId = callForUuid[action.callUUID] {
            let askedByApp = answeredCalls.contains(callId)
            answeredCalls.insert(callId)
            if !askedByApp { CallKitBridgeHolder.shared.actions?.accept(callId: callId) }
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        NSLog("[Call] CXEndCallAction call=%@ known=%d", action.callUUID.uuidString, callForUuid[action.callUUID] != nil ? 1 : 0)
        if let callId = callForUuid[action.callUUID] {
            CallKitBridgeHolder.shared.actions?.endOrDecline(callId: callId)
            forget(callId)
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        CallKitBridgeHolder.shared.actions?.setMuted(muted: action.isMuted)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        NSLog("[Call] CallKit audio session activated")
        let rtc = RTCAudioSession.sharedInstance()
        rtc.audioSessionDidActivate(audioSession)
        rtc.isAudioEnabled = true
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        NSLog("[Call] CallKit audio session deactivated")
        let rtc = RTCAudioSession.sharedInstance()
        rtc.audioSessionDidDeactivate(audioSession)
        rtc.isAudioEnabled = false
    }

    func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
        NSLog("[Call] CallKit timed out performing %@", String(describing: type(of: action)))
    }
}


/// The call in the system PiP window: AVPictureInPictureVideoCallViewController (iOS 15+) with the remote video and the camera
/// as an inset, both on sample-buffer layers; PiP starts by itself when the app backgrounds. Outgoing video keeps flowing because
/// the call's capture session gets multitasking camera access in VideoPipelineWatch.apple.kt.
final class PictureInPictureManager: NSObject, CallPictureInPictureBridge, AVPictureInPictureControllerDelegate {
    static let shared = PictureInPictureManager()

    private override init() {
        super.init()
    }

    private var controller: AVPictureInPictureController?
    private var content: AVPictureInPictureVideoCallViewController?
    private var remoteView: SampleBufferVideoView?
    private var selfView: SampleBufferVideoView?
    private var onRestore: () -> Void = {}
    private var track: RTCVideoTrack?
    private var lifecycleObservers: [NSObjectProtocol] = []
    private var counterTimer: Timer?
    // A window being stopped keeps its controller and live content until didStop, or it shows black while it animates away.
    private var stopping: [(controller: AVPictureInPictureController, release: () -> Void)] = []
    private var log: (String) -> Void = { NSLog("%@", $0) }

    func start(remoteTrack: Any, inset: Bool, insetMirrored: Bool, onRestore: @escaping () -> Void, log: @escaping (String) -> Void) -> Any? {
        guard let remote = remoteTrack as? RTCVideoTrack else {
            log("pip start skipped: remote track is \(type(of: remoteTrack))")
            return nil
        }
        let insetView = inset ? SampleBufferVideoView(mirrored: insetMirrored) : nil
        DispatchQueue.main.async { [self] in
            teardown()
            self.log = log
            self.onRestore = onRestore
            guard AVPictureInPictureController.isPictureInPictureSupported() else {
                log("pip start skipped: isPictureInPictureSupported=false")
                return
            }
            guard let source = UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene }).flatMap({ $0.windows })
                    .first(where: { $0.isKeyWindow })?.rootViewController?.view
            else {
                log("pip start skipped: no key window root view")
                return
            }

            let vc = AVPictureInPictureVideoCallViewController()
            vc.preferredContentSize = CGSize(width: 9, height: 16)
            let view = SampleBufferVideoView(mirrored: false)
            view.frame = vc.view.bounds
            view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.onSize = { [weak self] size in
                self?.log("pip remote first frame size=\(size)")
                self?.content?.preferredContentSize = size
            }
            vc.view.addSubview(view)
            remote.add(view)
            if let insetView {
                vc.view.addSubview(insetView)
                insetView.layer.cornerRadius = 8
                insetView.clipsToBounds = true
                // Constraints, not frames: the PiP window resizes the content view after this.
                insetView.translatesAutoresizingMaskIntoConstraints = false
                NSLayoutConstraint.activate([
                    insetView.widthAnchor.constraint(equalTo: vc.view.widthAnchor, multiplier: 0.28),
                    insetView.heightAnchor.constraint(equalTo: insetView.widthAnchor, multiplier: 4.0 / 3.0),
                    insetView.trailingAnchor.constraint(equalTo: vc.view.trailingAnchor, constant: -6),
                    insetView.bottomAnchor.constraint(equalTo: vc.view.bottomAnchor, constant: -6),
                ])
            }

            let pip = AVPictureInPictureController(
                contentSource: AVPictureInPictureController.ContentSource(
                    activeVideoCallSourceView: source, contentViewController: vc))
            pip.canStartPictureInPictureAutomaticallyFromInline = true
            pip.delegate = self
            controller = pip
            content = vc
            remoteView = view
            selfView = insetView
            track = remote
            log("pip armed: \(state(source: source))")
            // iOS does not always end an automatically started PiP when the app comes back, so the window would cover the call screen.
            lifecycleObservers = [NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
                guard let self, let controller = self.controller else { return }
                self.log("pip at \(UIApplication.didBecomeActiveNotification.rawValue): \(self.state(source: source))")
                if controller.isPictureInPictureActive { controller.stopPictureInPicture() }
            }]
        }
        return insetView
    }

    func stop() {
        DispatchQueue.main.async { [self] in
            if controller != nil { log("pip teardown: \(state(source: nil))") }
            teardown()
        }
    }

    private func state(source: UIView?) -> String {
        let audio = AVAudioSession.sharedInstance()
        let sourceState = source.map { "sourceInWindow=\($0.window != nil) sourceHidden=\($0.isHidden) sourceSize=\($0.bounds.size)" } ?? ""
        return "possible=\(controller?.isPictureInPicturePossible ?? false) active=\(controller?.isPictureInPictureActive ?? false) "
            + "autoFromInline=\(controller?.canStartPictureInPictureAutomaticallyFromInline ?? false) controllerRetained=\(controller != nil) "
            + "rtcAudioActive=\(RTCAudioSession.sharedInstance().isActive) audioCategory=\(audio.category.rawValue) mode=\(audio.mode.rawValue) "
            + "appState=\(UIApplication.shared.applicationState.rawValue) \(sourceState)"
    }

    private func teardown() {
        lifecycleObservers.forEach { NotificationCenter.default.removeObserver($0) }
        lifecycleObservers = []
        stopCounters()
        let (remoteView, track, selfView, content) = (remoteView, track, selfView, content)
        let release = {
            if let remoteView { track?.remove(remoteView); remoteView.removeFromSuperview() }
            selfView?.removeFromSuperview()
            _ = content
        }
        // stopPictureInPicture() does nothing unless the scene is foreground-active (AVKit's canAnimatePictureInPictureTransition);
        // releasing the controller stops it anyway, because its dealloc invalidates the window without that check.
        if let controller, controller.isPictureInPictureActive, UIApplication.shared.applicationState == .active {
            controller.canStartPictureInPictureAutomaticallyFromInline = false
            stopping.append((controller, release))
            controller.stopPictureInPicture()
        } else {
            release()
        }
        self.selfView = nil
        self.controller = nil
        self.content = nil
        self.remoteView = nil
        self.track = nil
    }

    private func startCounters() {
        stopCounters()
        counterTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            guard let self else { return }
            self.log("pip frames remote[\(self.remoteView?.drainCounters() ?? "-")] self[\(self.selfView?.drainCounters() ?? "-")]")
        }
    }

    private func stopCounters() {
        counterTimer?.invalidate()
        counterTimer = nil
    }

    private func event(_ name: String) {
        log("pip \(name) main=\(Thread.isMainThread) appState=\(UIApplication.shared.applicationState.rawValue) stopping=\(stopping.count)")
    }

    func pictureInPictureControllerWillStartPictureInPicture(_ c: AVPictureInPictureController) { event("will start") }
    func pictureInPictureControllerDidStartPictureInPicture(_ c: AVPictureInPictureController) {
        event("started")
        if c === controller { startCounters() }
    }
    func pictureInPictureControllerWillStopPictureInPicture(_ c: AVPictureInPictureController) {
        event("will stop")
        stopCounters()
    }
    func pictureInPictureController(_ c: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        event("failed to start: \(error)")
    }
    func pictureInPictureController(
        _ c: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void
    ) {
        event("restore requested")
        onRestore()
        completionHandler(true)
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ c: AVPictureInPictureController) {
        event("stopped")
        stopping.filter { $0.controller === c }.forEach { $0.release() }
        stopping.removeAll { $0.controller === c }
    }
}

/// WebRTC frames on an AVSampleBufferDisplayLayer. A Metal view (RTCMTLVideoView) stops presenting once the app is in the
/// background, so in the PiP window it froze on its last frame; a sample-buffer layer is composited by the system and keeps going.
final class SampleBufferVideoView: UIView, RTCVideoRenderer {
    private let displayLayer = AVSampleBufferDisplayLayer()
    private let mirrored: Bool
    private let lock = NSLock()
    private var rotation: RTCVideoRotation = ._0
    private var size = CGSize.zero
    private var enqueued = 0
    private var notReady = 0
    private var flushes = 0
    var onSize: (CGSize) -> Void = { _ in }

    init(mirrored: Bool) {
        self.mirrored = mirrored
        super.init(frame: .zero)
        backgroundColor = .black
        displayLayer.videoGravity = .resizeAspectFill
        layer.addSublayer(displayLayer)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let quarterTurn = rotation == ._90 || rotation == ._270
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        displayLayer.setAffineTransform(.identity)
        displayLayer.bounds = CGRect(origin: .zero, size: quarterTurn ? CGSize(width: bounds.height, height: bounds.width) : bounds.size)
        displayLayer.position = CGPoint(x: bounds.midX, y: bounds.midY)
        let turn = CGAffineTransform(rotationAngle: CGFloat(rotation.rawValue) * .pi / 180)
        displayLayer.setAffineTransform(mirrored ? turn.concatenating(CGAffineTransform(scaleX: -1, y: 1)) : turn)
        CATransaction.commit()
    }

    func setSize(_ size: CGSize) {}

    func renderFrame(_ frame: RTCVideoFrame?) {
        guard let frame, let pixelBuffer = Self.pixelBuffer(frame.buffer), let sample = Self.sampleBuffer(pixelBuffer) else { return }
        let renderer = displayLayer.sampleBufferRenderer
        lock.lock()
        if renderer.status == .failed || renderer.requiresFlushToResumeDecoding {
            flushes += 1
            renderer.flush()
        }
        if renderer.isReadyForMoreMediaData {
            renderer.enqueue(sample)
            enqueued += 1
        } else {
            notReady += 1
        }
        let quarterTurn = frame.rotation == ._90 || frame.rotation == ._270
        let displaySize = quarterTurn ? CGSize(width: CGFloat(frame.height), height: CGFloat(frame.width)) : CGSize(width: CGFloat(frame.width), height: CGFloat(frame.height))
        let changed = frame.rotation != rotation || displaySize != size
        let first = size == .zero
        rotation = frame.rotation
        size = displaySize
        lock.unlock()
        if changed {
            DispatchQueue.main.async { [self] in
                setNeedsLayout()
                if first { onSize(displaySize) }
            }
        }
    }

    func drainCounters() -> String {
        let renderer = displayLayer.sampleBufferRenderer
        lock.lock()
        defer { enqueued = 0; notReady = 0; flushes = 0; lock.unlock() }
        return "enqueued=\(enqueued) notReady=\(notReady) flushes=\(flushes) status=\(renderer.status.rawValue) "
            + "flushNeeded=\(renderer.requiresFlushToResumeDecoding) size=\(size) error=\(renderer.error.map { "\($0)" } ?? "nil")"
    }

    private static func pixelBuffer(_ buffer: RTCVideoFrameBuffer) -> CVPixelBuffer? {
        if let cv = buffer as? RTCCVPixelBuffer { return cv.pixelBuffer }
        let i420 = buffer.toI420()
        let width = Int(i420.width), height = Int(i420.height)
        var out: CVPixelBuffer?
        let attrs = [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary
        guard CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, attrs, &out) == kCVReturnSuccess,
              let out else { return nil }
        CVPixelBufferLockBaseAddress(out, [])
        defer { CVPixelBufferUnlockBaseAddress(out, []) }
        let yDst = CVPixelBufferGetBaseAddressOfPlane(out, 0)!.assumingMemoryBound(to: UInt8.self)
        let yStride = CVPixelBufferGetBytesPerRowOfPlane(out, 0)
        for row in 0..<height {
            memcpy(yDst + row * yStride, i420.dataY + row * Int(i420.strideY), width)
        }
        let uvDst = CVPixelBufferGetBaseAddressOfPlane(out, 1)!.assumingMemoryBound(to: UInt8.self)
        let uvStride = CVPixelBufferGetBytesPerRowOfPlane(out, 1)
        for row in 0..<Int(i420.chromaHeight) {
            let u = i420.dataU + row * Int(i420.strideU), v = i420.dataV + row * Int(i420.strideV)
            let dst = uvDst + row * uvStride
            for col in 0..<Int(i420.chromaWidth) {
                dst[2 * col] = u[col]
                dst[2 * col + 1] = v[col]
            }
        }
        return out
    }

    private static func sampleBuffer(_ pixelBuffer: CVPixelBuffer) -> CMSampleBuffer? {
        var format: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: pixelBuffer, formatDescriptionOut: &format) == noErr,
              let format else { return nil }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()), decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: nil, imageBuffer: pixelBuffer, formatDescription: format, sampleTiming: &timing, sampleBufferOut: &sample) == noErr,
              let sample else { return nil }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true), CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sample
    }
}
