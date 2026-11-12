package id.homebase.chat.conversationlist

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.uuid.Uuid

internal val NO_CONVERSATION: Uuid = Uuid.NIL

internal val FullScreenOverlay.AttachmentData.isChatListCapture: Boolean
    get() = conversationId == NO_CONVERSATION

@Immutable
data class SendToPicker(
    val caption: String,
    val viewOnce: Boolean,
    val recipients: ImmutableList<RecipientGroupModel>,
    val selected: ImmutableList<RecipientModel> = persistentListOf(),
    val search: TextFieldState = TextFieldState(),
)

internal fun SendToPicker.toggle(recipient: RecipientModel): SendToPicker =
    copy(selected = (if (recipient in selected) selected - recipient else selected + recipient).toPersistentList())

internal fun conversationToOpenAfterSend(targets: List<Uuid>): Uuid? = targets.singleOrNull()
