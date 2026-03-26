import UIKit
import SwiftUI
import Intents

/// Main entry point for the iOS Share Extension.
/// Checks auth, loads the conversation cache, shows a picker,
/// then saves shared content and hands off to the main app.
class ShareViewController: UIViewController {

    override func viewDidLoad() {
        super.viewDidLoad()

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
        let (conversations, _) = ShareConversationCacheReader.load()

        // 4. Present SwiftUI picker
        let pickerView = SharePickerView(
            conversations: conversations,
            onSelect: { [weak self] conversationIds in
                self?.handleSelection(conversationIds: conversationIds)
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
              let firstId = conversationIds.first else {
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

    /// Open the main app via URL scheme to complete the send.
    private func openMainApp(conversationIds: String) {
        guard let url = URL(string: "homebase-share://send?conversationIds=\(conversationIds)") else {
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
