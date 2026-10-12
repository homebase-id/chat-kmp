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


/// Remote call video in the system PiP window. AVPictureInPictureVideoCallViewController (iOS 15+) hosts a Metal view fed
/// by the remote track; PiP starts by itself when the app backgrounds. Capturing the local camera in the background needs
/// the com.apple.developer.avfoundation.multitasking-camera-access entitlement, which this app does not declare, so
/// outgoing video freezes there while the remote video and audio keep going.
final class PictureInPictureManager: NSObject, CallPictureInPictureBridge, RTCVideoViewDelegate, AVPictureInPictureControllerDelegate {
    static let shared = PictureInPictureManager()

    private var controller: AVPictureInPictureController?
    private var content: AVPictureInPictureVideoCallViewController?
    private var renderer: RTCMTLVideoView?
    private var track: RTCVideoTrack?
    private var possibleObservation: NSKeyValueObservation?

    func start(remoteTrack: Any) {
        guard let remote = remoteTrack as? RTCVideoTrack else {
            NSLog("[Call] PiP start skipped: remote track is %@", String(describing: type(of: remoteTrack)))
            return
        }
        DispatchQueue.main.async { [self] in
            teardown()
            guard AVPictureInPictureController.isPictureInPictureSupported() else {
                NSLog("[Call] PiP start skipped: isPictureInPictureSupported=false")
                return
            }
            guard let source = UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene }).flatMap({ $0.windows })
                    .first(where: { $0.isKeyWindow })?.rootViewController?.view
            else {
                NSLog("[Call] PiP start skipped: no key window root view")
                return
            }

            let vc = AVPictureInPictureVideoCallViewController()
            vc.preferredContentSize = CGSize(width: 9, height: 16)
            let view = RTCMTLVideoView(frame: vc.view.bounds)
            view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.videoContentMode = .scaleAspectFill
            view.delegate = self
            vc.view.addSubview(view)
            remote.add(view)

            let pip = AVPictureInPictureController(
                contentSource: AVPictureInPictureController.ContentSource(
                    activeVideoCallSourceView: source, contentViewController: vc))
            pip.canStartPictureInPictureAutomaticallyFromInline = true
            pip.delegate = self
            possibleObservation = pip.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { c, _ in
                NSLog("[Call] PiP possible=%d", c.isPictureInPicturePossible ? 1 : 0)
            }
            let audio = AVAudioSession.sharedInstance()
            NSLog("[Call] PiP armed: audioCategory=%@ mode=%@", audio.category.rawValue, audio.mode.rawValue)
            controller = pip
            content = vc
            renderer = view
            track = remote
        }
    }

    func stop() {
        DispatchQueue.main.async { [self] in teardown() }
    }

    private func teardown() {
        possibleObservation = nil
        controller?.canStartPictureInPictureAutomaticallyFromInline = false
        if controller?.isPictureInPictureActive == true { controller?.stopPictureInPicture() }
        if let renderer { track?.remove(renderer); renderer.delegate = nil; renderer.removeFromSuperview() }
        controller = nil
        content = nil
        renderer = nil
        track = nil
    }

    func pictureInPictureControllerWillStartPictureInPicture(_ c: AVPictureInPictureController) { NSLog("[Call] PiP will start") }
    func pictureInPictureControllerDidStartPictureInPicture(_ c: AVPictureInPictureController) { NSLog("[Call] PiP started") }
    func pictureInPictureController(_ c: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        NSLog("[Call] PiP failed to start: %@", String(describing: error))
    }
    func pictureInPictureControllerDidStopPictureInPicture(_ c: AVPictureInPictureController) { NSLog("[Call] PiP stopped") }

    func videoView(_ videoView: RTCVideoRenderer, didChangeVideoSize size: CGSize) {
        guard size.width > 0, size.height > 0 else { return }
        DispatchQueue.main.async { [self] in content?.preferredContentSize = size }
    }
}
