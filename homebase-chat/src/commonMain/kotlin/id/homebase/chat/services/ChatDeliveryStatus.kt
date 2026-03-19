package id.homebase.chat.services

enum class ChatDeliveryStatus(val value: Int) {
    /** Message is currently being sent; Used for optimistic updates */
    Sending(15),

    /** Message has been sent and delivered to your identity */
    Sent(20),

    /** Message has been delivered to the recipient's inbox */
    Delivered(30),

    /** Message has been read by the recipient */
    Read(40),

    /** Message failed to send to the recipient */
    Failed(50);

    companion object {
        fun fromValue(value: Int): ChatDeliveryStatus? = entries.find { it.value == value }
    }
}