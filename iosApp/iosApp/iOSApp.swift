import SwiftUI
import FirebaseCore
import FirebaseCrashlytics
import FirebaseMessaging
import ComposeApp
import UserNotifications

class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

  func application(_ application: UIApplication,
                   didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey : Any]? = nil) -> Bool {

      FirebaseApp.configure() //important

      //By default showPushNotification value is true.
      //When set showPushNotification to false foreground push  notification will not be shown.
      //You can still get notification content using #onPushNotification listener method.C
      NotifierManager.shared.initialize(configuration: NotificationPlatformConfigurationIos(
            showPushNotification: false,
            askNotificationPermissionOnStart: true,
            notificationSoundName: nil
          )
      )

      // Register notification categories with reply and mark-as-read actions
      registerNotificationCategories()

      // Set delegate for handling notification actions
      UNUserNotificationCenter.current().delegate = self

    return true
  }

  private func registerNotificationCategories() {
      let replyAction = UNTextInputNotificationAction(
          identifier: "REPLY_ACTION",
          title: "Reply",
          textInputButtonTitle: "Send",
          textInputPlaceholder: "Message..."
      )
      let markReadAction = UNNotificationAction(
          identifier: "MARK_READ_ACTION",
          title: "Mark as Read",
          options: []
      )
      let messageCategory = UNNotificationCategory(
          identifier: "MESSAGE_CATEGORY",
          actions: [replyAction, markReadAction],
          intentIdentifiers: [],
          options: .allowAnnouncement
      )
      UNUserNotificationCenter.current().setNotificationCategories([messageCategory])
  }

  // Present notifications while the app is in the foreground
  func userNotificationCenter(
      _ center: UNUserNotificationCenter,
      willPresent notification: UNNotification,
      withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) {
      // Don't present system notification in foreground — NotificationService
      // will show an in-app banner via Compose instead.
      completionHandler([])
  }

  // Handle notification action responses (reply, mark as read)
  func userNotificationCenter(
      _ center: UNUserNotificationCenter,
      didReceive response: UNNotificationResponse,
      withCompletionHandler completionHandler: @escaping () -> Void
  ) {
      let userInfo = response.notification.request.content.userInfo
      let conversationId = extractConversationId(from: userInfo)

      switch response.actionIdentifier {
      case "REPLY_ACTION":
          if let textResponse = response as? UNTextInputNotificationResponse,
             let convoId = conversationId {
              let replyText = textResponse.userText
              let notificationId = response.notification.request.identifier
              let bridge = NotificationActionBridge.companion.fromKoin()
              bridge.sendReply(conversationId: convoId, text: replyText) { success in
                  if success.boolValue {
                      UNUserNotificationCenter.current().removeDeliveredNotifications(
                          withIdentifiers: [notificationId]
                      )
                  } else {
                      print("Failed to send notification reply")
                  }
                  completionHandler()
              }
              return
          }
      case "MARK_READ_ACTION":
          if let convoId = conversationId {
              let notificationId = response.notification.request.identifier
              UNUserNotificationCenter.current().removeDeliveredNotifications(
                  withIdentifiers: [notificationId]
              )
              let bridge = NotificationActionBridge.companion.fromKoin()
              bridge.markAsRead(conversationId: convoId) { success in
                  if !success.boolValue {
                      print("Failed to mark as read from notification")
                  }
                  completionHandler()
              }
              return
          }
      default:
          // Default tap — handled by NotifierManager.onNotificationClicked
          NotifierManager.shared.onApplicationDidReceiveRemoteNotification(userInfo: userInfo)
      }

      completionHandler()
  }

  /// Extracts the conversation ID (typeId) from the push payload's data JSON.
  private func extractConversationId(from userInfo: [AnyHashable: Any]) -> String? {
      guard let dataString = userInfo["data"] as? String,
            let jsonData = dataString.data(using: .utf8),
            let payload = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
            let options = payload["options"] as? [String: Any],
            let typeId = options["typeId"] as? String,
            !typeId.isEmpty
      else { return nil }
      return typeId
  }

  func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Messaging.messaging().apnsToken = deviceToken
  }

  func application(_ application: UIApplication,
                   didReceiveRemoteNotification userInfo: [AnyHashable : Any],
                   fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
      NotifierManager.shared.onApplicationDidReceiveRemoteNotification(userInfo: userInfo)
      let orchestrator = BackgroundSyncOrchestrator.companion.fromKoin()
      orchestrator.triggerSync { success in
          completionHandler(success == true ? .newData : .failed)
      }
  }
    
}

@main
struct iOSApp: App {
    
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate$
    init() {
        // Inject FFmpegKit bridge into the Kotlin framework
        FFmpegKitBridgeHolder.shared.setBridge(bridge: FFmpegKitBridgeImpl())

        // Inject Crashlytics bridge into the Kotlin framework
        CrashlyticsBridgeHolder.shared.setBridge(bridge: CrashlyticsBridgeImpl())
    }
    
    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    handleShareURL(url)
                }
        }
    }

    /// Handles `homebase-share://send?conversationId=X` URLs from the share extension.
    private func handleShareURL(_ url: URL) {
        guard url.scheme == "homebase-share",
              url.host == "send",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let conversationId = components.queryItems?.first(where: { $0.name == "conversationIds" })?.value
        else { return }

        ShareHandlerBridge.shared.handleIncomingShare(conversationId: conversationId)
    }
}
