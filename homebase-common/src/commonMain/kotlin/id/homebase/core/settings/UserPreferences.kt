package id.homebase.core.settings

import com.russhwolf.settings.Settings
import id.homebase.api.image.MediaQuality
import id.homebase.api.image.SendQuality
import id.homebase.api.share.NotificationContentLevel
import id.homebase.api.share.NotificationExtensionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock
import kotlin.uuid.Uuid

class UserPreferences(private val settings: Settings) {
    private val _preferenceState = MutableStateFlow(
        PreferenceState(
            theme = theme,
            hapticsEnabled = hapticsEnabled,
            showDeveloperMenu = showDeveloperMenu,
            sendQualities = SendQualityApp.entries.associateWith { sendQuality(it) },
            autoSaveIncomingMedia = autoSaveIncomingMedia,
            autoSaveOnUnmeteredOnly = autoSaveOnUnmeteredOnly,
            enterSendsMessage = enterSendsMessage,
            arrowUpEditsLastMessage = arrowUpEditsLastMessage,
            mirrorFrontCamera = mirrorFrontCamera,
        )
    )
    val preferenceState: StateFlow<PreferenceState> = _preferenceState

    var language: String
        get() = settings.getString("language", "system")
        set(value) = settings.putString("language", value)

    var theme: ThemeState
        get() {
            val theme = settings.getString("theme", "System")
            return ThemeState.valueOf(theme)
        }
        set(value) {
            settings.putString("theme", value.name)
            _preferenceState.value = _preferenceState.value.copy(theme = value)
        }

    /**
     * Mirrored into [preferenceState] because it now gates UI chrome that must react immediately:
     * the Email setup toolbar entry is built from this in AppNavHost, and reading the plain
     * property there would leave the icon missing until the next app start.
     */
    var showDeveloperMenu: Boolean
        get() = settings.getBoolean("show_developer_menu", false)
        set(value) {
            settings.putBoolean("show_developer_menu", value)
            _preferenceState.value = _preferenceState.value.copy(showDeveloperMenu = value)
        }

    /**
     * Feed tab mode: the native KMP feed (default) vs the legacy WebView feed. Lets users opt back
     * to the WebView while the native feed is being polished. Read by AppNavHost's Feed route.
     */
    var useNativeFeed: Boolean
        get() = settings.getBoolean("use_native_feed", true)
        set(value) = settings.putBoolean("use_native_feed", value)

    /** The one-time sheet shown the first time a sender turns view once on. */
    var viewOnceIntroSeen: Boolean
        get() = settings.getBoolean("view_once_intro_seen", false)
        set(value) = settings.putBoolean("view_once_intro_seen", value)

    /** Master switch for in-app haptic feedback (default on). Read by GatedHaptics. */
    var hapticsEnabled: Boolean
        get() = settings.getBoolean("haptics_enabled", true)
        set(value) {
            settings.putBoolean("haptics_enabled", value)
            _preferenceState.value = _preferenceState.value.copy(hapticsEnabled = value)
        }

    /** The photo and video quality [app] sends with. */
    fun sendQuality(app: SendQualityApp): SendQuality {
        // Chat starts from the one setting every app shared before each had its own.
        val shared = if (app == SendQualityApp.Chat) quality("media_quality") else null
        return SendQuality(
            photo = quality("${app.key}_photo_quality") ?: shared ?: app.default.photo,
            video = (quality("${app.key}_video_quality") ?: shared)?.asVideoChoice() ?: app.default.video,
        )
    }

    fun setSendQuality(app: SendQualityApp, value: SendQuality) {
        settings.putString("${app.key}_photo_quality", value.photo.code)
        settings.putString("${app.key}_video_quality", value.video.code)
        _preferenceState.value = _preferenceState.value.let { it.copy(sendQualities = it.sendQualities + (app to value)) }
    }

    private fun quality(key: String): MediaQuality? = MediaQuality.fromCodeOrNull(settings.getStringOrNull(key))

    private fun MediaQuality.asVideoChoice(): MediaQuality = takeIf { it in SendQuality.VideoChoices } ?: MediaQuality.HD

    /**
     * Auto-save incoming chat photos and videos to the device album. Off by default — it writes
     * to storage the user never asked us to fill.
     */
    var autoSaveIncomingMedia: Boolean
        get() = settings.getBoolean("auto_save_incoming_media", false)
        set(value) {
            // Stamped on the way on so the first sync after enabling doesn't backfill the album
            // with every photo still in the recent-message window.
            if (value && !autoSaveIncomingMedia) {
                settings.putLong("auto_save_incoming_media_since", Clock.System.now().toEpochMilliseconds())
            }
            settings.putBoolean("auto_save_incoming_media", value)
            _preferenceState.value = _preferenceState.value.copy(autoSaveIncomingMedia = value)
        }

    /** Epoch ms at which [autoSaveIncomingMedia] was last switched on; 0 when it never was. */
    val autoSaveIncomingMediaSince: Long
        get() = settings.getLong("auto_save_incoming_media_since", 0L)

    // Saved but not read yet: Moments' and Feed's saves to the album are still to be built.
    var momentsAutoSaveIncomingMedia: Boolean
        get() = settings.getBoolean("moments_auto_save", false)
        set(value) = setAutoSave("moments_auto_save", value)

    var feedAutoSaveIncomingMedia: Boolean
        get() = settings.getBoolean("feed_auto_save", false)
        set(value) = setAutoSave("feed_auto_save", value)

    // Stamped like Chat's, so the save, once built, doesn't backfill the album with what came before.
    private fun setAutoSave(key: String, value: Boolean) {
        if (value && !settings.getBoolean(key, false)) settings.putLong("${key}_since", Clock.System.now().toEpochMilliseconds())
        settings.putBoolean(key, value)
    }

    /** Guard on [autoSaveIncomingMedia]: skip the download while the network is metered. */
    var autoSaveOnUnmeteredOnly: Boolean
        get() = settings.getBoolean("auto_save_unmetered_only", true)
        set(value) {
            settings.putBoolean("auto_save_unmetered_only", value)
            _preferenceState.value = _preferenceState.value.copy(autoSaveOnUnmeteredOnly = value)
        }

    var enterSendsMessage: Boolean
        get() = settings.getBoolean("composer_enter_sends", true)
        set(value) {
            settings.putBoolean("composer_enter_sends", value)
            _preferenceState.value = _preferenceState.value.copy(enterSendsMessage = value)
        }

    var arrowUpEditsLastMessage: Boolean
        get() = settings.getBoolean("composer_arrow_up_edits_last", true)
        set(value) {
            settings.putBoolean("composer_arrow_up_edits_last", value)
            _preferenceState.value = _preferenceState.value.copy(arrowUpEditsLastMessage = value)
        }

    var mirrorFrontCamera: Boolean
        get() = settings.getBoolean("camera_mirror_front", true)
        set(value) {
            settings.putBoolean("camera_mirror_front", value)
            _preferenceState.value = _preferenceState.value.copy(mirrorFrontCamera = value)
        }

    var preferredUserReactions: List<String>
        get() = settings.getStringOrNull("preferred_user_reactions")?.split(",") ?: listOf()
        set(value) = settings.putString("preferred_user_reactions", value.joinToString(","))

    // Notification preferences
    var playWhileAppOpen: Boolean
        get() = settings.getBoolean("notification_play_while_app_open", true)
        set(value) = settings.putBoolean("notification_play_while_app_open", value)

    var errorCollectionEnabled: Boolean
        get() = settings.getBoolean("error_collection_enabled", true)
        set(value) = settings.putBoolean("error_collection_enabled", value)


    // Installs that chose a level before the extension could read it still need it mirrored.
    init {
        NotificationExtensionStore.publishContentLevel(notificationContentLevel)
    }

    var notificationContentLevel: String
        get() = settings.getString("notification_content_level", NotificationContentLevel.NAME_CONTENT_ACTIONS.code)
        set(value) {
            settings.putString("notification_content_level", value)
            NotificationExtensionStore.publishContentLevel(value)
        }

    var includeMutedChatsInBadge: Boolean
        get() = settings.getBoolean("notification_include_muted_badge", false)
        set(value) = settings.putBoolean("notification_include_muted_badge", value)

    /**
     * Id of the conversation at the top of the chat list the last time the user was looking at it.
     * Persisted rather than held in memory because process death is the case index-based scroll
     * restore gets wrong.
     */
    var conversationListTopId: Uuid?
        get() {
            val raw = settings.getStringOrNull("conversationListTopId") ?: return null
            return try {
                Uuid.parse(raw)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        set(value) {
            if (value == null) settings.remove("conversationListTopId")
            else settings.putString("conversationListTopId", value.toString())
        }

   
    /**
     * Per-conversation scroll anchor — the uniqueId of the message the user
     * was looking at. Resolved to a list index against the freshly-loaded
     * messages on next open. Stored as a string (UUID) under
     * `conversationScrollAnchor-<conversationId>`.
     *
     * Replaces the older `conversationScrollIndex-*` int key, which was
     * meaningless across sessions because new messages between sessions
     * shift indices. The old key is abandoned with no migration: the first
     * open after upgrade falls through to "land at bottom" once.
     */
    fun getConversationScrollAnchor(conversationId: String): Uuid? {
        val raw = settings.getStringOrNull("conversationScrollAnchor-$conversationId")
            ?: return null
        return try {
            Uuid.parse(raw)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun setConversationScrollAnchor(conversationId: String, anchor: Uuid?) {
        val key = "conversationScrollAnchor-$conversationId"
        if (anchor == null) {
            settings.remove(key)
        } else {
            settings.putString(key, anchor.toString())
        }
    }

    fun getConversationScrollOffset(conversationId: String): Int? {
        return settings.getIntOrNull("conversationScrollOffset-$conversationId")
    }

    fun setConversationScrollOffset(conversationId: String, position: Int) {
        settings.putInt("conversationScrollOffset-$conversationId", position)
    }
}

data class PreferenceState(
    val theme: ThemeState,
    val hapticsEnabled: Boolean,
    val showDeveloperMenu: Boolean = false,
    val sendQualities: Map<SendQualityApp, SendQuality> = SendQualityApp.entries.associateWith { it.default },
    val autoSaveIncomingMedia: Boolean = false,
    val autoSaveOnUnmeteredOnly: Boolean = true,
    val enterSendsMessage: Boolean = true,
    val arrowUpEditsLastMessage: Boolean = true,
    val mirrorFrontCamera: Boolean = true,
)

fun PreferenceState.sendQuality(app: SendQualityApp): SendQuality = sendQualities.getValue(app)

/** The apps that send media people pick, each with its own [SendQuality] and default. */
enum class SendQualityApp(internal val key: String, val default: SendQuality) {
    Chat("chat", SendQuality.Standard),
    Moments("moments", SendQuality(MediaQuality.HD, MediaQuality.HD)),
    Feed("feed", SendQuality(MediaQuality.HD, MediaQuality.HD)),
}

enum class ThemeState {
    System,
    Dark,
    Light,
}

/**
 * Key-value store for small user preferences, shared by the whole app. Reads and writes are
 * synchronous and cheap; call from any thread. Absent keys return the caller's default.
 *
 * Persistence: values survive process restarts and app updates on every platform. Reinstall:
 * Android and iOS lose them (app data is removed on uninstall); desktop keeps them, since they
 * live in the OS preferences store (`java.util.prefs`, node `/id/homebase/app/window`) outside
 * the app; web keeps them in `localStorage` until the user clears site data, and a private
 * window may drop or reject them.
 *
 * Not secret: nothing here is encrypted. Android has no no-arg form: `createSettings()` throws
 * there; use `createSettings(Context)`, which backs this with SharedPreferences
 * `app_preferences`.
 */
expect fun createSettings(): Settings
