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
            .onChange(of: scenePhase) { _, newPhase in
                if newPhase == .active {
                    // Nudge the Metal layer to redraw after returning from background,
                    // working around stale Skia glyph-atlas caches on iOS.
                    DispatchQueue.main.async {
                        let view = MainViewControllerRef.shared.instance?.view
                        view?.setNeedsLayout()
                        view?.layer.setNeedsDisplay()
                    }
                }
            }
    }
}



