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
    @State private var triggerToast: String? = nil
    // Shake recovery v2: bumping this recreates ComposeView (SwiftUI dismantles + remakes the
    // ComposeUIViewController) → fresh skiko GrDirectContext → fresh glyph atlas. The only lever
    // that can rebuild the dead atlas (purge+recompose was field-falsified three times).
    @State private var composeViewEpoch = 0
    @State private var lastShakeAt = Date.distantPast
    // PREVENTION: don't build ComposeView until the scene has been .active at least once. iOS can
    // launch the process in the BACKGROUND (prewarm / push relaunch) and still connect the scene;
    // Compose's first frame then renders while backgrounded, iOS rejects the Metal submissions, and
    // the glyph atlas is born dead → all text blank at the first real foreground (fingerprint
    // fontCacheUsed=6889 count=4, 3/3 field captures). Latched true forever after — backgrounding
    // later must NOT tear the view down.
    @State private var hasBeenActive = false

    var body: some View {
        ZStack {
            if hasBeenActive || scenePhase == .active {
                ComposeView()
                    .id(composeViewEpoch)
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

            // Native (UIKit) toast for the blank-text auto-trigger — renders even while Compose text
            // is blank, so a TestFlight tester can judge true vs. false positive at the moment it fires.
            if let msg = triggerToast {
                VStack {
                    Text(msg)
                        .font(.footnote.weight(.semibold))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(Color.red.opacity(0.92), in: Capsule())
                        .padding(.top, 12)
                    Spacer()
                }
                .transition(.move(edge: .top).combined(with: .opacity))
                .zIndex(1)
            }
        }
        .onAppear {
            os_log("onAppear fired", log: textRenderLog, type: .info)
            // PREVENTION latch (companion to the .active onChange): if the scene is already active
            // when we appear, onChange never fires — latch here so a later backgrounding can never
            // un-build ComposeView (tearing it down on .background would recreate the very bug).
            if scenePhase == .active && !hasBeenActive {
                hasBeenActive = true
                IosGpuTextDiagnosticsKt.logPrevention(note: "appeared already .active — ComposeView built immediately")
            }
            // DISABLED for the blank-text recovery experiment — the cold-start 150ms Metal nudge is a
            // bare setNeedsDisplay (replays cached blobs) that hasn't reliably fixed the bug and would
            // confound attribution of the shake-triggered purge+recompose recovery. Re-enable to restore.
            // DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            //     os_log("Cold-start nudge firing at 150ms", log: textRenderLog, type: .info)
            //     nudgeMetalLayer(trigger: "onAppear")
            // }
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
                // PREVENTION latch: first .active ever → ComposeView gets built now (and stays built
                // through later backgrounding). Log it so homebase.log shows the deferral timeline.
                if !hasBeenActive {
                    hasBeenActive = true
                    IosGpuTextDiagnosticsKt.logPrevention(note: "first .active — ComposeView built now (deferred since launch)")
                }
                // DISABLED for the experiment (see onAppear): the foreground nudge would mask the blank
                // and confound the shake recovery. nudgeMetalLayer(trigger: "scenePhase→active")
            default:
                break
            }
        }
        .onChange(of: colorScheme) { _, newScheme in
            os_log("colorScheme changed to %{public}@", log: textRenderLog, type: .info, String(describing: newScheme))
            // DISABLED for the experiment (see onAppear):
            // DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            //     nudgeMetalLayer(trigger: "colorScheme→\(newScheme)")
            // }
        }
        .onReceive(NotificationCenter.default.publisher(for: .deviceDidShake)) { _ in
            // Shake recovery v2 — SURFACE RECREATION. Acknowledge visibly (native toast renders even
            // while Compose text is blank), log before-state, recreate the Compose view controller
            // (fresh GrDirectContext + empty atlas bookkeeping), then log after-state. Costs: the
            // composition rebuilds (navigation resets, in-progress typing lost) — acceptable on a
            // deliberate shake during an unusable screen. Debounced: double-shakes (seen in the
            // field) must not recreate twice.
            let now = Date()
            guard now.timeIntervalSince(lastShakeAt) > 5 else { return }
            lastShakeAt = now

            os_log("BLANK-TEXT shake — recreating Compose surface", log: textRenderLog, type: .error)
            withAnimation { triggerToast = "🔄 Shake: rebuilding screen to recover text… (logged — please share the log via Help, don't clear it)" }
            logShakeRecoveryState(phase: "before")
            composeViewEpoch += 1
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                logShakeRecoveryState(phase: "after-recreate")
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 7) {
                withAnimation { triggerToast = nil }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .blankTextTriggerFired)) { note in
            // Blank-text auto-trigger fired (observe-only). Show a native toast asking the tester to
            // judge true/false positive. Auto-dismisses after 6s.
            let used = (note.userInfo?["used"] as? Int64) ?? -1
            let peak = (note.userInfo?["peak"] as? Int64) ?? -1
            withAnimation { triggerToast = "⚠️ Blank-text trigger fired (cache \(used)B, peak \(peak)B). Is text missing? If not, it's a false positive." }
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) {
                withAnimation { triggerToast = nil }
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
