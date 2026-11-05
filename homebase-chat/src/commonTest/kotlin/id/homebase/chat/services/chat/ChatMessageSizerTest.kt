package id.homebase.chat.services.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatMessageSizerTest {
    // {"message":"…"} adds 14 bytes; 5248 bytes is under 7000 as plain base64 but 7020 once AES-CBC padded.
    @Test fun the_header_decision_measures_the_encrypted_size() {
        assertTrue(ChatMessageSizer.shouldEmbedInHeader("x".repeat(5233)))
        assertFalse(ChatMessageSizer.shouldEmbedInHeader("x".repeat(5234)))
    }
}
