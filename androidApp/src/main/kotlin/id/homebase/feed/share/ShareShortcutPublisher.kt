package id.homebase.feed.share

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import co.touchlab.kermit.Logger
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.image.ImageSize
import id.homebase.core.share.ShareableConversation
import id.homebase.feed.MainActivity
import id.homebase.feed.share.ShareReceiverActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Collections
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap

/**
 * Publishes dynamic shortcuts for recent conversations so they appear
 * as direct share targets in the Android share sheet's top row.
 *
 * Self-contained: subscribes to [ConversationStream.shareableConversations]
 * which already has resolved contact names and avatar URLs.
 * Uses [HomebaseImageLoader] to fetch encrypted group conversation avatars.
 */
@OptIn(ExperimentalUuidApi::class)
class ShareShortcutPublisher(
    private val context: Context,
    private val conversationStream: ConversationStream,
    private val imageLoader: HomebaseImageLoader,
) {

    companion object {
        private const val TAG = "ShareShortcutPublisher"
        private const val MAX_SHORTCUTS = 8
        private const val SHARE_CATEGORY = "id.homebase.feed.category.SHARE_TARGET"
        private const val AVATAR_SIZE = 108 // px — adaptive icon inner size
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            conversationStream.shareableConversations.collect { conversations ->
                if (conversations.isNotEmpty()) {
                    updateShortcuts(conversations)
                }
            }
        }
    }

    private suspend fun updateShortcuts(conversations: List<ShareableConversation>) {
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

        // Main intent: open the conversation directly when tapped from launcher long-press
        val mainIntent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(context, MainActivity::class.java)
            data = "homebase-fchat://conversation/${conversation.id}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return ShortcutInfoCompat.Builder(context, "share_${conversation.id}")
            .setShortLabel(conversation.displayName)
            .setLongLabel(conversation.displayName)
            .setIcon(icon)
            .setIntents(arrayOf(mainIntent, shareIntent))
            .setLongLived(true)
            .setIsConversation()
            .setRank(rank)
            .setCategories(Collections.singleton(SHARE_CATEGORY))
            .setPerson(person)
            .build()
    }

    private suspend fun loadAvatarIcon(conversation: ShareableConversation): IconCompat {
        // Try loading encrypted group avatar via HomebaseImageLoader
        val convoModel = conversationStream.getConversationById(Uuid.parse(conversation.id))
        if (convoModel != null && convoModel.avatarModel.type == ConversationAvatarModel.Type.ConversationImage) {
            val imageData = convoModel.avatarModel.imageData
            if (imageData != null) {
                try {
                    val cachedImage = imageLoader.loadThumbnail(
                        data = imageData,
                        targetSize = ImageSize.THUMB_SMALL,
                    )
                    if (cachedImage != null) {
                        val bitmap = BitmapFactory.decodeByteArray(
                            cachedImage.bytes,
                            0,
                            cachedImage.bytes.size
                        )
                        if (bitmap != null) {
                            return IconCompat.createWithAdaptiveBitmap(createCircularBitmap(bitmap))
                        }
                    }
                } catch (e: Exception) {
                    Logger.d(tag = TAG) { "Failed to load encrypted avatar for ${conversation.displayName}: ${e.message}" }
                }
            }
        }

        // Try loading public avatar URL (1:1 contacts)
        val avatarUrl = conversation.avatarUrl
        if (avatarUrl != null) {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val connection = URL(avatarUrl).openConnection()
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    connection.getInputStream().use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                if (bitmap != null) {
                    return IconCompat.createWithAdaptiveBitmap(createCircularBitmap(bitmap))
                }
            } catch (e: Exception) {
                Logger.d(tag = TAG) { "Failed to load avatar for ${conversation.displayName}: ${e.message}" }
            }
        }

        // Fallback: create initials bitmap
        return IconCompat.createWithAdaptiveBitmap(createInitialsBitmap(conversation.avatarInitials))
    }

    private fun createCircularBitmap(source: Bitmap): Bitmap {
        val size = AVATAR_SIZE
        val output = createBitmap(size, size)
        val canvas = Canvas(output)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = size / 2f

        // Draw circular clip
        canvas.drawCircle(radius, radius, radius, paint)
        paint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC_IN))

        // Scale and center-crop the source
        val srcSize = minOf(source.width, source.height)
        val srcX = (source.width - srcSize) / 2
        val srcY = (source.height - srcSize) / 2
        canvas.drawBitmap(
            source,
            Rect(srcX, srcY, srcX + srcSize, srcY + srcSize),
            Rect(0, 0, size, size),
            paint
        )

        return output
    }

    private fun createInitialsBitmap(initials: String): Bitmap {
        val size = AVATAR_SIZE
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)

        // Background circle
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6366F1.toInt() // Indigo accent
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

        // Initials text
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = size * 0.38f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val textBounds = Rect()
        val displayInitials = initials.take(2).uppercase()
        textPaint.getTextBounds(displayInitials, 0, displayInitials.length, textBounds)
        val y = size / 2f + textBounds.height() / 2f
        canvas.drawText(displayInitials, size / 2f, y, textPaint)

        return bitmap
    }
}
