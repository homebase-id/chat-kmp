import UIKit
import SwiftUI
import QuartzCore
import ComposeApp
import os.log

private let textRenderLog = OSLog(subsystem: "id.homebase.feed", category: "TextRendering")

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    static func dismantleUIViewController(_ uiViewController: UIViewController, coordinator: ()) {
        // Clear the reference to allow proper cleanup
        if let currentInstance = MainViewControllerRef.shared.instance,
           currentInstance === uiViewController {
            MainViewControllerRef.shared.instance = nil
        }
    }
}

struct ContentView: View {
    @Environment(\.scenePhase) var scenePhase
    @State private var showPrivacyOverlay = false

    var body: some View {
        ZStack {
            ComposeView()
                .ignoresSafeArea()

            if showPrivacyOverlay {
                PrivacyOverlayView()
                    .ignoresSafeArea()
                    .transition(.opacity)
            }
        }
        .onAppear {
            os_log("onAppear fired — scheduling cold-start Metal nudge (150ms)", log: textRenderLog, type: .info)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                os_log("Cold-start nudge firing at 150ms", log: textRenderLog, type: .info)
                nudgeMetalLayer(trigger: "onAppear")
            }
        }
        .onChange(of: scenePhase) { _, newPhase in
            os_log("scenePhase changed to %{public}@", log: textRenderLog, type: .info, String(describing: newPhase))
            switch newPhase {
            case .inactive:
                if VaultPrivacyBridge.shared.shouldProtect {
                    withAnimation(.easeIn(duration: 0.15)) {
                        showPrivacyOverlay = true
                    }
                }
            case .active:
                withAnimation(.easeOut(duration: 0.15)) {
                    showPrivacyOverlay = false
                }
                nudgeMetalLayer(trigger: "scenePhase→active")
            default:
                break
            }
        }
    }

    private func nudgeMetalLayer(trigger: String) {
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
            os_log("[%{public}@] ⚠️ NO CAMetalLayer view found in hierarchy — nudge had no target", log: textRenderLog, type: .error, trigger)
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
}

private struct PrivacyOverlayView: View {
    var body: some View {
        ZStack {
            Color(UIColor.systemBackground)
            VStack(spacing: 24) {
                Image(systemName: "lock")
                    .font(.system(size: 64, weight: .thin))
                    .foregroundColor(.accentColor)
                VStack(spacing: 8) {
                    Text(String(localized: "Vault is locked"))
                        .font(.title3.weight(.medium))
                        .foregroundColor(.primary)
                    Text(String(localized: "Authenticate to access your vault"))
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            }
        }
    }
}
