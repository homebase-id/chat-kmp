import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    
    init() {
        // Inject FFmpegKit bridge into the Kotlin framework
        FFmpegKitBridgeHolder.shared.setBridge(bridge: FFmpegKitBridgeImpl())
    }
    
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}