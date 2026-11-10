package id.homebase.core.share

import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.youauth.PermissionCallback
import id.homebase.core.moments.services.MomentCreateFlowState
import id.homebase.core.notifications.AppNavigator
import id.homebase.core.notifications.NotificationNavigationEvent.OpenConversation.Source
import id.homebase.core.session.IdentitySessionScope
import id.homebase.core.session.getOrNull
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface IncomingLink {
    data class Conversation(val id: String) : IncomingLink
    data class ShareSend(val ids: List<String>) : IncomingLink
    data object MomentShare : IncomingLink
    data object MomentCompose : IncomingLink
    data object WebDropCompose : IncomingLink
    data class Permission(val url: String) : IncomingLink
    data object DataUpgrade : IncomingLink
}

/** The only place app links are parsed; every platform entry point hands its URL here. */
class IncomingLinks(
    private val navigator: AppNavigator,
    private val shareContentProcessor: ShareContentProcessor,
    private val eventBus: EventBus,
    private val identitySession: IdentitySessionScope,
    private val scope: CoroutineScope,
) {
    /** False when the URL is not one of ours, so the caller can route it elsewhere. */
    fun handle(url: String, source: Source = Source.ShareIntent): Boolean {
        val link = parse(url) ?: return false
        Logger.i(tag = TAG) { "Incoming link: ${link::class.simpleName}" }
        when (link) {
            is IncomingLink.Conversation -> navigator.navigateToConversation(link.id, source)
            is IncomingLink.ShareSend -> navigator.navigateToConversation(link.ids.first(), Source.ShareIntent)
            IncomingLink.MomentShare -> handleMomentShare()
            IncomingLink.MomentCompose -> navigator.navigateToMomentCompose()
            IncomingLink.WebDropCompose -> navigator.navigateToWebDropCompose()
            is IncomingLink.Permission -> eventBus.tryEmit(PermissionCallback.parse(link.url).event)
            IncomingLink.DataUpgrade -> eventBus.tryEmit(BackendEvent.DataUpgradeReturned)
        }
        return true
    }

    // No cleanup(): that deletes the staged files, which the composer still needs.
    private fun handleMomentShare() {
        val descriptor = shareContentProcessor.readPendingContent()
        if (descriptor == null || descriptor.fileNames.isEmpty()) {
            Logger.w(tag = TAG) { "Moment share: no pending media to compose" }
            return
        }
        val attachments = descriptor.fileNames.zip(descriptor.mimeTypes).map { (name, mime) ->
            sharedMediaAttachment(shareContentProcessor.resolveFilePath(name), mime)
        }
        val draft = MomentCreateFlowState.Draft(attachments = attachments, description = descriptor.text ?: "")
        val open = identitySession.getOrNull<MomentCreateFlowState>()
        if (open != null) {
            open.setDraft(draft)
            navigator.navigateToMomentCompose()
            return
        }
        // Cold start: the session is not open yet. The draft is identity-scoped, so wait for the scope.
        scope.launch {
            val session = identitySession.currentScope.filterNotNull().first()
            session.get<MomentCreateFlowState>().setDraft(draft)
            navigator.navigateToMomentCompose()
        }
    }

    companion object {
        private const val TAG = "IncomingLinks"

        fun parse(url: String): IncomingLink? {
            val parsed = try {
                Url(url)
            } catch (e: Exception) {
                return null
            }
            return when (parsed.protocol.name) {
                "homebase-share", "homebase-share-dev" -> when (parsed.host) {
                    "send" -> parsed.parameters["conversationIds"]
                        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                        ?.takeIf { it.isNotEmpty() }
                        ?.let(IncomingLink::ShareSend)
                    "moment" -> IncomingLink.MomentShare
                    else -> null
                }
                "homebase-fchat" -> when (parsed.host) {
                    "conversation" -> parsed.segments.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?.let(IncomingLink::Conversation)
                    "moment-compose" -> IncomingLink.MomentCompose
                    "web-drop-compose" -> IncomingLink.WebDropCompose
                    "permission-callback" -> IncomingLink.Permission(url)
                    "data-upgrade-callback" -> IncomingLink.DataUpgrade
                    else -> null
                }
                else -> null
            }
        }
    }
}
