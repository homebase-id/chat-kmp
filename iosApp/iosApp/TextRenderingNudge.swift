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

/// Log a blank-text marker. When the intermittent iOS blank-text bug strikes, every label is
/// unreadable so the user can't tap an on-screen control — but a physical shake works regardless.
/// On shake we LOG ONLY (no recovery, no Kotlin bridge): a loud marker plus the read-only state of
/// every CAMetalLayer-backed Skiko view (device present? drawableSize?), so the moment can be lined
/// up against the automatic `GpuTextDiag` entries in homebase.log. Distinct from `nudgeMetalLayer`,
/// which calls `setNeedsDisplay` to attempt a fix.
func logBlankTextMarker(trigger: String) {
    os_log("[%{public}@] BLANK-TEXT MARKER — user reported missing text", log: textRenderLog, type: .error, trigger)
    guard let view = MainViewControllerRef.shared.instance?.view else {
        os_log("[%{public}@] marker: MainViewControllerRef.instance is nil", log: textRenderLog, type: .fault, trigger)
        return
    }
    view.layoutIfNeeded()
    var count = 0
    logMetalLayerState(in: view, trigger: trigger, count: &count)
    if count == 0 {
        os_log("[%{public}@] marker: NO CAMetalLayer view found in hierarchy", log: textRenderLog, type: .error, trigger)
    }
}

/// Read-only walk: logs CAMetalLayer state without touching it (no `setNeedsDisplay`).
private func logMetalLayerState(in view: UIView, trigger: String, count: inout Int) {
    if let metalLayer = view.layer as? CAMetalLayer {
        count += 1
        os_log("[%{public}@] marker CAMetalLayer: type=%{public}@, device=%{public}@, drawableSize=%.0fx%.0f, framebufferOnly=%{public}@",
               log: textRenderLog, type: .info, trigger,
               String(describing: type(of: view)),
               String(describing: metalLayer.device != nil),
               metalLayer.drawableSize.width, metalLayer.drawableSize.height,
               String(describing: metalLayer.framebufferOnly))
    }
    for subview in view.subviews {
        logMetalLayerState(in: subview, trigger: trigger, count: &count)
    }
}
