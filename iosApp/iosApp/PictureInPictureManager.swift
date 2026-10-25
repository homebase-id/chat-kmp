import AVFoundation
import AVKit
import UIKit
import ComposeApp
import Foundation
import WebRTC

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
            view.onFirstRenderedFrame = { [weak self] ms in self?.log("pip renderer got frames remote after \(ms)ms") }
            insetView?.onFirstRenderedFrame = { [weak self] ms in self?.log("pip renderer got frames self after \(ms)ms") }
            vc.view.addSubview(view)
            // Attached now so the first frame sizes the window before it opens; the view converts and enqueues only while rendering.
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
            selfView?.setRendering(false)
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

    // Telegram's rule: frames are converted and enqueued only while the window is up, not for the whole call.
    private func setRendering(_ on: Bool) {
        remoteView?.setRendering(on)
        selfView?.setRendering(on)
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

    func pictureInPictureControllerWillStartPictureInPicture(_ c: AVPictureInPictureController) {
        event("will start")
        if c === controller { setRendering(true) }
    }
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
        if c === controller { setRendering(false) }
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
        if c === controller { setRendering(false) }
        stopping.filter { $0.controller === c }.forEach { $0.release() }
        stopping.removeAll { $0.controller === c }
    }
}
