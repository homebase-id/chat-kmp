package id.homebase.feed.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.core.settings.ThemeState
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.feed.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Activity that handles incoming share intents from other apps.
 * Shows a conversation picker, then sends the shared content to the selected conversation.
 * Runs in the same process as the main app, so it has full access to Koin DI and the database.
 */
@OptIn(ExperimentalUuidApi::class)
class ShareReceiverActivity : ComponentActivity(), KoinComponent {

    private val youAuthFlowManager: YouAuthFlowManager by inject()
    private val conversationStream: ConversationStream by inject()
    private val chatMessageSenderService: ChatMessageSenderService by inject()
    private val fileOperationsProvider: FileOperationsProvider by inject()
    private val userPreferences: UserPreferences by inject()

    private var isSending by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check authentication
        val authState = youAuthFlowManager.authState.value
        if (authState !is YouAuthState.Authenticated) {
            Toast.makeText(this, "Please open Homebase Chat and sign in first", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Extract shared content
        val tempDir = File(cacheDir, "share_temp")
        val sharedContent = SharedContentExtractor.extract(intent, contentResolver, tempDir)
        if (sharedContent == null || sharedContent.isEmpty) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            val prefState by userPreferences.preferenceState.collectAsState()
            val isDarkTheme = if (prefState.theme == ThemeState.System) isSystemInDarkTheme()
                else prefState.theme == ThemeState.Dark

            HomebaseTheme(darkTheme = isDarkTheme) {
                SharePickerScreen(
                    conversationStream = conversationStream,
                    sharedContent = sharedContent,
                    isSending = isSending,
                    onConversationSelected = { conversationId ->
                        sendSharedContent(conversationId, sharedContent)
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    private fun sendSharedContent(conversationId: Uuid, content: SharedContent) {
        if (isSending) return
        isSending = true

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (content.hasFiles) {
                        sendWithFiles(conversationId, content)
                    } else if (content.hasText) {
                        chatMessageSenderService.sendNewMessage(
                            messageUniqueId = Uuid.random(),
                            conversationId = conversationId,
                            messageText = content.text!!,
                            previousMessageUniqueId = null,
                            payloadBundle = null,
                        )
                    }
                }

                Toast.makeText(this@ShareReceiverActivity, "Sent", Toast.LENGTH_SHORT).show()

                // Open conversation in main app
                val mainIntent = Intent(this@ShareReceiverActivity, MainActivity::class.java).apply {
                    data = Uri.parse("homebase-fchat://conversation/${conversationId}")
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(mainIntent)
                finish()
            } catch (e: Exception) {
                Logger.e("ShareReceiver") { "Failed to send: ${e.message}" }
                withContext(Dispatchers.Main) {
                    isSending = false
                    Toast.makeText(this@ShareReceiverActivity, "Failed to send: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                // Clean up temp files
                cleanupTempFiles()
            }
        }
    }

    private suspend fun sendWithFiles(conversationId: Uuid, content: SharedContent) {
        val attachments = content.files.map { file ->
            AttachmentInput(
                filePath = file.path,
                contentType = file.mimeType,
                displayName = file.displayName,
            )
        }

        val payloadBundle = MessageAttachmentBuilder.build(
            attachments = attachments,
            fileOperationsProvider = fileOperationsProvider,
        ) { index, _ -> "share_payload_$index" }

        chatMessageSenderService.sendNewMessage(
            messageUniqueId = Uuid.random(),
            conversationId = conversationId,
            messageText = content.text ?: "",
            previousMessageUniqueId = null,
            payloadBundle = payloadBundle,
        )
    }

    private fun cleanupTempFiles() {
        try {
            File(cacheDir, "share_temp").deleteRecursively()
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
    }
}
