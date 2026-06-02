package id.homebase.core.notifications

import id.homebase.api.common.OdinId
import id.homebase.api.youauth.YouAuthState
import id.homebase.core.config.AppConfig
import id.homebase.core.config.COMMUNITY_APP_ID
import id.homebase.core.config.FEED_APP_ID
import id.homebase.core.config.MAIL_APP_ID
import id.homebase.core.config.OWNER_APP_ID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the redirect URL a tapped notification builds for the web-app companions
 * (community, owner, mail, feed). These open the *logged-in* identity's own web
 * app — NOT the message sender's domain. The companion web clients are served
 * from and authenticated against our own identity; the author's host has no
 * session for us. Mirrors the RN app (which built these from getIdentity()) and
 * the in-app Feed WebView (https://{ownDomain}/apps/feed).
 *
 * Regression guards:
 *  - the original KMP port used notification.senderId here, sending the user to
 *    a stranger's domain on tap;
 *  - reading the domain synchronously dropped the tap on cold start, before
 *    restoreSession() had populated credentials — [resolveCompanionAppUrlEvent]
 *    awaits auth restoration (reusing awaitAuthRestored) to close that race.
 */
class CompanionAppUrlTest {

    private val ownDomain = "frodo.dotyou.cloud"

    private val authenticated =
        YouAuthState.Authenticated(
            identity = OdinId(ownDomain),
            clientAuthToken = "tok",
            sharedSecret = "sec",
        )

    // region buildCompanionAppUrlEvent — pure URL mapping

    @Test
    fun community_opensOwnIdentityRedirect() {
        val event = buildCompanionAppUrlEvent(
            appId = COMMUNITY_APP_ID,
            ownDomain = ownDomain,
            typeId = "channel-1",
            tagId = "msg-9",
        )
        assertEquals(
            NotificationNavigationEvent.OpenUrl(
                "https://frodo.dotyou.cloud/apps/community/redirect/channel-1/msg-9"
            ),
            event,
        )
    }

    @Test
    fun owner_opensOwnConnections() {
        val event = buildCompanionAppUrlEvent(OWNER_APP_ID, ownDomain, "x", "y")
        assertEquals(
            NotificationNavigationEvent.OpenUrl("https://frodo.dotyou.cloud/owner/connections"),
            event,
        )
    }

    @Test
    fun mail_opensOwnInbox() {
        val event = buildCompanionAppUrlEvent(MAIL_APP_ID, ownDomain, "thread-1", "")
        assertEquals(
            NotificationNavigationEvent.OpenUrl(
                "https://frodo.dotyou.cloud/apps/mail/inbox/thread-1"
            ),
            event,
        )
    }

    @Test
    fun feed_withTag_opensPost() {
        val event = buildCompanionAppUrlEvent(FEED_APP_ID, ownDomain, "", "post-7")
        assertEquals(
            NotificationNavigationEvent.OpenUrl(
                "https://frodo.dotyou.cloud/apps/feed/post/post-7"
            ),
            event,
        )
    }

    @Test
    fun feed_withoutTag_opensFeedRoot() {
        val event = buildCompanionAppUrlEvent(FEED_APP_ID, ownDomain, "", "")
        assertEquals(
            NotificationNavigationEvent.OpenUrl("https://frodo.dotyou.cloud/apps/feed"),
            event,
        )
    }

    @Test
    fun nullDomain_whenLoggedOut_returnsNull() {
        // No active credentials → nothing to open; must not fabricate a URL.
        assertNull(buildCompanionAppUrlEvent(COMMUNITY_APP_ID, null, "a", "b"))
    }

    @Test
    fun blankDomain_returnsNull() {
        // Guards the isBlank() half of the isNullOrBlank() check: an empty domain
        // must not produce "https:///apps/community/...".
        assertNull(buildCompanionAppUrlEvent(COMMUNITY_APP_ID, "", "a", "b"))
    }

    @Test
    fun unknownApp_returnsNull() {
        // Chat (and anything else) navigates in-app, not via this helper.
        assertNull(buildCompanionAppUrlEvent(AppConfig.APP_ID, ownDomain, "a", "b"))
    }

    // endregion

    // region resolveCompanionAppUrlEvent — awaits credential restoration (cold-start race)

    @Test
    fun resolve_whenAlreadyAuthenticated_returnsUrl() = runTest {
        val authState = MutableStateFlow<YouAuthState>(authenticated)

        val event = resolveCompanionAppUrlEvent(
            appId = COMMUNITY_APP_ID,
            authState = authState,
            typeId = "channel-1",
            tagId = "msg-9",
            timeout = 2.seconds,
        )

        assertEquals(
            NotificationNavigationEvent.OpenUrl(
                "https://frodo.dotyou.cloud/apps/community/redirect/channel-1/msg-9"
            ),
            event,
        )
    }

    @Test
    fun resolve_awaitsRestoration_thenReturnsUrl() = runTest {
        // Cold start: authState is still Initializing at tap time, then flips to
        // Authenticated ~12 ms later as restoreSession() completes.
        val authState = MutableStateFlow<YouAuthState>(YouAuthState.Initializing)

        val job = launch {
            val event = resolveCompanionAppUrlEvent(
                appId = COMMUNITY_APP_ID,
                authState = authState,
                typeId = "c",
                tagId = "m",
                timeout = 2.seconds,
            )
            assertEquals(
                NotificationNavigationEvent.OpenUrl(
                    "https://frodo.dotyou.cloud/apps/community/redirect/c/m"
                ),
                event,
            )
        }

        launch {
            delay(12.milliseconds)
            authState.value = authenticated
        }

        job.join()
    }

    @Test
    fun resolve_timesOut_whenStuckInitializing_returnsNull() = runTest {
        // Wedged restoration — credentials never arrive within the budget. The tap
        // produces no navigation rather than hanging.
        val authState = MutableStateFlow<YouAuthState>(YouAuthState.Initializing)

        assertNull(
            resolveCompanionAppUrlEvent(COMMUNITY_APP_ID, authState, "c", "m", 2.seconds)
        )
    }

    @Test
    fun resolve_whenUnauthenticated_returnsNull() = runTest {
        // Logged-out tap of a stale notification resolves immediately to null.
        val authState = MutableStateFlow<YouAuthState>(YouAuthState.Unauthenticated)

        assertNull(
            resolveCompanionAppUrlEvent(COMMUNITY_APP_ID, authState, "c", "m", 2.seconds)
        )
    }

    // endregion
}
