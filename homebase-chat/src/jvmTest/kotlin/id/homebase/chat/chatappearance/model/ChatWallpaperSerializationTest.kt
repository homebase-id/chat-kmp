package id.homebase.chat.chatappearance.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatWallpaperSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun noneRoundTrip() {
        roundTrip(ChatWallpaper.None)
    }

    @Test
    fun solidColorRoundTrip() {
        roundTrip(ChatWallpaper.SolidColor(id = "blush", colorArgb = 0xFFE26983))
    }

    @Test
    fun gradientColorRoundTrip() {
        roundTrip(
            ChatWallpaper.GradientColor(
                id = "sunset",
                colorsArgb = listOf(0xFFF3DC47, 0xFFE44040),
                positions = listOf(0f, 1f),
                angleDegrees = 168f,
            ),
        )
    }

    @Test
    fun gradientPreservesPositions() {
        val positions = listOf(
            0f, 0.08f, 0.15f, 0.23f, 0.29f, 0.35f, 0.41f, 0.47f,
            0.53f, 0.59f, 0.65f, 0.71f, 0.78f, 0.84f, 0.92f, 1f,
        )
        val original: ChatWallpaper = ChatWallpaper.GradientColor(
            id = "test",
            colorsArgb = List(16) { 0xFF000000 },
            positions = positions,
            angleDegrees = 180f,
        )
        val decoded = json.decodeFromString(
            ChatWallpaper.serializer(),
            json.encodeToString(ChatWallpaper.serializer(), original),
        )
        assertEquals(positions, (decoded as ChatWallpaper.GradientColor).positions)
    }

    @Test
    fun photoRoundTrip() {
        val original: ChatWallpaper = ChatWallpaper.Photo(id = "custom_1", payloadKey = "chat_wlpr")
        val decoded = json.decodeFromString(
            ChatWallpaper.serializer(),
            json.encodeToString(ChatWallpaper.serializer(), original),
        )
        assertEquals("chat_wlpr", (decoded as ChatWallpaper.Photo).payloadKey)
    }

    @Test
    fun unknownFieldsIgnored() {
        val jsonStr = """{"type":"solid_color","id":"test","colorArgb":4294901760,"extra":true}"""
        assertEquals("test", json.decodeFromString(ChatWallpaper.serializer(), jsonStr).id)
    }

    private fun roundTrip(original: ChatWallpaper) {
        val decoded = json.decodeFromString(
            ChatWallpaper.serializer(),
            json.encodeToString(ChatWallpaper.serializer(), original),
        )
        assertEquals(original, decoded)
    }
}
