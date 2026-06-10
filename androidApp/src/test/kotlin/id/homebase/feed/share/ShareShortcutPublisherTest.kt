package id.homebase.feed.share

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.test.core.app.ApplicationProvider
import id.homebase.core.share.ShareableConversation
import id.homebase.feed.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reproduces a bug observed in production where Shelly's Direct Share row
 * never showed Homebase contacts. Root cause: [ShareShortcutPublisher.updateShortcuts]
 * passes a list of [androidx.core.content.pm.ShortcutInfoCompat] to
 * [ShortcutManagerCompat.setDynamicShortcuts], which throws
 * `IllegalArgumentException: Shortcut must have a non-empty label` if ANY one of
 * the shortcuts has an empty label. The publisher's catch block swallows the
 * exception with a Logger.w — the entire batch silently drops, and zero share
 * shortcuts get registered for the Homebase app.
 *
 * Test asserts the buggy behavior: with one valid + one blank-label conversation,
 * the count of registered shortcuts is 0. After the fix (filter blank labels OR
 * fall back to avatarInitials), this test should fail and be updated to assert 1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShareShortcutPublisherTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private val noopAvatarLoader: suspend (ShareableConversation) -> IconCompat = {
        IconCompat.createWithAdaptiveBitmap(createBitmap(108, 108))
    }

    private fun newPublisher() = ShareShortcutPublisher(
        context = context,
        shareableConversations = MutableStateFlow(emptyList()),
        loadAvatarIcon = noopAvatarLoader,
    )

    private fun convo(
        id: String,
        displayName: String,
        timestamp: Long = 1L,
    ) = ShareableConversation(
        id = id,
        displayName = displayName,
        avatarInitials = "AB",
        isGroup = false,
        participantCount = 1,
        lastMessageTimestamp = timestamp,
        avatarUrl = null,
    )

    @Test
    fun `one blank displayName must not drop the entire batch`() = runTest {
        val publisher = newPublisher()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)

        publisher.updateShortcuts(
            listOf(
                convo(id = "11111111-1111-1111-1111-111111111111", displayName = "Alice", timestamp = 2L),
                convo(id = "22222222-2222-2222-2222-222222222222", displayName = "", timestamp = 1L),
            )
        )

        val published = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertEquals(
            2,
            published.size,
            "Direct Share targets vanish for the entire app when one conversation has a " +
                "blank displayName: setDynamicShortcuts is atomic and rejects the whole " +
                "batch with `Shortcut must have a non-empty label`. Publisher must " +
                "fallback-label blank conversations so all shortcuts still publish.",
        )

        val blankConvoShortcut = published.single {
            it.id == "share_22222222-2222-2222-2222-222222222222"
        }
        assertEquals(
            "AB",
            blankConvoShortcut.shortLabel.toString(),
            "Blank displayName must fall back to avatarInitials so setShortLabel " +
                "doesn't throw and the entry stays consistent with the avatar bitmap.",
        )
    }

    @Test
    fun `mainIntent is tagged with EXTRA_FROM_SHARE_SHORTCUT so MainActivity can route as ShareIntent`() = runTest {
        val publisher = newPublisher()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)

        publisher.updateShortcuts(
            listOf(convo(id = "11111111-1111-1111-1111-111111111111", displayName = "Alice"))
        )

        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).single()
        val mainIntent = shortcut.intents.first { it.action == Intent.ACTION_VIEW }
        assertTrue(
            mainIntent.getBooleanExtra(ShareShortcutPublisher.EXTRA_FROM_SHARE_SHORTCUT, false),
            "mainIntent must carry EXTRA_FROM_SHARE_SHORTCUT so MainActivity can tag the " +
                "resulting OpenConversation event as Source.ShareIntent. Without it, " +
                "AppNavHost falls into the NotificationTap branch (which expects a " +
                "PendingNotificationTap singleton) and navigation dead-ends — " +
                "see homebase.log: popBackStack(ChatList)=false at 13:04:34.",
        )
    }

    @Test
    fun `launcher tap opens the conversation, not the empty share receiver`() = runTest {
        // Reproduces: long-press the app icon, tap a recent contact. The app opens the
        // conversation but ALSO flashes a "nothing to share" toast.
        //
        // Root cause: ShortcutInfoCompat.setIntents(Intent[]) follows startActivities()
        // back-stack semantics — the LAST intent is the activity launched on top, the
        // rest sit beneath it. A launcher long-press tap launches that top intent. If it
        // is the ACTION_SEND intent into ShareReceiverActivity, there is no shared content
        // (the user came from the launcher, not a share sheet), so the receiver shows
        // "nothing to share" and finishes — while MainActivity beneath it reveals the
        // conversation. Direct Share is routed by <share-target> + EXTRA_SHORTCUT_ID and
        // does NOT use setIntents, so the share intent here is pure harm.
        val publisher = newPublisher()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)

        publisher.updateShortcuts(
            listOf(convo(id = "11111111-1111-1111-1111-111111111111", displayName = "Alice"))
        )

        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).single()
        val launched = shortcut.intents.last()
        assertEquals(
            Intent.ACTION_VIEW,
            launched.action,
            "Launcher tap launches the last intent; it must open the conversation " +
                "(ACTION_VIEW), not trigger the share receiver (ACTION_SEND).",
        )
        assertEquals(
            MainActivity::class.java.name,
            launched.component?.className,
            "Launcher tap must land in MainActivity (conversation), never in " +
                "ShareReceiverActivity — which would show \"nothing to share\".",
        )
        assertTrue(
            shortcut.intents.none { it.action == Intent.ACTION_SEND },
            "The shortcut must carry NO ACTION_SEND intent — re-adding one (even beneath " +
                "the launch intent in the back stack) reintroduces the \"nothing to share\" bug.",
        )
    }

    @Test
    fun `all valid displayNames publish all shortcuts (sanity)`() = runTest {
        val publisher = newPublisher()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)

        publisher.updateShortcuts(
            listOf(
                convo(id = "11111111-1111-1111-1111-111111111111", displayName = "Alice", timestamp = 2L),
                convo(id = "22222222-2222-2222-2222-222222222222", displayName = "Bob", timestamp = 1L),
            )
        )

        val published = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertEquals(2, published.size)
    }
}
