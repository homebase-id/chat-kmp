package id.homebase.chat.widget

import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyQuotePlainTextTest {

    @Test
    fun codeBlock_quotesCodeWithoutFence() {
        assertEquals("fun main() {", "```kotlin\nfun main() {".replyQuotePlainText())
    }

    @Test
    fun emphasisAndBrArtifacts_areStripped() {
        assertEquals("hello world", "<br>\n**hello** _world_".replyQuotePlainText())
    }

    @Test
    fun plainText_isUntouched() {
        assertEquals("well-being", "well-being".replyQuotePlainText())
    }
}
