import UIKit
import QuartzCore
import ComposeApp
import os.log

let textRenderLog = OSLog(subsystem: "id.homebase.feed", category: "TextRendering")

/// Walk the view hierarchy to find CAMetalLayer-backed views (Skiko's SkikoUIView)
/// and call setNeedsDisplay() on each, forcing Skia to rebuild the glyph atlas.
func nudgeMetalLayer(trigger: String) {
    guard let view = MainViewControllerRef.shared.instance?.view else {
        os_log("[%{public}@] MainViewControllerRef.instance is nil — nudge skipped", log: textRenderLog, type: .fault, trigger)
        return
    }

    let inWindow = view.window != nil
    os_log("[%{public}@] root view found (inWindow=%{public}@, frame=%{public}@)", log: textRenderLog, type: .info,
           trigger, String(describing: inWindow), String(describing: view.frame))

    view.layoutIfNeeded()

    var metalViewCount = 0
    forceSkiaRedraw(in: view, trigger: trigger, count: &metalViewCount)

    if metalViewCount == 0 {
        os_log("[%{public}@] NO CAMetalLayer view found in hierarchy — nudge had no target", log: textRenderLog, type: .error, trigger)
    } else {
        os_log("[%{public}@] nudged %d CAMetalLayer-backed view(s)", log: textRenderLog, type: .info, trigger, metalViewCount)
    }
}

private func forceSkiaRedraw(in view: UIView, trigger: String, count: inout Int) {
    if let metalLayer = view.layer as? CAMetalLayer {
        count += 1
        let hasDevice = metalLayer.device != nil
        let drawableSize = metalLayer.drawableSize
        let fbOnly = metalLayer.framebufferOnly
        os_log("[%{public}@] found CAMetalLayer view: type=%{public}@, device=%{public}@, drawableSize=%.0fx%.0f, framebufferOnly=%{public}@",
               log: textRenderLog, type: .info,
               trigger,
               String(describing: type(of: view)),
               String(describing: hasDevice),
               drawableSize.width, drawableSize.height,
               String(describing: fbOnly))
        view.setNeedsDisplay()
    }
    for subview in view.subviews {
        forceSkiaRedraw(in: subview, trigger: trigger, count: &count)
    }
}

/// Bridge implementation that Compose KMP can invoke via TextRenderingHelper.
class MetalLayerNudger: TextRenderingNudger {
    func nudge() {
        DispatchQueue.main.async {
            nudgeMetalLayer(trigger: "compose-tap")
        }
    }
}

// MARK: - Blank-text diagnostics marker (device shake)

extension Notification.Name {
    /// Posted by `UIWindow.motionEnded` below when the user shakes the device.
    static let deviceDidShake = Notification.Name("id.homebase.feed.deviceDidShake")
    /// Posted by `BlankTextTriggerToastListener` when the Kotlin blank-text auto-trigger fires.
    static let blankTextTriggerFired = Notification.Name("id.homebase.feed.blankTextTriggerFired")
}

/// Receives the Kotlin blank-text auto-trigger (observe-only) and surfaces it as a native toast in
/// ContentView. Native SwiftUI text renders even while Compose is blank, so the toast is visible at
/// exactly the moment we need a tester to judge: was the screen actually blank (true positive) or
/// fine (false positive)? Registered in `iOSApp.init`.
class BlankTextTriggerToastListener: BlankTextTriggerListener {
    func onTriggerFired(usedBytes: Int64, peakBytes: Int64) {
        os_log("BLANK-TEXT trigger fired (observe-only): used=%lld peak=%lld", log: textRenderLog, type: .error, usedBytes, peakBytes)
        DispatchQueue.main.async {
            NotificationCenter.default.post(
                name: .blankTextTriggerFired,
                object: nil,
                userInfo: ["used": usedBytes, "peak": peakBytes]
            )
        }
    }
}

extension UIWindow {
    /// Canonical SwiftUI shake-detection hook: UIKit delivers the shake motion up the responder
    /// chain to the key window, so catching it here works app-wide without stealing first-responder
    /// status from Compose text fields. We only post a Notification; ContentView observes it.
    open override func motionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {
        if motion == .motionShake {
            NotificationCenter.default.post(name: .deviceDidShake, object: nil)
        }
    }
}

/// Shake recovery v2 (SURFACE RECREATION) — logging half. The recovery itself is in ContentView:
/// it bumps `.id()` on `ComposeView`, which tears down the ComposeUIViewController and rebuilds it,
/// giving skiko a fresh GrDirectContext + empty glyph atlas (the only lever that can rebuild the
/// dead atlas; purge+recompose was field-falsified three times). This helper reads the (read-only)
/// CAMetalLayer state from the CURRENT view hierarchy (old controller for phase "before", new one
/// for phase "after-recreate") and forwards it to Kotlin so it lands in the shareable homebase.log.
func logShakeRecoveryState(phase: String) {
    var count = 0
    var devicePresent = false
    var drawableW = 0.0
    var drawableH = 0.0
    if let view = MainViewControllerRef.shared.instance?.view {
        view.layoutIfNeeded()
        collectMetalState(in: view, count: &count, devicePresent: &devicePresent, drawableW: &drawableW, drawableH: &drawableH)
    } else {
        os_log("shake[%{public}@]: MainViewControllerRef.instance is nil", log: textRenderLog, type: .fault, phase)
    }

    IosGpuTextDiagnosticsKt.logShakeRecovery(
        phase: phase,
        metalLayerCount: Int32(count),
        metalDevicePresent: devicePresent,
        drawableWidth: drawableW,
        drawableHeight: drawableH
    )
}

/// Read-only walk: collect CAMetalLayer state without touching it (no `setNeedsDisplay`).
private func collectMetalState(in view: UIView, count: inout Int, devicePresent: inout Bool, drawableW: inout Double, drawableH: inout Double) {
    if let metalLayer = view.layer as? CAMetalLayer {
        count += 1
        if metalLayer.device != nil { devicePresent = true }
        drawableW = Double(metalLayer.drawableSize.width)
        drawableH = Double(metalLayer.drawableSize.height)
    }
    for subview in view.subviews {
        collectMetalState(in: subview, count: &count, devicePresent: &devicePresent, drawableW: &drawableW, drawableH: &drawableH)
    }
}
