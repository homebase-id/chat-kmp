# Chat Bubble Colors & Wallpapers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Signal-style chat bubble color customization and conversation wallpapers with global defaults and per-conversation overrides.

**Architecture:** Data models in `chatappearance/model/` (pure Kotlin, no Compose). Persistence via `UserPreferences` (global) and `ConversationLocalAppDataJson` in `localAppData.content` (per-conversation). Bubble colors and wallpaper delivered to the widget tree via `CompositionLocal`s provided at `ConversationContent` level. Three new UI screens navigated from Appearance Settings and Conversation Settings.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx.serialization, Koin DI, multiplatform-settings, Coil3

**Spec:** `docs/superpowers/specs/2026-05-07-chat-color-wallpaper-design.md`

**Build/Test commands:**
```bash
# Run all chat module JVM tests
./gradlew homebase-chat:jvmTest --rerun-tasks

# Run specific test class
./gradlew homebase-chat:jvmTest --tests "id.homebase.chat.chatappearance.model.ChatColorSerializationTest" --rerun-tasks

# Full project build check (no tests)
./gradlew homebase-chat:compileKotlinJvm

# Desktop app (visual testing)
./gradlew desktopApp:run
```

---

## File Map

### New Files

| File | Responsibility |
|------|---------------|
| `homebase-chat/.../chatappearance/model/ChatColor.kt` | Sealed interface: Auto, NotSet, Solid, Gradient |
| `homebase-chat/.../chatappearance/model/ChatColorPresets.kt` | 22 built-in bubble colors + `findById()` |
| `homebase-chat/.../chatappearance/model/ChatWallpaper.kt` | Sealed interface: None, SolidColor, GradientColor, Photo |
| `homebase-chat/.../chatappearance/model/ChatWallpaperPresets.kt` | 21 built-in wallpaper presets + `findById()` |
| `homebase-chat/.../chatappearance/model/ChatColorsMapper.kt` | Deterministic wallpaper→color lookup table |
| `homebase-chat/.../chatappearance/model/GroupNameColors.kt` | 36-color palette for group member names |
| `homebase-chat/.../chatappearance/model/BubbleContentColor.kt` | Luminance-based text color for bubble foreground |
| `homebase-chat/.../chatappearance/data/ChatAppearanceRepository.kt` | Reads/writes global + per-conversation settings |
| `homebase-chat/.../chatappearance/ui/LocalChatAppearance.kt` | CompositionLocals for active color/wallpaper |
| `homebase-chat/.../chatappearance/ui/ChatColorWallpaperViewModel.kt` | ViewModel for all 3 settings screens |
| `homebase-chat/.../chatappearance/ui/ChatColorWallpaperScreen.kt` | Main settings screen (preview + links) |
| `homebase-chat/.../chatappearance/ui/ChatColorPickerScreen.kt` | Color grid with live preview |
| `homebase-chat/.../chatappearance/ui/WallpaperPickerScreen.kt` | Wallpaper grid + photo picker |
| `homebase-chat/.../chatappearance/ui/components/ChatPreviewMockup.kt` | Mini conversation preview widget |
| `homebase-chat/.../chatappearance/ui/components/ColorCircleItem.kt` | Color swatch (solid or gradient circle) |
| `homebase-chat/.../chatappearance/ui/components/WallpaperTileItem.kt` | Wallpaper grid tile |

### Test Files

| File | Tests |
|------|-------|
| `homebase-chat/src/jvmTest/.../chatappearance/model/ChatColorSerializationTest.kt` | JSON round-trip for all ChatColor subtypes |
| `homebase-chat/src/jvmTest/.../chatappearance/model/ChatColorPresetsTest.kt` | Unique IDs, valid ARGB, findById |
| `homebase-chat/src/jvmTest/.../chatappearance/model/ChatWallpaperSerializationTest.kt` | JSON round-trip for all ChatWallpaper subtypes |
| `homebase-chat/src/jvmTest/.../chatappearance/model/ChatWallpaperPresetsTest.kt` | Unique IDs, valid ARGB, positions monotonic |
| `homebase-chat/src/jvmTest/.../chatappearance/model/ChatColorsMapperTest.kt` | All 21 mappings + defaults |
| `homebase-chat/src/jvmTest/.../chatappearance/model/GroupNameColorsTest.kt` | 36 entries, deterministic, distribution |
| `homebase-chat/src/jvmTest/.../chatappearance/model/BubbleContentColorTest.kt` | WCAG AA contrast for all 22 presets |
| `homebase-chat/src/jvmTest/.../chatappearance/model/ConversationLocalAppDataJsonTest.kt` | Backward compat, new fields default null |
| `homebase-chat/src/jvmTest/.../chatappearance/data/ChatAppearanceRepositoryTest.kt` | Global get/set, per-conversation fallback |
| `homebase-chat/src/jvmTest/.../chatappearance/ui/ChatColorWallpaperViewModelTest.kt` | State transitions, reset, per-conversation mode |

### Modified Files

| File | Change |
|------|--------|
| `homebase-chat/.../services/convo/ConversationLocalAppDataJson.kt` | Add `chatColorId`, `wallpaper`, `wallpaperDimInDarkTheme` fields |
| `homebase-chat/.../services/ChatProtocol.kt` | Add `PAYLOAD_KEY_WALLPAPER` constant |
| `homebase-common/.../settings/UserPreferences.kt` | Add 3 global appearance preferences |
| `homebase-chat/.../widget/MessageBubbleRaw.kt` | Read bubble color from `LocalActiveChatColor` |
| `homebase-chat/.../widget/PendingMessageBubble.kt` | Read bubble color from `LocalActiveChatColor` |
| `homebase-chat/.../widget/MessageBubble.kt` | Use `GroupNameColors` for sender name in groups |
| `homebase-chat/.../widget/ConversationContent.kt` | Provide CompositionLocals + wallpaper background |
| `homebase-common/.../navigation/Routes.kt` | Add 3 new route objects |
| `homebase-core/.../navigation/AppNavHost.kt` | Wire 3 new composable destinations |
| `homebase-core/.../di/AppModule.kt` | Register repository + ViewModel |
| `homebase-core/.../screens/appearance/AppearanceSettingsScreen.kt` | Add "Chat Color & Wallpaper" row |
| `homebase-chat/.../conversationsettings/ConversationSettingsScreen.kt` | Add "Chat Color & Wallpaper" row |

---

## Task 1: ChatColor Data Model + Presets + Tests

**Files:**
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/ChatColor.kt`
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/ChatColorPresets.kt`
- Test: `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/ChatColorSerializationTest.kt`
- Test: `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/ChatColorPresetsTest.kt`

- [ ] **Step 1: Write ChatColor serialization tests**

```kotlin
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
```

- [ ] **Step 2: Write ChatColorPresets tests**

```kotlin
package id.homebase.chat.chatappearance.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatColorPresetsTest {
    @Test
    fun allPresetsHaveUniqueIds() {
        val ids = ChatColorPresets.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun has22BuiltInColors() {
        assertEquals(22, ChatColorPresets.all.size)
    }

    @Test
    fun solidColorsHaveNonZeroArgb() {
        ChatColorPresets.solids.forEach { color ->
            assertTrue(color.colorArgb != 0L, "Solid color ${color.id} has zero ARGB")
        }
    }

    @Test
    fun gradientsHaveExactlyTwoColors() {
        ChatColorPresets.gradients.forEach { gradient ->
            assertEquals(2, gradient.colorsArgb.size, "Gradient ${gradient.id} should have 2 colors")
        }
    }

    @Test
    fun gradientsHaveValidAngle() {
        ChatColorPresets.gradients.forEach { gradient ->
            assertTrue(gradient.angleDegrees in 0f..360f, "Gradient ${gradient.id} angle ${gradient.angleDegrees} out of range")
        }
    }

    @Test
    fun findByIdReturnsCorrectPreset() {
        ChatColorPresets.all.forEach { preset ->
            val found = ChatColorPresets.findById(preset.id)
            assertNotNull(found)
            assertEquals(preset.id, found.id)
        }
    }

    @Test
    fun findByIdReturnsNullForUnknown() {
        assertNull(ChatColorPresets.findById("nonexistent"))
    }

    @Test
    fun ultramarineIsDefault() {
        assertEquals("ultramarine", ChatColorPresets.default.id)
    }

    @Test
    fun has13SolidsAnd9Gradients() {
        assertEquals(13, ChatColorPresets.solids.size)
        assertEquals(9, ChatColorPresets.gradients.size)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew homebase-chat:jvmTest --tests "id.homebase.chat.chatappearance.model.ChatColorSerializationTest" --tests "id.homebase.chat.chatappearance.model.ChatColorPresetsTest" --rerun-tasks
```
Expected: FAIL — classes `ChatColor` and `ChatColorPresets` do not exist.

- [ ] **Step 4: Create ChatColor.kt**

```kotlin
package id.homebase.chat.chatappearance.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ChatColor {
    val id: String

    @Serializable
    @SerialName("auto")
    data object Auto : ChatColor {
        override val id: String = "auto"
    }

    @Serializable
    @SerialName("not_set")
    data object NotSet : ChatColor {
        override val id: String = "not_set"
    }

    @Serializable
    @SerialName("solid")
    data class Solid(
        override val id: String,
        val colorArgb: Long,
    ) : ChatColor

    @Serializable
    @SerialName("gradient")
    data class Gradient(
        override val id: String,
        val colorsArgb: List<Long>,
        val angleDegrees: Float,
    ) : ChatColor
}
```

- [ ] **Step 5: Create ChatColorPresets.kt**

```kotlin
package id.homebase.chat.chatappearance.model

object ChatColorPresets {
    val ultramarine = ChatColor.Gradient(
        id = "ultramarine", colorsArgb = listOf(0xFF0553F0, 0xFF2C6CED), angleDegrees = 0f,
    )

    val crimson = ChatColor.Solid(id = "crimson", colorArgb = 0xFFCF163E)
    val vermilion = ChatColor.Solid(id = "vermilion", colorArgb = 0xFFC73F0A)
    val burlap = ChatColor.Solid(id = "burlap", colorArgb = 0xFF6F6A58)
    val forest = ChatColor.Solid(id = "forest", colorArgb = 0xFF3B7845)
    val wintergreen = ChatColor.Solid(id = "wintergreen", colorArgb = 0xFF1D8663)
    val teal = ChatColor.Solid(id = "teal", colorArgb = 0xFF077D92)
    val blue = ChatColor.Solid(id = "blue", colorArgb = 0xFF336BA3)
    val indigo = ChatColor.Solid(id = "indigo", colorArgb = 0xFF6058CA)
    val violet = ChatColor.Solid(id = "violet", colorArgb = 0xFF9932C8)
    val plum = ChatColor.Solid(id = "plum", colorArgb = 0xFFAA377A)
    val taupe = ChatColor.Solid(id = "taupe", colorArgb = 0xFF8F616A)
    val steel = ChatColor.Solid(id = "steel", colorArgb = 0xFF71717F)

    val ember = ChatColor.Gradient(id = "ember", colorsArgb = listOf(0xFFE57C00, 0xFF5E0000), angleDegrees = 162f)
    val midnight = ChatColor.Gradient(id = "midnight", colorsArgb = listOf(0xFF2C2C3A, 0xFF787891), angleDegrees = 180f)
    val infrared = ChatColor.Gradient(id = "infrared", colorsArgb = listOf(0xFFF65560, 0xFF442CED), angleDegrees = 192f)
    val lagoon = ChatColor.Gradient(id = "lagoon", colorsArgb = listOf(0xFF004066, 0xFF32867D), angleDegrees = 180f)
    val fluorescent = ChatColor.Gradient(id = "fluorescent", colorsArgb = listOf(0xFFEC13DD, 0xFF1B36C6), angleDegrees = 192f)
    val basil = ChatColor.Gradient(id = "basil", colorsArgb = listOf(0xFF2F9373, 0xFF077343), angleDegrees = 180f)
    val sublime = ChatColor.Gradient(id = "sublime", colorsArgb = listOf(0xFF6281D5, 0xFF974460), angleDegrees = 180f)
    val sea = ChatColor.Gradient(id = "sea", colorsArgb = listOf(0xFF498FD4, 0xFF2C66A0), angleDegrees = 180f)
    val tangerine = ChatColor.Gradient(id = "tangerine", colorsArgb = listOf(0xFFDB7133, 0xFF911231), angleDegrees = 192f)

    val solids: List<ChatColor.Solid> = listOf(
        crimson, vermilion, burlap, forest, wintergreen, teal,
        blue, indigo, violet, plum, taupe, steel,
    )

    val gradients: List<ChatColor.Gradient> = listOf(
        ultramarine, ember, midnight, infrared, lagoon,
        fluorescent, basil, sublime, sea, tangerine,
    )

    val all: List<ChatColor> = listOf(ultramarine) + solids + gradients.drop(1)

    val default: ChatColor.Gradient = ultramarine

    fun findById(id: String): ChatColor? = all.firstOrNull { it.id == id }
}
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
./gradlew homebase-chat:jvmTest --tests "id.homebase.chat.chatappearance.model.ChatColorSerializationTest" --tests "id.homebase.chat.chatappearance.model.ChatColorPresetsTest" --rerun-tasks
```
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/ChatColor.kt \
       homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/ChatColorPresets.kt \
       homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/ChatColorSerializationTest.kt \
       homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/ChatColorPresetsTest.kt
git commit -m "feat(chat-appearance): add ChatColor data model and 22 built-in presets with tests"
```

---

## Task 2: ChatWallpaper Data Model + Presets + Tests

**Files:**
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/ChatWallpaper.kt`
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/ChatWallpaperPresets.kt`
- Test: `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/ChatWallpaperSerializationTest.kt`
- Test: `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/ChatWallpaperPresetsTest.kt`

- [ ] **Step 1: Write ChatWallpaper serialization tests**

```kotlin
package id.homebase.chat.chatappearance.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatWallpaperSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun noneRoundTrip() {
        val original: ChatWallpaper = ChatWallpaper.None
        val encoded = json.encodeToString(ChatWallpaper.serializer(), original)
        val decoded = json.decodeFromString(ChatWallpaper.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun solidColorRoundTrip() {
        val original: ChatWallpaper = ChatWallpaper.SolidColor(id = "blush", colorArgb = 0xFFE26983)
        val encoded = json.encodeToString(ChatWallpaper.serializer(), original)
        val decoded = json.decodeFromString(ChatWallpaper.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun gradientColorRoundTrip() {
        val original: ChatWallpaper = ChatWallpaper.GradientColor(
            id = "sunset",
            colorsArgb = listOf(0xFFF3DC47, 0xFFE44040),
            positions = listOf(0f, 1f),
            angleDegrees = 168f,
        )
        val encoded = json.encodeToString(ChatWallpaper.serializer(), original)
        val decoded = json.decodeFromString(ChatWallpaper.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun gradientPreservesPositions() {
        val positions = listOf(0f, 0.08f, 0.15f, 0.23f, 0.29f, 0.35f, 0.41f, 0.47f,
            0.53f, 0.59f, 0.65f, 0.71f, 0.78f, 0.84f, 0.92f, 1f)
        val original: ChatWallpaper = ChatWallpaper.GradientColor(
            id = "test", colorsArgb = List(16) { 0xFF000000 }, positions = positions, angleDegrees = 180f,
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
        val encoded = json.encodeToString(ChatWallpaper.serializer(), original)
        val decoded = json.decodeFromString(ChatWallpaper.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals("chat_wlpr", (decoded as ChatWallpaper.Photo).payloadKey)
    }

    @Test
    fun unknownFieldsIgnored() {
        val jsonStr = """{"type":"solid_color","id":"test","colorArgb":4294901760,"extra":true}"""
        val decoded = json.decodeFromString(ChatWallpaper.serializer(), jsonStr)
        assertEquals("test", decoded.id)
    }
}
```

- [ ] **Step 2: Write ChatWallpaperPresets tests**

```kotlin
package id.homebase.chat.chatappearance.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatWallpaperPresetsTest {
    @Test
    fun allPresetsHaveUniqueIds() {
        val ids = ChatWallpaperPresets.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun has21BuiltInWallpapers() {
        assertEquals(21, ChatWallpaperPresets.all.size)
    }

    @Test
    fun has12SolidsAnd9Gradients() {
        assertEquals(12, ChatWallpaperPresets.solids.size)
        assertEquals(9, ChatWallpaperPresets.gradientsList.size)
    }

    @Test
    fun solidWallpapersHaveNonZeroArgb() {
        ChatWallpaperPresets.solids.forEach { wp ->
            assertTrue(wp.colorArgb != 0L, "Solid wallpaper ${wp.id} has zero ARGB")
        }
    }

    @Test
    fun gradientWallpapersHave16ColorStops() {
        ChatWallpaperPresets.gradientsList.forEach { wp ->
            assertEquals(16, wp.colorsArgb.size, "Gradient ${wp.id} should have 16 color stops")
            assertEquals(16, wp.positions.size, "Gradient ${wp.id} should have 16 positions")
        }
    }

    @Test
    fun gradientPositionsMonotonicallyIncreasing() {
        ChatWallpaperPresets.gradientsList.forEach { wp ->
            for (i in 1 until wp.positions.size) {
                assertTrue(
                    wp.positions[i] >= wp.positions[i - 1],
                    "Gradient ${wp.id}: position[$i]=${wp.positions[i]} < position[${i - 1}]=${wp.positions[i - 1]}"
                )
            }
        }
    }

    @Test
    fun gradientPositionsStartAtZeroEndAtOne() {
        ChatWallpaperPresets.gradientsList.forEach { wp ->
            assertEquals(0f, wp.positions.first(), "Gradient ${wp.id} should start at 0")
            assertEquals(1f, wp.positions.last(), "Gradient ${wp.id} should end at 1")
        }
    }

    @Test
    fun findByIdReturnsCorrectPreset() {
        ChatWallpaperPresets.all.forEach { preset ->
            val found = ChatWallpaperPresets.findById(preset.id)
            assertNotNull(found)
            assertEquals(preset.id, found.id)
        }
    }

    @Test
    fun findByIdReturnsNullForUnknown() {
        assertNull(ChatWallpaperPresets.findById("nonexistent"))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew homebase-chat:jvmTest --tests "id.homebase.chat.chatappearance.model.ChatWallpaperSerializationTest" --tests "id.homebase.chat.chatappearance.model.ChatWallpaperPresetsTest" --rerun-tasks
```
Expected: FAIL

- [ ] **Step 4: Create ChatWallpaper.kt**

```kotlin
package id.homebase.chat.chatappearance.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ChatWallpaper {
    val id: String

    @Serializable
    @SerialName("none")
    data object None : ChatWallpaper {
        override val id: String = "none"
    }

    @Serializable
    @SerialName("solid_color")
    data class SolidColor(
        override val id: String,
        val colorArgb: Long,
    ) : ChatWallpaper

    @Serializable
    @SerialName("gradient_color")
    data class GradientColor(
        override val id: String,
        val colorsArgb: List<Long>,
        val positions: List<Float>,
        val angleDegrees: Float,
    ) : ChatWallpaper

    @Serializable
    @SerialName("photo")
    data class Photo(
        override val id: String,
        val payloadKey: String = "chat_wlpr",
    ) : ChatWallpaper
}
```

- [ ] **Step 5: Create ChatWallpaperPresets.kt**

Create with all 12 solid + 9 gradient (16-stop) presets. Solid colors from the spec:

```kotlin
package id.homebase.chat.chatappearance.model

object ChatWallpaperPresets {
    // 12 Solid wallpapers
    val blush = ChatWallpaper.SolidColor(id = "blush", colorArgb = 0xFFE26983)
    val copper = ChatWallpaper.SolidColor(id = "copper", colorArgb = 0xFFDF9171)
    val dust = ChatWallpaper.SolidColor(id = "dust", colorArgb = 0xFF9E9887)
    val celadon = ChatWallpaper.SolidColor(id = "celadon", colorArgb = 0xFF89AE8F)
    val rainforest = ChatWallpaper.SolidColor(id = "rainforest", colorArgb = 0xFF146148)
    val pacific = ChatWallpaper.SolidColor(id = "pacific", colorArgb = 0xFF32C7E2)
    val frost = ChatWallpaper.SolidColor(id = "frost", colorArgb = 0xFF7C99B6)
    val navy = ChatWallpaper.SolidColor(id = "navy", colorArgb = 0xFF403B91)
    val lilac = ChatWallpaper.SolidColor(id = "lilac", colorArgb = 0xFFC988E7)
    val pink = ChatWallpaper.SolidColor(id = "pink", colorArgb = 0xFFE297C3)
    val eggplant = ChatWallpaper.SolidColor(id = "eggplant", colorArgb = 0xFF624249)
    val silver = ChatWallpaper.SolidColor(id = "silver", colorArgb = 0xFFA2A2AA)

    // 9 Gradient wallpapers — 16-stop from Signal
    // Positions shared by most gradients
    private val standardPositions = listOf(
        0.0000f, 0.0807f, 0.1554f, 0.2250f, 0.2904f, 0.3526f, 0.4125f, 0.4710f,
        0.5290f, 0.5875f, 0.6474f, 0.7096f, 0.7750f, 0.8446f, 0.9193f, 1.0000f,
    )

    val sunset = ChatWallpaper.GradientColor(
        id = "sunset", angleDegrees = 168f,
        colorsArgb = listOf(
            0xFFF3DC47, 0xFFF3DA47, 0xFFF2D546, 0xFFF2CC46,
            0xFFF1C146, 0xFFEFB445, 0xFFEEA544, 0xFFEC9644,
            0xFFEB8743, 0xFFE97743, 0xFFE86942, 0xFFE65C41,
            0xFFE55041, 0xFFE54841, 0xFFE44240, 0xFFE44040,
        ),
        positions = standardPositions,
    )
    val noir = ChatWallpaper.GradientColor(
        id = "noir", angleDegrees = 180f,
        colorsArgb = listOf(
            0xFF16161D, 0xFF17171E, 0xFF1A1A22, 0xFF1F1F28,
            0xFF26262F, 0xFF2D2D38, 0xFF353542, 0xFF3E3E4C,
            0xFF474757, 0xFF4F4F61, 0xFF57576B, 0xFF5F5F74,
            0xFF65657C, 0xFF6A6A82, 0xFF6D6D85, 0xFF6E6E87,
        ),
        positions = standardPositions,
    )
    val heatmap = ChatWallpaper.GradientColor(
        id = "heatmap", angleDegrees = 192f,
        colorsArgb = listOf(
            0xFFF53844, 0xFFF33845, 0xFFEC3848, 0xFFE2384C,
            0xFFD63851, 0xFFC73857, 0xFFB6385E, 0xFFA43866,
            0xFF93376D, 0xFF813775, 0xFF70377C, 0xFF613782,
            0xFF553787, 0xFF4B378B, 0xFF44378E, 0xFF42378F,
        ),
        positions = listOf(
            0.0000f, 0.0075f, 0.0292f, 0.0637f, 0.1097f, 0.1659f, 0.2310f, 0.3037f,
            0.3827f, 0.4666f, 0.5541f, 0.6439f, 0.7347f, 0.8252f, 0.9141f, 1.0000f,
        ),
    )
    val aqua = ChatWallpaper.GradientColor(
        id = "aqua", angleDegrees = 180f,
        colorsArgb = listOf(
            0xFF0093E9, 0xFF0294E9, 0xFF0696E7, 0xFF0D99E5,
            0xFF169EE3, 0xFF21A3E0, 0xFF2DA8DD, 0xFF3AAEDA,
            0xFF46B5D6, 0xFF53BBD3, 0xFF5FC0D0, 0xFF6AC5CD,
            0xFF73CACB, 0xFF7ACDC9, 0xFF7ECFC7, 0xFF80D0C7,
        ),
        positions = standardPositions,
    )
    val iridescent = ChatWallpaper.GradientColor(
        id = "iridescent", angleDegrees = 192f,
        colorsArgb = listOf(
            0xFFF04CE6, 0xFFEE4BE6, 0xFFE54AE5, 0xFFD949E5,
            0xFFC946E4, 0xFFB644E3, 0xFFA141E3, 0xFF8B3FE2,
            0xFF743CE1, 0xFF5E39E0, 0xFF4936DF, 0xFF3634DE,
            0xFF2632DD, 0xFF1930DD, 0xFF112FDD, 0xFF0E2FDD,
        ),
        positions = standardPositions,
    )
    val monstera = ChatWallpaper.GradientColor(
        id = "monstera", angleDegrees = 180f,
        colorsArgb = listOf(
            0xFF65CDAC, 0xFF64CDAB, 0xFF60CBA8, 0xFF5BC8A3,
            0xFF55C49D, 0xFF4DC096, 0xFF45BB8F, 0xFF3CB687,
            0xFF33B17F, 0xFF2AAC76, 0xFF21A76F, 0xFF1AA268,
            0xFF139F62, 0xFF0E9C5E, 0xFF0B9A5B, 0xFF0A995A,
        ),
        positions = standardPositions,
    )
    val bliss = ChatWallpaper.GradientColor(
        id = "bliss", angleDegrees = 180f,
        colorsArgb = listOf(
            0xFFD8E1FA, 0xFFD8E0F9, 0xFFD8DEF7, 0xFFD8DBF3,
            0xFFD8D6EE, 0xFFD7D1E8, 0xFFD7CCE2, 0xFFD7C6DB,
            0xFFD7BFD4, 0xFFD7B9CD, 0xFFD6B4C7, 0xFFD6AFC1,
            0xFFD6AABC, 0xFFD6A7B8, 0xFFD6A5B6, 0xFFD6A4B5,
        ),
        positions = standardPositions,
    )
    val sky = ChatWallpaper.GradientColor(
        id = "sky", angleDegrees = 180f,
        colorsArgb = listOf(
            0xFFD8EBFD, 0xFFD7EAFD, 0xFFD5E9FD, 0xFFD2E7FD,
            0xFFCDE5FD, 0xFFC8E3FD, 0xFFC3E0FD, 0xFFBDDDFC,
            0xFFB7DAFC, 0xFFB2D7FC, 0xFFACD4FC, 0xFFA7D1FC,
            0xFFA3CFFB, 0xFFA0CDFB, 0xFF9ECCFB, 0xFF9DCCFB,
        ),
        positions = standardPositions,
    )
    val peach = ChatWallpaper.GradientColor(
        id = "peach", angleDegrees = 192f,
        colorsArgb = listOf(
            0xFFFFE5C2, 0xFFFFE4C1, 0xFFFFE2BF, 0xFFFFDFBD,
            0xFFFEDBB9, 0xFFFED6B5, 0xFFFED1B1, 0xFFFDCCAC,
            0xFFFDC6A8, 0xFFFDC0A3, 0xFFFCBB9F, 0xFFFCB69B,
            0xFFFCB297, 0xFFFCAF95, 0xFFFCAD93, 0xFFFCAC92,
        ),
        positions = standardPositions,
    )

    val solids: List<ChatWallpaper.SolidColor> = listOf(
        blush, copper, dust, celadon, rainforest, pacific,
        frost, navy, lilac, pink, eggplant, silver,
    )

    val gradientsList: List<ChatWallpaper.GradientColor> = listOf(
        sunset, noir, heatmap, aqua, iridescent, monstera, bliss, sky, peach,
    )

    val all: List<ChatWallpaper> = solids + gradientsList

    fun findById(id: String): ChatWallpaper? = all.firstOrNull { it.id == id }
}
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
./gradlew homebase-chat:jvmTest --tests "id.homebase.chat.chatappearance.model.ChatWallpaperSerializationTest" --tests "id.homebase.chat.chatappearance.model.ChatWallpaperPresetsTest" --rerun-tasks
```
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/ChatWallpaper.kt \
       homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/ChatWallpaperPresets.kt \
       homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/ChatWallpaperSerializationTest.kt \
       homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/ChatWallpaperPresetsTest.kt
git commit -m "feat(chat-appearance): add ChatWallpaper data model and 21 built-in presets with tests"
```

---

## Task 3: ChatColorsMapper + GroupNameColors + BubbleContentColor + Tests

**Files:**
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/ChatColorsMapper.kt`
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/GroupNameColors.kt`
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/BubbleContentColor.kt`
- Test: `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/ChatColorsMapperTest.kt`
- Test: `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/GroupNameColorsTest.kt`
- Test: `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/BubbleContentColorTest.kt`

- [ ] **Step 1: Write ChatColorsMapper tests**

```kotlin
package id.homebase.chat.chatappearance.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ChatColorsMapperTest {
    @Test
    fun blushMapsToCrimson() = assertEquals("crimson", ChatColorsMapper.resolve(ChatWallpaperPresets.blush).id)

    @Test
    fun copperMapsToVermilion() = assertEquals("vermilion", ChatColorsMapper.resolve(ChatWallpaperPresets.copper).id)

    @Test
    fun dustMapsToBuilap() = assertEquals("burlap", ChatColorsMapper.resolve(ChatWallpaperPresets.dust).id)

    @Test
    fun celadonMapsToForest() = assertEquals("forest", ChatColorsMapper.resolve(ChatWallpaperPresets.celadon).id)

    @Test
    fun rainforestMapsToWintergreen() = assertEquals("wintergreen", ChatColorsMapper.resolve(ChatWallpaperPresets.rainforest).id)

    @Test
    fun pacificMapsToTeal() = assertEquals("teal", ChatColorsMapper.resolve(ChatWallpaperPresets.pacific).id)

    @Test
    fun frostMapsToBlue() = assertEquals("blue", ChatColorsMapper.resolve(ChatWallpaperPresets.frost).id)

    @Test
    fun navyMapsToIndigo() = assertEquals("indigo", ChatColorsMapper.resolve(ChatWallpaperPresets.navy).id)

    @Test
    fun lilacMapsToViolet() = assertEquals("violet", ChatColorsMapper.resolve(ChatWallpaperPresets.lilac).id)

    @Test
    fun pinkMapsToPlum() = assertEquals("plum", ChatColorsMapper.resolve(ChatWallpaperPresets.pink).id)

    @Test
    fun eggplantMapsToTaupe() = assertEquals("taupe", ChatColorsMapper.resolve(ChatWallpaperPresets.eggplant).id)

    @Test
    fun silverMapsToSteel() = assertEquals("steel", ChatColorsMapper.resolve(ChatWallpaperPresets.silver).id)

    @Test
    fun sunsetMapsToEmber() = assertEquals("ember", ChatColorsMapper.resolve(ChatWallpaperPresets.sunset).id)

    @Test
    fun noirMapsToMidnight() = assertEquals("midnight", ChatColorsMapper.resolve(ChatWallpaperPresets.noir).id)

    @Test
    fun heatmapMapsToInfrared() = assertEquals("infrared", ChatColorsMapper.resolve(ChatWallpaperPresets.heatmap).id)

    @Test
    fun aquaMapsToLagoon() = assertEquals("lagoon", ChatColorsMapper.resolve(ChatWallpaperPresets.aqua).id)

    @Test
    fun iridescentMapsToFluorescent() = assertEquals("fluorescent", ChatColorsMapper.resolve(ChatWallpaperPresets.iridescent).id)

    @Test
    fun monsteraMapsToBasil() = assertEquals("basil", ChatColorsMapper.resolve(ChatWallpaperPresets.monstera).id)

    @Test
    fun blissMapsToSublime() = assertEquals("sublime", ChatColorsMapper.resolve(ChatWallpaperPresets.bliss).id)

    @Test
    fun skyMapsToSea() = assertEquals("sea", ChatColorsMapper.resolve(ChatWallpaperPresets.sky).id)

    @Test
    fun peachMapsToTangerine() = assertEquals("tangerine", ChatColorsMapper.resolve(ChatWallpaperPresets.peach).id)

    @Test
    fun noneMapsToUltramarine() = assertEquals("ultramarine", ChatColorsMapper.resolve(ChatWallpaper.None).id)

    @Test
    fun photoMapsToUltramarine() {
        val photo = ChatWallpaper.Photo(id = "custom", payloadKey = "chat_wlpr")
        assertEquals("ultramarine", ChatColorsMapper.resolve(photo).id)
    }

    @Test
    fun mappingIsDeterministic() {
        val first = ChatColorsMapper.resolve(ChatWallpaperPresets.blush)
        val second = ChatColorsMapper.resolve(ChatWallpaperPresets.blush)
        assertEquals(first, second)
    }

    @Test
    fun everyBuiltInWallpaperHasMapping() {
        ChatWallpaperPresets.all.forEach { wp ->
            val mapped = ChatColorsMapper.resolve(wp)
            assertNotNull(ChatColorPresets.findById(mapped.id), "Mapped color ${mapped.id} not found in presets")
        }
    }
}
```

- [ ] **Step 2: Write GroupNameColors tests**

```kotlin
package id.homebase.chat.chatappearance.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupNameColorsTest {
    @Test
    fun paletteHas36Entries() {
        assertEquals(36, GroupNameColors.palette.size)
    }

    @Test
    fun allEntriesHaveNonZeroLightAndDarkArgb() {
        GroupNameColors.palette.forEachIndexed { idx, entry ->
            assertTrue(entry.lightTheme != 0L, "Entry $idx lightTheme is zero")
            assertTrue(entry.darkTheme != 0L, "Entry $idx darkTheme is zero")
        }
    }

    @Test
    fun getColorReturnsDarkVariantWhenDark() {
        val odinId = "test.odin.id"
        val color = GroupNameColors.getColor(odinId, isDarkTheme = true)
        val idx = kotlin.math.abs(odinId.hashCode()) % 36
        assertEquals(GroupNameColors.palette[idx].darkTheme, color)
    }

    @Test
    fun getColorReturnsLightVariantWhenLight() {
        val odinId = "test.odin.id"
        val color = GroupNameColors.getColor(odinId, isDarkTheme = false)
        val idx = kotlin.math.abs(odinId.hashCode()) % 36
        assertEquals(GroupNameColors.palette[idx].lightTheme, color)
    }

    @Test
    fun sameOdinIdAlwaysReturnsSameColor() {
        val odinId = "deterministic.test"
        val first = GroupNameColors.getColor(odinId, isDarkTheme = false)
        val second = GroupNameColors.getColor(odinId, isDarkTheme = false)
        assertEquals(first, second)
    }

    @Test
    fun distributionIsReasonable() {
        val distinctColors = (1..100).map { i ->
            GroupNameColors.getColor("user$i.example.com", isDarkTheme = false)
        }.distinct()
        assertTrue(distinctColors.size >= 10, "Only ${distinctColors.size} distinct colors for 100 users")
    }
}
```

- [ ] **Step 3: Write BubbleContentColor tests**

```kotlin
package id.homebase.chat.chatappearance.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BubbleContentColorTest {
    @Test
    fun darkBubbleProducesWhiteContent() {
        assertEquals(0xFFFFFFFF, BubbleContentColor.forBubble(ChatColorPresets.crimson))
        assertEquals(0xFFFFFFFF, BubbleContentColor.forBubble(ChatColorPresets.forest))
        assertEquals(0xFFFFFFFF, BubbleContentColor.forBubble(ChatColorPresets.midnight))
    }

    @Test
    fun ultramarineGradientProducesWhiteContent() {
        assertEquals(0xFFFFFFFF, BubbleContentColor.forBubble(ChatColorPresets.ultramarine))
    }

    @Test
    fun allPresetsHaveAdequateContrast() {
        ChatColorPresets.all.forEach { color ->
            val contentColor = BubbleContentColor.forBubble(color)
            val bgLuminance = BubbleContentColor.relativeLuminance(
                BubbleContentColor.primaryArgb(color)
            )
            val fgLuminance = BubbleContentColor.relativeLuminance(contentColor)
            val lighter = maxOf(bgLuminance, fgLuminance)
            val darker = minOf(bgLuminance, fgLuminance)
            val ratio = (lighter + 0.05) / (darker + 0.05)
            assertTrue(
                ratio >= 4.5,
                "Color ${color.id}: contrast ratio $ratio < 4.5 WCAG AA"
            )
        }
    }

    @Test
    fun primaryArgbExtractsSolidColor() {
        assertEquals(0xFFCF163E, BubbleContentColor.primaryArgb(ChatColorPresets.crimson))
    }

    @Test
    fun primaryArgbExtractsFirstGradientColor() {
        assertEquals(0xFF0553F0, BubbleContentColor.primaryArgb(ChatColorPresets.ultramarine))
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

```bash
./gradlew homebase-chat:jvmTest --tests "id.homebase.chat.chatappearance.model.ChatColorsMapperTest" --tests "id.homebase.chat.chatappearance.model.GroupNameColorsTest" --tests "id.homebase.chat.chatappearance.model.BubbleContentColorTest" --rerun-tasks
```
Expected: FAIL

- [ ] **Step 5: Create ChatColorsMapper.kt**

```kotlin
package id.homebase.chat.chatappearance.model

object ChatColorsMapper {
    private val wallpaperToColor: Map<String, ChatColor> = mapOf(
        "blush" to ChatColorPresets.crimson,
        "copper" to ChatColorPresets.vermilion,
        "dust" to ChatColorPresets.burlap,
        "celadon" to ChatColorPresets.forest,
        "rainforest" to ChatColorPresets.wintergreen,
        "pacific" to ChatColorPresets.teal,
        "frost" to ChatColorPresets.blue,
        "navy" to ChatColorPresets.indigo,
        "lilac" to ChatColorPresets.violet,
        "pink" to ChatColorPresets.plum,
        "eggplant" to ChatColorPresets.taupe,
        "silver" to ChatColorPresets.steel,
        "sunset" to ChatColorPresets.ember,
        "noir" to ChatColorPresets.midnight,
        "heatmap" to ChatColorPresets.infrared,
        "aqua" to ChatColorPresets.lagoon,
        "iridescent" to ChatColorPresets.fluorescent,
        "monstera" to ChatColorPresets.basil,
        "bliss" to ChatColorPresets.sublime,
        "sky" to ChatColorPresets.sea,
        "peach" to ChatColorPresets.tangerine,
    )

    fun resolve(wallpaper: ChatWallpaper): ChatColor =
        wallpaperToColor[wallpaper.id] ?: ChatColorPresets.default
}
```

- [ ] **Step 6: Create GroupNameColors.kt**

```kotlin
package id.homebase.chat.chatappearance.model

import kotlin.math.abs

data class GroupNameColor(
    val lightTheme: Long,
    val darkTheme: Long,
)

object GroupNameColors {
    val palette: List<GroupNameColor> = listOf(
        GroupNameColor(0xFF006DA3, 0xFF00A7FA),
        GroupNameColor(0xFF007A3D, 0xFF00B85C),
        GroupNameColor(0xFFC13215, 0xFFFF6F52),
        GroupNameColor(0xFFB814B8, 0xFFF65AF6),
        GroupNameColor(0xFF5B6976, 0xFF8BA1B6),
        GroupNameColor(0xFF3D7406, 0xFF5EB309),
        GroupNameColor(0xFFCC0066, 0xFFF76EB2),
        GroupNameColor(0xFF2E51FF, 0xFF8599FF),
        GroupNameColor(0xFF9C5711, 0xFFD5920B),
        GroupNameColor(0xFF007575, 0xFF00B2B2),
        GroupNameColor(0xFFD00B4D, 0xFFFF6B9C),
        GroupNameColor(0xFF8F2AF4, 0xFFBF80FF),
        GroupNameColor(0xFFD00B0B, 0xFFFF7070),
        GroupNameColor(0xFF067906, 0xFF0AB80A),
        GroupNameColor(0xFF5151F6, 0xFF9494FF),
        GroupNameColor(0xFF866118, 0xFFD68F00),
        GroupNameColor(0xFF067953, 0xFF00B87A),
        GroupNameColor(0xFFA20CED, 0xFFCF7CF8),
        GroupNameColor(0xFF4B7000, 0xFF74AD00),
        GroupNameColor(0xFFC70A88, 0xFFF76EC9),
        GroupNameColor(0xFFB34209, 0xFFF57A3D),
        GroupNameColor(0xFF06792D, 0xFF0AB844),
        GroupNameColor(0xFF7A3DF5, 0xFFAF8AF9),
        GroupNameColor(0xFF6B6B24, 0xFFA4A437),
        GroupNameColor(0xFFD00B2C, 0xFFF77389),
        GroupNameColor(0xFF2D7906, 0xFF42B309),
        GroupNameColor(0xFFAF0BD0, 0xFFE06EF7),
        GroupNameColor(0xFF32763E, 0xFF4BAF5C),
        GroupNameColor(0xFF2662D9, 0xFF7DA1E8),
        GroupNameColor(0xFF76681E, 0xFFB89B0A),
        GroupNameColor(0xFF067462, 0xFF09B397),
        GroupNameColor(0xFF6447F5, 0xFFA18FF9),
        GroupNameColor(0xFF5E6E0C, 0xFF8FAA09),
        GroupNameColor(0xFF077288, 0xFF00AED1),
        GroupNameColor(0xFFC20AA3, 0xFFF75FDD),
        GroupNameColor(0xFF2D761E, 0xFF43B42D),
    )

    fun getColor(odinId: String, isDarkTheme: Boolean): Long {
        val index = abs(odinId.hashCode()) % palette.size
        return if (isDarkTheme) palette[index].darkTheme else palette[index].lightTheme
    }
}
```

- [ ] **Step 7: Create BubbleContentColor.kt**

```kotlin
package id.homebase.chat.chatappearance.model

import kotlin.math.pow

object BubbleContentColor {
    private const val WHITE: Long = 0xFFFFFFFF
    private const val DARK: Long = 0xFF1B1C1F

    fun forBubble(chatColor: ChatColor): Long {
        val bgArgb = primaryArgb(chatColor)
        val lum = relativeLuminance(bgArgb)
        return if (lum > 0.179) DARK else WHITE
    }

    fun primaryArgb(chatColor: ChatColor): Long = when (chatColor) {
        is ChatColor.Solid -> chatColor.colorArgb
        is ChatColor.Gradient -> chatColor.colorsArgb.first()
        is ChatColor.Auto -> primaryArgb(ChatColorPresets.default)
        is ChatColor.NotSet -> primaryArgb(ChatColorPresets.default)
    }

    fun relativeLuminance(argb: Long): Double {
        val r = linearize(((argb shr 16) and 0xFF).toInt())
        val g = linearize(((argb shr 8) and 0xFF).toInt())
        val b = linearize((argb and 0xFF).toInt())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun linearize(channel: Int): Double {
        val s = channel / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
}
```

- [ ] **Step 8: Run tests to verify they pass**

```bash
./gradlew homebase-chat:jvmTest --tests "id.homebase.chat.chatappearance.model.ChatColorsMapperTest" --tests "id.homebase.chat.chatappearance.model.GroupNameColorsTest" --tests "id.homebase.chat.chatappearance.model.BubbleContentColorTest" --rerun-tasks
```
Expected: ALL PASS

- [ ] **Step 9: Commit**

```bash
git add homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/ChatColorsMapper.kt \
       homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/GroupNameColors.kt \
       homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/BubbleContentColor.kt \
       homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/ChatColorsMapperTest.kt \
       homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/GroupNameColorsTest.kt \
       homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/BubbleContentColorTest.kt
git commit -m "feat(chat-appearance): add auto-color mapper, group name colors, and content color utility with tests"
```

---

## Task 4: Persistence Layer — ConversationLocalAppDataJson + UserPreferences + Tests

**Files:**
- Modify: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationLocalAppDataJson.kt`
- Modify: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/ChatProtocol.kt:89-95`
- Modify: `homebase-common/src/commonMain/kotlin/id/homebase/core/settings/UserPreferences.kt`
- Test: `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/ConversationLocalAppDataJsonTest.kt`

- [ ] **Step 1: Write ConversationLocalAppDataJson backward-compat tests**

```kotlin
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
        assertEquals(1715100000000, parsed.lastReadTime?.milliseconds)
        assertNull(parsed.chatColorId)
        assertNull(parsed.wallpaper)
        assertNull(parsed.wallpaperDimInDarkTheme)
    }

    @Test
    fun newFieldsDefaultToNullWhenAbsent() {
        val parsed = json.decodeFromString(
            ConversationLocalAppDataJson.serializer(), "{}"
        )
        assertNull(parsed.chatColorId)
        assertNull(parsed.wallpaper)
        assertNull(parsed.wallpaperDimInDarkTheme)
    }

    @Test
    fun existingFieldsSurviveWithNewFieldsPresent() {
        val fullJson = """{"lastReadTime":1715100000000,"lastExitedAt":1715200000000,"chatColorId":"crimson"}"""
        val parsed = json.decodeFromString(ConversationLocalAppDataJson.serializer(), fullJson)
        assertEquals(1715100000000, parsed.lastReadTime?.milliseconds)
        assertEquals(1715200000000, parsed.lastExitedAt?.milliseconds)
        assertEquals("crimson", parsed.chatColorId)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val futureJson = """{"lastReadTime":1715100000000,"futureField":"hello","chatColorId":"teal"}"""
        val parsed = json.decodeFromString(ConversationLocalAppDataJson.serializer(), futureJson)
        assertEquals("teal", parsed.chatColorId)
    }

    @Test
    fun copyPreservesAllFields() {
        val original = ConversationLocalAppDataJson(
            lastReadTime = id.homebase.api.common.time.UnixTimeUtc(1715100000000),
            lastExitedAt = id.homebase.api.common.time.UnixTimeUtc(1715200000000),
            chatColorId = "crimson",
        )
        val copied = original.copy(chatColorId = "teal")
        assertEquals("teal", copied.chatColorId)
        assertEquals(1715100000000, copied.lastReadTime?.milliseconds)
        assertEquals(1715200000000, copied.lastExitedAt?.milliseconds)
    }

    @Test
    fun fullRoundTripWithAllFields() {
        val original = ConversationLocalAppDataJson(
            lastReadTime = id.homebase.api.common.time.UnixTimeUtc(1715100000000),
            chatColorId = "crimson",
            wallpaper = ChatWallpaperData(type = "solid_color", id = "blush", colorArgb = 0xFFE26983),
            wallpaperDimInDarkTheme = false,
        )
        val encoded = json.encodeToString(ConversationLocalAppDataJson.serializer(), original)
        val decoded = json.decodeFromString(ConversationLocalAppDataJson.serializer(), encoded)
        assertEquals(original.chatColorId, decoded.chatColorId)
        assertEquals(original.wallpaper, decoded.wallpaper)
        assertEquals(original.wallpaperDimInDarkTheme, decoded.wallpaperDimInDarkTheme)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew homebase-chat:jvmTest --tests "id.homebase.chat.chatappearance.model.ConversationLocalAppDataJsonTest" --rerun-tasks
```
Expected: FAIL — `chatColorId`, `wallpaper`, `wallpaperDimInDarkTheme` fields don't exist yet.

- [ ] **Step 3: Create ChatWallpaperData.kt**

A flat serializable wrapper for embedding wallpaper info in `ConversationLocalAppDataJson`:

```kotlin
package id.homebase.chat.chatappearance.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatWallpaperData(
    val type: String,
    val id: String,
    val colorArgb: Long? = null,
    val colorsArgb: List<Long>? = null,
    val positions: List<Float>? = null,
    val angleDegrees: Float? = null,
    val payloadKey: String? = null,
) {
    companion object {
        fun from(wallpaper: ChatWallpaper): ChatWallpaperData? = when (wallpaper) {
            is ChatWallpaper.None -> null
            is ChatWallpaper.SolidColor -> ChatWallpaperData(
                type = "solid_color", id = wallpaper.id, colorArgb = wallpaper.colorArgb,
            )
            is ChatWallpaper.GradientColor -> ChatWallpaperData(
                type = "gradient_color", id = wallpaper.id,
                colorsArgb = wallpaper.colorsArgb, positions = wallpaper.positions,
                angleDegrees = wallpaper.angleDegrees,
            )
            is ChatWallpaper.Photo -> ChatWallpaperData(
                type = "photo", id = wallpaper.id, payloadKey = wallpaper.payloadKey,
            )
        }

        fun toWallpaper(data: ChatWallpaperData?): ChatWallpaper {
            if (data == null) return ChatWallpaper.None
            return when (data.type) {
                "solid_color" -> ChatWallpaperPresets.findById(data.id)
                    ?: ChatWallpaper.SolidColor(id = data.id, colorArgb = data.colorArgb ?: 0)
                "gradient_color" -> ChatWallpaperPresets.findById(data.id)
                    ?: ChatWallpaper.GradientColor(
                        id = data.id,
                        colorsArgb = data.colorsArgb ?: emptyList(),
                        positions = data.positions ?: emptyList(),
                        angleDegrees = data.angleDegrees ?: 180f,
                    )
                "photo" -> ChatWallpaper.Photo(
                    id = data.id, payloadKey = data.payloadKey ?: "chat_wlpr",
                )
                else -> ChatWallpaper.None
            }
        }
    }
}
```

- [ ] **Step 4: Modify ConversationLocalAppDataJson.kt**

Add three new nullable fields. Current file at `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationLocalAppDataJson.kt`:

```kotlin
package id.homebase.chat.services.convo

import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.chatappearance.model.ChatWallpaperData
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid

@Serializable
data class ConversationLocalAppDataJson(
    @Transient
    val conversationId: Uuid = Uuid.Companion.NIL,
    val lastReadTime: UnixTimeUtc? = null,
    val lastExitedAt: UnixTimeUtc? = null,
    val chatColorId: String? = null,
    val wallpaper: ChatWallpaperData? = null,
    val wallpaperDimInDarkTheme: Boolean? = null,
)
```

- [ ] **Step 5: Add PAYLOAD_KEY_WALLPAPER to ChatProtocol.kt**

In `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/ChatProtocol.kt`, add after the existing payload key constants (around line 93):

```kotlin
const val PAYLOAD_KEY_WALLPAPER = "chat_wlpr"
```

- [ ] **Step 6: Add global preferences to UserPreferences.kt**

In `homebase-common/src/commonMain/kotlin/id/homebase/core/settings/UserPreferences.kt`, add after existing preferences:

```kotlin
var globalChatColorId: String
    get() = settings.getString("global_chat_color_id", "auto")
    set(value) { settings.putString("global_chat_color_id", value) }

var globalWallpaperJson: String
    get() = settings.getString("global_wallpaper_json", "")
    set(value) { settings.putString("global_wallpaper_json", value) }

var globalWallpaperDimInDarkTheme: Boolean
    get() = settings.getBoolean("global_wallpaper_dim_dark", true)
    set(value) { settings.putBoolean("global_wallpaper_dim_dark", value) }
```

- [ ] **Step 7: Run tests to verify they pass**

```bash
./gradlew homebase-chat:jvmTest --tests "id.homebase.chat.chatappearance.model.ConversationLocalAppDataJsonTest" --rerun-tasks
```
Expected: ALL PASS

- [ ] **Step 8: Commit**

```bash
git add homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/model/ChatWallpaperData.kt \
       homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationLocalAppDataJson.kt \
       homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/ChatProtocol.kt \
       homebase-common/src/commonMain/kotlin/id/homebase/core/settings/UserPreferences.kt \
       homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/model/ConversationLocalAppDataJsonTest.kt
git commit -m "feat(chat-appearance): extend persistence layer with color/wallpaper fields"
```

---

## Task 5: ChatAppearanceRepository + Tests

**Files:**
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/data/ChatAppearanceRepository.kt`
- Test: `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/data/ChatAppearanceRepositoryTest.kt`

- [ ] **Step 1: Write repository tests**

```kotlin
package id.homebase.chat.chatappearance.data

import com.russhwolf.settings.MapSettings
import id.homebase.chat.chatappearance.model.*
import id.homebase.core.settings.UserPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatAppearanceRepositoryTest {
    private fun createRepo(): ChatAppearanceRepository {
        val settings = MapSettings()
        val userPrefs = UserPreferences(settings)
        return ChatAppearanceRepository(userPrefs)
    }

    @Test
    fun defaultGlobalChatColorIsAuto() {
        val repo = createRepo()
        assertEquals(ChatColor.Auto, repo.getGlobalChatColor())
    }

    @Test
    fun setAndGetGlobalChatColor() {
        val repo = createRepo()
        repo.setGlobalChatColor(ChatColorPresets.crimson)
        val result = repo.getGlobalChatColor()
        assertTrue(result is ChatColor.Solid)
        assertEquals("crimson", result.id)
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
        val repo = createRepo()
        assertEquals(ChatWallpaper.None, repo.getGlobalWallpaper())
    }

    @Test
    fun setAndGetGlobalWallpaper() {
        val repo = createRepo()
        repo.setGlobalWallpaper(ChatWallpaperPresets.blush)
        val result = repo.getGlobalWallpaper()
        assertTrue(result is ChatWallpaper.SolidColor)
        assertEquals("blush", result.id)
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
        val repo = createRepo()
        assertTrue(repo.getGlobalDimInDarkTheme())
    }

    @Test
    fun setDimInDarkTheme() {
        val repo = createRepo()
        repo.setGlobalDimInDarkTheme(false)
        assertEquals(false, repo.getGlobalDimInDarkTheme())
    }

    @Test
    fun resolveAutoColorWithWallpaper() {
        val repo = createRepo()
        val resolved = repo.resolveAutoColor(ChatWallpaperPresets.blush)
        assertEquals("crimson", resolved.id)
    }

    @Test
    fun resolveAutoColorWithNone() {
        val repo = createRepo()
        val resolved = repo.resolveAutoColor(ChatWallpaper.None)
        assertEquals("ultramarine", resolved.id)
    }

    @Test
    fun effectiveColorUsesGlobalWhenNoConversationOverride() {
        val repo = createRepo()
        repo.setGlobalChatColor(ChatColorPresets.teal)
        val effective = repo.getEffectiveColor(globalColorId = "teal", conversationColorId = null)
        assertEquals("teal", effective.id)
    }

    @Test
    fun effectiveColorUsesConversationOverrideWhenSet() {
        val repo = createRepo()
        repo.setGlobalChatColor(ChatColorPresets.teal)
        val effective = repo.getEffectiveColor(globalColorId = "teal", conversationColorId = "crimson")
        assertEquals("crimson", effective.id)
    }

    @Test
    fun effectiveWallpaperUsesGlobalWhenNoConversationOverride() {
        val repo = createRepo()
        repo.setGlobalWallpaper(ChatWallpaperPresets.blush)
        val effective = repo.getEffectiveWallpaper(globalWallpaperJson = repo.getGlobalWallpaperJson(), conversationWallpaper = null)
        assertEquals("blush", effective.id)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew homebase-chat:jvmTest --tests "id.homebase.chat.chatappearance.data.ChatAppearanceRepositoryTest" --rerun-tasks
```
Expected: FAIL

- [ ] **Step 3: Create ChatAppearanceRepository.kt**

```kotlin
package id.homebase.chat.chatappearance.data

import id.homebase.chat.chatappearance.model.*
import id.homebase.core.settings.UserPreferences
import kotlinx.serialization.json.Json

class ChatAppearanceRepository(
    private val userPreferences: UserPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getGlobalChatColor(): ChatColor {
        val id = userPreferences.globalChatColorId
        if (id == "auto") return ChatColor.Auto
        return ChatColorPresets.findById(id) ?: ChatColor.Auto
    }

    fun setGlobalChatColor(color: ChatColor) {
        userPreferences.globalChatColorId = color.id
    }

    fun resetGlobalChatColor() {
        userPreferences.globalChatColorId = "auto"
    }

    fun getGlobalWallpaper(): ChatWallpaper {
        val jsonStr = userPreferences.globalWallpaperJson
        if (jsonStr.isBlank()) return ChatWallpaper.None
        return try {
            val data = json.decodeFromString(ChatWallpaperData.serializer(), jsonStr)
            ChatWallpaperData.toWallpaper(data)
        } catch (_: Throwable) {
            ChatWallpaper.None
        }
    }

    fun getGlobalWallpaperJson(): String = userPreferences.globalWallpaperJson

    fun setGlobalWallpaper(wallpaper: ChatWallpaper) {
        val data = ChatWallpaperData.from(wallpaper)
        userPreferences.globalWallpaperJson = if (data != null) {
            json.encodeToString(ChatWallpaperData.serializer(), data)
        } else ""
    }

    fun resetGlobalWallpaper() {
        userPreferences.globalWallpaperJson = ""
    }

    fun getGlobalDimInDarkTheme(): Boolean = userPreferences.globalWallpaperDimInDarkTheme

    fun setGlobalDimInDarkTheme(enabled: Boolean) {
        userPreferences.globalWallpaperDimInDarkTheme = enabled
    }

    fun resolveAutoColor(wallpaper: ChatWallpaper): ChatColor = ChatColorsMapper.resolve(wallpaper)

    fun getEffectiveColor(globalColorId: String, conversationColorId: String?): ChatColor {
        val id = conversationColorId ?: globalColorId
        if (id == "auto") return ChatColor.Auto
        return ChatColorPresets.findById(id) ?: ChatColor.Auto
    }

    fun getEffectiveWallpaper(globalWallpaperJson: String, conversationWallpaper: ChatWallpaperData?): ChatWallpaper {
        if (conversationWallpaper != null) return ChatWallpaperData.toWallpaper(conversationWallpaper)
        if (globalWallpaperJson.isBlank()) return ChatWallpaper.None
        return try {
            ChatWallpaperData.toWallpaper(json.decodeFromString(ChatWallpaperData.serializer(), globalWallpaperJson))
        } catch (_: Throwable) {
            ChatWallpaper.None
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew homebase-chat:jvmTest --tests "id.homebase.chat.chatappearance.data.ChatAppearanceRepositoryTest" --rerun-tasks
```
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/data/ChatAppearanceRepository.kt \
       homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/data/ChatAppearanceRepositoryTest.kt
git commit -m "feat(chat-appearance): add ChatAppearanceRepository with global settings and auto-color resolution"
```

---

## Task 6: CompositionLocals + Bubble Color Integration

**Files:**
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/ui/LocalChatAppearance.kt`
- Modify: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageBubbleRaw.kt:214-221`
- Modify: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/PendingMessageBubble.kt:132,176`

- [ ] **Step 1: Create LocalChatAppearance.kt**

```kotlin
package id.homebase.chat.chatappearance.ui

import androidx.compose.runtime.staticCompositionLocalOf
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.model.ChatColorPresets
import id.homebase.chat.chatappearance.model.ChatWallpaper

val LocalActiveChatColor = staticCompositionLocalOf<ChatColor> { ChatColorPresets.default }
val LocalActiveWallpaper = staticCompositionLocalOf<ChatWallpaper> { ChatWallpaper.None }
val LocalWallpaperDimInDarkTheme = staticCompositionLocalOf { true }
```

- [ ] **Step 2: Modify MessageBubbleRaw.kt bubble color logic**

In `homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageBubbleRaw.kt`, replace lines 214-221:

**Before:**
```kotlin
val backgroundColor =
    if (emojiOnly) Color.Unspecified
    else if (sentByYou) HomebaseTheme.extendedColors.bubbleSentSurface
    else MaterialTheme.colorScheme.surfaceContainerHigh
val contentColor =
    if (emojiOnly) MaterialTheme.colorScheme.onSurface
    else if (sentByYou) HomebaseTheme.extendedColors.bubbleSentOnSurface
    else MaterialTheme.colorScheme.onSurface
```

**After:**
```kotlin
val activeChatColor = LocalActiveChatColor.current
val resolvedBubbleColor = remember(activeChatColor) {
    when (activeChatColor) {
        is ChatColor.Solid -> Color(activeChatColor.colorArgb)
        is ChatColor.Gradient -> Color(activeChatColor.colorsArgb.first())
        else -> null
    }
}
val resolvedContentColor = remember(activeChatColor) {
    Color(BubbleContentColor.forBubble(activeChatColor))
}
val backgroundColor =
    if (emojiOnly) Color.Unspecified
    else if (sentByYou) resolvedBubbleColor ?: HomebaseTheme.extendedColors.bubbleSentSurface
    else MaterialTheme.colorScheme.surfaceContainerHigh
val contentColor =
    if (emojiOnly) MaterialTheme.colorScheme.onSurface
    else if (sentByYou) resolvedContentColor
    else MaterialTheme.colorScheme.onSurface
```

Add imports at the top of the file:
```kotlin
import id.homebase.chat.chatappearance.model.BubbleContentColor
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.ui.LocalActiveChatColor
```

For gradient bubbles, also add a gradient brush modifier on the Surface. Find the `Surface(` call (around line 259) and add a gradient background when the chat color is a Gradient:

```kotlin
val bubbleGradientBrush = remember(activeChatColor) {
    if (activeChatColor is ChatColor.Gradient) {
        Brush.linearGradient(colors = activeChatColor.colorsArgb.map { Color(it) })
    } else null
}
```

Then on the `Surface` modifier, add `.then(if (sentByYou && bubbleGradientBrush != null) Modifier.background(bubbleGradientBrush, bubbleShape) else Modifier)` before the existing modifiers.

- [ ] **Step 3: Modify PendingMessageBubble.kt**

In `homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/PendingMessageBubble.kt`:

At line 132 (`MediaPlaceholderBubble`), replace:
```kotlin
color = HomebaseTheme.extendedColors.bubbleSentSurface,
```
with:
```kotlin
color = run {
    val chatColor = LocalActiveChatColor.current
    when (chatColor) {
        is ChatColor.Solid -> Color(chatColor.colorArgb)
        is ChatColor.Gradient -> Color(chatColor.colorsArgb.first())
        else -> HomebaseTheme.extendedColors.bubbleSentSurface
    }
},
```

At line 176 (`GenericPendingBubble`), replace:
```kotlin
val backgroundColor = HomebaseTheme.extendedColors.bubbleSentSurface
val contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface
```
with:
```kotlin
val chatColor = LocalActiveChatColor.current
val backgroundColor = when (chatColor) {
    is ChatColor.Solid -> Color(chatColor.colorArgb)
    is ChatColor.Gradient -> Color(chatColor.colorsArgb.first())
    else -> HomebaseTheme.extendedColors.bubbleSentSurface
}
val contentColor = Color(BubbleContentColor.forBubble(chatColor))
```

Add imports:
```kotlin
import id.homebase.chat.chatappearance.model.BubbleContentColor
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.ui.LocalActiveChatColor
```

- [ ] **Step 4: Verify compilation**

```bash
./gradlew homebase-chat:compileKotlinJvm
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/ui/LocalChatAppearance.kt \
       homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageBubbleRaw.kt \
       homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/PendingMessageBubble.kt
git commit -m "feat(chat-appearance): integrate dynamic bubble colors via CompositionLocal"
```

---

## Task 7: Wallpaper Background + ConversationContent Integration

**Files:**
- Modify: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationContent.kt:505-507`

- [ ] **Step 1: Modify ConversationContent.kt to provide CompositionLocals and render wallpaper**

In `homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationContent.kt`, at line 505 where the existing `CompositionLocalProvider` is:

**Before:**
```kotlin
CompositionLocalProvider(
    LocalCurrentOdinId provides (uiState.ownerSession?.odinId?.domainName ?: ""),
) {
```

**After:**
```kotlin
CompositionLocalProvider(
    LocalCurrentOdinId provides (uiState.ownerSession?.odinId?.domainName ?: ""),
    LocalActiveChatColor provides effectiveChatColor,
    LocalActiveWallpaper provides effectiveWallpaper,
    LocalWallpaperDimInDarkTheme provides dimInDarkTheme,
) {
```

The `effectiveChatColor`, `effectiveWallpaper`, and `dimInDarkTheme` values need to come from the ViewModel/UiState. For now, provide defaults that will be wired in when the ViewModel is updated:

Add at the start of the `ConversationContent` composable body (before CompositionLocalProvider):
```kotlin
val effectiveChatColor = ChatColorPresets.default as ChatColor
val effectiveWallpaper: ChatWallpaper = ChatWallpaper.None
val dimInDarkTheme = true
```

Add wallpaper rendering. Find the `Scaffold(` call inside the CompositionLocalProvider and wrap it in a `Box` with wallpaper:

```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    val wallpaper = LocalActiveWallpaper.current
    when (wallpaper) {
        is ChatWallpaper.SolidColor -> Box(Modifier.fillMaxSize().background(Color(wallpaper.colorArgb)))
        is ChatWallpaper.GradientColor -> Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(colors = wallpaper.colorsArgb.map { Color(it) })
            )
        )
        is ChatWallpaper.Photo -> { /* TODO: Task 14 — fetch and display payload image */ }
        is ChatWallpaper.None -> { }
    }
    if (isSystemInDarkTheme() && LocalWallpaperDimInDarkTheme.current && wallpaper !is ChatWallpaper.None) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
    }
    Scaffold(
        containerColor = if (wallpaper is ChatWallpaper.None) MaterialTheme.colorScheme.background else Color.Transparent,
        // ... rest of existing Scaffold
    )
}
```

Add imports:
```kotlin
import id.homebase.chat.chatappearance.model.*
import id.homebase.chat.chatappearance.ui.LocalActiveChatColor
import id.homebase.chat.chatappearance.ui.LocalActiveWallpaper
import id.homebase.chat.chatappearance.ui.LocalWallpaperDimInDarkTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.isSystemInDarkTheme
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew homebase-chat:compileKotlinJvm
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationContent.kt
git commit -m "feat(chat-appearance): add wallpaper background rendering and CompositionLocal wiring in ConversationContent"
```

---

## Task 8: ViewModel + Tests

**Files:**
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/ui/ChatColorWallpaperViewModel.kt`
- Test: `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/ui/ChatColorWallpaperViewModelTest.kt`

- [ ] **Step 1: Write ViewModel tests**

```kotlin
package id.homebase.chat.chatappearance.ui

import com.russhwolf.settings.MapSettings
import id.homebase.chat.chatappearance.data.ChatAppearanceRepository
import id.homebase.chat.chatappearance.model.*
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

@OptIn(ExperimentalCoroutinesApi::class)
class ChatColorWallpaperViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repo: ChatAppearanceRepository
    private lateinit var vm: ChatColorWallpaperViewModel

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repo = ChatAppearanceRepository(UserPreferences(MapSettings()))
    }

    @AfterTest
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createVm(conversationId: String? = null): ChatColorWallpaperViewModel {
        return ChatColorWallpaperViewModel(repo, conversationId)
    }

    @Test
    fun initialStateIsAutoAndNone() = runTest {
        vm = createVm()
        val state = vm.uiState.value
        assertEquals(ChatColor.Auto, state.activeChatColor)
        assertEquals(ChatWallpaper.None, state.activeWallpaper)
        assertTrue(state.dimInDarkTheme)
        assertFalse(state.isPerConversation)
    }

    @Test
    fun setChatColorUpdatesState() = runTest {
        vm = createVm()
        vm.setChatColor(ChatColorPresets.crimson)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("crimson", vm.uiState.value.activeChatColor.id)
    }

    @Test
    fun setWallpaperUpdatesState() = runTest {
        vm = createVm()
        vm.setWallpaper(ChatWallpaperPresets.blush)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("blush", vm.uiState.value.activeWallpaper.id)
    }

    @Test
    fun setDimInDarkThemeUpdatesState() = runTest {
        vm = createVm()
        vm.setDimInDarkTheme(false)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.dimInDarkTheme)
    }

    @Test
    fun resetChatColorsResetsToAuto() = runTest {
        vm = createVm()
        vm.setChatColor(ChatColorPresets.crimson)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.resetChatColors()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ChatColor.Auto, vm.uiState.value.activeChatColor)
    }

    @Test
    fun resetWallpapersResetsToNone() = runTest {
        vm = createVm()
        vm.setWallpaper(ChatWallpaperPresets.blush)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.resetWallpapers()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ChatWallpaper.None, vm.uiState.value.activeWallpaper)
    }

    @Test
    fun perConversationModeWhenConversationIdProvided() = runTest {
        vm = createVm(conversationId = "some-conversation-id")
        assertTrue(vm.uiState.value.isPerConversation)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew homebase-chat:jvmTest --tests "id.homebase.chat.chatappearance.ui.ChatColorWallpaperViewModelTest" --rerun-tasks
```
Expected: FAIL

- [ ] **Step 3: Create ChatColorWallpaperViewModel.kt**

```kotlin
package id.homebase.chat.chatappearance.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.chatappearance.data.ChatAppearanceRepository
import id.homebase.chat.chatappearance.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatColorWallpaperViewModel(
    private val repository: ChatAppearanceRepository,
    private val conversationId: String? = null,
) : ViewModel() {

    data class UiState(
        val activeChatColor: ChatColor = ChatColor.Auto,
        val activeWallpaper: ChatWallpaper = ChatWallpaper.None,
        val dimInDarkTheme: Boolean = true,
        val isPerConversation: Boolean = false,
        val allBubbleColors: List<ChatColor> = ChatColorPresets.all,
        val allWallpaperPresets: List<ChatWallpaper> = ChatWallpaperPresets.all,
    )

    private val _uiState = MutableStateFlow(
        UiState(
            activeChatColor = repository.getGlobalChatColor(),
            activeWallpaper = repository.getGlobalWallpaper(),
            dimInDarkTheme = repository.getGlobalDimInDarkTheme(),
            isPerConversation = conversationId != null,
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun setChatColor(color: ChatColor) {
        viewModelScope.launch {
            repository.setGlobalChatColor(color)
            _uiState.update { it.copy(activeChatColor = color) }
        }
    }

    fun setWallpaper(wallpaper: ChatWallpaper) {
        viewModelScope.launch {
            repository.setGlobalWallpaper(wallpaper)
            _uiState.update { it.copy(activeWallpaper = wallpaper) }
        }
    }

    fun setDimInDarkTheme(enabled: Boolean) {
        viewModelScope.launch {
            repository.setGlobalDimInDarkTheme(enabled)
            _uiState.update { it.copy(dimInDarkTheme = enabled) }
        }
    }

    fun resetChatColors() {
        viewModelScope.launch {
            repository.resetGlobalChatColor()
            _uiState.update { it.copy(activeChatColor = ChatColor.Auto) }
        }
    }

    fun resetWallpapers() {
        viewModelScope.launch {
            repository.resetGlobalWallpaper()
            _uiState.update { it.copy(activeWallpaper = ChatWallpaper.None) }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew homebase-chat:jvmTest --tests "id.homebase.chat.chatappearance.ui.ChatColorWallpaperViewModelTest" --rerun-tasks
```
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/ui/ChatColorWallpaperViewModel.kt \
       homebase-chat/src/jvmTest/kotlin/id/homebase/chat/chatappearance/ui/ChatColorWallpaperViewModelTest.kt
git commit -m "feat(chat-appearance): add ChatColorWallpaperViewModel with global get/set/reset and tests"
```

---

## Task 9: UI Components — ColorCircleItem, WallpaperTileItem, ChatPreviewMockup

**Files:**
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/ui/components/ColorCircleItem.kt`
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/ui/components/WallpaperTileItem.kt`
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/ui/components/ChatPreviewMockup.kt`

- [ ] **Step 1: Create ColorCircleItem.kt**

A circle that renders a solid color or gradient, with optional selection ring and "auto" text label. See spec section 7.2 for size (56.dp) and selection (3dp ring border).

```kotlin
package id.homebase.chat.chatappearance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.homebase.chat.chatappearance.model.ChatColor

@Composable
fun ColorCircleItem(
    chatColor: ChatColor,
    isSelected: Boolean,
    isAutoItem: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectionBorder = if (isSelected) {
        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
    } else Modifier

    Box(
        modifier = modifier
            .size(56.dp)
            .then(selectionBorder)
            .clip(CircleShape)
            .then(
                when (chatColor) {
                    is ChatColor.Solid -> Modifier.background(Color(chatColor.colorArgb), CircleShape)
                    is ChatColor.Gradient -> Modifier.background(
                        Brush.linearGradient(chatColor.colorsArgb.map { Color(it) }),
                        CircleShape,
                    )
                    else -> Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isAutoItem) {
            Text(
                text = "auto",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
```

- [ ] **Step 2: Create WallpaperTileItem.kt**

A rectangular tile (~2:3 aspect ratio) rendering a solid color, gradient, or "None" state.

```kotlin
package id.homebase.chat.chatappearance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.homebase.chat.chatappearance.model.ChatWallpaper

@Composable
fun WallpaperTileItem(
    wallpaper: ChatWallpaper,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val selectionBorder = if (isSelected) {
        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
    } else Modifier

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .then(selectionBorder)
            .clip(shape)
            .then(
                when (wallpaper) {
                    is ChatWallpaper.SolidColor -> Modifier.background(Color(wallpaper.colorArgb), shape)
                    is ChatWallpaper.GradientColor -> Modifier.background(
                        Brush.linearGradient(wallpaper.colorsArgb.map { Color(it) }),
                        shape,
                    )
                    else -> Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
            )
        }
        if (wallpaper is ChatWallpaper.None) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 3: Create ChatPreviewMockup.kt**

A miniature fake conversation showing sent/received bubbles with the current chat color on the current wallpaper. See spec section 7.1 for layout.

```kotlin
package id.homebase.chat.chatappearance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.homebase.chat.chatappearance.model.BubbleContentColor
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.model.ChatColorPresets
import id.homebase.chat.chatappearance.model.ChatWallpaper

@Composable
fun ChatPreviewMockup(
    chatColor: ChatColor,
    wallpaper: ChatWallpaper,
    modifier: Modifier = Modifier,
) {
    val resolvedColor = when (chatColor) {
        is ChatColor.Auto -> ChatColorPresets.default
        is ChatColor.NotSet -> ChatColorPresets.default
        else -> chatColor
    }
    val bubbleBg = when (resolvedColor) {
        is ChatColor.Solid -> Modifier.background(Color(resolvedColor.colorArgb), RoundedCornerShape(16.dp))
        is ChatColor.Gradient -> Modifier.background(
            Brush.linearGradient(resolvedColor.colorsArgb.map { Color(it) }),
            RoundedCornerShape(16.dp),
        )
        else -> Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
    }
    val bubbleTextColor = Color(BubbleContentColor.forBubble(resolvedColor))

    val wallpaperBg = when (wallpaper) {
        is ChatWallpaper.SolidColor -> Modifier.background(Color(wallpaper.colorArgb))
        is ChatWallpaper.GradientColor -> Modifier.background(
            Brush.linearGradient(wallpaper.colorsArgb.map { Color(it) })
        )
        else -> Modifier.background(MaterialTheme.colorScheme.background)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(wallpaperBg),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Start)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(
                    modifier = Modifier.width(120.dp).height(12.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .then(bubbleBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(
                    modifier = Modifier.width(140.dp).height(12.dp)
                        .background(bubbleTextColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}
```

- [ ] **Step 4: Verify compilation**

```bash
./gradlew homebase-chat:compileKotlinJvm
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/ui/components/
git commit -m "feat(chat-appearance): add ColorCircleItem, WallpaperTileItem, and ChatPreviewMockup components"
```

---

## Task 10: ChatColorWallpaperScreen (Main Settings)

**Files:**
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/ui/ChatColorWallpaperScreen.kt`

- [ ] **Step 1: Create ChatColorWallpaperScreen.kt**

Main settings screen with preview, chat color row, wallpaper row, dim toggle, reset buttons. See spec section 7.1.

```kotlin
package id.homebase.chat.chatappearance.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.model.ChatWallpaper
import id.homebase.chat.chatappearance.ui.components.ChatPreviewMockup
import id.homebase.chat.chatappearance.ui.components.ColorCircleItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatColorWallpaperScreen(
    uiState: ChatColorWallpaperViewModel.UiState,
    onNavigateBack: () -> Unit,
    onNavigateToChatColorPicker: () -> Unit,
    onNavigateToWallpaperPicker: () -> Unit,
    onDimInDarkThemeChanged: (Boolean) -> Unit,
    onResetChatColors: () -> Unit,
    onResetWallpapers: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat Color & Wallpaper") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                ChatPreviewMockup(
                    chatColor = uiState.activeChatColor,
                    wallpaper = uiState.activeWallpaper,
                    modifier = Modifier.padding(16.dp),
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToChatColorPicker)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Chat Color", style = MaterialTheme.typography.bodyLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ColorCircleItem(
                                chatColor = uiState.activeChatColor,
                                isSelected = false,
                                isAutoItem = uiState.activeChatColor is ChatColor.Auto,
                                onClick = onNavigateToChatColorPicker,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    TextButton(
                        onClick = onResetChatColors,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text("Reset Chat Colors", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToWallpaperPicker)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Set Wallpaper", style = MaterialTheme.typography.bodyLarge)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Dark Theme Dims Wallpaper", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = uiState.dimInDarkTheme,
                            onCheckedChange = onDimInDarkThemeChanged,
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    TextButton(
                        onClick = onResetWallpapers,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text("Reset Wallpapers", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew homebase-chat:compileKotlinJvm
```

- [ ] **Step 3: Commit**

```bash
git add homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/ui/ChatColorWallpaperScreen.kt
git commit -m "feat(chat-appearance): add ChatColorWallpaperScreen main settings UI"
```

---

## Task 11: ChatColorPickerScreen

**Files:**
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/ui/ChatColorPickerScreen.kt`

- [ ] **Step 1: Create ChatColorPickerScreen.kt**

Color grid with live preview, auto option, solid/gradient circles. See spec section 7.2.

```kotlin
package id.homebase.chat.chatappearance.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.model.ChatWallpaper
import id.homebase.chat.chatappearance.ui.components.ChatPreviewMockup
import id.homebase.chat.chatappearance.ui.components.ColorCircleItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatColorPickerScreen(
    activeChatColor: ChatColor,
    activeWallpaper: ChatWallpaper,
    allColors: List<ChatColor>,
    onColorSelected: (ChatColor) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat Color") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                ChatPreviewMockup(
                    chatColor = activeChatColor,
                    wallpaper = activeWallpaper,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(16.dp))

            if (activeChatColor is ChatColor.Auto) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Text(
                        text = "Auto matches the color to the wallpaper",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        ColorCircleItem(
                            chatColor = ChatColor.Auto,
                            isSelected = activeChatColor is ChatColor.Auto,
                            isAutoItem = true,
                            onClick = { onColorSelected(ChatColor.Auto) },
                        )
                    }
                    items(allColors) { color ->
                        ColorCircleItem(
                            chatColor = color,
                            isSelected = activeChatColor.id == color.id,
                            onClick = { onColorSelected(color) },
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew homebase-chat:compileKotlinJvm
```

- [ ] **Step 3: Commit**

```bash
git add homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/ui/ChatColorPickerScreen.kt
git commit -m "feat(chat-appearance): add ChatColorPickerScreen with live preview and color grid"
```

---

## Task 12: WallpaperPickerScreen

**Files:**
- Create: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/ui/WallpaperPickerScreen.kt`

- [ ] **Step 1: Create WallpaperPickerScreen.kt**

Wallpaper grid with "Choose from Photos" and presets. See spec section 7.3.

```kotlin
package id.homebase.chat.chatappearance.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.chatappearance.model.ChatWallpaper
import id.homebase.chat.chatappearance.ui.components.WallpaperTileItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperPickerScreen(
    activeWallpaper: ChatWallpaper,
    allPresets: List<ChatWallpaper>,
    onWallpaperSelected: (ChatWallpaper) -> Unit,
    onChooseFromPhotos: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set Wallpaper") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onChooseFromPhotos)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = null)
                        Text("Choose from Photos", style = MaterialTheme.typography.bodyLarge)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Presets", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        WallpaperTileItem(
                            wallpaper = ChatWallpaper.None,
                            isSelected = activeWallpaper is ChatWallpaper.None,
                            onClick = { onWallpaperSelected(ChatWallpaper.None) },
                        )
                    }
                    items(allPresets) { wp ->
                        WallpaperTileItem(
                            wallpaper = wp,
                            isSelected = activeWallpaper.id == wp.id,
                            onClick = { onWallpaperSelected(wp) },
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew homebase-chat:compileKotlinJvm
```

- [ ] **Step 3: Commit**

```bash
git add homebase-chat/src/commonMain/kotlin/id/homebase/chat/chatappearance/ui/WallpaperPickerScreen.kt
git commit -m "feat(chat-appearance): add WallpaperPickerScreen with photo picker and preset grid"
```

---

## Task 13: Navigation, Entry Points, and DI

**Files:**
- Modify: `homebase-common/src/commonMain/kotlin/id/homebase/core/ui/navigation/Routes.kt`
- Modify: `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/navigation/AppNavHost.kt`
- Modify: `homebase-core/src/commonMain/kotlin/id/homebase/core/di/AppModule.kt`
- Modify: `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/appearance/AppearanceSettingsScreen.kt`
- Modify: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationsettings/ConversationSettingsScreen.kt`

- [ ] **Step 1: Add routes to Routes.kt**

In `homebase-common/src/commonMain/kotlin/id/homebase/core/ui/navigation/Routes.kt`, add inside the `Route` sealed class:

```kotlin
@Serializable
@SerialName("chat-color-wallpaper")
data class ChatColorWallpaper(val conversationId: String? = null) : Route()

@Serializable
@SerialName("chat-color-picker")
data class ChatColorPicker(val conversationId: String? = null) : Route()

@Serializable
@SerialName("wallpaper-picker")
data class WallpaperPicker(val conversationId: String? = null) : Route()
```

- [ ] **Step 2: Register DI in AppModule.kt**

In `homebase-core/src/commonMain/kotlin/id/homebase/core/di/AppModule.kt`:

Add import:
```kotlin
import id.homebase.chat.chatappearance.data.ChatAppearanceRepository
import id.homebase.chat.chatappearance.ui.ChatColorWallpaperViewModel
```

Add in the `appModule` block near other singletons:
```kotlin
single { ChatAppearanceRepository(get()) }
```

Add in the ViewModels section:
```kotlin
viewModel { params ->
    ChatColorWallpaperViewModel(
        repository = get(),
        conversationId = params.getOrNull(),
    )
}
```

- [ ] **Step 3: Wire navigation in AppNavHost.kt**

In `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/navigation/AppNavHost.kt`, add three composable destinations:

```kotlin
composable<Route.ChatColorWallpaper> { backStackEntry ->
    if (isAuthenticated) {
        val route = backStackEntry.toRoute<Route.ChatColorWallpaper>()
        val viewModel: ChatColorWallpaperViewModel = koinViewModel { parametersOf(route.conversationId) }
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        ChatColorWallpaperScreen(
            uiState = uiState,
            onNavigateBack = { navController.popBackStack() },
            onNavigateToChatColorPicker = {
                navController.navigate(Route.ChatColorPicker(route.conversationId))
            },
            onNavigateToWallpaperPicker = {
                navController.navigate(Route.WallpaperPicker(route.conversationId))
            },
            onDimInDarkThemeChanged = viewModel::setDimInDarkTheme,
            onResetChatColors = viewModel::resetChatColors,
            onResetWallpapers = viewModel::resetWallpapers,
        )
    }
}

composable<Route.ChatColorPicker> { backStackEntry ->
    if (isAuthenticated) {
        val route = backStackEntry.toRoute<Route.ChatColorPicker>()
        val viewModel: ChatColorWallpaperViewModel = koinViewModel { parametersOf(route.conversationId) }
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        ChatColorPickerScreen(
            activeChatColor = uiState.activeChatColor,
            activeWallpaper = uiState.activeWallpaper,
            allColors = uiState.allBubbleColors,
            onColorSelected = viewModel::setChatColor,
            onNavigateBack = { navController.popBackStack() },
        )
    }
}

composable<Route.WallpaperPicker> { backStackEntry ->
    if (isAuthenticated) {
        val route = backStackEntry.toRoute<Route.WallpaperPicker>()
        val viewModel: ChatColorWallpaperViewModel = koinViewModel { parametersOf(route.conversationId) }
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        WallpaperPickerScreen(
            activeWallpaper = uiState.activeWallpaper,
            allPresets = uiState.allWallpaperPresets,
            onWallpaperSelected = viewModel::setWallpaper,
            onChooseFromPhotos = { /* TODO: launch gallery picker */ },
            onNavigateBack = { navController.popBackStack() },
        )
    }
}
```

Add imports:
```kotlin
import id.homebase.chat.chatappearance.ui.*
```

- [ ] **Step 4: Add entry point in AppearanceSettingsScreen.kt**

In `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/appearance/AppearanceSettingsScreen.kt`, after the Theme row (around line 139), add a clickable row that navigates to `Route.ChatColorWallpaper()`:

```kotlin
Spacer(modifier = Modifier.height(16.dp))
Card(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToChatColorWallpaper() }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Chat Color & Wallpaper", style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
```

Add `onNavigateToChatColorWallpaper: () -> Unit` parameter to the composable function signature and wire it in AppNavHost's AppearanceSettings composable with `navController.navigate(Route.ChatColorWallpaper())`.

- [ ] **Step 5: Add entry point in ConversationSettingsScreen.kt**

In `homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationsettings/ConversationSettingsScreen.kt`, after the avatar/name display, add a similar row that navigates to `Route.ChatColorWallpaper(conversationId = conversationId)`.

- [ ] **Step 6: Verify full project compilation**

```bash
./gradlew homebase-chat:compileKotlinJvm homebase-core:compileKotlinJvm homebase-common:compileKotlinJvm
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Run all chat module tests**

```bash
./gradlew homebase-chat:jvmTest --rerun-tasks
```
Expected: ALL PASS

- [ ] **Step 8: Commit**

```bash
git add homebase-common/src/commonMain/kotlin/id/homebase/core/ui/navigation/Routes.kt \
       homebase-core/src/commonMain/kotlin/id/homebase/core/ui/navigation/AppNavHost.kt \
       homebase-core/src/commonMain/kotlin/id/homebase/core/di/AppModule.kt \
       homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/appearance/AppearanceSettingsScreen.kt \
       homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationsettings/ConversationSettingsScreen.kt
git commit -m "feat(chat-appearance): wire navigation, DI, and entry points for chat color & wallpaper settings"
```

---

## Task 14: Group Name Colors Integration

**Files:**
- Modify: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageBubble.kt:438-453`

- [ ] **Step 1: Replace sender name color logic in ReceivedMessageBubble**

In `homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageBubble.kt`, at lines 438-443 where sender name color is computed:

**Before:**
```kotlin
val authorOdinColor = getOdinIdColor(message.originalAuthor?.domainName ?: "")
val isDark = isSystemInDarkTheme()
val finalAuthorColor = if (isDark) authorOdinColor.darkTheme else authorOdinColor.lightTheme
```

**After:**
```kotlin
val isDark = isSystemInDarkTheme()
val finalAuthorColor = Color(
    GroupNameColors.getColor(
        odinId = message.originalAuthor?.domainName ?: "",
        isDarkTheme = isDark,
    )
)
```

Add import:
```kotlin
import id.homebase.chat.chatappearance.model.GroupNameColors
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew homebase-chat:compileKotlinJvm
```

- [ ] **Step 3: Commit**

```bash
git add homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageBubble.kt
git commit -m "feat(chat-appearance): use 36-color GroupNameColors palette for group member names"
```

---

## Task 15: Visual Testing + Final Verification

- [ ] **Step 1: Run all tests across all modules**

```bash
./gradlew homebase-chat:jvmTest homebase-auth:jvmTest homebase-common:jvmTest homebase-api:jvmTest --rerun-tasks
```
Expected: ALL PASS

- [ ] **Step 2: Launch desktop app for visual testing**

```bash
./gradlew desktopApp:run
```

Verify:
1. Navigate to Settings -> Appearance -> "Chat Color & Wallpaper"
2. Verify the preview mockup renders with current color
3. Navigate to Chat Color picker — verify grid shows all 22 colors + auto
4. Tap a color — verify preview updates in real-time
5. Navigate to Set Wallpaper — verify 21 presets + "None" tile
6. Select a wallpaper — verify it appears in conversation background
7. Toggle Dark Theme Dims Wallpaper — verify 20% black overlay in dark mode
8. Reset Chat Colors — verify returns to Auto
9. Reset Wallpapers — verify returns to plain background
10. Open a conversation — verify bubble color matches selected color

- [ ] **Step 3: Final commit if any visual fixes needed**

```bash
git add -A
git commit -m "fix(chat-appearance): visual polish from desktop testing"
```
