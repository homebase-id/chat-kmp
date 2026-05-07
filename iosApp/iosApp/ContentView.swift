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

    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            .onAppear {
                // Cold-start: .onChange(of: scenePhase) won't fire when .active
                // is already the initial phase, so the glyph-atlas stays stale.
                // Post a delayed nudge to cover the first-launch path.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                    nudgeMetalLayer()
                }
            }
            .onChange(of: scenePhase) { _, newPhase in
                if newPhase == .active {
                    nudgeMetalLayer()
                }
            }
    }

    private func nudgeMetalLayer() {
        let view = MainViewControllerRef.shared.instance?.view
        view?.setNeedsLayout()
        view?.layer.setNeedsDisplay()
    }
}



