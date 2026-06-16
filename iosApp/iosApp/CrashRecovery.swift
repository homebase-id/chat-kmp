import SwiftUI
import ComposeApp
import UIKit

/// Holds the pending crash report path so ContentView can gate Compose until the
/// user taps Continue. Set during didFinishLaunching's arm-and-check step.
final class CrashRecoveryModel: ObservableObject {
    static let shared = CrashRecoveryModel()
    @Published var pendingReportPath: String?
    private init() {}
}

private enum CrashBrand {
    static let primary = dyn(0x2C58C3, 0xB6C5FA)
    static let onPrimary = dyn(0xFFFFFF, 0x1E2438)
    static let surface = dyn(0xFBFCFF, 0x1B1C1F)
    static let onSurface = dyn(0x1B1B1D, 0xE2E1E5)
    static let onSurfaceVariant = dyn(0x545863, 0xBEBFC5)
    static let secondaryContainer = dyn(0xDCE5F9, 0x414659)
    static let onSecondaryContainer = dyn(0x151D2C, 0xDCE1F9)
    static let surfaceVariant = dyn(0xE7EBF3, 0x303133)

    private static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? rgb(dark) : rgb(light) })
    }
    private static func rgb(_ hex: UInt32) -> UIColor {
        UIColor(
            red: CGFloat((hex >> 16) & 0xFF) / 255.0,
            green: CGFloat((hex >> 8) & 0xFF) / 255.0,
            blue: CGFloat(hex & 0xFF) / 255.0,
            alpha: 1.0
        )
    }
}

struct CrashRecoveryView: View {
    let reportPath: String
    @State private var showDetails = false
    @State private var showShare = false

    var body: some View {
        ZStack {
            CrashBrand.surface.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 18) {
                    Text("(╯°□°)╯︵ ┻━┻")
                        .font(.system(size: 36))
                        .foregroundColor(CrashBrand.primary)
                        .padding(.top, 64)
                    Text("Well, this is awkward.")
                        .font(.title2.weight(.semibold))
                        .foregroundColor(CrashBrand.onSurface)
                        .multilineTextAlignment(.center)
                    Text("Homebase tripped over its own feet and had to sit down for a second. You really shouldn't be seeing this — but here we are. Let's get you back.")
                        .multilineTextAlignment(.center)
                        .foregroundColor(CrashBrand.onSurfaceVariant)
                        .padding(.horizontal, 24)

                    VStack(spacing: 12) {
                        Button { continueToApp() } label: {
                            Text("Continue")
                                .font(.headline)
                                .foregroundColor(CrashBrand.onPrimary)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                                .background(CrashBrand.primary)
                                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        }
                        .buttonStyle(.plain)

                        Button { showShare = true } label: {
                            Text("Share crash report")
                                .font(.headline)
                                .foregroundColor(CrashBrand.onSecondaryContainer)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                                .background(CrashBrand.secondaryContainer)
                                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.horizontal, 28)
                    .padding(.top, 12)

                    DisclosureGroup("Technical details", isExpanded: $showDetails) {
                        CrashReportTextView(text: reportText)
                            .frame(height: 320)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .background(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .fill(CrashBrand.surfaceVariant)
                            )
                            .padding(.top, 8)
                    }
                    .tint(CrashBrand.primary)
                    .padding(.horizontal, 24)
                    .padding(.top, 4)

                    Text("Your stuff is safe. Sharing the report helps us fix this.")
                        .font(.footnote)
                        .foregroundColor(CrashBrand.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                        .padding(.bottom, 24)
                }
            }
        }
        .sheet(isPresented: $showShare) {
            CrashShareSheet(items: [URL(fileURLWithPath: reportPath)])
        }
    }

    private var reportText: String {
        (try? String(contentsOfFile: reportPath, encoding: .utf8)) ?? "(report unavailable)"
    }

    private func continueToApp() {
        MainViewControllerKt.clearPendingCrash()
        MainViewControllerKt.initializeApp()
        CrashRecoveryModel.shared.pendingReportPath = nil
    }
}

struct CrashShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

struct CrashReportTextView: UIViewRepresentable {
    let text: String

    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView()
        tv.isEditable = false
        tv.isSelectable = true
        tv.isScrollEnabled = true
        tv.alwaysBounceVertical = true
        tv.backgroundColor = .clear
        tv.font = UIFont.monospacedSystemFont(ofSize: 11, weight: .regular)
        tv.textColor = UIColor(CrashBrand.onSurfaceVariant)
        tv.textContainerInset = UIEdgeInsets(top: 12, left: 12, bottom: 12, right: 12)
        tv.text = text
        return tv
    }

    func updateUIView(_ uiView: UITextView, context: Context) {
        uiView.text = text
    }
}
