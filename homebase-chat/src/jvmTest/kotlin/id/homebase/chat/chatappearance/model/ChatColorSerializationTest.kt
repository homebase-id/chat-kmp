package id.homebase.chat.chatappearance.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatColorSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun autoRoundTrip() {
        val original: ChatColor = ChatColor.Auto
        val encoded = json.encodeToString(ChatColor.serializer(), original)
        val decoded = json.decodeFromString(ChatColor.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun notSetRoundTrip() {
        val original: ChatColor = ChatColor.NotSet
        val encoded = json.encodeToString(ChatColor.serializer(), original)
        val decoded = json.decodeFromString(ChatColor.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun solidRoundTrip() {
        val original: ChatColor = ChatColor.Solid(id = "crimson", colorArgb = 0xFFCF163E)
        val encoded = json.encodeToString(ChatColor.serializer(), original)
        val decoded = json.decodeFromString(ChatColor.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun gradientRoundTrip() {
        val original: ChatColor = ChatColor.Gradient(
            id = "ember",
            colorsArgb = listOf(0xFFE57C00, 0xFF5E0000),
            angleDegrees = 162f,
        )
        val encoded = json.encodeToString(ChatColor.serializer(), original)
        val decoded = json.decodeFromString(ChatColor.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun gradientPreservesColorOrder() {
        val original: ChatColor = ChatColor.Gradient(
            id = "test",
            colorsArgb = listOf(0xFFAABBCC, 0xFF112233),
            angleDegrees = 180f,
        )
        val decoded = json.decodeFromString(
            ChatColor.serializer(),
            json.encodeToString(ChatColor.serializer(), original),
        )
        assertEquals(
            listOf(0xFFAABBCC, 0xFF112233),
            (decoded as ChatColor.Gradient).colorsArgb,
        )
    }

    @Test
    fun unknownFieldsIgnored() {
        val jsonStr = """{"type":"solid","id":"test","colorArgb":4294901760,"unknownField":"hello"}"""
        val decoded = json.decodeFromString(ChatColor.serializer(), jsonStr)
        assertEquals("test", decoded.id)
    }
}
