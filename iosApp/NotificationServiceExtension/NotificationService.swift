import UserNotifications
import Intents
import FirebaseCore
import os
#if canImport(HomebaseNotifKit)
import HomebaseNotifKit
#endif

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
        let silent = options["silent"] as? Bool ?? false

        if silent {
            contentHandler(content)
            return
        }

        let unEncryptedMessage = options["unEncryptedMessage"] as? String
        let appDisplayName = payload["appDisplayName"] as? String ?? "Homebase"

        // Set default content
        content.title = appDisplayName
        content.body = formatBody(
            senderId: senderId,
            senderName: senderId,
            appId: appId,
            typeId: typeId,
            unEncryptedMessage: unEncryptedMessage,
            appDisplayName: appDisplayName
        )
        content.sound = .default

        // Group by conversation for chat notifications
        if isChatNotification(appId: appId) {
            content.threadIdentifier = typeId
            content.categoryIdentifier = "MESSAGE_CATEGORY"
        }

        // Fetch sender avatar and apply Communication style
        fetchSenderAvatar(senderId: senderId) { [weak self] imageData in
            guard let self = self else {
                contentHandler(content)
                return
            }

            let senderName = self.extractDisplayName(from: senderId)

            content.body = self.formatBody(
                senderId: senderId,
                senderName: senderName,
                appId: appId,
                typeId: typeId,
                unEncryptedMessage: unEncryptedMessage,
                appDisplayName: appDisplayName
            )

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
            return updatedContent
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

    /// Wire-format sentinel that the chat sender writes for Event
    /// messages instead of the title — kept in sync with
    /// `EVENT_NOTIF_SENTINEL` in homebase-notifshared.
    private static let eventNotifSentinel = "__hb_event__|"

    /// Bridges to the shared KMP formatter (HomebaseNotifKit). Returns
    /// nil for non-event payloads (caller falls back to today's
    /// behavior).
    ///
    /// **Xcode wiring required for the full body format ("Event in 1
    /// hour"):** the NSE target needs `HomebaseNotifKit.framework`
    /// added to "Link Binary With Libraries" in its build settings.
    /// The iosApp's "Compile Kotlin Framework" Run Script already
    /// builds the framework via Gradle
    /// (`:homebase-notifshared:embedAndSignAppleFrameworkForXcode`),
    /// but linking it into the NSE is a manual Xcode-UI step that
    /// must be done on a Mac. Until that lands, the `#else` branch
    /// here renders a Swift-side fallback that strips the sentinel
    /// token and shows a generic body so iOS users never see the raw
    /// `__hb_event__|<ms>` string in their notification.
    private func tryFormatTypedEventBody(_ raw: String) -> String? {
        #if canImport(HomebaseNotifKit)
        return EventNotificationFormatterKt.tryFormatEventNotificationBodyNow(raw: raw)
        #else
        return fallbackEventBody(raw)
        #endif
    }

    /// Last-resort body when HomebaseNotifKit isn't linked. Recognizes
    /// the sentinel prefix and re-attaches a group suffix if present,
    /// but doesn't try to compute relative time — that's the
    /// framework's job. Shipped so iOS users get a usable notification
    /// even before the framework linkage lands.
    private func fallbackEventBody(_ raw: String) -> String? {
        guard raw.hasPrefix(Self.eventNotifSentinel) else { return nil }
        // Split off optional " in <groupName>" suffix. The body of the
        // token (after the sentinel) is a numeric timestamp, so " in "
        // can only appear as the group separator.
        let afterSentinel = String(raw.dropFirst(Self.eventNotifSentinel.count))
        if let inRange = afterSentinel.range(of: " in ") {
            let groupName = String(afterSentinel[inRange.upperBound...])
            return "Event in \(groupName)"
        }
        return "Event"
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
