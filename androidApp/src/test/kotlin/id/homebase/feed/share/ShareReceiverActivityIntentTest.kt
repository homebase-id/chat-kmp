package id.homebase.feed.share

import android.app.Application
import android.content.Intent
import id.homebase.feed.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Guards the regression where sharing into Homebase sent the message but never switched
 * to the conversation.
 *
 * The three send paths in [ShareReceiverActivity] (text-only single, text-only multi,
 * edited files) all re-open [MainActivity] on the target conversation. That deep link is
 * only routed to the conversation if it carries
 * [ShareShortcutPublisher.EXTRA_FROM_SHARE_SHORTCUT] == true — MainActivity.handleIntent()
 * uses that extra to pick `OpenConversation.Source.ShareIntent` (which AppNavHost routes via
 * selectConversationOnChatList) over `Source.NotificationTap` (which expects a
 * PendingNotificationTap/messageId a share never has, so it dead-ends). All three paths now
 * funnel through [ShareReceiverActivity.openConversationIntent]; this test pins that helper.
 */
@OptIn(ExperimentalUuidApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShareReceiverActivityIntentTest {

    // buildActivity(...).get() returns the activity WITHOUT running onCreate(), so the
    // lazy Koin `by inject()` services are never resolved — openConversationIntent only
    // needs the Activity's Context, nothing injected.
    private val activity: ShareReceiverActivity
        get() = Robolectric.buildActivity(ShareReceiverActivity::class.java).get()

    @Test
    fun `openConversationIntent carries EXTRA_FROM_SHARE_SHORTCUT so it routes as ShareIntent`() {
        val conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111")

        val intent = activity.openConversationIntent(conversationId)

        assertTrue(
            intent.getBooleanExtra(ShareShortcutPublisher.EXTRA_FROM_SHARE_SHORTCUT, false),
            "After sending, the intent re-opening MainActivity must carry " +
                "EXTRA_FROM_SHARE_SHORTCUT so MainActivity.handleIntent() classifies it as " +
                "OpenConversation.Source.ShareIntent. Without it the deep link falls into the " +
                "NotificationTap branch (which needs a messageId a share lacks) and the " +
                "conversation never opens.",
        )
    }

    @Test
    fun `openConversationIntent targets MainActivity with the conversation deep link`() {
        val conversationId = Uuid.parse("22222222-2222-2222-2222-222222222222")

        val intent = activity.openConversationIntent(conversationId)

        assertEquals(
            MainActivity::class.java.name,
            intent.component?.className,
            "The post-share intent must land in MainActivity, not the share receiver.",
        )
        assertEquals(
            "homebase-fchat://conversation/$conversationId",
            intent.data?.toString(),
            "The deep link must encode the just-shared-to conversation id so the app " +
                "switches to it.",
        )
    }

    @Test
    fun `openConversationIntent sets CLEAR_TOP and SINGLE_TOP flags`() {
        val conversationId = Uuid.parse("33333333-3333-3333-3333-333333333333")

        val intent = activity.openConversationIntent(conversationId)

        assertTrue(
            intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0,
            "Re-opening MainActivity must reuse the existing task (CLEAR_TOP).",
        )
        assertTrue(
            intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0,
            "Re-opening MainActivity must not spawn a duplicate (SINGLE_TOP).",
        )
    }
}
