import UserNotifications
import Intents
import FirebaseCore
import os
import HomebaseNotifKit

class NotificationService: UNNotificationServiceExtension {

    var contentHandler: ((UNNotificationContent) -> Void)?
    var bestAttemptContent: UNMutableNotificationContent?

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        // Configure Firebase so Crashlytics' signal handler captures crashes in THIS
        // extension process — it has its own bundle/process, so the main app's
        // FirebaseApp.configure() does not cover it. Guarded so repeated didReceive
        // calls configure only once.
        if FirebaseApp.app() == nil {
            FirebaseApp.configure()
        }

        // Push-chain breadcrumb (#988), Console.app-only by design: the NSE is a separate
        // process — Kermit/homebase.log is not initialized here and two-process writes to
        // the rolling log file are unsafe. The NSE only decorates the visible notification;
        // the capture chain (onPushArrived → forceCapture → upload) runs in the main app's
        // didReceiveRemoteNotification, whose hops DO land in homebase.log.
        os_log("NSE didReceive: mutable-content push arrived (dataKeys=%d)",
               (request.content.userInfo.count))

        self.contentHandler = contentHandler
        bestAttemptContent = request.content.mutableCopy() as? UNMutableNotificationContent

        guard let content = bestAttemptContent else {
            contentHandler(request.content)
            return
        }

        // Redacted before any early return or the expiry path hands `content` over as-is; an
        // empty field stays empty so a silent push isn't turned into a visible one.
        let contentLevel = NotificationPreviewBridge.shared.contentLevel() ?? Self.fullContentLevel
        if !Self.showsRealContent(contentLevel) {
            if !content.body.isEmpty { content.body = Self.redactedBody }
            if contentLevel == Self.noNameOrContentLevel, !content.title.isEmpty {
                content.title = "Homebase"
            }
        }

        // Parse the push payload from the "data" field
        guard let dataString = request.content.userInfo["data"] as? String,
              let jsonData = dataString.data(using: .utf8),
              let payload = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any]
        else {
            contentHandler(content)
            return
        }

        let senderId = payload["senderId"] as? String ?? ""
        let options = payload["options"] as? [String: Any] ?? [:]
        let appId = options["appId"] as? String ?? ""
        let typeId = options["typeId"] as? String ?? ""
        let tagId = options["tagId"] as? String ?? ""
        let silent = options["silent"] as? Bool ?? false

        if silent {
            contentHandler(content)
            return
        }

        let unEncryptedMessage = options["unEncryptedMessage"] as? String
        let appDisplayName = payload["appDisplayName"] as? String ?? "Homebase"
        let encryptedPreview = payload["encryptedPreview"] as? String

        // Sequenced ahead of the avatar fetch rather than raced with it: the tray read is a
        // local call that returns in milliseconds, so nesting costs almost nothing of the
        // 30s budget and needs no join, and it puts the badge on `content` — which is also
        // `bestAttemptContent` — before any terminal contentHandler path can run.
        applyBadgeFromTray(to: content) { [weak self] in
            guard let self = self else {
                contentHandler(content)
                return
            }
            self.resolveChatPreview(
                appId: appId,
                conversationId: typeId,
                messageId: tagId,
                encryptedPreview: encryptedPreview,
                contentLevel: contentLevel
            ) { preview in
                self.decorate(
                    content: content,
                    senderId: senderId,
                    appId: appId,
                    typeId: typeId,
                    unEncryptedMessage: preview ?? unEncryptedMessage,
                    appDisplayName: appDisplayName,
                    contentLevel: contentLevel,
                    contentHandler: contentHandler
                )
            }
        }
    }

    // MARK: - Decrypted Content

    /// Same contract as Android's in-app resolver: the message is decrypted here, on the device,
    /// from the header sealed into the push or else fetched, and any miss leaves the sender's
    /// placeholder in place.
    private func resolveChatPreview(
        appId: String,
        conversationId: String,
        messageId: String,
        encryptedPreview: String?,
        contentLevel: String,
        completion: @escaping (String?) -> Void
    ) {
        guard Self.showsRealContent(contentLevel),
              [Self.chatAppId, Self.chatAppIdUuid].contains(appId),
              !conversationId.isEmpty, !messageId.isEmpty
        else {
            completion(nil)
            return
        }
        NotificationPreviewBridge.shared.resolvePreview(
            conversationId: conversationId,
            messageId: messageId,
            encryptedPreview: encryptedPreview
        ) { preview, missReason in
            // Public: Kermit's default os_log writer redacts every line to <private>.
            if let missReason = missReason {
                os_log("NSE preview miss: %{public}@", missReason)
            }
            completion(preview)
        }
    }

    // MARK: - Content Level

    /// Swift keeps its own copy: codes must match `NotificationContentLevel` in homebase-api.
    private static let fullContentLevel = "name_content_actions"
    private static let nameOnlyLevel = "name_only"
    private static let noNameOrContentLevel = "no_name_or_content"
    private static let redactedBody = "New notification"

    private static func showsRealContent(_ level: String) -> Bool {
        level != nameOnlyLevel && level != noNameOrContentLevel
    }

    private func decorate(
        content: UNMutableNotificationContent,
        senderId: String,
        appId: String,
        typeId: String,
        unEncryptedMessage: String?,
        appDisplayName: String,
        contentLevel: String,
        contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        let showsContent = Self.showsRealContent(contentLevel)

        // Set default content
        content.title = appDisplayName
        content.body = showsContent
            ? formatBody(
                senderId: senderId,
                senderName: senderId,
                appId: appId,
                typeId: typeId,
                unEncryptedMessage: unEncryptedMessage,
                appDisplayName: appDisplayName
            )
            : Self.redactedBody
        content.sound = .default

        // Group by conversation for chat notifications. Content-less pushes get the
        // Reply-only category — no Mark as Read when there's nothing to read (#983).
        if isChatNotification(appId: appId) {
            content.threadIdentifier = typeId
            content.categoryIdentifier = showsContent && hasRealContent(unEncryptedMessage)
                ? "MESSAGE_CATEGORY"
                : "MESSAGE_NO_CONTENT_CATEGORY"
        }

        // No sender either: skip the avatar and the Communication style, which both name them.
        if contentLevel == Self.noNameOrContentLevel {
            content.title = "Homebase"
            contentHandler(content)
            return
        }

        // Fetch sender avatar and apply Communication style
        fetchSenderAvatar(senderId: senderId) { [weak self] imageData in
            guard let self = self else {
                contentHandler(content)
                return
            }

            let senderName = self.extractDisplayName(from: senderId)

            if showsContent {
                content.body = self.formatBody(
                    senderId: senderId,
                    senderName: senderName,
                    appId: appId,
                    typeId: typeId,
                    unEncryptedMessage: unEncryptedMessage,
                    appDisplayName: appDisplayName
                )
            } else {
                content.title = senderName
            }

            // Apply Communication Notification style for chat messages
            if self.isChatNotification(appId: appId) {
                if let updatedContent = self.applyCommunicationStyle(
                    content: content,
                    senderId: senderId,
                    senderName: senderName,
                    conversationId: typeId,
                    avatarData: imageData
                ) {
                    contentHandler(updatedContent)
                    return
                }
            }

            // Fallback: attach avatar as image
            if let imageData = imageData {
                self.attachAvatar(to: content, imageData: imageData)
            }

            contentHandler(content)
        }
    }

    override func serviceExtensionTimeWillExpire() {
        if let contentHandler = contentHandler, let content = bestAttemptContent {
            contentHandler(content)
        }
    }

    // MARK: - App Icon Badge

    private let badgeQueue = DispatchQueue(label: "id.homebase.nse.badge")

    /// The tray is the count, so nothing is persisted between runs: a notification the
    /// user swiped away or an extension run iOS skipped costs one push of drift, not a
    /// stored total that never comes back. `+1` is this notification, not yet in the tray.
    ///
    /// Counted here rather than sent as `aps.badge`: the payload transits the
    /// Odin.PushNotification relay and Firebase, and the unread total must not join it.
    private func applyBadgeFromTray(
        to content: UNMutableNotificationContent,
        then next: @escaping () -> Void
    ) {
        let queue = badgeQueue
        var resumed = false
        let resume: (Int?) -> Void = { delivered in
            queue.async {
                guard !resumed else { return }
                resumed = true
                if let delivered = delivered {
                    content.badge = NSNumber(value: delivered + 1)
                }
                next()
            }
        }

        UNUserNotificationCenter.current().getDeliveredNotifications { resume($0.count) }
        // The push outranks the badge, so a tray read that never calls back can't hold it.
        queue.asyncAfter(deadline: .now() + 2) { resume(nil) }
    }

    // MARK: - Communication Notification Style

    private func applyCommunicationStyle(
        content: UNMutableNotificationContent,
        senderId: String,
        senderName: String,
        conversationId: String,
        avatarData: Data?
    ) -> UNNotificationContent? {
        let handle = INPersonHandle(value: senderId, type: .unknown)

        var personImage: INImage?
        if let data = avatarData {
            personImage = INImage(imageData: data)
        }

        let sender = INPerson(
            personHandle: handle,
            nameComponents: nil,
            displayName: senderName,
            image: personImage,
            contactIdentifier: nil,
            customIdentifier: senderId
        )

        let intent = INSendMessageIntent(
            recipients: nil,
            outgoingMessageType: INOutgoingMessageType.unknown,
            content: content.body,
            speakableGroupName: nil,
            conversationIdentifier: conversationId,
            serviceName: "Homebase",
            sender: sender,
            attachments: nil
        )

        let interaction = INInteraction(intent: intent, response: nil)
        interaction.direction = INInteractionDirection.incoming
        interaction.donate(completion: nil)

        do {
            let updatedContent = try content.updating(from: intent)
            guard let restamped = updatedContent.mutableCopy() as? UNMutableNotificationContent
            else { return updatedContent }
            // updating(from:) is not documented to carry the badge across.
            restamped.badge = content.badge
            return restamped
        } catch {
            return nil
        }
    }

    // MARK: - Avatar Fetching

    private func fetchSenderAvatar(senderId: String, completion: @escaping (Data?) -> Void) {
        guard !senderId.isEmpty,
              let url = URL(string: "https://\(senderId)/pub/image")
        else {
            completion(nil)
            return
        }

        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 5
        let session = URLSession(configuration: config)

        session.dataTask(with: url) { data, response, error in
            guard let data = data,
                  let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200,
                  !data.isEmpty
            else {
                completion(nil)
                return
            }
            completion(data)
        }.resume()
    }

    private func attachAvatar(to content: UNMutableNotificationContent, imageData: Data) {
        let tempDir = NSTemporaryDirectory()
        let fileName = UUID().uuidString + ".jpg"
        let filePath = tempDir + fileName

        guard (try? imageData.write(to: URL(fileURLWithPath: filePath))) != nil,
              let attachment = try? UNNotificationAttachment(
                  identifier: "sender_avatar",
                  url: URL(fileURLWithPath: filePath),
                  options: nil
              )
        else { return }

        content.attachments = [attachment]
    }

    // MARK: - Body Formatting

    /// App IDs — must match AppConfig values in KMP code
    /// Chat APP_ID is stored without dashes in KMP but may arrive in either format
    private static let chatAppId = "2d78140138044b57b4aad8e4e2ef39f4"
    private static let chatAppIdUuid = "2d781401-3804-4b57-b4aa-d8e4e2ef39f4"
    private static let mailAppId = "6e8ecfff-7c15-40e4-94f4-d6e83bfb5857"
    private static let communityAppId = "77ed6136-6b33-4654-8088-3d89c91e6065"
    private static let feedAppId = "5f887d80-0132-4294-ba40-bda79155551d"
    private static let ownerAppId = "ac126e09-54cb-4878-a690-856be692da16"

    /// Owner type IDs
    private static let ownerFollowerTypeId = "2cc468af-109b-4216-8119-542401e32f4d"
    private static let ownerConnectionRequestTypeId = "8ee62e9e-c224-47ad-b663-21851207f768"
    private static let ownerConnectionAcceptedTypeId = "79f0932a-056e-490b-8208-3a820ad7c321"
    private static let ownerIntroReceivedTypeId = "f100bfa0-ac4e-468a-9322-bdaf6059ec8a"
    private static let ownerIntroAcceptedTypeId = "f56ee792-56dd-45fd-8f9e-f96bb5d0e3de"

    /// Feed type IDs
    private static let feedNewContentTypeId = "ad695388-c2df-47a0-ad5b-fc9f9e1fffc9"
    private static let feedNewReactionTypeId = "37dae95d-e137-4bd4-b782-8512aaa2c96a"
    private static let feedNewCommentTypeId = "1e08b70a-3826-4840-8372-18410bfc02c7"

    private func isChatNotification(appId: String) -> Bool {
        [Self.chatAppId, Self.chatAppIdUuid, Self.mailAppId, Self.communityAppId].contains(appId)
    }

    /// Sender-side placeholder body for chat pushes — not real content. Keep in
    /// sync with `CONTENTLESS_PLACEHOLDER` in the shared NotificationService.kt.
    private static let contentlessPlaceholder = "You have a new message"

    /// Mirrors the `hasContent` test in the shared NotificationService.kt.
    private func hasRealContent(_ unEncryptedMessage: String?) -> Bool {
        guard let message = unEncryptedMessage, !message.isEmpty else { return false }
        return message != Self.contentlessPlaceholder
    }

    private func formatBody(
        senderId: String,
        senderName: String,
        appId: String,
        typeId: String,
        unEncryptedMessage: String?,
        appDisplayName: String
    ) -> String {
        if let message = unEncryptedMessage, !message.isEmpty {
            // Privacy-aware short-circuit: typed messages (currently
            // Events) send a sentinel token instead of the message
            // content. The shared KMP formatter recognizes it and
            // renders a body that never leaks the title.
            if let formatted = tryFormatTypedEventBody(message) {
                return formatted
            }
            return message.replacingOccurrences(of: senderId, with: senderName)
        }

        switch appId {
        case Self.ownerAppId:
            switch typeId {
            case Self.ownerFollowerTypeId:
                return "\(senderName) started following you"
            case Self.ownerConnectionRequestTypeId:
                return "\(senderName) sent you a connection request"
            case Self.ownerConnectionAcceptedTypeId:
                return "\(senderName) accepted your connection request"
            case Self.ownerIntroReceivedTypeId:
                return "\(senderName) introduced you to someone"
            case Self.ownerIntroAcceptedTypeId:
                return "\(senderName) confirmed the introduction"
            default:
                return "\(senderName) sent you a notification via \(appDisplayName)"
            }

        case Self.chatAppId, Self.chatAppIdUuid, Self.mailAppId, Self.communityAppId:
            return "\(senderName) sent a message"

        case Self.feedAppId:
            switch typeId {
            case Self.feedNewContentTypeId:
                return "\(senderName) uploaded a new post"
            case Self.feedNewReactionTypeId:
                return "\(senderName) reacted to your post"
            case Self.feedNewCommentTypeId:
                return "\(senderName) commented on your post"
            default:
                return "\(senderName) sent you a notification via \(appDisplayName)"
            }

        default:
            return "\(senderName) sent you a notification via \(appDisplayName)"
        }
    }

    /// Privacy-aware Event token (`__hb_event__|<ms>`) rendered by the shared KMP formatter;
    /// nil for any other body.
    private func tryFormatTypedEventBody(_ raw: String) -> String? {
        EventNotificationFormatterKt.tryFormatEventNotificationBodyNow(raw: raw)
    }

    /// Extracts a display name from the sender's domain.
    /// e.g. "frodo.baggins.me" -> "Frodo Baggins"
    private func extractDisplayName(from senderId: String) -> String {
        let parts = senderId.split(separator: ".")
        if parts.count >= 2 {
            let name = parts.prefix(parts.count - 1)
                .map { $0.prefix(1).uppercased() + $0.dropFirst() }
                .joined(separator: " ")
            if !name.isEmpty { return name }
        }
        return senderId
    }
}
