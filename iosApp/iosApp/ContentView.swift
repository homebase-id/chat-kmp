import UIKit
import SwiftUI
import ComposeApp
import os.log

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
    @Environment(\.colorScheme) var colorScheme
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
        .onChange(of: colorScheme) { _, newScheme in
            os_log("colorScheme changed to %{public}@", log: textRenderLog, type: .info, String(describing: newScheme))
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                nudgeMetalLayer(trigger: "colorScheme→\(newScheme)")
            }
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
