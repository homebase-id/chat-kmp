import UIKit
import SwiftUI
import Intents
import FirebaseCore

/// Main entry point for the iOS Share Extension.
/// Checks auth, loads the conversation cache, shows a picker,
/// then saves shared content and hands off to the main app.
class ShareViewController: UIViewController {

    override func viewDidLoad() {
        super.viewDidLoad()

        // Configure Firebase so Crashlytics captures crashes in the share-extension
        // process (separate bundle/process from the main app). Guarded against
        // re-entry across extension reuse.
        if FirebaseApp.app() == nil {
            FirebaseApp.configure()
        }

        // 1. Check authentication via App Group cache
        guard ShareAuthChecker.isAuthenticated() else {
            showError(
                title: "Not Signed In",
                message: "Please open Homebase Chat and sign in first."
            )
            return
        }

        // 2. Check if launched from a share suggestion (tapped a recommended contact)
        if let conversationId = extractConversationIdFromIntent() {
            handleSelection(conversationIds: [conversationId])
            return
        }

        // 3. Load conversation cache from App Group
        let (conversations, _, momentsActivated, ownerDomain, ownerDisplayName) =
            ShareConversationCacheReader.load()

        // "New Moment" needs media and an activated Moments feature — mirror the
        // Android picker's gating.
        let hasMedia = extensionContext.map { SharedContentSaver.hasMedia(in: $0) } ?? false

        // 4. Present SwiftUI picker
        let pickerView = SharePickerView(
            conversations: conversations,
            showMomentOption: momentsActivated && hasMedia,
            ownerDisplayName: ownerDisplayName,
            ownerDomain: ownerDomain,
            onSelect: { [weak self] conversationIds in
                self?.handleSelection(conversationIds: conversationIds)
            },
            onSelectMoment: { [weak self] in
                self?.handleMomentSelection()
            },
            onCancel: { [weak self] in
                self?.cancelExtension()
            }
        )

        let hostingController = UIHostingController(rootView: pickerView)
        addChild(hostingController)
        view.addSubview(hostingController.view)
        hostingController.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            hostingController.view.topAnchor.constraint(equalTo: view.topAnchor),
            hostingController.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            hostingController.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            hostingController.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        hostingController.didMove(toParent: self)
    }

    // MARK: - Intent Detection

    /// Extract conversation ID from INSendMessageIntent when launched via share suggestion.
    private func extractConversationIdFromIntent() -> String? {
        guard let intent = extensionContext?.intent as? INSendMessageIntent else {
            return nil
        }
        return intent.conversationIdentifier
    }

    // MARK: - Selection Handling

    private func handleSelection(conversationIds: [String]) {
        guard let extensionContext = extensionContext,
              !conversationIds.isEmpty else {
            cancelExtension()
            return
        }

        let joinedIds = conversationIds.joined(separator: ",")

        // Save shared content to App Group
        SharedContentSaver.save(
            extensionContext: extensionContext,
            conversationId: joinedIds
        ) { [weak self] success in
            if success {
                self?.openMainApp(conversationIds: joinedIds)
            } else {
                self?.showError(
                    title: "Error",
                    message: "Failed to prepare shared content."
                )
            }
        }
    }

    /// Stage the shared media for a new moment, then hand off to the main app's
    /// moments composer. Unlike the conversation path there's no target to pick —
    /// the composer (audience, trim, description) lives in the main app.
    private func handleMomentSelection() {
        guard let extensionContext = extensionContext else {
            cancelExtension()
            return
        }

        SharedContentSaver.save(
            extensionContext: extensionContext,
            conversationId: SharedContentSaver.momentTargetId
        ) { [weak self] success in
            if success {
                self?.openMainAppForMoment()
            } else {
                self?.showError(
                    title: "Error",
                    message: "Failed to prepare shared content."
                )
            }
        }
    }

    /// Variant-specific share URL scheme. A dev + prod build installed side by side must NOT
    /// both register the same scheme, or this extension's openURL hand-off can be claimed by
    /// the wrong app — which then reads a different App Group than the one we wrote to, so the
    /// share silently vanishes. Derived from the (already variant-specific) App Group id; must
    /// stay in sync with SHARE_URL_SCHEME in the xcconfigs that the main app registers.
    private var shareUrlScheme: String {
        SharedContentSaver.appGroupId.hasSuffix(".dev") ? "homebase-share-dev" : "homebase-share"
    }

    /// Open the main app's moments composer via URL scheme.
    private func openMainAppForMoment() {
        guard let url = URL(string: "\(shareUrlScheme)://moment") else {
            cancelExtension()
            return
        }
        openURL(url)
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }

    /// Open the main app via URL scheme to complete the send.
    private func openMainApp(conversationIds: String) {
        guard let url = URL(string: "\(shareUrlScheme)://send?conversationIds=\(conversationIds)") else {
            cancelExtension()
            return
        }

        // Use the responder chain to open the URL from an extension
        openURL(url)

        // Complete the extension
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }

    /// Opens a URL from an extension context using the responder chain.
    @objc private func openURL(_ url: URL) {
        var responder: UIResponder? = self
        while responder != nil {
            if let application = responder as? UIApplication {
                application.open(url, options: [:], completionHandler: nil)
                return
            }
            responder = responder?.next
        }

        // Fallback: use selector-based approach
        let selector = sel_registerName("openURL:")
        var currentResponder: UIResponder? = self
        while let r = currentResponder {
            if r.responds(to: selector) {
                r.perform(selector, with: url)
                return
            }
            currentResponder = r.next
        }
    }

    // MARK: - Error Handling

    private func showError(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.cancelExtension()
        })
        present(alert, animated: true)
    }

    private func cancelExtension() {
        extensionContext?.cancelRequest(
            withError: NSError(domain: "HomebaseShare", code: 0, userInfo: nil)
        )
    }
}
