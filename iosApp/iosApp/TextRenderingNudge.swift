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
