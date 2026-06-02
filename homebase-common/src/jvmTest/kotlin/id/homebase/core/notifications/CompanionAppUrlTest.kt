package id.homebase.core.notifications

import id.homebase.core.config.COMMUNITY_APP_ID
import id.homebase.core.config.FEED_APP_ID
import id.homebase.core.config.MAIL_APP_ID
import id.homebase.core.config.OWNER_APP_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the redirect URL a tapped notification builds for the web-app companions
 * (community, owner, mail, feed). These open the *logged-in* identity's own web
 * app — NOT the message sender's domain. The companion web clients are served
 * from and authenticated against our own identity; the author's host has no
 * session for us. Mirrors the RN app (which built these from getIdentity()) and
 * the in-app Feed WebView (https://{ownDomain}/apps/feed).
 *
 * Regression guard: the original KMP port used notification.senderId here, which
 * sent the user to a stranger's domain on tap.
 */
class CompanionAppUrlTest {

    private val ownDomain = "frodo.dotyou.cloud"

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
    fun unknownApp_returnsNull() {
        // Chat (and anything else) navigates in-app, not via this helper.
        assertNull(buildCompanionAppUrlEvent("2d78140138044b57b4aad8e4e2ef39f4", ownDomain, "a", "b"))
    }
}
