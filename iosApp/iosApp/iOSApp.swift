import SwiftUI
import FirebaseCore
import FirebaseCrashlytics
import FirebaseMessaging
import ComposeApp
import SafariServices
import UserNotifications

class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

  func application(_ application: UIApplication,
                   didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey : Any]? = nil) -> Bool {

      // Configure Firebase FIRST — before any heavy init. FirebaseApp.configure()
      // is what arms Crashlytics' signal/Mach/NSException handlers; until it runs,
      // nothing is captured. initializeApp() below starts Koin, file logging, and a
      // runBlocking database open/migration, any of which can crash on launch for
      // some users. Configuring first means those launch crashes get reported
      // instead of vanishing. (Android gets this ordering for free via Firebase's
      // auto-init ContentProvider, which runs before Application.onCreate; iOS has
      // no such hook, so the order here is load-bearing.)
      FirebaseApp.configure()

      // Run Koin, logging, and database init before any UI framework setup.
      // This keeps the main-thread run-loop free between heavy init and the
      // first Compose frame, preventing the iOS text-rendering race condition.
      //
      // Arm crash handling FIRST (no Koin/DB) and check for a crash from last run.
      // If there is one, DEFER heavy init until the user taps Continue (avoids an
      // init-time crash loop). Otherwise initialize normally.
      if let pending = MainViewControllerKt.armCrashHandlingAndCheckPending() {
          CrashRecoveryModel.shared.pendingReportPath = pending
      } else {
          MainViewControllerKt.initializeApp()
      }

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
      // Reply-from-notification is disabled until the flow is hardened and pushes show real
      // content (#1048 / #859): the send can silently fail, and replying blind to the
      // content-less "You have a new message" push is nonsensical. Flip to re-enable; the
      // action stays defined (dormant).
      let replyFromNotificationEnabled = false

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
          actions: replyFromNotificationEnabled ? [replyAction, markReadAction] : [markReadAction],
          intentIdentifiers: [],
          options: []
      )
      // Reply-only category for content-less pushes ("You have a new message"
      // placeholder) — no Mark as Read when there's nothing to read (#983).
      // The extension picks between the two categories. With reply disabled this
      // carries no actions.
      let messageNoContentCategory = UNNotificationCategory(
          identifier: "MESSAGE_NO_CONTENT_CATEGORY",
          actions: replyFromNotificationEnabled ? [replyAction] : [],
          intentIdentifiers: [],
          options: []
      )
      UNUserNotificationCenter.current().setNotificationCategories(
          [messageCategory, messageNoContentCategory]
      )
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
          // Default tap (UNNotificationDefaultActionIdentifier): route to the
          // shared tap handler so NotificationService.handleNotificationClicked
          // runs — it sets PendingNotificationTap and emits the OpenConversation /
          // OpenMoment navigation event that ConversationListViewModel / AppNavHost
          // act on. This previously called onApplicationDidReceiveRemoteNotification,
          // which is the push-ARRIVAL entry (re-displays + background-syncs) and
          // never emits a navigation event — so chat-message taps opened nothing.
          // Build the payload the same way the arrival path does: skip the "aps"
          // envelope; the "data" key holds the JSON the handler deserialises.
          var data: [String: Any] = [:]
          for (k, v) in userInfo {
              guard let key = k as? String, key != "aps" else { continue }
              if let str = v as? String { data[key] = str }
          }
          NotificationEntry.companion.fromKoin().onNotificationTappedAsync(payload: data) {}
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
      // Funnel into the shared NotificationEntry.onPushArrived so iOS goes
      // through the same code path as Android (DriveSyncWorker invokes the
      // same method). onPushArrived runs both the rich-notification display
      // (NotificationService.onFcmMessageReceived → handleIncomingPayload)
      // and the background sync (BackgroundSyncOrchestrator.syncIfAuthenticated).
      let aps = userInfo["aps"] as? [String: Any]
      let alert = aps?["alert"] as? [String: Any]
      let title = alert?["title"] as? String
      let body = alert?["body"] as? String
      var data: [String: String] = [:]
      for (k, v) in userInfo {
          guard let key = k as? String, key != "aps" else { continue }
          if let str = v as? String { data[key] = str }
      }
      // Hop 1 of the push→capture chain (#988) — lands in homebase.log via the exported
      // Kotlin bridge (Kermit is up: initializeApp ran in didFinishLaunching). appState
      // raw values: 0=active, 1=inactive, 2=background. The delegate runs on the main
      // thread, so reading applicationState here is legal.
      PushChainLoggingKt.logPushChain(
          hop: "received(ios)",
          detail: "appState=\(application.applicationState.rawValue) hasAps=\(aps != nil) dataKeys=\(data.count)")
      let entry = NotificationEntry.companion.fromKoin()
      entry.onPushArrivedAsync(title: title, body: body, data: data) { success in
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
                    handleIncomingURL(url)
                }
        }
    }

    private func handleIncomingURL(_ url: URL) {
        switch url.scheme {
        // Accept both the prod scheme and the dev build's variant (see SHARE_URL_SCHEME /
        // ShareViewController.shareUrlScheme) — the app only ever receives its own.
        case "homebase-share", "homebase-share-dev":
            if url.host == "moment" {
                ShareHandlerBridge.shared.handleIncomingMomentShare()
            } else {
                handleShareURL(url)
            }
        case "homebase-fchat":
            switch url.host {
            case "permission-callback":
                handlePermissionCallbackURL(url)
            case "data-upgrade-callback":
                handleDataUpgradeCallbackURL()
            case "create-account-callback":
                handleCreateAccountCallbackURL(url)
            default:
                break
            }
        default:
            break
        }
    }

    /// Handles `homebase-share://send?conversationId=X` URLs from the share extension.
    private func handleShareURL(_ url: URL) {
        guard url.host == "send",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let conversationId = components.queryItems?.first(where: { $0.name == "conversationIds" })?.value
        else { return }

        ShareHandlerBridge.shared.handleIncomingShare(conversationId: conversationId)
    }

    /// Handles `homebase-fchat://data-upgrade-callback` URLs returned from the
    /// owner-console data-upgrade page. Triggers an immediate upgrade status re-check.
    private func handleDataUpgradeCallbackURL() {
        DataUpgradeCallbackBridge.shared.handleDataUpgradeCallback()
    }

    /// Handles `homebase-fchat://create-account-callback?domain=...`, the return leg of the
    /// sign-up flow. The owner console redirects at our scheme once the new identity is set up;
    /// SFSafariViewController hands that to the system, which reopens the app — with the browser
    /// still presented on top of it, so dismiss it before the login screen underneath is seen.
    private func handleCreateAccountCallbackURL(_ url: URL) {
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        if let domain = components?.queryItems?.first(where: { $0.name == "domain" })?.value,
           !domain.isEmpty {
            CreatedIdentityRelay.shared.deliver(domain: domain)
        }
        dismissInAppBrowser()
    }

    private func dismissInAppBrowser() {
        guard var top = UIApplication.shared.connectedScenes
            .compactMap({ ($0 as? UIWindowScene)?.keyWindow })
            .first?.rootViewController
        else { return }

        while let presented = top.presentedViewController {
            top = presented
        }
        if top is SFSafariViewController {
            top.dismiss(animated: true)
        }
    }

    /// Handles `homebase-fchat://permission-callback?status=...` URLs returned from the
    /// owner-console "Extend Permissions" flow. `status=canceled` routes the user back
    /// to the chat tab without re-prompting; success triggers a permission recheck.
    private func handlePermissionCallbackURL(_ url: URL) {
        guard url.host == "permission-callback" else { return }
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let status = components?.queryItems?.first(where: { $0.name == "status" })?.value?.lowercased()
        let canceled = (status == "canceled" || status == "cancelled")
        PermissionCallbackBridge.shared.handlePermissionCallback(canceled: canceled)
    }
}
