package id.homebase.core.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

object ActiveConversation {
    private val _conversation = MutableStateFlow<Uuid?>(null)
    val conversation: StateFlow<Uuid?> = _conversation.asStateFlow()

    private val _isDisplayingChatList = MutableStateFlow(false)
    val isDisplayingChatList: StateFlow<Boolean> = _isDisplayingChatList.asStateFlow()

    fun selectConversation(conversationId: Uuid?) {
        _conversation.value = conversationId
    }

    fun setDisplayingChatList(isDisplaying: Boolean) {
        _isDisplayingChatList.value = isDisplaying
    }
}