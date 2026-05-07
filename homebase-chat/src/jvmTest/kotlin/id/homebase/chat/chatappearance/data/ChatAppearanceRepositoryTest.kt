package id.homebase.chat.chatappearance.data

import com.russhwolf.settings.Settings
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.model.ChatColorPresets
import id.homebase.chat.chatappearance.model.ChatWallpaper
import id.homebase.chat.chatappearance.model.ChatWallpaperData
import id.homebase.chat.chatappearance.model.ChatWallpaperPresets
import id.homebase.core.settings.UserPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Minimal in-memory [Settings] for testing without the multiplatform-settings-test artifact. */
private class InMemorySettings : Settings {
    private val map = mutableMapOf<String, Any>()

    override val keys: Set<String> get() = map.keys
    override val size: Int get() = map.size
    override fun clear() = map.clear()
    override fun remove(key: String) { map.remove(key) }
    override fun hasKey(key: String): Boolean = key in map

    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getInt(key: String, defaultValue: Int): Int = map[key] as? Int ?: defaultValue
    override fun getIntOrNull(key: String): Int? = map[key] as? Int

    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getLong(key: String, defaultValue: Long): Long = map[key] as? Long ?: defaultValue
    override fun getLongOrNull(key: String): Long? = map[key] as? Long

    override fun putString(key: String, value: String) { map[key] = value }
    override fun getString(key: String, defaultValue: String): String = map[key] as? String ?: defaultValue
    override fun getStringOrNull(key: String): String? = map[key] as? String

    override fun putFloat(key: String, value: Float) { map[key] = value }
    override fun getFloat(key: String, defaultValue: Float): Float = map[key] as? Float ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = map[key] as? Float

    override fun putDouble(key: String, value: Double) { map[key] = value }
    override fun getDouble(key: String, defaultValue: Double): Double = map[key] as? Double ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = map[key] as? Double

    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = map[key] as? Boolean ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = map[key] as? Boolean
}

class ChatAppearanceRepositoryTest {

    private fun createRepo(): ChatAppearanceRepository {
        val settings = InMemorySettings()
        val userPrefs = UserPreferences(settings)
        return ChatAppearanceRepository(userPrefs)
    }

    @Test
    fun defaultGlobalChatColorIsAuto() {
        assertEquals(ChatColor.Auto, createRepo().getGlobalChatColor())
    }

    @Test
    fun setAndGetGlobalChatColor() {
        val repo = createRepo()
        repo.setGlobalChatColor(ChatColorPresets.crimson)
        assertTrue(repo.getGlobalChatColor() is ChatColor.Solid)
        assertEquals("crimson", repo.getGlobalChatColor().id)
    }

    @Test
    fun resetGlobalChatColorReturnsAuto() {
        val repo = createRepo()
        repo.setGlobalChatColor(ChatColorPresets.crimson)
        repo.resetGlobalChatColor()
        assertEquals(ChatColor.Auto, repo.getGlobalChatColor())
    }

    @Test
    fun defaultGlobalWallpaperIsNone() {
        assertEquals(ChatWallpaper.None, createRepo().getGlobalWallpaper())
    }

    @Test
    fun setAndGetGlobalWallpaper() {
        val repo = createRepo()
        repo.setGlobalWallpaper(ChatWallpaperPresets.blush)
        assertTrue(repo.getGlobalWallpaper() is ChatWallpaper.SolidColor)
        assertEquals("blush", repo.getGlobalWallpaper().id)
    }

    @Test
    fun resetGlobalWallpaperReturnsNone() {
        val repo = createRepo()
        repo.setGlobalWallpaper(ChatWallpaperPresets.blush)
        repo.resetGlobalWallpaper()
        assertEquals(ChatWallpaper.None, repo.getGlobalWallpaper())
    }

    @Test
    fun defaultDimInDarkThemeIsTrue() {
        assertTrue(createRepo().getGlobalDimInDarkTheme())
    }

    @Test
    fun setDimInDarkTheme() {
        val repo = createRepo()
        repo.setGlobalDimInDarkTheme(false)
        assertEquals(false, repo.getGlobalDimInDarkTheme())
    }

    @Test
    fun resolveAutoColorWithWallpaper() {
        assertEquals("crimson", createRepo().resolveAutoColor(ChatWallpaperPresets.blush).id)
    }

    @Test
    fun resolveAutoColorWithNone() {
        assertEquals("ultramarine", createRepo().resolveAutoColor(ChatWallpaper.None).id)
    }

    @Test
    fun effectiveColorUsesGlobalWhenNoConversationOverride() {
        val repo = createRepo()
        repo.setGlobalChatColor(ChatColorPresets.teal)
        assertEquals("teal", repo.resolveEffectiveColor(null, ChatWallpaper.None).id)
    }

    @Test
    fun effectiveColorUsesConversationOverrideWhenSet() {
        val repo = createRepo()
        repo.setGlobalChatColor(ChatColorPresets.teal)
        assertEquals("crimson", repo.resolveEffectiveColor("crimson", ChatWallpaper.None).id)
    }

    @Test
    fun effectiveColorResolvesAutoThroughMapper() {
        val repo = createRepo()
        val effective = repo.resolveEffectiveColor("auto", ChatWallpaperPresets.blush)
        assertEquals("crimson", effective.id)
    }

    @Test
    fun effectiveWallpaperUsesGlobalWhenNoConversationOverride() {
        val repo = createRepo()
        repo.setGlobalWallpaper(ChatWallpaperPresets.blush)
        assertEquals("blush", repo.resolveEffectiveWallpaper(null).id)
    }

    @Test
    fun effectiveWallpaperUsesConversationOverride() {
        val repo = createRepo()
        repo.setGlobalWallpaper(ChatWallpaperPresets.blush)
        val overrideData = ChatWallpaperData(
            type = "solid_color",
            id = "frost",
            colorArgb = 0xFF7C99B6,
        )
        assertEquals("frost", repo.resolveEffectiveWallpaper(overrideData).id)
    }
}
