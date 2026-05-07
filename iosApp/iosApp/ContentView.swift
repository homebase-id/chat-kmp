import UIKit
import SwiftUI
import ComposeApp

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
        .onChange(of: scenePhase) { _, newPhase in
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
                DispatchQueue.main.async {
                    let view = MainViewControllerRef.shared.instance?.view
                    view?.setNeedsLayout()
                    view?.layer.setNeedsDisplay()
                }
            default:
                break
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



