package id.homebase.chat.chatappearance.model

import id.homebase.chat.services.convo.ConversationLocalAppDataJson
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationLocalAppDataJsonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun oldJsonWithoutNewFieldsDeserializes() {
        val oldJson = """{"lastReadTime":1715100000000}"""
        val parsed = json.decodeFromString(ConversationLocalAppDataJson.serializer(), oldJson)
        assertNull(parsed.chatColorId)
        assertNull(parsed.wallpaper)
        assertNull(parsed.wallpaperDimInDarkTheme)
    }

    @Test
    fun newFieldsDefaultToNullWhenAbsent() {
        val parsed = json.decodeFromString(ConversationLocalAppDataJson.serializer(), "{}")
        assertNull(parsed.chatColorId)
        assertNull(parsed.wallpaper)
        assertNull(parsed.wallpaperDimInDarkTheme)
    }

    @Test
    fun existingFieldsSurviveWithNewFieldsPresent() {
        val fullJson = """{"lastReadTime":1715100000000,"chatColorId":"crimson"}"""
        val parsed = json.decodeFromString(ConversationLocalAppDataJson.serializer(), fullJson)
        assertEquals("crimson", parsed.chatColorId)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val futureJson = """{"futureField":"hello","chatColorId":"teal"}"""
        val parsed = json.decodeFromString(ConversationLocalAppDataJson.serializer(), futureJson)
        assertEquals("teal", parsed.chatColorId)
    }

    @Test
    fun copyPreservesAllFields() {
        val original = ConversationLocalAppDataJson(chatColorId = "crimson")
        val copied = original.copy(chatColorId = "teal")
        assertEquals("teal", copied.chatColorId)
    }

    @Test
    fun wallpaperDataRoundTrip() {
        val wpData = ChatWallpaperData(
            type = "solid_color",
            id = "blush",
            colorArgb = 0xFFE26983,
        )
        val original = ConversationLocalAppDataJson(
            chatColorId = "crimson",
            wallpaper = wpData,
            wallpaperDimInDarkTheme = false,
        )
        val encoded = json.encodeToString(ConversationLocalAppDataJson.serializer(), original)
        val decoded = json.decodeFromString(ConversationLocalAppDataJson.serializer(), encoded)
        assertEquals("crimson", decoded.chatColorId)
        assertEquals(wpData, decoded.wallpaper)
        assertEquals(false, decoded.wallpaperDimInDarkTheme)
    }
}
