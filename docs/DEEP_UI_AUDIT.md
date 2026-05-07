# Deep UI/UX Audit: Line-by-Line Code Review

> Generated 2026-05-07 | Companion to SIGNAL_VS_HOMEBASE_AUDIT.md
> Every finding has file path + line number. Sorted by severity.

---

## Table of Contents

- [Section 1: Chat Conversation Screen](#section-1-chat-conversation-screen)
- [Section 2: Settings & Preferences](#section-2-settings--preferences)
- [Section 3: Camera & Media Features](#section-3-camera--media-features)
- [Section 4: Cross-Cutting Issues](#section-4-cross-cutting-issues)
- [Section 5: Prioritized Fix List](#section-5-prioritized-fix-list)

---

## Section 1: Chat Conversation Screen

### 1.1 MESSAGE GROUPING (The #1 Visual Problem)

**Status: COMPLETELY MISSING**

Every single message renders identically — same shape, same spacing, same author name. There is zero awareness of consecutive messages from the same sender.

**What Signal does:** Messages from the same sender within 3 minutes form a "cluster":
- 1dp spacing between clustered messages vs 6dp between different senders
- Corner radii change: START (18/18/18/4), MIDDLE (18/4/4/18), END (18/4/18/18), ALONE (all 18)
- Author name only on first message in cluster (groups)
- Avatar only on last message in cluster (groups)
- Timestamp only on last message in cluster

**What Homebase does:**
- `MessageItem.kt` receives a single message with no `isFirstInGroup`/`isLastInGroup` parameters
- `ConversationContent.kt:784` uses `Arrangement.spacedBy(8.dp)` for ALL items uniformly
- `MessageBubbleRaw.kt:249-256` computes shape only from `sentByYou` and `mediaOnly` — no cluster state
- `renderAuthorName` is always `true` for every group message (set in ConversationContent)

**Impact:** The conversation looks amateurish and wastes ~40% more vertical space than Signal.

**Fix requires:**
1. Add `MessageClusterPosition` enum (ALONE/START/MIDDLE/END) to `MessageListContentModel.Message`
2. Compute cluster positions in ViewModel when building the message list (3-min window, same sender)
3. Pass cluster position to `MessageBubbleRaw` for corner radius selection
4. Suppress author name for MIDDLE/END positions
5. Show avatar only for END/ALONE positions
6. Use 1-2dp spacing for clustered items, 8dp between groups

---

### 1.2 BUBBLE PADDING IS WRONG

**File:** `MessageBubbleRaw.kt:396-398`

```kotlin
// CURRENT (too much vertical padding):
.padding(horizontal = 12.dp, vertical = 12.dp)

// SHOULD BE (matches Signal and the project's own Dimens):
.padding(horizontal = 12.dp).padding(top = 7.dp, bottom = 7.dp)
```

The `Dimens.kt` file defines `bubbleTopPadding = 7.dp` and `bubbleBottomPadding = 7.dp` (lines 90, 97) but `MessageBubbleRaw` ignores them and uses 12dp. This makes every bubble 10dp taller than it should be.

**Author name padding also excessive** at `MessageBubbleRaw.kt:357-359`:
```kotlin
// CURRENT: 8dp top AND 8dp bottom
.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp)
// Creates 20dp gap between author name bottom and text top (8dp + 12dp)
// SHOULD BE: ~4dp bottom to match Signal's density
```

---

### 1.3 NO SENDER AVATAR ON RECEIVED MESSAGES

**Status: MISSING**

In group conversations, received messages have NO avatar beside them. Signal shows a 28dp circular avatar aligned to the bottom-left of the last message in a cluster.

**Where to add:** `MessageBubble.kt` in `ReceivedMessageBubble` (line ~363). Need a `Row` with avatar on the left, bubble on the right, where avatar is only visible for END/ALONE cluster positions.

---

### 1.4 MEDIA GALLERY TOO SMALL

**File:** `Dimens.kt:63` — `albumTotalWidth = 210.dp`

210dp is absurdly narrow on modern phones (360-412dp wide). With bubble padding, a media gallery takes up roughly half the screen width. Signal sizes galleries at ~66% of screen width.

**3-image layout is also wrong** (`MediaGallery.kt:187-250`): Uses 2-on-top + 1-full-below. Signal uses 1-large-left + 2-small-stacked-right, which is more visually appealing.

**Missing:** No video badge/duration on video thumbnails within gallery cells.

---

### 1.5 TOP BAR ISSUES

**File:** `ConversationContent.kt:512-689`

| Issue | Line | Detail |
|-------|------|--------|
| Wrong back icon | 588 | `Icons.Default.ChevronLeft` — violates CLAUDE.md RTL rule |
| No online/last-seen status | 540-554 | Only shows name in `Column`, no subtitle. Signal shows "last seen X min ago" or "online" |
| No member count for groups | 540-554 | Signal shows "N members" under group name |
| No typing indicator | — | No "typing..." subtitle when contact is typing |
| Avatar too small | 537 | 32dp with 12sp font. Signal uses 36-40dp |
| `MutableInteractionSource()` not remembered | 524 | Creates new instance every recomposition |

---

### 1.6 SCROLL-TO-BOTTOM FAB MISSING UNREAD COUNT

**File:** `ConversationContent.kt:891-915`

The FAB appears when scrolled up but has no badge showing unread message count. Signal shows "3" on the FAB when 3 new messages arrived while scrolled up.

Also missing: **"NEW MESSAGES" separator** — Signal inserts a visual divider above unread messages.

---

### 1.7 TIMESTAMP FOOTER NEEDS BOTTOM PADDING

**File:** `MessageBubbleRaw.kt:451-453`

```kotlin
// CURRENT: No bottom padding on timestamp row
.padding(start = 8.dp)
// Signal adds ~5dp (bubbleFooterBottomPadding) below the timestamp
```

When the timestamp is on its own line, it hugs the bubble bottom edge.

---

### 1.8 INPUT BAR ISSUES

**File:** `MessageInputBar.kt`

| Issue | Line | Severity |
|-------|------|----------|
| Recording delay is 1000ms (should be ~300ms) | 543 | HIGH — users think recording is broken |
| No voice recording lock (slide up to lock) | — | HIGH — can't record hands-free |
| No voice message preview before send | — | MEDIUM — sends immediately on release |
| No mention/@ autocomplete for groups | — | MEDIUM |
| No GIF picker button | — | MEDIUM |
| No "is typing" broadcast | — | HIGH — typing indicators can't work without this |
| Hard-coded color `Color(0xFF393B3D)` for divider | 1173 | LOW — breaks in light mode |
| `BlueBackgroundIconButton` naming misleading | 937 | LOW — code smell |
| Commented-out AnimatedVisibility for toolbar | 561-573 | LOW — dead code |
| Desktop expand button nearly invisible (alpha 0) | 248-267 | MEDIUM — users can't discover expanded mode |

---

### 1.9 LINK PREVIEW ISSUES

**File:** `LinkPreview.kt`

| Issue | Line | Detail |
|-------|------|--------|
| No background on receiver-side card | 251 | Preview text blends into bubble with no visual separation |
| No loading skeleton during fetch | — | Preview appears abruptly after network delay |
| No favicon next to domain | — | Signal shows site favicon |
| Inconsistent ContentScale (Fit vs Crop) | 274 | Sender uses Crop, receiver uses Fit — same link looks different |

---

### 1.10 SWIPE-TO-REPLY THRESHOLD TOO HIGH

**File:** `SwipeableMessageWrapper.kt:58-59`

```kotlin
val SWIPE_THRESHOLD = 72.dp  // Signal uses ~50-56dp
val MAX_OFFSET = 100.dp      // Only 28dp elastic range — feels hard-stopped
```

Signal: 64dp trigger, 96dp max = 32dp elastic range. Homebase: 72dp trigger = requires larger finger movement.

---

### 1.11 REPLY PREVIEW BAR MISSING ACCENT

**File:** `ReplyPreviewBar.kt`

The reply preview above the input bar has no colored left accent bar. The `InlineReplyPreview` inside bubbles DOES have a 3dp accent bar. This visual inconsistency is jarring — the same "reply" concept looks different in two places.

Also missing: slide-in animation on appear, swipe-to-dismiss gesture.

---

### 1.12 SENT MESSAGE HIT TARGET TOO WIDE

**File:** `MessageBubble.kt:256`

```kotlin
Box(modifier = Modifier.fillMaxWidth()  // <-- entire row is long-press target
    .combinedClickable(onLongClick = ...)
```

The `fillMaxWidth()` combined with `combinedClickable` means tapping anywhere on the row — even the empty space to the LEFT of a right-aligned sent bubble — triggers the long-press menu. Signal only responds to long-press on the bubble itself.

---

### 1.13 NO FAILED MESSAGE STATE

The delivery status shows Pending (clock), Sent, Delivered, Read — but there is NO error/failed state. Signal shows a red exclamation with "Not delivered" and a retry button.

---

### 1.14 NO FLOATING DATE HEADER ON SCROLL

Signal shows a sticky date pill at the top of the screen while scrolling through messages. Homebase only has date section items within the list — no floating header while actively scrolling.

---

## Section 2: Settings & Preferences

### 2.1 CONVERSATION SETTINGS SCREEN (The Most Under-Built Screen)

**File:** `ConversationSettingsScreen.kt` — **Only 114 lines. Only shows avatar + name.**

**What Signal's equivalent has:**
- Avatar with camera overlay (tap to change)
- Name, phone number, bio/about
- Action buttons row: Message, Audio Call, Video Call, Mute, Search
- Disappearing messages toggle with duration picker
- Chat color & wallpaper selection
- Custom notification sound
- Shared media/files/links 3-tab grid
- Starred messages
- Safety number verification
- Block/report
- Mutual groups list

**What Homebase has:** Avatar. Name. That's it.

---

### 2.2 CONTACT INFO SCREEN (Nearly As Bare)

**File:** `ContactInfoScreen.kt` — **Only 111 lines. Shows avatar (72dp), name, domain.**

Missing: Action buttons, bio, shared media, block, report, mutual groups, connection state display (fields exist in `ContactUiModel` but aren't rendered).

---

### 2.3 SETTINGS SCREEN VISUAL ISSUES

**File:** `SettingsScreen.kt`

| Issue | Line | Detail |
|-------|------|--------|
| Flat list with no visual grouping | 278-374 | Every item looks identical. No section headers like "Account", "General", "Danger Zone" |
| Danger zone not visually distinct | 377-388 | Delete Account and Logout use the same styling as Help. No red color, no warning |
| No subtitles on items | — | Signal shows "Theme, chat wallpaper" under Appearance |
| Profile area is basic | 247-276 | No bio, no edit button, no status, no tap-to-edit-avatar |
| Profile Info opens external URL | 287-296 | Should be an in-app profile editor |

### Missing Settings Screens (Entire categories):

| Screen | Priority | Signal Has |
|--------|----------|-----------|
| **Privacy Settings** | CRITICAL | Read receipts, typing indicators, screen lock, incognito keyboard, screenshot blocking |
| **Chat Settings** | HIGH | Enter-key-sends, link previews, message font size, chat backups |
| **Backup/Export** | HIGH | Encrypted local backup, chat export |
| **Per-conversation notifications** | MEDIUM | Custom sound, vibrate per conversation |
| **Account/Linked Devices** | MEDIUM | Device management |
| **Data & Network Settings** | MEDIUM | Auto-download (WiFi/Mobile/Roaming), data saver, proxy |

---

### 2.4 APPEARANCE SETTINGS (Almost Empty)

**File:** `AppearanceSettingsScreen.kt` — **Only 2 options: Language and Theme.**

Missing:
- Message font size (Small/Normal/Large/Extra Large)
- Chat wallpapers (solids + gradients + photo)
- Chat bubble color (13+ solid colors + 9 gradients in Signal)
- Dynamic color / Material You (Android 12+)
- App icon customization

The `getIconForTheme()` function (line 165-171) is defined but never called — dead code.

---

### 2.5 STORAGE SETTINGS (Decent But Incomplete)

**File:** `StorageSettingsScreen.kt`

**GOOD:** Cache usage visualization with colored bar, cache legend, Coil memory tracking, orphan cache detection, drive listing, defragment button.

**UGLY:**
- Hardcoded English strings in `OrphanCoilCacheWarning` (line 457-459)
- Usage bar only 10dp tall (hard to read color segments)
- `errorContainer` color used for thumbnail cache (semantically wrong)
- `Icons.Default.ChevronLeft` back button (line 121)

**Missing vs Signal:**
- Per-conversation storage breakdown
- Media type breakdown (Photos/Videos/Files/Other)
- "Manage Storage" to delete large items
- Auto-download settings (WiFi/Mobile/Roaming)
- Data saver mode
- Keep messages duration (auto-delete old messages)

---

### 2.6 NOTIFICATION SETTINGS ISSUES

**File:** `NotificationSettingsScreen.kt`

**Hardcoded English strings in `NotificationContentLevel`** (UiState file): `displayName` field contains `"Name, Content, and Actions"`, `"Name Only"`, `"No Name or Content"` — NOT using `stringResource()`. **Localization bug.**

`ChevronRight` used as selection indicator (line 569) — should be a checkmark (`Icons.Default.Check`).

Missing: Per-conversation sounds, notification profiles (Focus modes), ringtone picker, visual preview of content level options.

---

### 2.7 GROUP SETTINGS ISSUES

**File:** `GroupSettingsScreen.kt`

| Issue | Line | Detail |
|-------|------|--------|
| Member avatar only 28dp | 734 | Too small. Signal uses ~40dp in member lists |
| Member row padding 20dp vertical | 725 | Tall rows with tiny avatars look empty |
| Hardcoded error "No group could be loaded" | 288 | Not using `stringResource()` |
| No group description field | — | Signal supports editable group descriptions |
| No invite link/QR | — | Signal has shareable group link |
| No permissions management | — | Who can edit info, send messages, add members |
| No shared media section | — | Signal shows media/files/links grid |
| No mute toggle | — | Must go through conversation menu |
| No disappearing messages | — | Signal has timer with duration picker |

---

## Section 3: Camera & Media Features

### 3.1 CAMERA INTEGRATION

**Current state:** Camera works via `rememberCameraManager()` (expect/actual per platform):
- **Android:** Uses `ActivityResultContracts.TakePicture()` — launches system camera, returns photo
- **iOS:** Uses `UIImagePickerController` with `.camera` source
- **Desktop:** No camera support (returns null)

**What Signal has:** Dedicated in-app `CameraXFragment` with:
- Compose-based camera HUD with controls
- Flash toggle, front/back switch, timer
- Photo AND video capture modes
- Gallery thumbnail shortcut
- Face detection for auto-focus
- Video constraints and max duration

**Gap:** Homebase has no in-app camera experience. It delegates to the system camera app, which:
- Breaks the flow (user leaves the app)
- No video recording shortcut from chat
- No camera controls (flash, switch)
- Desktop has zero camera support

**Recently added:** Camera button in `FullScreenAttachmentEditor.kt` attachment strip (PR #451). This is a step forward but still launches the system camera.

---

### 3.2 IMAGE EDITOR

**Files:** `image-editor-ui/src/commonMain/kotlin/id/homebase/imageeditor/ui/`

**What exists:**
- **CropScreen:** Aspect ratio lock, free rotation, 90-degree rotation, flip horizontal, undo/redo, reset. Dark theme forced. Well-built.
- **DrawScreen:** Freehand drawing with brush selection, color picker (HSV slider), stroke width adjustment, undo/redo/reset. Dark theme forced. Well-built.

**What's MISSING vs Signal:**
- **Text overlay** — Signal lets you add text with fonts/colors on images
- **Blur tool** — Signal has manual blur brush AND automatic face detection blur
- **Stickers on images** — Signal lets you place sticker overlays from installed packs
- **Image quality selection** — Signal has "Standard" vs "High Quality" send option

---

### 3.3 AUDIO MESSAGES

**Current state:**
- Recording via `MessageInputBar` with press-hold gesture
- Waveform bar width defined: `Dimens.waveFormBarWidth = 2.dp`
- `AudioPlayerWidget` exists in homebase-common for playback
- Platform implementations: Android uses media player, JVM is a complete stub (non-functional), iOS uses AVAudioPlayer

**Issues:**
- No waveform preview before sending (sends immediately on release)
- No voice recording lock (slide up for hands-free)
- Desktop audio playback is broken (JvmAudioPlayer is empty stub)
- 1000ms delay before recording starts (should be ~300ms)

**Signal comparison:** Full `WaveFormSeekBarView` with played/unplayed color distinction, Lottie play/pause animation, MM:ss duration display, and a voice draft preview before sending.

---

### 3.4 FULL SCREEN MEDIA VIEWER

**File:** `FullScreenMediaViewer.kt`

**GOOD:** Pinch zoom, drag to pan, double-tap reset, multi-image swipe navigation, gradient overlay for timestamp readability, share/save/delete actions.

**Missing:**
- No media rail (thumbnail strip for navigating between media)
- No caption display
- No swipe-down-to-dismiss gesture
- No video inline playback (separate `FullScreenVideoPlayer` exists but is separate flow)

---

### 3.5 EMOJI PICKER

**File:** `EmojiSelection.kt`

**GOOD:** Section-based browsing, search, skin tone variants with popup, grid layout with 32dp cells, back/delete buttons in message mode.

**Issues:**
- No recently-used emoji section (most chat apps show this first)
- No emoji suggestions as you type (Signal shows relevant emoji while typing "cat", "happy", etc.)
- Loads all emoji data on mount (`loadEmojiData()` line 91-99) — could cause jank

---

### 3.6 DOCUMENT/FILE DISPLAY

Files shared as documents show icon + "File" label via `DocumentMediaItem`. Basic but functional.

**Missing:**
- No in-app document preview (PDF viewer, etc.)
- No file size display on document messages
- No download progress indicator

---

### 3.7 LOCATION SHARING

**File:** `LocationPreview.kt` + `LocationPreviewRenderer`

Location sharing infrastructure exists (GPS launcher, static map preview fetch, coordinate staging). However:
- Map preview depends on external provider (`LocationPreviewProvider`) which is a dev stub
- No live location sharing
- No location picker map UI (just uses current GPS coordinates)

---

## Section 4: Cross-Cutting Issues

### 4.1 RTL ICON VIOLATIONS — 37 instances

**`Icons.Default.ChevronLeft` used in 19+ screens** for back navigation instead of `Icons.AutoMirrored.Filled.ArrowBack`. This breaks RTL layout support (Arabic, Hebrew, etc.) where back arrows should point right.

Files affected:
- ConversationContent.kt, ConversationSettingsScreen.kt, ContactInfoScreen.kt
- AppearanceSettingsScreen.kt, NotificationSettingsScreen.kt, StorageSettingsScreen.kt
- GroupSettingsScreen.kt, AddGroupMembersScreen.kt, SelectMembersScreen.kt
- CreateConversationScreen.kt, CreateConversationGroupScreen.kt, EditConversationGroupScreen.kt
- ArchivedConversationsScreen.kt, MessageInfoScreen.kt, FullScreenMediaViewer.kt
- FullScreenVideoPlayer.kt, ConversationListPane.kt, HelpScreen.kt, DeveloperMenuScreen.kt

Also: `Icons.Default.ChevronRight` used for navigation indicators and selection marks.

---

### 4.2 HARDCODED ENGLISH STRINGS — 30+ instances

User-visible strings not using `stringResource()`:

| File | String | Line(s) |
|------|--------|---------|
| ConversationSettingsScreen.kt | "No group could be loaded" | 84 |
| ContactInfoScreen.kt | "No contact could be loaded" | 84 |
| GroupSettingsScreen.kt | "No group could be loaded" | 288 |
| StorageSettingsScreen.kt | "Orphan Coil disk cache detected" + body | 457-459 |
| HelpScreen.kt | "Developer menu" | 249 |
| NotificationSettingsUiState.kt | "Name, Content, and Actions", "Name Only", "No Name or Content" | 5-10 |
| ConversationListViewModel.kt | 20+ "Failed to..." error messages | 836-1871 |

---

### 4.3 ACCESSIBILITY GAPS

- **66 instances of `contentDescription = null`** in homebase-chat module
- Minimal `semantics()` modifier usage for screen readers
- No accessibility testing in test suite
- No font size setting for users with vision impairments

---

### 4.4 MISSING ANIMATIONS

| Animation | Signal Has | Homebase Has |
|-----------|-----------|--------------|
| Typing indicator (3-dot wave) | Yes (1500ms cycle) | No |
| Pending send rotation | Yes (360deg/1500ms) | Static clock icon |
| Quote reveal pulse | Yes (150ms highlight) | Scrolls but no highlight |
| Reaction overshoot | Yes (1.2f->1.8f->1.2f) | Spring on count only |
| Reply preview slide-in | Yes (vertical slide) | Instant appear |
| Date header fade on scroll | Yes (sticky floating pill) | No floating header |

---

### 4.5 THEME ISSUES

**File:** `Theme.kt`

- `bubbleSentSurface` uses `LightColors.Primary` in BOTH light and dark themes (lines 109, 131). The sent bubble is the same bright blue in dark mode — can be too bright. Signal darkens the sent color in dark mode.
- Extended colors (`Success`, `Warning`, `Info`) are hardcoded static colors (not theme-aware). `Success = #4CAF50` may have poor contrast on dark surfaces.
- No `shapes` override in `MaterialTheme()` call — relies on defaults, which don't match the custom 18dp corner radii used in messages.
- No dynamic color support (Material You / Android 12+)

---

## Section 5: Prioritized Fix List

### TIER 1: "Looks Broken" — Fix Before Any Demo (1-2 weeks)

| # | Fix | Files | Effort |
|---|-----|-------|--------|
| T1.1 | **Message clustering** (group same-sender messages, 4-state corners, variable spacing) | MessageItem.kt, MessageBubbleRaw.kt, ConversationContent.kt, ViewModel | Large |
| T1.2 | **Bubble vertical padding** from 12dp to 7dp | MessageBubbleRaw.kt:397 | Tiny |
| T1.3 | **Author name padding** from 8dp bottom to 4dp | MessageBubbleRaw.kt:359 | Tiny |
| T1.4 | **Add sender avatars** on received group messages | MessageBubble.kt | Medium |
| T1.5 | **Fix all 37 ChevronLeft violations** to AutoMirrored.ArrowBack | 19 files | Small |
| T1.6 | **Typing indicator** (protocol + 3-dot animation + list snippet replacement) | New composable + protocol | Large |
| T1.7 | **Recording delay** from 1000ms to 300ms | MessageInputBar.kt:543 | Tiny |

### TIER 2: "Feels Unfinished" — Before Public Beta (2-4 weeks)

| # | Fix | Files | Effort |
|---|-----|-------|--------|
| T2.1 | **Rebuild ConversationSettingsScreen** with action buttons, shared media, mute, disappearing msgs | ConversationSettingsScreen.kt (near-complete rewrite) | Large |
| T2.2 | **Rebuild ContactInfoScreen** with action buttons, bio, shared media, block | ContactInfoScreen.kt (near-complete rewrite) | Large |
| T2.3 | **Chat wallpapers** (solid colors + gradients + photo, per-conversation) | New screen + ConversationContent background | Large |
| T2.4 | **Bubble color customization** (13+ solids + gradients) | New screen + Theme extension | Large |
| T2.5 | **Message font size setting** | AppearanceSettingsScreen + Dimens | Medium |
| T2.6 | **Privacy settings screen** (read receipts, typing indicators, screen lock) | New screen | Medium |
| T2.7 | **Settings visual grouping** with section headers and danger zone styling | SettingsScreen.kt | Small |
| T2.8 | **Online/last-seen status** in conversation top bar | ConversationContent.kt top bar | Medium |
| T2.9 | **Unread count badge** on scroll-to-bottom FAB | ConversationContent.kt | Small |
| T2.10 | **"NEW MESSAGES" separator** | ConversationContent.kt LazyColumn | Small |
| T2.11 | **Draft indicator** in conversation list ("Draft:" prefix) | ConversationItem.kt | Small |
| T2.12 | **Muted icon** (bell-disabled) on conversation list items | ConversationItem.kt + FromTextView equivalent | Small |
| T2.13 | **Pinned icon** on conversation list items | ConversationItem.kt | Small |
| T2.14 | **2-line snippet** in conversation list | ConversationItem.kt | Tiny |
| T2.15 | **Media gallery width** responsive to screen (66% of screen width, not fixed 210dp) | Dimens.kt + MediaGallery.kt | Medium |
| T2.16 | **Fix all hardcoded English strings** to use stringResource() | 30+ instances across codebase | Small |

### TIER 3: "Polished Product" — Before Launch (4-8 weeks)

| # | Fix | Files | Effort |
|---|-----|-------|--------|
| T3.1 | **Shared media gallery** (3-tab: Media/Documents/Links) | New screen | Large |
| T3.2 | **Multi-select messages** (long-press to enter, bulk operations) | MessageBubble.kt + new selection state | Large |
| T3.3 | **Swipe-to-archive** on conversation list items | ConversationListPane.kt | Medium |
| T3.4 | **Voice recording lock** (slide up for hands-free) | MessageInputBar.kt | Medium |
| T3.5 | **GIF picker** (Giphy/Tenor integration) | New composable + API integration | Large |
| T3.6 | **In-app camera** (CameraX Compose on Android, native on iOS) | New expect/actual screens | Very Large |
| T3.7 | **Image editor: text overlay** | image-editor-ui | Medium |
| T3.8 | **Image editor: blur tool** | image-editor-ui | Medium |
| T3.9 | **Disappearing messages** | Protocol + UI (timer picker, system message) | Large |
| T3.10 | **Floating date header** on scroll | ConversationContent.kt | Medium |
| T3.11 | **Chat folders** (horizontal scrollable tabs) | ConversationListPane.kt + new composable | Large |
| T3.12 | **Forwarded message indicator** ("Forwarded" label on bubbles) | MessageBubble.kt | Small |
| T3.13 | **Failed message state** (red indicator + retry button) | MessageBubble.kt + DeliveryStatus | Medium |
| T3.14 | **Sent bubble fill-width fix** (hit target only on bubble, not empty space) | MessageBubble.kt:256 | Small |
| T3.15 | **Voice message preview** before send (waveform + play button) | MessageInputBar.kt | Medium |
| T3.16 | **Chat settings screen** (enter-key-sends, link previews, backups) | New screen | Medium |
| T3.17 | **Group description field** | GroupSettingsScreen + EditConversationGroupScreen | Small |
| T3.18 | **Group invite links** (shareable link + QR code) | GroupSettingsScreen + API | Medium |
| T3.19 | **Reaction "my reaction" styling** (distinct background when user reacted) | ReactionList.kt | Small |
| T3.20 | **Recently-used emoji section** in picker | EmojiSelection.kt | Medium |
| T3.21 | **Desktop audio playback** (JvmAudioPlayer is empty stub) | JvmAudioPlayer.kt | Medium |
| T3.22 | **Backup/Export functionality** | New screens + file operations | Large |
| T3.23 | **Pending send rotation animation** | DeliveryStatus composable | Tiny |

---

## Appendix: Quick Wins (< 1 hour each)

These can be done immediately for visible improvement:

1. **Bubble padding 12dp -> 7dp** (MessageBubbleRaw.kt:397) — makes every bubble more compact
2. **Author name bottom padding 8dp -> 4dp** (MessageBubbleRaw.kt:359)
3. **Recording delay 1000ms -> 300ms** (MessageInputBar.kt:543)
4. **2-line snippet** in conversation list (ConversationItem.kt)
5. **Pending send icon rotation** (MessageBubble.kt delivery status)
6. **Danger zone red text** on Delete Account and Logout (SettingsScreen.kt)
7. **Section headers** in settings ("General", "Account", "Danger Zone")
8. **Reply preview accent bar** (ReplyPreviewBar.kt — add 3dp colored left border)
9. **Fix InlineReplyPreview corner typo** (MessageBubble.kt:815 — 16dp/15dp should be 16dp/16dp)
10. **Link preview background** on receiver side (LinkPreview.kt:251)

---

*This document should be used alongside SIGNAL_VS_HOMEBASE_AUDIT.md for the complete picture.*
