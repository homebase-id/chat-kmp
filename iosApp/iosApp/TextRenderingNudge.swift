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

/// Blank-text recovery EXPERIMENT, fired by a device shake. When the bug strikes every label is
/// unreadable, so the user can't tap an on-screen control — a physical shake works regardless.
///
/// We read the (read-only) CAMetalLayer state from the view hierarchy and hand it to Kotlin
/// `onBlankTextShake`, which logs the font-cache state to homebase.log BEFORE, attempts a recovery
/// (purge Skia caches + force a full Compose re-composition so glyphs re-rasterize), and logs the
/// font-cache state again ~1.5s later. The before/after counts tell us whether the recovery worked.
/// Passing the CAMetalLayer fields means the GPU-surface state lands in the shareable homebase.log
/// too (Kotlin can't read Compose's Metal view).
func captureAndRecoverOnShake() {
    os_log("BLANK-TEXT shake — capturing state + attempting recovery", log: textRenderLog, type: .error)

    var count = 0
    var devicePresent = false
    var drawableW = 0.0
    var drawableH = 0.0
    if let view = MainViewControllerRef.shared.instance?.view {
        view.layoutIfNeeded()
        collectMetalState(in: view, count: &count, devicePresent: &devicePresent, drawableW: &drawableW, drawableH: &drawableH)
    } else {
        os_log("shake: MainViewControllerRef.instance is nil", log: textRenderLog, type: .fault)
    }

    IosGpuTextDiagnosticsKt.onBlankTextShake(
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
