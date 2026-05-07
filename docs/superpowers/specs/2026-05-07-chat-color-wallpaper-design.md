# Chat Bubble Colors & Wallpapers — Design Spec

**Date:** 2026-05-07
**Branch:** (to be created from main)
**Reference:** Signal-Android implementation (`Signal-Android/app/src/main/java/org/thoughtcrime/securesms/conversation/colors/` and `.../wallpaper/`)

---

## 1. Overview

Add Signal-style chat bubble color customization and conversation wallpapers to Homebase Chat. Users can set a global default and override per-conversation. Both features are local-only (visible only to the user).

### Scope

- 22 built-in bubble colors (13 solid + 9 gradient)
- 21 built-in wallpaper presets (12 solid + 9 gradient)
- Custom photo wallpaper from gallery
- "Auto" mode: deterministic wallpaper-to-color mapping
- Per-conversation overrides stored in `localAppData.content`
- Global defaults stored in `UserPreferences`
- 36-color group member name palette (light/dark variants, hash-based assignment)
- Dark-theme wallpaper dimming toggle

### Out of Scope

- Custom user-created colors (Signal's HSV slider editor)
- Syncing color/wallpaper preferences across devices
- Wallpaper image patterns/textures shipped as bundled assets

---

## 2. Data Model

### 2.1 ChatColor

```kotlin
@Serializable
sealed interface ChatColor {
    val id: String

    @Serializable
    data object Auto : ChatColor {
        override val id: String = "auto"
    }

    @Serializable
    data object NotSet : ChatColor {
        override val id: String = "not_set"
    }

    @Serializable
    data class Solid(
        override val id: String,
        val colorArgb: Long,
    ) : ChatColor

    @Serializable
    data class Gradient(
        override val id: String,
        val colorsArgb: List<Long>,
        val angleDegrees: Float,
    ) : ChatColor
}
```

Colors stored as `Long` ARGB values (not `androidx.compose.ui.graphics.Color`) so the model stays in commonMain without Compose dependency.

### 2.2 ChatWallpaper

```kotlin
@Serializable
sealed interface ChatWallpaper {
    val id: String

    @Serializable
    data object None : ChatWallpaper {
        override val id: String = "none"
    }

    @Serializable
    data class SolidColor(
        override val id: String,
        val colorArgb: Long,
    ) : ChatWallpaper

    @Serializable
    data class GradientColor(
        override val id: String,
        val colorsArgb: List<Long>,
        val positions: List<Float>,
        val angleDegrees: Float,
    ) : ChatWallpaper

    @Serializable
    data class Photo(
        override val id: String,
        val payloadKey: String = ChatProtocol.PAYLOAD_KEY_WALLPAPER,
    ) : ChatWallpaper
}
```

### 2.3 GroupNameColor

```kotlin
data class GroupNameColor(
    val lightTheme: Long,  // ARGB
    val darkTheme: Long,   // ARGB
)
```

36-entry palette. Assignment: `palette[abs(odinId.hashCode()) % 36]`, picking light/dark variant based on current theme.

---

## 3. Built-in Presets

### 3.1 Bubble Color Presets (from Signal/Dart reference)

**Default gradient:**
- Ultramarine: `#0553F0` -> `#2C6CED` at 0deg

**13 Solid colors:**
| Name | Hex |
|------|-----|
| Crimson | #CF163E |
| Vermilion | #C73F0A |
| Burlap | #6F6A58 |
| Forest | #3B7845 |
| Wintergreen | #1D8663 |
| Teal | #077D92 |
| Blue | #336BA3 |
| Indigo | #6058CA |
| Violet | #9932C8 |
| Plum | #AA377A |
| Taupe | #8F616A |
| Steel | #71717F |

**9 Gradient colors:**
| Name | Colors | Angle |
|------|--------|-------|
| Ember | #E57C00 -> #5E0000 | 162deg |
| Midnight | #2C2C3A -> #787891 | 180deg |
| Infrared | #F65560 -> #442CED | 192deg |
| Lagoon | #004066 -> #32867D | 180deg |
| Fluorescent | #EC13DD -> #1B36C6 | 192deg |
| Basil | #2F9373 -> #077343 | 180deg |
| Sublime | #6281D5 -> #974460 | 180deg |
| Sea | #498FD4 -> #2C66A0 | 180deg |
| Tangerine | #DB7133 -> #911231 | 192deg |

### 3.2 Wallpaper Presets (from Signal reference)

**12 Solid wallpapers (from Signal's `SingleColorChatWallpaper`):**
| Name | ARGB |
|------|------|
| Blush | 0xFFE26983 |
| Copper | 0xFFDF9171 |
| Dust | 0xFF9E9887 |
| Celadon | 0xFF89AE8F |
| Rainforest | 0xFF146148 |
| Pacific | 0xFF32C7E2 |
| Frost | 0xFF7C99B6 |
| Navy | 0xFF403B91 |
| Lilac | 0xFFC988E7 |
| Pink | 0xFFE297C3 |
| Eggplant | 0xFF624249 |
| Silver | 0xFFA2A2AA |

**9 Gradient wallpapers (16-stop multi-color, from Signal's `GradientChatWallpaper`):**

Each gradient has 16 color stops with matching position arrays. Key values:

| Name | Angle | Start Color | End Color |
|------|-------|-------------|-----------|
| Sunset | 168deg | 0xFFF3DC47 | 0xFFE44040 |
| Noir | 180deg | 0xFF16161D | 0xFF6E6E87 |
| Heatmap | 192deg | 0xFFF53844 | 0xFF42378F |
| Aqua | 180deg | 0xFF0093E9 | 0xFF80D0C7 |
| Iridescent | 192deg | 0xFFF04CE6 | 0xFF0E2FDD |
| Monstera | 180deg | 0xFF65CDAC | 0xFF0A995A |
| Bliss | 180deg | 0xFFD8E1FA | 0xFFD6A4B5 |
| Sky | 180deg | 0xFFD8EBFD | 0xFF9DCCFB |
| Peach | 192deg | 0xFFFFE5C2 | 0xFFFCAC92 |

Full 16-stop color and position arrays are in `ChatWallpaperPresets.kt` (copied verbatim from Signal's `GradientChatWallpaper.java`).

---

## 4. Auto Color Mapping

Deterministic lookup table (Signal's pattern). Each wallpaper preset maps to exactly one bubble color:

| Wallpaper | Auto Bubble Color |
|-----------|-------------------|
| Blush | Crimson |
| Copper | Vermilion |
| Dust | Burlap |
| Celadon | Forest |
| Rainforest | Wintergreen |
| Pacific | Teal |
| Frost | Blue |
| Navy | Indigo |
| Lilac | Violet |
| Pink | Plum |
| Eggplant | Taupe |
| Silver | Steel |
| Sunset | Ember |
| Noir | Midnight |
| Heatmap | Infrared |
| Aqua | Lagoon |
| Iridescent | Fluorescent |
| Monstera | Basil |
| Bliss | Sublime |
| Sky | Sea |
| Peach | Tangerine |
| None / Photo | Ultramarine (default) |

---

## 5. Persistence

### 5.1 Global Settings — UserPreferences

Stored in `UserPreferences` (multiplatform-settings), following existing pattern:

```kotlin
// In UserPreferences.kt
var globalChatColorId: String
    get() = settings.getString("global_chat_color_id", "auto")
    set(value) = settings.putString("global_chat_color_id", value)

var globalWallpaperJson: String
    get() = settings.getString("global_wallpaper_json", "")
    set(value) = settings.putString("global_wallpaper_json", value)

var globalWallpaperDimInDarkTheme: Boolean
    get() = settings.getBoolean("global_wallpaper_dim_dark", true)
    set(value) = settings.putBoolean("global_wallpaper_dim_dark", value)
```

### 5.2 Per-Conversation Settings — localAppData.content

Extend `ConversationLocalAppDataJson` with new optional fields:

```kotlin
@Serializable
data class ConversationLocalAppDataJson(
    val conversationId: Uuid = Uuid.NIL,       // DEPRECATED
    val lastReadTime: UnixTimeUtc? = null,
    val lastExitedAt: UnixTimeUtc? = null,
    // NEW fields:
    val chatColorId: String? = null,            // null = use global; stores ChatColor.id
    val wallpaper: ChatWallpaperData? = null,   // null = use global; nested serializable object
    val wallpaperDimInDarkTheme: Boolean? = null, // null = use global
)
```

`ChatWallpaperData` is a `@Serializable` data class holding `type` (solid/gradient/photo), `id`, and the relevant color fields. For photo wallpapers, it stores `type = "photo"` — the actual image bytes live in a payload on the conversation file (see 5.3).

For `chatColorId`, we store just the string ID (e.g., "crimson", "auto", "ultramarine"). Built-in colors are resolved by ID lookup in `ChatColorPresets`. This keeps the JSON small.

Write flow (same as existing `lastReadTime` updates):
1. Read existing `ConversationLocalAppDataJson` from `localAppData.content`
2. Deserialize -> `.copy(chatColorId = newColorId)` -> serialize back
3. Write via `OptimisticWriter.stampConversationLocalAppData()`
4. Sync to server via outbox (UpdateLocalAppdataContentOutboxRequest)

### 5.3 Photo Wallpaper Storage — Payload System

Custom photo wallpapers are stored as an encrypted payload on the conversation's `HomebaseFile`, following the same pattern as `chat_links`, `chat_loc`, etc.

**New payload key constant** in `ChatProtocol.kt`:
```kotlin
const val PAYLOAD_KEY_WALLPAPER = "chat_wlpr"   // 9 chars, matches ^[a-z0-9_]{8,10}$
```

**Upload flow** (when user picks a photo from gallery):
1. User selects image via gallery picker
2. Image is resized/compressed (target ~1080px wide, JPEG quality 80)
3. Create `PayloadFile(key = PAYLOAD_KEY_WALLPAPER, filePath = tempImagePath, contentType = "image/jpeg")`
4. Upload payload to the conversation file via existing `DriveUploadProvider` payload upload API
5. A `PayloadDescriptor` with key `"chat_wlpr"` appears in the conversation file's `fileMetadata.payloads` list
6. Set `wallpaper = ChatWallpaperData(type = "photo")` in `ConversationLocalAppDataJson`

**Read flow** (when conversation opens):
1. Check `localAppData.content` — if `wallpaper.type == "photo"`, need to fetch payload
2. Look up `PayloadDescriptor` with key `"chat_wlpr"` in `fileMetadata.payloads`
3. Fetch decrypted bytes via `DriveFileProviderCached.getPayloadBytesDecrypted(driveId, fileId, "chat_wlpr", keyHeader)`
4. Existing 3-tier cache (memory 404 cache -> disk cache -> network) handles caching automatically
5. Display via Coil's `AsyncImage` from the cached bytes

**Removal flow** (when user clears photo wallpaper):
1. Remove `wallpaper` field from `ConversationLocalAppDataJson`
2. Delete the `"chat_wlpr"` payload from the conversation file (via payload delete API)

**Global photo wallpaper**: For the global setting, the photo is stored as a payload on a dedicated system file (or the user's profile/settings drive file). The `globalWallpaperJson` in `UserPreferences` stores `type = "photo"` and a reference to the file+payload location.

---

## 6. Architecture

### 6.1 Repository

```kotlin
class ChatAppearanceRepository(
    private val userPreferences: UserPreferences,
    private val optimisticWriter: OptimisticWriter,
    private val dbm: DatabaseManager,
) {
    // Global
    fun getGlobalChatColor(): ChatColor
    fun setGlobalChatColor(color: ChatColor)
    fun getGlobalWallpaper(): ChatWallpaper
    fun setGlobalWallpaper(wallpaper: ChatWallpaper)
    fun getGlobalDimInDarkTheme(): Boolean
    fun setGlobalDimInDarkTheme(enabled: Boolean)

    // Per-conversation
    suspend fun getConversationChatColor(conversationId: Uuid): ChatColor?
    suspend fun setConversationChatColor(conversationId: Uuid, color: ChatColor?)
    suspend fun getConversationWallpaper(conversationId: Uuid): ChatWallpaper?
    suspend fun setConversationWallpaper(conversationId: Uuid, wallpaper: ChatWallpaper?)

    // Effective (per-conversation with global fallback)
    suspend fun getEffectiveChatColor(conversationId: Uuid): ChatColor
    suspend fun getEffectiveWallpaper(conversationId: Uuid): ChatWallpaper

    // Reset
    fun resetGlobalChatColor()
    fun resetGlobalWallpaper()
    suspend fun resetConversationAppearance(conversationId: Uuid)

    // Auto resolution
    fun resolveAutoColor(wallpaper: ChatWallpaper): ChatColor
}
```

### 6.2 CompositionLocals

```kotlin
// LocalChatAppearance.kt
val LocalActiveChatColor = staticCompositionLocalOf<ChatColor> { ChatColorPresets.ultramarine }
val LocalActiveWallpaper = staticCompositionLocalOf<ChatWallpaper> { ChatWallpaper.None }
val LocalWallpaperDimInDarkTheme = staticCompositionLocalOf { true }
```

Provided at `ConversationContent` level, wrapping the Scaffold:

```kotlin
CompositionLocalProvider(
    LocalActiveChatColor provides effectiveChatColor,
    LocalActiveWallpaper provides effectiveWallpaper,
    LocalWallpaperDimInDarkTheme provides dimEnabled,
) {
    Scaffold(...) { ... }
}
```

### 6.3 ViewModel

```kotlin
class ChatColorWallpaperViewModel(
    private val repository: ChatAppearanceRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // From navigation args
    private val conversationId: Uuid? = savedStateHandle["conversationId"]

    data class UiState(
        val activeChatColor: ChatColor = ChatColor.Auto,
        val activeWallpaper: ChatWallpaper = ChatWallpaper.None,
        val dimInDarkTheme: Boolean = true,
        val isPerConversation: Boolean = false,
    )

    val uiState: StateFlow<UiState>

    fun setChatColor(color: ChatColor)
    fun setWallpaper(wallpaper: ChatWallpaper)
    fun setDimInDarkTheme(enabled: Boolean)
    fun resetChatColors()
    fun resetWallpapers()
}
```

---

## 7. UI Screens

### 7.1 ChatColorWallpaperScreen (main settings)

**Route:** `Route.ChatColorWallpaper(conversationId: String? = null)`

**Layout:**
- `TopAppBar`: title "Chat Color & Wallpaper", back arrow
- **Chat preview mockup** (Card): Miniature conversation showing:
  - Fake top bar with "Contact Name"
  - Received message bubble (gray, left-aligned)
  - Sent message bubble (active color, right-aligned)
  - Fake input bar at bottom
  - Current wallpaper as background
- **Chat Color section** (Card):
  - "Chat Color" row with current color circle indicator + chevron -> navigates to picker
  - "Reset Chat Colors" text button (`MaterialTheme.colorScheme.error`)
- **Wallpaper section** (Card):
  - "Set Wallpaper" row with chevron -> navigates to picker
  - "Dark Theme Dims Wallpaper" row with `Switch`
  - "Reset Wallpapers" text button (`MaterialTheme.colorScheme.error`)

### 7.2 ChatColorPickerScreen

**Route:** `Route.ChatColorPicker(conversationId: String? = null)`

**Layout:**
- `TopAppBar`: title "Chat Color", back arrow
- **Live preview** (Card): Mini conversation with two sample messages:
  - Received: "Here's a preview of the chat color."
  - Sent: "The color is visible to only you." (updates in real-time as user taps colors)
- **Color grid** (`LazyVerticalGrid`, 4 columns):
  - First item: "auto" circle with text label, ring border when selected
  - Solid color circles (13 items)
  - Gradient color circles (9 items, rendered with `Brush.linearGradient`)
  - Selected state: 3dp ring border around circle
  - Circle size: 56.dp with 8.dp spacing
- **Info banner** (when auto selected): "Auto matches the color to the wallpaper" in a tinted container

### 7.3 WallpaperPickerScreen

**Route:** `Route.WallpaperPicker(conversationId: String? = null)`

**Layout:**
- `TopAppBar`: title "Set Wallpaper", back arrow
- **"Choose from Photos"** row (Card): Gallery icon + text + chevron
  - Launches platform gallery picker (FileKit/existing gallery launcher)
  - Selected photo saved to `{appDataDir}/wallpapers/{uuid}.jpg`
- **"Presets"** section header (`MaterialTheme.typography.titleSmall`)
- **Preset grid** (`LazyVerticalGrid`, 3 columns):
  - Solid color tiles (12 items)
  - Gradient color tiles (9 items)
  - Aspect ratio: ~2:3 (portrait-ish tiles)
  - Selected state: checkmark overlay + border highlight
  - First item: "None" tile with slash/X icon to clear wallpaper

---

## 8. Integration Points

### 8.1 MessageBubbleRaw.kt

Replace hardcoded bubble color logic (lines 214-221):

**Before:**
```kotlin
val backgroundColor =
    if (emojiOnly) Color.Unspecified
    else if (sentByYou) HomebaseTheme.extendedColors.bubbleSentSurface
    else MaterialTheme.colorScheme.surfaceContainerHigh
```

**After:**
```kotlin
val chatColor = LocalActiveChatColor.current
val backgroundColor = when {
    emojiOnly -> Color.Unspecified
    !sentByYou -> MaterialTheme.colorScheme.surfaceContainerHigh
    else -> when (chatColor) {
        is ChatColor.Solid -> Color(chatColor.colorArgb)
        is ChatColor.Gradient -> Color.Unspecified // handled by brush modifier
        is ChatColor.Auto -> Color.Unspecified // resolved before reaching here
        is ChatColor.NotSet -> HomebaseTheme.extendedColors.bubbleSentSurface
    }
}
```

For gradient bubbles, apply `Modifier.background(brush, shape)` instead of `Modifier.background(color, shape)`.

Content color (text on bubble): Compute using luminance check on the primary color. If luminance > 0.5 use dark text, else white.

### 8.2 ConversationContent.kt

Add wallpaper rendering behind the message LazyColumn:

```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    // Wallpaper layer
    val wallpaper = LocalActiveWallpaper.current
    WallpaperBackground(wallpaper)

    // Dark mode dim overlay
    val dimEnabled = LocalWallpaperDimInDarkTheme.current
    if (isSystemInDarkTheme() && dimEnabled && wallpaper !is ChatWallpaper.None) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
    }

    // Existing Scaffold with messages
    Scaffold(...) { ... }
}
```

### 8.3 ConversationMessagesPane.kt

The ViewModel needs to expose `effectiveChatColor` and `effectiveWallpaper` flows. These are collected in `ConversationContent` and provided via CompositionLocals.

### 8.4 ReceivedMessageBubble (Group Name Colors)

In group conversations where member names are shown, use the 36-color palette:

```kotlin
val nameColor = GroupNameColors.getColor(
    odinId = message.senderOdinId,
    isDarkTheme = isSystemInDarkTheme()
)
Text(text = displayName, color = nameColor)
```

### 8.5 PendingMessageBubble.kt

Must also read from `LocalActiveChatColor` for consistent appearance while messages are sending.

### 8.6 Navigation (Routes.kt + AppNavHost.kt)

Add three new routes and wire them in AppNavHost:

```kotlin
@Serializable
data class ChatColorWallpaper(val conversationId: String? = null) : Route

@Serializable
data class ChatColorPicker(val conversationId: String? = null) : Route

@Serializable
data class WallpaperPicker(val conversationId: String? = null) : Route
```

### 8.7 Entry Points

- **Global**: AppearanceSettingsScreen -> new "Chat Color & Wallpaper" row
- **Per-conversation**: ConversationSettingsScreen -> new "Chat Color & Wallpaper" row

### 8.8 DI (AppModule.kt)

```kotlin
single { ChatAppearanceRepository(get(), get(), get()) }
viewModelOf(::ChatColorWallpaperViewModel)
```

---

## 9. File Structure

```
homebase-chat/src/commonMain/kotlin/id/homebase/chat/
  chatappearance/
    model/
      ChatColor.kt                     // sealed interface + kotlinx.serialization
      ChatColorPresets.kt              // 22 built-in bubble colors
      ChatWallpaper.kt                 // sealed interface + kotlinx.serialization
      ChatWallpaperPresets.kt          // 21 built-in wallpaper presets
      ChatColorsMapper.kt             // wallpaper -> auto-color deterministic lookup
      GroupNameColors.kt              // 36-color palette with light/dark variants
    data/
      ChatAppearanceRepository.kt     // reads/writes global + per-conversation
    ui/
      ChatColorWallpaperScreen.kt     // main settings screen
      ChatColorWallpaperViewModel.kt  // shared ViewModel for all 3 screens
      ChatColorPickerScreen.kt        // color grid with live preview
      WallpaperPickerScreen.kt        // wallpaper grid + photo picker
      LocalChatAppearance.kt          // CompositionLocals
      components/
        ChatPreviewMockup.kt          // mini conversation preview widget
        ColorCircleItem.kt            // color swatch (solid or gradient circle)
        WallpaperTileItem.kt          // wallpaper grid tile
```

**Modified existing files:**
- `ConversationLocalAppDataJson.kt` — add 3 new nullable fields
- `ConversationContent.kt` — provide CompositionLocals + wallpaper background
- `MessageBubbleRaw.kt` — read bubble color from CompositionLocal
- `PendingMessageBubble.kt` — read bubble color from CompositionLocal
- `MessageBubble.kt` — pass group name color to ReceivedMessageBubble
- `Routes.kt` — add 3 new route objects
- `AppNavHost.kt` — wire 3 new composable destinations
- `AppModule.kt` — register repository + ViewModel
- `UserPreferences.kt` — add 3 global preference fields
- `AppearanceSettingsScreen.kt` — add "Chat Color & Wallpaper" row
- `ConversationSettingsScreen.kt` — add "Chat Color & Wallpaper" row

---

## 10. KMP Compliance Checklist

- All strings via `stringResource()` from compose resources
- M3 color roles from `MaterialTheme.colorScheme` (except the custom bubble colors which are the feature)
- M3 typography from `MaterialTheme.typography`
- `start`/`end` padding (no left/right)
- `contentDescription` on meaningful icons
- `Icons.AutoMirrored.*` for directional icons
- `collectAsStateWithLifecycle()` for ViewModel StateFlows
- No platform imports in commonMain
- Photo wallpaper stored as encrypted payload via existing `DriveUploadProvider`/`DriveFileProviderCached`
- Color values stored as `Long` ARGB in data model (Compose-free)

---

## 11. Testing

All tests go in `jvmTest` (following the existing project pattern — `FormatDurationLabelTest`, `LocalAttachmentContextVideoEqualityTest`). Pure-logic unit tests, no Compose UI test infrastructure.

### 11.1 Data Model Tests

**`ChatColorSerializationTest`**
- Serializes/deserializes each `ChatColor` subtype (Auto, NotSet, Solid, Gradient) to/from JSON
- Round-trip: encode -> decode -> assert equality
- Verifies unknown JSON fields are ignored (forward compatibility)
- Verifies `Gradient` preserves color order and angle

**`ChatWallpaperSerializationTest`**
- Serializes/deserializes each `ChatWallpaper` subtype (None, SolidColor, GradientColor, Photo)
- Round-trip for all subtypes
- Photo subtype preserves payloadKey
- GradientColor preserves positions array and angle

**`ChatColorPresetsTest`**
- All 22 built-in colors have unique IDs
- All solid colors have valid non-zero ARGB values
- All gradients have exactly 2 colors and a valid angle (0-360)
- `findById(id)` returns correct preset for every built-in color
- `findById("unknown")` returns null

**`ChatWallpaperPresetsTest`**
- All 21 built-in wallpapers have unique IDs
- All solid wallpapers have valid non-zero ARGB values
- All gradient wallpapers have 16 color stops with matching 16-entry position arrays
- Positions are monotonically increasing from 0.0 to 1.0
- `findById(id)` returns correct preset for every built-in wallpaper

### 11.2 Auto Color Mapping Tests

**`ChatColorsMapperTest`**
- Every solid wallpaper maps to the expected bubble color (12 cases)
- Every gradient wallpaper maps to the expected bubble color (9 cases)
- `ChatWallpaper.None` maps to Ultramarine (default)
- `ChatWallpaper.Photo` maps to Ultramarine (default)
- Mapping is deterministic (call twice, same result)
- Every built-in wallpaper has a mapping (no unmapped presets)
- Every mapped bubble color is a valid preset from `ChatColorPresets`

### 11.3 Group Name Color Tests

**`GroupNameColorsTest`**
- Palette has exactly 36 entries
- Each entry has both lightTheme and darkTheme ARGB values (non-zero)
- `getColor(odinId, isDarkTheme=true)` returns darkTheme variant
- `getColor(odinId, isDarkTheme=false)` returns lightTheme variant
- Same odinId always returns same color (deterministic)
- Different odinIds produce a reasonable distribution across the palette (test with 100 random IDs, assert at least 10 distinct colors used)

### 11.4 Repository Tests

**`ChatAppearanceRepositoryTest`** (unit test with fake/mock UserPreferences and OptimisticWriter)

Global settings:
- Default global chat color is `Auto`
- `setGlobalChatColor(Crimson)` -> `getGlobalChatColor()` returns Crimson
- Default global wallpaper is `None`
- `setGlobalWallpaper(Blush)` -> `getGlobalWallpaper()` returns Blush
- `resetGlobalChatColor()` -> returns Auto
- `resetGlobalWallpaper()` -> returns None
- Default dim-in-dark-theme is true

Per-conversation (with mocked OptimisticWriter):
- `getConversationChatColor(id)` returns null when no override set
- `setConversationChatColor(id, Crimson)` -> `getConversationChatColor(id)` returns Crimson
- `setConversationChatColor(id, null)` clears the override
- Same pattern for conversation wallpaper

Effective resolution (fallback logic):
- No per-conversation override -> returns global value
- Per-conversation override set -> returns override, not global
- Per-conversation override cleared -> falls back to global again
- Auto color resolves through mapper based on effective wallpaper

### 11.5 ConversationLocalAppDataJson Tests

**`ConversationLocalAppDataJsonTest`**
- Existing fields (`lastReadTime`, `lastExitedAt`) survive serialization with new fields present
- New fields (`chatColorId`, `wallpaper`, `wallpaperDimInDarkTheme`) default to null when absent from JSON
- Backward compatibility: JSON without new fields deserializes without error (critical — old clients wrote this JSON)
- Forward compatibility: JSON with unknown fields deserializes without error
- `.copy(chatColorId = "crimson")` preserves all other fields unchanged
- Full round-trip with all fields populated

### 11.6 Content Color Contrast Tests

**`BubbleContentColorTest`**
- Dark bubble colors (Crimson, Forest, Midnight, etc.) produce white content color
- Light bubble colors (if any) produce dark content color
- Ultramarine gradient uses white content color
- All 22 built-in colors produce readable content colors (luminance contrast ratio > 4.5:1 per WCAG AA)

### 11.7 ViewModel Tests

**`ChatColorWallpaperViewModelTest`** (unit test with fake repository)

- Initial state: color = Auto, wallpaper = None, dim = true
- `setChatColor(Crimson)` updates `uiState.activeChatColor` to Crimson
- `setWallpaper(Blush)` updates `uiState.activeWallpaper` to Blush
- `setDimInDarkTheme(false)` updates `uiState.dimInDarkTheme` to false
- `resetChatColors()` resets to Auto
- `resetWallpapers()` resets to None
- Per-conversation mode: `conversationId != null` -> `isPerConversation = true`
- Per-conversation reads/writes go through conversation-specific repository methods
