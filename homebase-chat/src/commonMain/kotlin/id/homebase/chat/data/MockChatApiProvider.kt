package id.homebase.chat.data

import id.homebase.chat.ConversationViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MockChatApiProvider {
    
    companion object {
        val CONVERSATION_ALICE = Uuid.parse("550e8400-e29b-41d4-a716-446655440001")
        val CONVERSATION_BOB = Uuid.parse("550e8400-e29b-41d4-a716-446655440002")
        val CONVERSATION_TEAM = Uuid.parse("550e8400-e29b-41d4-a716-446655440003")
        val CONVERSATION_DIANA = Uuid.parse("550e8400-e29b-41d4-a716-446655440004")
        val CONVERSATION_STARLIGHT = Uuid.parse("550e8400-e29b-41d4-a716-446655440005")
        val CONVERSATION_FRANK = Uuid.parse("550e8400-e29b-41d4-a716-446655440006")
        val CONVERSATION_GRACE = Uuid.parse("550e8400-e29b-41d4-a716-446655440007")
        val CONVERSATION_HENRY = Uuid.parse("550e8400-e29b-41d4-a716-446655440008")
        val CONVERSATION_FAMILY = Uuid.parse("550e8400-e29b-41d4-a716-446655440009")
        val CONVERSATION_IVY = Uuid.parse("550e8400-e29b-41d4-a716-446655440010")
    }
    
    private val _conversations = MutableStateFlow(getInitialConversations())
    val conversations: StateFlow<List<ConversationViewModel>> = _conversations.asStateFlow()
    
    private val _messages = MutableStateFlow(getInitialMessages())
    val messages: StateFlow<List<MessageViewModel>> = _messages.asStateFlow()
    
    private val _contacts = MutableStateFlow(getInitialContacts())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()
    
    private fun getInitialConversations(): List<ConversationViewModel> = listOf(
ConversationViewModel(
            id = CONVERSATION_ALICE,
            name = "Alice Johnson",
            lastMessage = "Hey! Are we still on for dinner tonight?",
            timestamp = Clock.System.now() - 15.minutes,
            unreadCount = 2,
            avatarInitials = "AJ",
            avatarUrl = "https://i.pravatar.cc/150?img=1",
            avatarTiny = null,
            lastRead = Clock.System.now()
        ),
ConversationViewModel(
            id = CONVERSATION_BOB,
            name = "Bob Smith",
            lastMessage = "Thanks for your help earlier! 👍",
            timestamp = Clock.System.now() - 1.hours - 15.minutes,
            unreadCount = 0,
            avatarInitials = "BS",
            avatarUrl = "https://i.pravatar.cc/150?img=12",
            avatarTiny = null,
            lastRead = Clock.System.now()
        ),
ConversationViewModel(
            id = CONVERSATION_TEAM,
            name = "Team Planning",
            lastMessage = "Charlie: The meeting is rescheduled to 3 PM",
            timestamp = Clock.System.now() - 2.hours - 30.minutes,
            unreadCount = 5,
            avatarInitials = "TP",
            avatarUrl = "https://i.pravatar.cc/150?img=60",
            avatarTiny = null,
            lastRead = Clock.System.now()
),
ConversationViewModel(
            id = CONVERSATION_DIANA,
            name = "Diana Martinez",
            lastMessage = "Can you send me those files?",
            timestamp = Clock.System.now() - 3.hours - 20.minutes,
            unreadCount = 1,
            avatarInitials = "DM",
            avatarUrl = "https://i.pravatar.cc/150?img=5",
            avatarTiny = null,
            lastRead = Clock.System.now()
),
ConversationViewModel(
            id = CONVERSATION_STARLIGHT,
            name = "Project Starlight",
            lastMessage = "Emily: I've pushed the latest changes",
            timestamp = Clock.System.now() - 1.days,
            unreadCount = 0,
            avatarInitials = "PS",
            avatarUrl = "https://i.pravatar.cc/150?img=70",
            avatarTiny = null,
            lastRead = Clock.System.now()
        ),
ConversationViewModel(
            id = CONVERSATION_FRANK,
            name = "Frank Wilson",
            lastMessage = "See you tomorrow!",
            timestamp = Clock.System.now() - 1.days - 3.hours,
            unreadCount = 0,
            avatarInitials = "FW",
            avatarUrl = "",
            avatarTiny = null,
            lastRead = Clock.System.now()
        ),
ConversationViewModel(
            id = CONVERSATION_GRACE,
            name = "Grace Lee",
            lastMessage = "That sounds great! 😊",
            timestamp = Clock.System.now() - 3.days,
            unreadCount = 0,
            avatarInitials = "GL",
            avatarUrl = "https://i.pravatar.cc/150?img=9",
            avatarTiny = null,
            lastRead = Clock.System.now()
),
ConversationViewModel(
            id = CONVERSATION_HENRY,
            name = "Henry Davis",
            lastMessage = "I'll take a look at it",
            timestamp = Clock.System.now() - 3.days - 2.hours,
            unreadCount = 0,
            avatarInitials = "HD",
            avatarUrl = "https://i.pravatar.cc/150?img=14",
            avatarTiny = null,
            lastRead = Clock.System.now()
),
ConversationViewModel(
            id = CONVERSATION_FAMILY,
            name = "Family",
            lastMessage = "Mom: Don't forget to call grandma",
            timestamp = Clock.System.now() - 4.days,
            unreadCount = 0,
            avatarInitials = "FM",
            avatarUrl = "https://i.pravatar.cc/150?img=65",
            avatarTiny = null,
            lastRead = Clock.System.now()
        ),
ConversationViewModel(
            id = CONVERSATION_IVY,
            name = "Ivy Chen",
            lastMessage = "Perfect! See you there",
            timestamp = Clock.System.now() - 5.days,
            unreadCount = 0,
            avatarInitials = "IC",
            avatarUrl = "https://i.pravatar.cc/150?img=10",
            avatarTiny = null,
            lastRead = Clock.System.now()
        ),
    )
    
    private fun getInitialContacts(): List<Contact> = listOf(
Contact(
            id = Uuid.random(),
            name = "Alice Johnson",
            avatarInitials = "AJ",
            avatarUrl = "https://i.pravatar.cc/150?img=1",
            status = "Available"
        ),
Contact(
            id = Uuid.random(),
            name = "Bob Smith",
            avatarInitials = "BS",
            avatarUrl = "https://i.pravatar.cc/150?img=12",
            status = "Away"
        ),
Contact(
            id = Uuid.random(),
            name = "Diana Martinez",
            avatarInitials = "DM",
            avatarUrl = "https://i.pravatar.cc/150?img=5",
            status = "Available"
        ),
Contact(
            id = Uuid.random(),
            name = "Frank Wilson",
            avatarInitials = "FW",
            avatarUrl = "https://i.pravatar.cc/150?img=13",
            status = "Available"
        ),
Contact(
            id = Uuid.random(),
            name = "Grace Lee",
            avatarInitials = "GL",
            avatarUrl = "https://i.pravatar.cc/150?img=9",
            status = "Busy"
        ),
Contact(
            id = Uuid.random(),
            name = "Henry Davis",
            avatarInitials = "HD",
            avatarUrl = "https://i.pravatar.cc/150?img=14",
            status = "Available"
        ),
Contact(
            id = Uuid.random(),
            name = "Ivy Chen",
            avatarInitials = "IC",
            avatarUrl = "https://i.pravatar.cc/150?img=10",
            status = "Available"
        ),
Contact(
            id = Uuid.random(),
            name = "Jack Brown",
            avatarInitials = "JB",
            avatarUrl = "https://i.pravatar.cc/150?img=15",
            status = "Away"
        ),
Contact(
            id = Uuid.random(),
            name = "Kate Wilson",
            avatarInitials = "KW",
            avatarUrl = "https://i.pravatar.cc/150?img=20",
            status = "Available"
        ),
Contact(
            id = Uuid.random(),
            name = "Liam Taylor",
            avatarInitials = "LT",
            avatarUrl = "https://i.pravatar.cc/150?img=33",
            status = "Offline"
        ),
    )
    
    private fun getInitialMessages(): List<MessageViewModel> = listOf(
        // Conversation 1 - Alice Johnson
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_ALICE,
            content = "Hi! How are you doing?",
            timestamp = Clock.System.now() - 30.minutes,
            senderId = "user1",
            senderOdinId = "Alice Johnson",
            isCurrentUser = false,
            isRead = true,
            messageAppData = MessageAppData()
        ),
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_ALICE,
            content = "I'm doing great, thanks! How about you?",
            timestamp = Clock.System.now() - 28.minutes,
            senderId = "me",
            senderOdinId = "Me",
            isCurrentUser = true,
            isRead = true,
            messageAppData = MessageAppData()
        ),
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_ALICE,
            content = "Hey! Are we still on for dinner tonight?",
            timestamp = Clock.System.now() - 15.minutes,
            senderId = "user1",
            senderOdinId = "Alice Johnson",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_ALICE,
            content = "What time works for you?",
            timestamp = Clock.System.now() - 10.minutes,
            senderId = "user1",
            senderOdinId = "Alice Johnson",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
        
        // Conversation 2 - Bob Smith
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_BOB,
            content = "Could you help me with that bug we discussed?",
            timestamp = Clock.System.now() - 2.hours,
            senderId = "user2",
            senderOdinId = "Bob Smith",
            isCurrentUser = false,
            isRead = true,
            messageAppData = MessageAppData()
        ),
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_BOB,
            content = "Sure! I'll take a look at it now.",
            timestamp = Clock.System.now() - 1.hours - 45.minutes,
            senderId = "me",
            senderOdinId = "Me",
            isCurrentUser = true,
            isRead = true,
            messageAppData = MessageAppData()
        ),
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_BOB,
            content = "Thanks for your help earlier! 👍",
            timestamp = Clock.System.now() - 1.hours - 15.minutes,
            senderId = "user2",
            senderOdinId = "Bob Smith",
            isCurrentUser = false,
            isRead = true,
            messageAppData = MessageAppData()
        ),
        
        // Conversation 3 - Team Planning
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_TEAM,
            content = "What time is our meeting today?",
            timestamp = Clock.System.now() - 3.hours,
            senderId = "user3",
            senderOdinId = "David",
            isCurrentUser = false,
            isRead = true,
            messageAppData = MessageAppData()
        ),
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_TEAM,
            content = "It was scheduled for 2 PM",
            timestamp = Clock.System.now() - 2.hours - 45.minutes,
            senderId = "me",
            senderOdinId = "Me",
            isCurrentUser = true,
            isRead = true,
            messageAppData = MessageAppData()
        ),
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_TEAM,
            content = "The meeting is rescheduled to 3 PM",
            timestamp = Clock.System.now() - 2.hours - 30.minutes,
            senderId = "user4",
            senderOdinId = "Charlie",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_TEAM,
            content = "Can everyone make it at that time?",
            timestamp = Clock.System.now() - 2.hours - 20.minutes,
            senderId = "user5",
            senderOdinId = "Sarah",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_TEAM,
            content = "I need to check the agenda",
            timestamp = Clock.System.now() - 2.hours - 15.minutes,
            senderId = "user6",
            senderOdinId = "Mike",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_TEAM,
            content = "The updated agenda is in the shared folder",
            timestamp = Clock.System.now() - 2.hours - 10.minutes,
            senderId = "user3",
            senderOdinId = "David",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_TEAM,
            content = "Thanks David!",
            timestamp = Clock.System.now() - 2.hours - 5.minutes,
            senderId = "user5",
            senderOdinId = "Sarah",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
        
        // Conversation 4 - Diana Martinez
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_DIANA,
            content = "Can you send me those files?",
            timestamp = Clock.System.now() - 3.hours - 20.minutes,
            senderId = "user5",
            senderOdinId = "Diana Martinez",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
        
        // Conversation 5 - Project Starlight
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_STARLIGHT,
            content = "I've pushed the latest changes",
            timestamp = Clock.System.now() - 1.days,
            senderId = "user6",
            senderOdinId = "Emily",
            isCurrentUser = false,
            isRead = true,
            messageAppData = MessageAppData()
        ),
MessageViewModel(
            id = Uuid.random(),
            conversationId = CONVERSATION_STARLIGHT,
            content = "Great! I'll review them soon.",
            timestamp = Clock.System.now() - 1.days + 10.minutes,
            senderId = "me",
            senderOdinId = "Me",
            isCurrentUser = true,
            isRead = true,
            messageAppData = MessageAppData()
        ),
    )
    
    fun getConversationById(id: Uuid): ConversationViewModel? {
        return _conversations.value.find { it.id == id }
    }
    
    fun getMessagesByConversationId(conversationId: Uuid): List<MessageViewModel> {
        return _messages.value.filter { it.conversationId == conversationId }
    }
    
    private fun calculateUnreadCount(conversationId: Uuid): Int {
        return _messages.value.count { 
            it.conversationId == conversationId && !it.isCurrentUser && !it.isRead 
        }
    }
    
    private fun updateConversationUnreadCount(conversationId: Uuid) {
        val unreadCount = calculateUnreadCount(conversationId)
        val conversation = getConversationById(conversationId)
        conversation?.let {
            updateConversation(it.copy(unreadCount = unreadCount))
        }
    }
    
    fun markConversationAsRead(conversationId: Uuid) {
        _messages.value = _messages.value.map { message ->
            if (message.conversationId == conversationId && !message.isCurrentUser && !message.isRead) {
                message.copy(isRead = true)
            } else {
                message
            }
        }
        updateConversationUnreadCount(conversationId)
    }
    
    fun createConversation(
        name: String,
        avatarInitials: String,
        isPinned: Boolean = false,
        avatarUrl: String = "https://i.pravatar.cc/150?img=${(1..70).random()}"
    ): ConversationViewModel {
        val newId = Uuid.random()
        val newConversationViewModel = ConversationViewModel(
            id = newId,
            name = name,
            lastMessage = "",
            timestamp = Clock.System.now(),
            unreadCount = 0,
            avatarInitials = avatarInitials,
            avatarUrl = avatarUrl,
            avatarTiny = null,
            lastRead = Clock.System.now()
        )
        _conversations.value = _conversations.value + newConversationViewModel
        return newConversationViewModel
    }
    
    fun updateConversation(conversationViewModel: ConversationViewModel) {
        _conversations.value = _conversations.value.map { 
            if (it.id == conversationViewModel.id) conversationViewModel else it
        }
    }
    
    fun deleteConversation(conversationId: Uuid) {
        _conversations.value = _conversations.value.filter { it.id != conversationId }
        _messages.value = _messages.value.filter { it.conversationId != conversationId }
    }
    
    fun addMessage(
        conversationId: Uuid,
        content: String,
        senderId: String,
        senderName: String,
        isCurrentUser: Boolean = false,
        timestamp: Instant = Clock.System.now()
    ): MessageViewModel {
        val newId = Uuid.random()
        val newMessageViewModel = MessageViewModel(
            id = newId,
            conversationId = conversationId,
            content = content,
            timestamp = timestamp,
            senderId = senderId,
            senderOdinId = senderName,
            isCurrentUser = isCurrentUser,
            isRead = isCurrentUser, // Current user's messages are always read
            messageAppData = MessageAppData()
        )
        _messages.value = _messages.value + newMessageViewModel
        
        // Update conversation's last message
        val conversation = getConversationById(conversationId)
        conversation?.let {
            updateConversation(
                it.copy(
                    lastMessage = content,
                    timestamp = timestamp
                )
            )
        }
        
        updateConversationUnreadCount(conversationId)
        
        return newMessageViewModel
    }

    fun updateMessage(messageViewModel: MessageViewModel) {
        _messages.value = _messages.value.map { 
            if (it.id == messageViewModel.id) messageViewModel else it
        }
        
        // Update conversation's last message if this is the latest message
        val conversationMessages = getMessagesByConversationId(messageViewModel.conversationId)
        if (conversationMessages.lastOrNull()?.id == messageViewModel.id) {
            val conversation = getConversationById(messageViewModel.conversationId)
            conversation?.let {
                updateConversation(
                    it.copy(
                        lastMessage = messageViewModel.content,
                        timestamp = messageViewModel.timestamp
                    )
                )
            }
        }
    }
    
    fun deleteMessage(messageId: Uuid) {
        val message = _messages.value.find { it.id == messageId }
        _messages.value = _messages.value.filter { it.id != messageId }
        
        // Update conversation's last message if the deleted message was the last one
        message?.let { deletedMessage ->
            val conversationMessages = getMessagesByConversationId(deletedMessage.conversationId)
            val conversation = getConversationById(deletedMessage.conversationId)
            conversation?.let {
                val lastMsg = conversationMessages.lastOrNull()
                updateConversation(
                    it.copy(
                        lastMessage = lastMsg?.content ?: "",
                        timestamp = lastMsg?.timestamp ?: Clock.System.now()
                    )
                )
            }
        }
    }
    
    fun getAllContacts(): List<Contact> {
        return _contacts.value
    }
    
    fun searchContacts(query: String): List<Contact> {
        return if (query.isBlank()) {
            _contacts.value
        } else {
            _contacts.value.filter { 
                it.name.contains(query, ignoreCase = true)
            }
        }
    }
    
    fun createConversationFromContact(contact: Contact): ConversationViewModel {
        val existingConversation = _conversations.value.find { 
            it.name == contact.name 
        }
        
        return existingConversation ?: createConversation(
            name = contact.name,
            avatarInitials = contact.avatarInitials,
            isPinned = false,
            avatarUrl = contact.avatarUrl
        )
    }
}
