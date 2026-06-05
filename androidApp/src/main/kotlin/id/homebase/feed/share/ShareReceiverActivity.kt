package id.homebase.feed.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.safeDeleteRecursively
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.widget.FullScreenAttachmentEditor
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.moments.MomentsPreferences
import id.homebase.core.moments.services.MomentCreateFlowState
import id.homebase.core.settings.ThemeState
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.feed.MainActivity
import id.homebase.feed.R
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val COLD_TAG = "ShareCold"

/**
 * Activity that handles incoming share intents from other apps.
 * Shows a conversation picker, then sends the shared content to the selected conversation.
 * Runs in the same process as the main app, so it has full access to Koin DI and the database.
 */
@OptIn(ExperimentalUuidApi::class)
class ShareReceiverActivity : ComponentActivity(), KoinComponent {

    private val youAuthFlowManager: YouAuthFlowManager by inject()
    private val conversationStream: ConversationStream by inject()
    private val contactService: ContactService by inject()
    private val ownerSessionRepository: OwnerSessionRepository by inject()
    private val chatMessageSenderService: ChatMessageSenderService by inject()
    private val fileOperationsProvider: FileOperationsProvider by inject()
    private val userPreferences: UserPreferences by inject()
    private val authConnectionCoordinator: AuthConnectionCoordinator by inject()
    private val momentCreateFlowState: MomentCreateFlowState by inject()
    private val momentsPreferences: MomentsPreferences by inject()

    private var isSending by mutableStateOf(false)
    private var isProcessing by mutableStateOf(false)
    private var screenState by mutableStateOf<ShareScreenState>(ShareScreenState.Picking)
    private var editorAttachments by mutableStateOf<List<AttachmentPendingFile>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Logger.d(tag = COLD_TAG) { "onCreate: action=${intent.action} type=${intent.type} hasData=${intent.data != null}" }

        // Wait for auth state to finish initializing (handles process-kill restart race)
        lifecycleScope.launch {
            val authState = youAuthFlowManager.authState
                .dropWhile { it is YouAuthState.Initializing }
                .first()
            Logger.d(tag = COLD_TAG) { "onCreate: authState=${authState::class.simpleName}" }
            if (authState !is YouAuthState.Authenticated) {
                Toast.makeText(
                    this@ShareReceiverActivity,
                    getString(R.string.share_auth_required),
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }
            initShareFlow()
        }
    }

    private fun initShareFlow() {
        Logger.d(tag = COLD_TAG) { "initShareFlow: enter, ownerSession=${if (ownerSessionRepository.user.value == null) "null" else "loaded"}" }

        // Force-resolve AuthConnectionCoordinator so its init { authState.collect } block
        // fires. On a cold-start share (process force-killed, then a generic share) nothing
        // else touches this Koin singleton, so OwnerSessionRepository.load() never runs and
        // the picker renders an empty list (ConversationEnricher short-circuits on null
        // ownerSession). `by inject()` is lazy — reading the property here triggers
        // resolution and the same loadProfile / drive bootstrap chain that AppViewModel
        // gets in MainActivity.
        @Suppress("UNUSED_EXPRESSION")
        authConnectionCoordinator
        Logger.d(tag = COLD_TAG) { "initShareFlow: resolved AuthConnectionCoordinator" }

        // Kick the streams ourselves too — defense-in-depth no-op (start() is idempotent),
        // so the picker still loads if Koin wiring around the coordinator changes.
        conversationStream.start()
        contactService.start()
        Logger.d(tag = COLD_TAG) { "initShareFlow: kicked streams (conversationStream + contactService)" }

        // Extract shared content
        val tempDir = File(cacheDir, "share_temp")
        val sharedContent = SharedContentExtractor.extract(intent, contentResolver, tempDir)
        Logger.d(tag = COLD_TAG) {
            if (sharedContent == null) "initShareFlow: extract() returned null"
            else "initShareFlow: extract() files=${sharedContent.files.size} hasText=${sharedContent.hasText} textLen=${sharedContent.text?.length ?: 0}"
        }
        if (sharedContent == null || sharedContent.isEmpty) {
            Toast.makeText(this, getString(R.string.share_nothing_to_share), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Check for direct share target (user tapped a conversation shortcut in the share sheet).
        // Try multiple detection mechanisms since Android behavior varies by version:
        // 1. intent.data URI — defensive fallback for any send intent that carries the
        //    homebase-fchat://conversation/{id} URI directly.
        // 2. EXTRA_SHORTCUT_ID (set by ChooserActivity on API 29+) — the primary Direct
        //    Share mechanism; the conversation shortcut no longer launches this activity
        //    itself (it opens MainActivity), so Direct Share relies on this.
        val directShareConvoId = extractDirectShareConversationId()
        Logger.d(tag = COLD_TAG) { "initShareFlow: directShareConvoId=$directShareConvoId" }
        if (directShareConvoId != null) {
            try {
                val conversationId = Uuid.parse(directShareConvoId)
                if (sharedContent.hasFiles) {
                    // Show overlay while converting files, then transition to editor
                    isProcessing = true
                    lifecycleScope.launch {
                        val attachments = withContext(Dispatchers.IO) {
                            convertToAttachmentFiles(sharedContent.files)
                        }
                        val title = resolveConversationTitle(setOf(conversationId))
                        editorAttachments = attachments
                        isProcessing = false
                        screenState = ShareScreenState.Previewing(
                            selectedConversationIds = setOf(conversationId),
                            attachments = attachments,
                            conversationTitle = title,
                        )
                    }
                } else {
                    // Text-only: send directly
                    sendSharedContent(conversationId, sharedContent)
                    return
                }
            } catch (_: Exception) {
                // Invalid UUID — fall through to picker
            }
        }

        Logger.d(tag = COLD_TAG) { "initShareFlow: reached setContent (picker path)" }
        setContent {
            val prefState by userPreferences.preferenceState.collectAsStateWithLifecycle()
            val isDarkTheme = if (prefState.theme == ThemeState.System) isSystemInDarkTheme()
            else prefState.theme == ThemeState.Dark

            // Hoisted unconditionally to satisfy Compose composition rules for ActivityResult launchers
            val fileLauncher = rememberFilePickerLauncher { file ->
                file?.let {
                    editorAttachments = editorAttachments + AttachmentPendingFile.File(
                        Uuid.random(), it
                    )
                }
            }
            val galleryLauncher = rememberFilePickerLauncher(
                type = FileKitType.ImageAndVideo
            ) { file ->
                file?.let {
                    val mimeType = it.mimeType()?.toString() ?: ""
                    val pending = if (mimeType.startsWith("video/")) {
                        AttachmentPendingFile.FileVideo(Uuid.random(), it)
                    } else {
                        AttachmentPendingFile.FileImage(Uuid.random(), it)
                    }
                    editorAttachments = editorAttachments + pending
                }
            }

            HomebaseTheme(darkTheme = isDarkTheme) {
                Box(modifier = Modifier.fillMaxSize()) {
                when (val state = screenState) {
                    is ShareScreenState.Picking -> {
                        SharePickerScreen(
                            conversationStream = conversationStream,
                            contactService = contactService,
                            ownerSessionRepository = ownerSessionRepository,
                            sharedContent = sharedContent,
                            hasFiles = sharedContent.hasFiles,
                            isSending = isSending,
                            // Moments need media — only offer "New Moment" when the
                            // share carries files and the feature is activated.
                            showNewMomentOption = sharedContent.hasFiles &&
                                momentsPreferences.activated.value,
                            onTargetSelected = { target ->
                                when (target) {
                                    is ShareTarget.Conversations ->
                                        onConversationsPicked(target.ids, sharedContent)
                                    ShareTarget.NewMoment ->
                                        startNewMoment(sharedContent)
                                }
                            },
                            onCancel = { finish() },
                        )
                    }

                    is ShareScreenState.Previewing -> {
                        val textFieldState = remember { RichTextState() }
                        var currentPage by remember { mutableStateOf(0) }

                        if (editorAttachments.isEmpty()) {
                            // User removed all files — go back to picker
                            screenState = ShareScreenState.Picking
                        } else {
                            FullScreenAttachmentEditor(
                                data = FullScreenOverlay.AttachmentData(
                                    selected = editorAttachments[currentPage.coerceIn(0, editorAttachments.lastIndex)].attachmentId,
                                    conversationTitle = state.conversationTitle,
                                    conversationId = state.selectedConversationIds.first(),
                                    attachments = editorAttachments,
                                ),
                                textFieldState = textFieldState,
                                currentPage = currentPage,
                                onPageChanged = { currentPage = it },
                                onSaveFile = { /* Not needed in share flow */ },
                                onAddFile = { fileLauncher.launch() },
                                onAddImage = { galleryLauncher.launch() },
                                onCameraClick = { /* Not available in share flow */ },
                                onRemoveFile = { _, attachmentId ->
                                    val updated = editorAttachments.filter { it.attachmentId != attachmentId }
                                    editorAttachments = updated
                                    if (currentPage >= updated.size) {
                                        currentPage = maxOf(0, updated.lastIndex)
                                    }
                                },
                                onSendMessage = { _, message, files ->
                                    sendEditedFiles(
                                        conversationIds = state.selectedConversationIds,
                                        caption = message,
                                        files = files,
                                    )
                                },
                                onDismiss = {
                                    editorAttachments = emptyList()
                                    screenState = ShareScreenState.Picking
                                },
                            )
                        }
                    }

                }

                // Semi-transparent overlay while processing (file conversion or sending)
                if (isProcessing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                            .clickable(onClick = {}),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.inversePrimary,
                        )
                    }
                }
                } // Box
            }
        }
    }

    /**
     * Terminal dispatch for the [ShareTarget.Conversations] branch. Files go
     * through the in-activity preview/caption editor; text-only sends directly.
     */
    private fun onConversationsPicked(conversationIds: Set<Uuid>, content: SharedContent) {
        Logger.d(tag = COLD_TAG) {
            "onConversationsPicked: count=${conversationIds.size} hasFiles=${content.hasFiles}"
        }
        if (content.hasFiles) {
            // Show overlay while converting files
            isProcessing = true
            lifecycleScope.launch {
                val attachments = withContext(Dispatchers.IO) {
                    convertToAttachmentFiles(content.files)
                }
                val title = resolveConversationTitle(conversationIds)
                editorAttachments = attachments
                isProcessing = false
                screenState = ShareScreenState.Previewing(
                    selectedConversationIds = conversationIds,
                    attachments = attachments,
                    conversationTitle = title,
                )
            }
        } else {
            // Text-only: send directly as before
            sendToMultipleConversations(conversationIds, content)
        }
    }

    /**
     * Terminal dispatch for the [ShareTarget.NewMoment] branch. Reuses the same
     * `convertToAttachmentFiles` step the chat path uses, seeds the moments
     * composer draft ([MomentCreateFlowState] is a process-wide Koin singleton
     * that [MomentComposeViewModel] reads on init), then hands off to
     * `Route.MomentCompose` in the main app. The moments composer is the editor
     * here — trim/crop/description/audience all live there — so there's no
     * in-activity preview step on this path.
     */
    private fun startNewMoment(content: SharedContent) {
        if (!content.hasFiles) {
            finish()
            return
        }
        isProcessing = true
        lifecycleScope.launch {
            val attachments = withContext(Dispatchers.IO) {
                convertToAttachmentFiles(content.files)
            }
            momentCreateFlowState.setDraft(
                MomentCreateFlowState.Draft(
                    attachments = attachments,
                    description = content.text ?: "",
                )
            )
            Logger.d(tag = COLD_TAG) { "startNewMoment: seeded draft with ${attachments.size} attachments" }
            // The moments composer owns the temp files now; don't reap share_temp.
            startActivity(openMomentComposeIntent())
            finish()
        }
    }

    private fun sendToMultipleConversations(conversationIds: Set<Uuid>, content: SharedContent) {
        if (isSending) return
        isSending = true

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (conversationId in conversationIds) {
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
                }

                val countLabel = if (conversationIds.size > 1) {
                    getString(R.string.share_sent_multiple, conversationIds.size)
                } else {
                    getString(R.string.share_sent)
                }
                Toast.makeText(this@ShareReceiverActivity, countLabel, Toast.LENGTH_SHORT).show()

                // Open the first conversation in the main app
                startActivity(openConversationIntent(conversationIds.first()))
                finish()
            } catch (e: Exception) {
                Logger.e(tag = "ShareReceiver") { "Failed to send: ${e.message}" }
                withContext(Dispatchers.Main) {
                    isSending = false
                    Toast.makeText(
                        this@ShareReceiverActivity,
                        "Failed to send: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                cleanupTempFiles()
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

                Toast.makeText(this@ShareReceiverActivity, getString(R.string.share_sent), Toast.LENGTH_SHORT).show()

                // Open conversation in main app
                startActivity(openConversationIntent(conversationId))
                finish()
            } catch (e: Exception) {
                Logger.e(tag = "ShareReceiver") { "Failed to send: ${e.message}" }
                withContext(Dispatchers.Main) {
                    isSending = false
                    Toast.makeText(
                        this@ShareReceiverActivity,
                        "Failed to send: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
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
        ) { index, _ -> "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index" }

        chatMessageSenderService.sendNewMessage(
            messageUniqueId = Uuid.random(),
            conversationId = conversationId,
            messageText = content.text ?: "",
            previousMessageUniqueId = null,
            payloadBundle = payloadBundle,
        )
    }

    private fun cleanupTempFiles() {
        safeDeleteRecursively(cacheDir.absolutePath, "share_temp")
    }

    /**
     * Builds the Intent that re-opens [MainActivity] on the just-shared-to conversation.
     *
     * The [ShareShortcutPublisher.EXTRA_FROM_SHARE_SHORTCUT] extra is what makes
     * MainActivity.handleIntent() classify this deep link as
     * `OpenConversation.Source.ShareIntent` instead of `Source.NotificationTap`. The
     * ShareIntent source routes through AppNavHost's `selectConversationOnChatList`, which
     * actually opens the conversation; the NotificationTap source resolves via a
     * PendingNotificationTap that needs a messageId a share never has, so without the extra
     * the deep link dead-ends and the conversation never opens. Centralized here so all
     * three send paths (text-only single, text-only multi, edited files) stay in sync.
     */
    internal fun openConversationIntent(conversationId: Uuid): Intent =
        Intent(this, MainActivity::class.java).apply {
            data = "homebase-fchat://conversation/$conversationId".toUri()
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ShareShortcutPublisher.EXTRA_FROM_SHARE_SHORTCUT, true)
        }

    /**
     * Re-opens [MainActivity] on the moments composer. The draft has already
     * been seeded into [MomentCreateFlowState]; MainActivity.handleIntent reads
     * this `homebase-fchat://moment-compose` deep link and emits the
     * OpenMomentCompose navigation event that AppNavHost routes to
     * `Route.MomentCompose`.
     */
    internal fun openMomentComposeIntent(): Intent =
        Intent(this, MainActivity::class.java).apply {
            data = "homebase-fchat://moment-compose".toUri()
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    private fun extractDirectShareConversationId(): String? {
        // Method 1: Check intent data URI (homebase-fchat://conversation/{uuid})
        intent.data?.let { uri ->
            if (uri.scheme == "homebase-fchat" && uri.host == "conversation") {
                uri.lastPathSegment?.let { return it }
            }
        }

        // Method 2: EXTRA_SHORTCUT_ID set by Android's ChooserActivity (API 29+)
        // Format: "share_{uuid}" as defined in ShareShortcutPublisher
        @Suppress("DEPRECATION")
        intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.let { shortcutId ->
            return shortcutId.removePrefix("share_")
        }

        return null
    }

    private fun convertToAttachmentFiles(files: List<SharedFile>): List<AttachmentPendingFile> {
        return files.map { sharedFile ->
            val platformFile = PlatformFile(java.io.File(sharedFile.path))
            when {
                sharedFile.mimeType.startsWith("image/") ->
                    AttachmentPendingFile.FileImage(Uuid.random(), platformFile)
                sharedFile.mimeType.startsWith("video/") ->
                    // Editor renders the poster via Coil's VideoFrameDecoder when bytes
                    // are null; the upload pipeline extracts its own thumbnails from the
                    // file path inside VideoPayloadProcessor. So we don't need to do any
                    // synchronous extraction here — that was the slowest part of opening
                    // the share preview for big videos.
                    AttachmentPendingFile.FileVideo(Uuid.random(), platformFile, thumbnailBytes = null)
                else ->
                    AttachmentPendingFile.File(Uuid.random(), platformFile)
            }
        }
    }

    private fun resolveConversationTitle(conversationIds: Set<Uuid>): String {
        val conversations = conversationStream.conversations.value.items
        val names = conversationIds.take(2).mapNotNull { id ->
            conversations.find { it.id == id }?.name
        }
        val remaining = conversationIds.size - names.size.coerceAtMost(2)

        return when {
            names.isEmpty() -> getString(R.string.share_preview_title_single)
            names.size == 1 && remaining == 0 -> names.first()
            names.size == 1 && remaining > 0 -> getString(R.string.share_preview_title_others, names.first(), remaining)
            remaining > 0 -> getString(R.string.share_preview_title_others, names.joinToString(", "), remaining)
            else -> names.joinToString(", ")
        }
    }

    private fun sendEditedFiles(
        conversationIds: Set<Uuid>,
        caption: String,
        files: List<AttachmentPendingFile>,
    ) {
        Logger.d(tag = COLD_TAG) {
            "sendEditedFiles: convoCount=${conversationIds.size} fileCount=${files.size} captionLen=${caption.length}"
        }
        isProcessing = true

        lifecycleScope.launch {
            try {
                // Build payload on IO (thumbnail generation is the slow part)
                val attachments = withContext(Dispatchers.IO) {
                    files.map { attachment ->
                        when (attachment) {
                            is AttachmentPendingFile.FileImage -> AttachmentInput(
                                filePath = attachment.file.toString(),
                                contentType = attachment.file.mimeType()?.toString() ?: "image/jpeg",
                                displayName = attachment.file.name,
                            )
                            is AttachmentPendingFile.FileVideo -> AttachmentInput(
                                filePath = attachment.file.toString(),
                                contentType = attachment.file.mimeType()?.toString() ?: "video/mp4",
                                displayName = attachment.file.name,
                            )
                            is AttachmentPendingFile.File -> AttachmentInput(
                                filePath = attachment.file.toString(),
                                contentType = attachment.file.mimeType()?.toString() ?: "application/octet-stream",
                                displayName = attachment.file.name,
                            )
                            is AttachmentPendingFile.Gallery -> AttachmentInput(
                                filePath = attachment.image.file.toString(),
                                contentType = "image/jpeg",
                                displayName = attachment.image.fileName,
                            )
                            is AttachmentPendingFile.Audio -> AttachmentInput(
                                filePath = attachment.audioFile.toString(),
                                contentType = "audio/m4a",
                                displayName = attachment.audioFile.name,
                                waveformFile = attachment.waveformFile?.toString(),
                                audioLengthSeconds = attachment.lengthSeconds,
                            )
                        }
                    }
                }

                val payloadBundle = withContext(Dispatchers.IO) {
                    MessageAttachmentBuilder.build(
                        attachments = attachments,
                        fileOperationsProvider = fileOperationsProvider,
                    ) { index, _ -> "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index" }
                }

                // Navigate immediately — don't wait for sends to complete
                val firstId = conversationIds.first()
                Logger.d(tag = COLD_TAG) { "sendEditedFiles: launching MainActivity for convo=$firstId then finish()" }
                startActivity(openConversationIntent(firstId))

                // Fire sends in background scope that survives the activity finish.
                // Safe because MainActivity starts before finish(), keeping the process alive.
                // For large uploads, consider migrating to WorkManager.
                val sendScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                sendScope.launch {
                    try {
                        for (conversationId in conversationIds) {
                            chatMessageSenderService.sendNewMessage(
                                messageUniqueId = Uuid.random(),
                                conversationId = conversationId,
                                messageText = caption,
                                previousMessageUniqueId = null,
                                payloadBundle = payloadBundle,
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e(tag = "ShareReceiver") { "Background send failed: ${e.message}" }
                    } finally {
                        cleanupTempFiles()
                    }
                }

                finish()
            } catch (e: Exception) {
                Logger.e(tag = "ShareReceiver") { "Failed to prepare send: ${e.message}" }
                isProcessing = false
                Toast.makeText(
                    this@ShareReceiverActivity,
                    "Failed to send: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private sealed class ShareScreenState {
        data object Picking : ShareScreenState()
        data class Previewing(
            val selectedConversationIds: Set<Uuid>,
            val attachments: List<AttachmentPendingFile>,
            val conversationTitle: String,
        ) : ShareScreenState()
    }
}
