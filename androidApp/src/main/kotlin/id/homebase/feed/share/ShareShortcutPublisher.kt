package id.homebase.feed.share

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import co.touchlab.kermit.Logger
import id.homebase.core.share.ShareableConversation
import id.homebase.feed.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.Collections
import androidx.core.net.toUri

/**
 * Publishes dynamic shortcuts for recent conversations so they appear
 * as direct share targets in the Android share sheet's top row.
 *
 * Avatar loading is delegated via the [loadAvatarIcon] function so the publisher
 * can be exercised in unit tests without spinning up the full ConversationStream
 * + HomebaseImageLoader graph.
 */
class ShareShortcutPublisher(
    private val context: Context,
    private val shareableConversations: Flow<List<ShareableConversation>>,
    private val loadAvatarIcon: suspend (ShareableConversation) -> IconCompat,
) {

    companion object {
        const val EXTRA_FROM_SHARE_SHORTCUT = "id.homebase.feed.share.FROM_SHARE_SHORTCUT"
        private const val TAG = "ShareShortcutPublisher"
        private const val MAX_SHORTCUTS = 8
        private const val SHARE_CATEGORY = "id.homebase.feed.category.SHARE_TARGET"
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            shareableConversations.collect { conversations ->
                if (conversations.isNotEmpty()) {
                    updateShortcuts(conversations)
                }
            }
        }
    }

    internal suspend fun updateShortcuts(conversations: List<ShareableConversation>) {
        try {
            val shortcuts = conversations
                .sortedByDescending { it.lastMessageTimestamp }
                .take(MAX_SHORTCUTS)
                .mapIndexed { index, conversation -> buildShortcut(conversation, rank = index) }

            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            Logger.i(tag = TAG) { "Published ${shortcuts.size} share shortcuts" }
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "Failed to update share shortcuts: ${e.message}" }
        }
    }

    private suspend fun buildShortcut(
        conversation: ShareableConversation,
        rank: Int,
    ): ShortcutInfoCompat {
        val icon = loadAvatarIcon(conversation)

        val person = Person.Builder()
            .setName(conversation.displayName)
            .setKey(conversation.id)
            .setIcon(icon)
            .build()

        // Share intent: receive shared content and route to the conversation
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            component = ComponentName(context, ShareReceiverActivity::class.java)
            data = "homebase-fchat://conversation/${conversation.id}".toUri()
            type = "*/*"
        }

        // Main intent: open the conversation directly when tapped from launcher long-press,
        // and also runs underneath the shareIntent on Direct Share so the user lands on the
        // conversation after sending. The EXTRA lets MainActivity tag the resulting
        // OpenConversation event as Source.ShareIntent so AppNavHost routes via
        // selectConversationOnChatList instead of the PendingNotificationTap path.
        val mainIntent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(context, MainActivity::class.java)
            data = "homebase-fchat://conversation/${conversation.id}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_FROM_SHARE_SHORTCUT, true)
        }

        // displayName can be blank for unresolved 1:1 contacts; setShortLabel/setLongLabel
        // throw IllegalArgumentException on empty strings, and setDynamicShortcuts is atomic
        // — one bad row drops the entire batch and Direct Share goes dark for the whole app.
        val label = conversation.displayName
            .ifBlank { conversation.avatarInitials }
            .ifBlank { "Conversation" }

        return ShortcutInfoCompat.Builder(context, "share_${conversation.id}")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(icon)
            .setIntents(arrayOf(mainIntent, shareIntent))
            .setLongLived(true)
            .setIsConversation()
            .setRank(rank)
            .setCategories(Collections.singleton(SHARE_CATEGORY))
            .setPerson(person)
            .build()
    }
}
