package id.homebase.chat.data

import id.homebase.chat.Conversation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class MockChatApiProvider {
    
    private val _conversations = MutableStateFlow(getInitialConversations())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    
    private val _messages = MutableStateFlow(getInitialMessages())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    
    private val _contacts = MutableStateFlow(getInitialContacts())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()
    
    private fun getInitialConversations(): List<Conversation> = listOf(
        Conversation(
            id = "1",
            name = "Alice Johnson",
            lastMessage = "Hey! Are we still on for dinner tonight?",
            timestamp = Clock.System.now() - 15.minutes,
            unreadCount = 2,
            avatarInitials = "AJ",
            avatarUrl = "https://i.pravatar.cc/150?img=1",
            isPinned = true
        ),
        Conversation(
            id = "2",
            name = "Bob Smith",
            lastMessage = "Thanks for your help earlier! 👍",
            timestamp = Clock.System.now() - 1.hours - 15.minutes,
            unreadCount = 0,
            avatarInitials = "BS",
            avatarUrl = "https://i.pravatar.cc/150?img=12",
        ),
        Conversation(
            id = "3",
            name = "Team Planning",
            lastMessage = "Charlie: The meeting is rescheduled to 3 PM",
            timestamp = Clock.System.now() - 2.hours - 30.minutes,
            unreadCount = 5,
            avatarInitials = "TP",
            avatarUrl = "https://i.pravatar.cc/150?img=60",
            isPinned = true
        ),
        Conversation(
            id = "4",
            name = "Diana Martinez",
            lastMessage = "Can you send me those files?",
            timestamp = Clock.System.now() - 3.hours - 20.minutes,
            unreadCount = 1,
            avatarInitials = "DM",
            avatarUrl = "https://i.pravatar.cc/150?img=5",
        ),
        Conversation(
            id = "5",
            name = "Project Starlight",
            lastMessage = "Emily: I've pushed the latest changes",
            timestamp = Clock.System.now() - 1.days,
            unreadCount = 0,
            avatarInitials = "PS",
            avatarUrl = "https://i.pravatar.cc/150?img=70",
        ),
        Conversation(
            id = "6",
            name = "Frank Wilson",
            lastMessage = "See you tomorrow!",
            timestamp = Clock.System.now() - 1.days - 3.hours,
            unreadCount = 0,
            avatarInitials = "FW",
            avatarUrl = "",
        ),
        Conversation(
            id = "7",
            name = "Grace Lee",
            lastMessage = "That sounds great! 😊",
            timestamp = Clock.System.now() - 3.days,
            unreadCount = 0,
            avatarInitials = "GL",
            avatarUrl = "https://i.pravatar.cc/150?img=9",
        ),
        Conversation(
            id = "8",
            name = "Henry Davis",
            lastMessage = "I'll take a look at it",
            timestamp = Clock.System.now() - 3.days - 2.hours,
            unreadCount = 0,
            avatarInitials = "HD",
            avatarUrl = "https://i.pravatar.cc/150?img=14",
        ),
        Conversation(
            id = "9",
            name = "Family",
            lastMessage = "Mom: Don't forget to call grandma",
            timestamp = Clock.System.now() - 4.days,
            unreadCount = 0,
            avatarInitials = "FM",
            avatarUrl = "https://i.pravatar.cc/150?img=65",
        ),
        Conversation(
            id = "10",
            name = "Ivy Chen",
            lastMessage = "Perfect! See you there",
            timestamp = Clock.System.now() - 5.days,
            unreadCount = 0,
            avatarInitials = "IC",
            avatarUrl = "https://i.pravatar.cc/150?img=10",
        ),
    )
    
    private fun getInitialContacts(): List<Contact> = listOf(
        Contact(
            id = "c1",
            name = "Alice Johnson",
            avatarInitials = "AJ",
            avatarUrl = "https://i.pravatar.cc/150?img=1",
            status = "Available"
        ),
        Contact(
            id = "c2",
            name = "Bob Smith",
            avatarInitials = "BS",
            avatarUrl = "https://i.pravatar.cc/150?img=12",
            status = "Away"
        ),
        Contact(
            id = "c4",
            name = "Diana Martinez",
            avatarInitials = "DM",
            avatarUrl = "https://i.pravatar.cc/150?img=5",
            status = "Available"
        ),
        Contact(
            id = "c6",
            name = "Frank Wilson",
            avatarInitials = "FW",
            avatarUrl = "https://i.pravatar.cc/150?img=13",
            status = "Available"
        ),
        Contact(
            id = "c7",
            name = "Grace Lee",
            avatarInitials = "GL",
            avatarUrl = "https://i.pravatar.cc/150?img=9",
            status = "Busy"
        ),
        Contact(
            id = "c8",
            name = "Henry Davis",
            avatarInitials = "HD",
            avatarUrl = "https://i.pravatar.cc/150?img=14",
            status = "Available"
        ),
        Contact(
            id = "c10",
            name = "Ivy Chen",
            avatarInitials = "IC",
            avatarUrl = "https://i.pravatar.cc/150?img=10",
            status = "Available"
        ),
        Contact(
            id = "c11",
            name = "Jack Brown",
            avatarInitials = "JB",
            avatarUrl = "https://i.pravatar.cc/150?img=15",
            status = "Away"
        ),
        Contact(
            id = "c12",
            name = "Kate Wilson",
            avatarInitials = "KW",
            avatarUrl = "https://i.pravatar.cc/150?img=20",
            status = "Available"
        ),
        Contact(
            id = "c13",
            name = "Liam Taylor",
            avatarInitials = "LT",
            avatarUrl = "https://i.pravatar.cc/150?img=33",
            status = "Offline"
        ),
    )
    
    private fun getInitialMessages(): List<Message> = listOf(
        // Conversation 1 - Alice Johnson
        Message(
            id = "m1_1",
            conversationId = "1",
            content = "Hi! How are you doing?",
            timestamp = Clock.System.now() - 30.minutes,
            senderId = "user1",
            senderName = "Alice Johnson",
            isCurrentUser = false,
            isRead = true,
            messageAppData = MessageAppData()
        ),
        Message(
            id = "m1_2",
            conversationId = "1",
            content = "I'm doing great, thanks! How about you?",
            timestamp = Clock.System.now() - 28.minutes,
            senderId = "me",
            senderName = "Me",
            isCurrentUser = true,
            isRead = true,
            messageAppData = MessageAppData()
        ),
        Message(
            id = "m1_3",
            conversationId = "1",
            content = "Hey! Are we still on for dinner tonight?",
            timestamp = Clock.System.now() - 15.minutes,
            senderId = "user1",
            senderName = "Alice Johnson",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
        Message(
            id = "m1_4",
            conversationId = "1",
            content = "What time works for you?",
            timestamp = Clock.System.now() - 10.minutes,
            senderId = "user1",
            senderName = "Alice Johnson",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
        
        // Conversation 2 - Bob Smith
        Message(
            id = "m2_1",
            conversationId = "2",
            content = "Could you help me with that bug we discussed?",
            timestamp = Clock.System.now() - 2.hours,
            senderId = "user2",
            senderName = "Bob Smith",
            isCurrentUser = false,
            isRead = true,
            messageAppData = MessageAppData()
        ),
        Message(
            id = "m2_2",
            conversationId = "2",
            content = "Sure! I'll take a look at it now.",
            timestamp = Clock.System.now() - 1.hours - 45.minutes,
            senderId = "me",
            senderName = "Me",
            isCurrentUser = true,
            isRead = true,
            messageAppData = MessageAppData()
        ),
        Message(
            id = "m2_3",
            conversationId = "2",
            content = "Thanks for your help earlier! 👍",
            timestamp = Clock.System.now() - 1.hours - 15.minutes,
            senderId = "user2",
            senderName = "Bob Smith",
            isCurrentUser = false,
            isRead = true,
            messageAppData = MessageAppData()
        ),
        
        // Conversation 3 - Team Planning
        Message(
            id = "m3_1",
            conversationId = "3",
            content = "What time is our meeting today?",
            timestamp = Clock.System.now() - 3.hours,
            senderId = "user3",
            senderName = "David",
            isCurrentUser = false,
            isRead = true,
            messageAppData = MessageAppData()
        ),
        Message(
            id = "m3_2",
            conversationId = "3",
            content = "It was scheduled for 2 PM",
            timestamp = Clock.System.now() - 2.hours - 45.minutes,
            senderId = "me",
            senderName = "Me",
            isCurrentUser = true,
            isRead = true,
            messageAppData = MessageAppData()
        ),
        Message(
            id = "m3_3",
            conversationId = "3",
            content = "The meeting is rescheduled to 3 PM",
            timestamp = Clock.System.now() - 2.hours - 30.minutes,
            senderId = "user4",
            senderName = "Charlie",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
        Message(
            id = "m3_4",
            conversationId = "3",
            content = "Can everyone make it at that time?",
            timestamp = Clock.System.now() - 2.hours - 20.minutes,
            senderId = "user5",
            senderName = "Sarah",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
        Message(
            id = "m3_5",
            conversationId = "3",
            content = "I need to check the agenda",
            timestamp = Clock.System.now() - 2.hours - 15.minutes,
            senderId = "user6",
            senderName = "Mike",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
        Message(
            id = "m3_6",
            conversationId = "3",
            content = "The updated agenda is in the shared folder",
            timestamp = Clock.System.now() - 2.hours - 10.minutes,
            senderId = "user3",
            senderName = "David",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
        Message(
            id = "m3_7",
            conversationId = "3",
            content = "Thanks David!",
            timestamp = Clock.System.now() - 2.hours - 5.minutes,
            senderId = "user5",
            senderName = "Sarah",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
        
        // Conversation 4 - Diana Martinez
        Message(
            id = "m4_1",
            conversationId = "4",
            content = "Can you send me those files?",
            timestamp = Clock.System.now() - 3.hours - 20.minutes,
            senderId = "user5",
            senderName = "Diana Martinez",
            isCurrentUser = false,
            isRead = false,
            messageAppData = MessageAppData()
        ),
        
        // Conversation 5 - Project Starlight
        Message(
            id = "m5_1",
            conversationId = "5",
            content = "I've pushed the latest changes",
            timestamp = Clock.System.now() - 1.days,
            senderId = "user6",
            senderName = "Emily",
            isCurrentUser = false,
            isRead = true,
            messageAppData = MessageAppData()
        ),
        Message(
            id = "m5_2",
            conversationId = "5",
            content = "Great! I'll review them soon.",
            timestamp = Clock.System.now() - 1.days + 10.minutes,
            senderId = "me",
            senderName = "Me",
            isCurrentUser = true,
            isRead = true,
            messageAppData = MessageAppData()
        ),
    )
    
    fun getConversationById(id: String): Conversation? {
        return _conversations.value.find { it.id == id }
    }
    
    fun getMessagesByConversationId(conversationId: String): List<Message> {
        return _messages.value.filter { it.conversationId == conversationId }
    }
    
    private fun calculateUnreadCount(conversationId: String): Int {
        return _messages.value.count { 
            it.conversationId == conversationId && !it.isCurrentUser && !it.isRead 
        }
    }
    
    private fun updateConversationUnreadCount(conversationId: String) {
        val unreadCount = calculateUnreadCount(conversationId)
        val conversation = getConversationById(conversationId)
        conversation?.let {
            updateConversation(it.copy(unreadCount = unreadCount))
        }
    }
    
    fun markConversationAsRead(conversationId: String) {
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
    ): Conversation {
        val newId = ((_conversations.value.maxOfOrNull { it.id.toIntOrNull() ?: 0 } ?: 0) + 1).toString()
        val newConversation = Conversation(
            id = newId,
            name = name,
            lastMessage = "",
            timestamp = Clock.System.now(),
            unreadCount = 0,
            avatarInitials = avatarInitials,
            avatarUrl = avatarUrl,
            isPinned = isPinned
        )
        _conversations.value = _conversations.value + newConversation
        return newConversation
    }
    
    fun updateConversation(conversation: Conversation) {
        _conversations.value = _conversations.value.map { 
            if (it.id == conversation.id) conversation else it 
        }
    }
    
    fun deleteConversation(conversationId: String) {
        _conversations.value = _conversations.value.filter { it.id != conversationId }
        _messages.value = _messages.value.filter { it.conversationId != conversationId }
    }
    
    fun addMessage(
        conversationId: String,
        content: String,
        senderId: String,
        senderName: String,
        isCurrentUser: Boolean = false,
        timestamp: Instant = Clock.System.now()
    ): Message {
        val newId = "m${conversationId}_${Clock.System.now().toEpochMilliseconds()}"
        val newMessage = Message(
            id = newId,
            conversationId = conversationId,
            content = content,
            timestamp = timestamp,
            senderId = senderId,
            senderName = senderName,
            isCurrentUser = isCurrentUser,
            isRead = isCurrentUser, // Current user's messages are always read
            messageAppData = MessageAppData()
        )
        _messages.value = _messages.value + newMessage
        
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
        
        return newMessage
    }

    fun updateMessage(message: Message) {
        _messages.value = _messages.value.map { 
            if (it.id == message.id) message else it 
        }
        
        // Update conversation's last message if this is the latest message
        val conversationMessages = getMessagesByConversationId(message.conversationId)
        if (conversationMessages.lastOrNull()?.id == message.id) {
            val conversation = getConversationById(message.conversationId)
            conversation?.let {
                updateConversation(
                    it.copy(
                        lastMessage = message.content,
                        timestamp = message.timestamp
                    )
                )
            }
        }
    }
    
    fun deleteMessage(messageId: String) {
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
    
    fun createConversationFromContact(contact: Contact): Conversation {
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
