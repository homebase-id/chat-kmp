package id.homebase.feed

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import co.touchlab.kermit.Logger
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.mmk.kmpnotifier.notification.NotifierManager
import com.mmk.kmpnotifier.notification.configuration.NotificationPlatformConfiguration
import id.homebase.api.ActivityProvider
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.sweepShareOutbound
import id.homebase.api.storage.SecureStorage
import id.homebase.api.storage.SharedPreferences
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.di.allModules
import id.homebase.core.diagnostics.MainThreadWatchdog
import id.homebase.core.logging.CrashLogger
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.logging.StartupLogger
import id.homebase.core.util.PlatformInfo
import chat_kmp.homebase_common.BuildConfig
import id.homebase.core.notifications.NotificationService
import id.homebase.core.notifications.RichNotificationDisplayer
import id.homebase.core.settings.UserPreferences
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.feed.share.ShareShortcutAvatarLoader
import id.homebase.feed.share.ShareShortcutPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.GlobalContext.startKoin

class MainApplication : Application(), KoinComponent {
    override fun onCreate() {
        super.onCreate()

        if (getProcessName() != packageName) return

        // Earliest app-code mark. The existing "APP STARTUP" header is logged late (after
        // DB + Koin), so these checkpoints are what actually size the cold-start phases —
        // diff their log timestamps. See StartupLogger.
        StartupLogger.checkpoint("onCreate start")

        // Register the application Context up-front so components that only
        // need Context (cacheDir, ContentResolver — e.g. FFmpegUtils Android
        // actuals, VideoThumbnailExtractor) can resolve it before MainActivity
        // exists, and instrumented tests can register a test Context the same
        // way. Activity-scoped access still goes through ActivityProvider.initialize.
        ActivityProvider.initializeApplicationContext(this)

        // Initialize storage (must be done before App() which may access storage)
        SecureStorage.initialize(this)
        SharedPreferences.initialize(this) // TODO: Maybe we should use injectable UserPreferences

        runBlocking {
            DatabaseManager.initializeWithRecovery(DatabaseDriverFactory(applicationContext))
        }
        StartupLogger.checkpoint("db init done")

        startKoin {
            // Log Koin into Android logger
            androidLogger()
            // Reference Android context
            androidContext(this@MainApplication)
            // Load modules
            modules(allModules)
        }
        StartupLogger.checkpoint("koin started")

        // Configure Crashlytics based on user preference
        val userPreferences = get<UserPreferences>()
        Firebase.crashlytics.isCrashlyticsCollectionEnabled = userPreferences.errorCollectionEnabled

        try {
            val logsDir = filesDir.resolve("logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            LoggerConfig.initialize(logDirectory =  Path(logsDir.absolutePath))
        } catch (e: Exception) {
            Logger.e("MainApplication", e, "Failed to initialize file logging")
        }

        val platformInfo = get<PlatformInfo>()
        StartupLogger.logAppStartupInfo(platformInfo.versionName, platformInfo.versionCode, BuildConfig.APP_BUILD_TIME)

        // Set up uncaught exception handler for crash logging
        setupCrashHandler()

        // Detect main-thread stalls before Android ANRs. Logs the main-thread stack to
        // homebase.log when a frame budget is exceeded — gives us a usable trace ~1s
        // before the OS would kill us at 5s.
        //
        // TEMPORARY (diagnostic): threshold lowered 4000→800ms and throttle 30s→1s to
        // capture the cold-boot "tap on a visible list does nothing for ~2s" stall, which
        // sits under the normal 4s ANR-grade threshold (build 1674 log: selectedConversationId
        // set at T but the navigation effect didn't run until T+2.2s — Main was wedged but
        // both off-Main suspects, selectAllUnreadCount and ShareShortcutPublisher, were ruled
        // out). The low throttle is so an earlier first-composition stall can't swallow the
        // single report and hide the tap stall. Revert to MainThreadWatchdog() once the cold
        // first-tap Main stack is captured and the cause is pinned.
        MainThreadWatchdog(thresholdMs = 800, throttleMs = 1_000).start()

        // Provide application context for rich notifications
        RichNotificationDisplayer.initialize(this, smallIconResId = R.mipmap.ic_launcher_monochrome)

        // Create notification channels (Android O+)
        createNotificationChannels()

        // Initialize KMPNotifier for push notifications
        NotifierManager.initialize(
            configuration =
                NotificationPlatformConfiguration.Android(
                    notificationIconResId = R.mipmap.ic_launcher_foreground,
                    showPushNotification = false,
                    notificationChannelData =
                        NotificationPlatformConfiguration.Android.NotificationChannelData(
                            id = "messages",
                            name = "Messages",
                        ),
                )
        )

        // Register notification listener immediately so tokens/pushes arriving
        // before the UI composes are not lost
        val notificationService: NotificationService = get()
        notificationService.startListening()
        // Brackets createNotificationChannels + NotifierManager.initialize() (KMPNotifier/
        // Firebase) — a prime suspect for cold-process stalls on the FCM boot.
        StartupLogger.checkpoint("notifications init done")

        // Publish share shortcuts when conversations change
        initShareShortcuts()

        // Reap cleartext share-OUT temps every time the app reaches the
        // foreground. The chat "Share to other app" flow writes decrypted
        // copies of E2EE Homebase payloads into <cacheDir>/share_outbound/
        // and hands the URI to Intent.ACTION_SEND via FileProvider; the
        // receiving app reads it through the URI grant. Once we're back in
        // foreground, the receiving app has had its read window — we delete
        // the cleartext so it doesn't sit on disk indefinitely.
        // Cold-start is covered by StartupCacheAudit; this is the bound on
        // a same-process share's on-disk lifetime.
        registerShareOutboundForegroundSweep()

        StartupLogger.checkpoint("onCreate end")
    }

    private fun registerShareOutboundForegroundSweep() {
        val fileOps = get<FileOperationsProvider>()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                appScope.launch {
                    runCatching { sweepShareOutbound(fileOps.getCacheDirectory()) }
                        .onFailure { Logger.w("share_outbound foreground sweep failed", it) }
                }
            }
        })
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun initShareShortcuts() {
        val conversationStream = get<ConversationStream>()
        val avatarLoader = ShareShortcutAvatarLoader(conversationStream, get())
        val publisher = ShareShortcutPublisher(
            context = this,
            shareableConversations = conversationStream.shareableConversations,
            loadAvatarIcon = avatarLoader::loadIcon,
        )
        publisher.start(appScope)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Messages channel — chat, mail, community (high priority for heads-up)
            nm.createNotificationChannel(
                NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    enableVibration(true)
                    setShowBadge(true)
                    description = "Chat messages, mail, and community notifications"
                }
            )

            // Social channel — followers, connection requests, introductions
            nm.createNotificationChannel(
                NotificationChannel("social", "Social", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    description = "Followers, connection requests, and introductions"
                }
            )

            // Feed channel — posts, reactions, comments
            nm.createNotificationChannel(
                NotificationChannel("feed", "Feed", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    description = "New posts, reactions, and comments"
                }
            )
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                CrashLogger.logCrash(thread.name, throwable)
            } catch (e: Exception) {
                // If crash logging fails, still call the default handler
                e.printStackTrace()
            } finally {
                // Call the original handler to let the app crash normally
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
