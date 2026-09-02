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
    // PREVENTION: don't build ComposeView until the scene has been .active at least once. iOS can
    // launch the process in the BACKGROUND (prewarm / push relaunch) and still connect the scene;
    // Compose's first frame then renders while backgrounded, iOS rejects the Metal submissions, and
    // the glyph atlas is born dead → all text blank at the first real foreground (fingerprint
    // fontCacheUsed=6889 count=4, 3/3 field captures). Latched true forever after — backgrounding
    // later must NOT tear the view down.
    @State private var hasBeenActive = false
    // Crash recovery: when a crash report from the previous run is pending, show the
    // native SwiftUI recovery screen INSTEAD of Compose until the user taps Continue
    // (which runs the deferred heavy init and flips pendingReportPath back to nil).
    @ObservedObject private var crashModel = CrashRecoveryModel.shared

    var body: some View {
        if let reportPath = crashModel.pendingReportPath {
            CrashRecoveryView(reportPath: reportPath)
        } else {
            mainContent
        }
    }

    private var mainContent: some View {
        ZStack {
            if hasBeenActive || scenePhase == .active {
                ComposeView()
                    .ignoresSafeArea()
            } else {
                // Native placeholder while the scene has never been active (background launch, or
                // the first milliseconds of a normal launch before .active lands).
                Color(UIColor.systemBackground)
                    .ignoresSafeArea()
            }

            if showPrivacyOverlay {
                PrivacyOverlayView()
                    .ignoresSafeArea()
                    .transition(.opacity)
            }
        }
        .onAppear {
            // PREVENTION latch (companion to the .active onChange): if the scene is already active
            // when we appear, onChange never fires — latch here so a later backgrounding can never
            // un-build ComposeView (tearing it down on .background would recreate the very bug).
            if scenePhase == .active && !hasBeenActive {
                hasBeenActive = true
                IosGpuTextDiagnosticsKt.logPrevention(note: "appeared already .active — ComposeView built immediately")
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
                // PREVENTION latch: first .active ever → ComposeView gets built now (and stays built
                // through later backgrounding). Log it so homebase.log shows the deferral timeline.
                if !hasBeenActive {
                    hasBeenActive = true
                    IosGpuTextDiagnosticsKt.logPrevention(note: "first .active — ComposeView built now (deferred since launch)")
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
