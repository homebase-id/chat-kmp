package id.homebase.chat.chatappearance.ui

import com.russhwolf.settings.Settings
import id.homebase.chat.chatappearance.data.ChatAppearanceRepository
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.model.ChatColorPresets
import id.homebase.chat.chatappearance.model.ChatWallpaper
import id.homebase.chat.chatappearance.model.ChatWallpaperPresets
import id.homebase.core.settings.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

@OptIn(ExperimentalCoroutinesApi::class)
class ChatColorWallpaperViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createVm(conversationId: String? = null): ChatColorWallpaperViewModel {
        val settings = InMemorySettings()
        val userPrefs = UserPreferences(settings)
        val repo = ChatAppearanceRepository(userPrefs)
        return ChatColorWallpaperViewModel(repo, conversationId)
    }

    @Test
    fun initialStateIsAutoAndNone() = runTest {
        val vm = createVm()
        assertEquals(ChatColor.Auto, vm.uiState.value.activeChatColor)
        assertEquals(ChatWallpaper.None, vm.uiState.value.activeWallpaper)
        assertTrue(vm.uiState.value.dimInDarkTheme)
        assertFalse(vm.uiState.value.isPerConversation)
    }

    @Test
    fun setChatColorUpdatesState() = runTest {
        val vm = createVm()
        vm.setChatColor(ChatColorPresets.crimson)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("crimson", vm.uiState.value.activeChatColor.id)
    }

    @Test
    fun setWallpaperUpdatesState() = runTest {
        val vm = createVm()
        vm.setWallpaper(ChatWallpaperPresets.blush)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("blush", vm.uiState.value.activeWallpaper.id)
    }

    @Test
    fun setDimInDarkThemeUpdatesState() = runTest {
        val vm = createVm()
        vm.setDimInDarkTheme(false)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.dimInDarkTheme)
    }

    @Test
    fun resetChatColorsResetsToAuto() = runTest {
        val vm = createVm()
        vm.setChatColor(ChatColorPresets.crimson)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.resetChatColors()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ChatColor.Auto, vm.uiState.value.activeChatColor)
    }

    @Test
    fun resetWallpapersResetsToNone() = runTest {
        val vm = createVm()
        vm.setWallpaper(ChatWallpaperPresets.blush)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.resetWallpapers()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ChatWallpaper.None, vm.uiState.value.activeWallpaper)
    }

    @Test
    fun perConversationModeWhenConversationIdProvided() = runTest {
        val vm = createVm(conversationId = "some-id")
        assertTrue(vm.uiState.value.isPerConversation)
    }
}
