# Signal vs Homebase: Comprehensive UI/UX & Feature Audit

> Generated 2026-05-07 | Signal-Android repo + chat-kmp codebase deep-dive

---

## Table of Contents

- [Part 1: UI/UX Beauty & Polish](#part-1-uiux-beauty--polish)
  - [1.1 Conversation List](#11-conversation-list-home-screen)
  - [1.2 Message Bubbles](#12-message-bubbles)
  - [1.3 Input Bar / Composer](#13-input-bar--composer)
  - [1.4 Reactions](#14-reactions)
  - [1.5 Media Display](#15-media-display)
  - [1.6 Theming & Appearance](#16-theming--appearance)
  - [1.7 Animations & Micro-interactions](#17-animations--micro-interactions)
  - [1.8 Profile & Group Screens](#18-profile--group-screens)
  - [1.9 Accessibility](#19-accessibility)
- [Part 2: Missing Features](#part-2-missing-features)
  - [2.1 Critical (Users will leave without these)](#21-critical---users-will-leave-without-these)
  - [2.2 High Priority (Users expect these)](#22-high-priority---users-expect-these)
  - [2.3 Medium Priority (Delight features)](#23-medium-priority---delight-features)
  - [2.4 Nice-to-Have (Future roadmap)](#24-nice-to-have---future-roadmap)
- [Part 3: Implementation Checkpoints](#part-3-implementation-checkpoints)

---

## Part 1: UI/UX Beauty & Polish

### 1.1 Conversation List (Home Screen)

#### What Signal Does Well

| Feature | Signal Implementation | Homebase Current State | Gap Severity |
|---------|----------------------|----------------------|--------------|
| **Typing indicator in list** | Replaces snippet text with animated 3-dot indicator (1500ms cycle, 600ms/dot, 0.4 min alpha, 0.75 min scale) | **MISSING** - No typing indicator anywhere | **CRITICAL** |
| **Message grouping in time** | Messages within 3 minutes from same sender cluster together (1dp spacing vs 6dp isolated) | No consecutive message grouping - every bubble rendered identically with 8dp spacing | **HIGH** |
| **Unread badge** | Pill shape (12.5dp radius), `colorPrimary` background, white text, `LabelMedium` 13sp. Separate @-mention indicator pill | Basic Material3 Badge with `bubbleSentSurface` color. No mention indicator | **MEDIUM** |
| **Draft indicator** | "Draft:" prefix in italic with secondary color before snippet text | **MISSING** | **HIGH** |
| **Muted indicator** | Bell-disabled icon (16dp) appended after name text as centered image span | **MISSING** - Muted state exists in data but no visual indicator on list | **HIGH** |
| **Pinned indicator** | Pin icon (16dp) prepended to name text as left compound drawable | **MISSING** - Pin action exists in menu but no visual indicator on list item | **HIGH** |
| **Pull-to-filter** | Custom `ConversationListFilterPullView` - pull down gesture reveals "Unread only" filter with haptic feedback, 300ms animation | **MISSING** - Only has InputChip filter toggle, no pull gesture | **MEDIUM** |
| **Chat folders** | Horizontal scrollable `ChatFolderAdapter` with folder-specific actions (Mute All, Read All) | **MISSING** | **MEDIUM** |
| **Multi-select action bar** | Long-press enters selection mode with bulk operations: read/unread, pin/unpin, mute, archive, delete | Only long-press popup with pin/archive/mark-read. No multi-select | **HIGH** |
| **Item height & density** | Min 84dp height, 48dp avatar, 2-line snippet, spacious but information-dense | Similar sizing (48dp avatar, 12dp padding) but single-line snippet max | **LOW** |
| **Snippet max lines** | 2 lines max with ellipsis for message preview | 1 line max - loses context on longer messages | **MEDIUM** |
| **Selection/activation background** | Inset 12dp, radius 18dp, `secondaryContainer` color. Ripple effect on tap | RoundedCornerShape(8dp) with secondaryContainer at 0.7 alpha | **LOW** |
| **Swipe actions on conversations** | Swipe to archive with undo snackbar | **MISSING** - No swipe gestures on conversation list items | **HIGH** |
| **Onboarding hints** | With <6 conversations: "new group" and "invite friends" prompts. Auto-clear after 6+ | Basic empty state text only | **LOW** |

#### Specific Fixes Needed

1. **Add typing indicator to conversation list items** - Replace snippet text when someone is typing
2. **Add draft indicator** - Show "Draft:" prefix in snippet when unsent text exists
3. **Add muted icon** - Bell-disabled icon after contact name
4. **Add pinned icon** - Pin icon before contact name
5. **Increase snippet to 2 lines** - More context for users scanning the list
6. **Add swipe-to-archive** - Right swipe on conversation items
7. **Add multi-select mode** - Long press to enter, action bar at top for bulk operations

---

### 1.2 Message Bubbles

#### What Signal Does Well

| Feature | Signal Implementation | Homebase Current State | Gap Severity |
|---------|----------------------|----------------------|--------------|
| **Consecutive message clustering** | Messages within 3 min from same sender: collapsed corners (18dp -> 4dp on connecting side), 1dp spacing vs 6dp | No clustering. Every message gets same shape and 8dp spacing | **CRITICAL** |
| **Corner radius system** | 4 states per direction (ALONE/START/MIDDLE/END) with precise corner control. Sent: BR collapses. Received: BL collapses | Only 2 states: standard (18dp) and one sharp corner (4dp on bottom-end for sent, bottom-start for received). No START/MIDDLE/END | **HIGH** |
| **Bubble edge margin** | 32dp from opposite edge (sent messages have 32dp left margin, received have 32dp right margin) | No explicit opposite-edge margin documented. Messages may stretch too wide | **MEDIUM** |
| **Footer positioning** | Smart 4-state system: TUCKED (overlay), END (inline if space), UNDERNEATH (new line), NONE. Calculated from last line width vs footer width + 8dp gap | Basic smart placement: fits on last line if space, otherwise new line. Only 2 states | **MEDIUM** |
| **Sent bubble color** | Customizable! Default ULTRAMARINE (#315FF4). 13 solid colors + 9 gradients available. Per-conversation override | Fixed `bubbleSentSurface` from extended colors. No customization | **HIGH** |
| **Received bubble (wallpaper mode)** | Separate color for wallpaper context: `colorNeutralInverse` text, special bubble background | No wallpaper support = no wallpaper-aware coloring | **HIGH** |
| **Avatar on received messages** | 28dp avatar at bottom-left of last message in cluster. Only shown on END/ALONE position | No avatar next to received messages in conversation view | **HIGH** |
| **Group sender colors** | 36 light/dark theme color pairs cycling through members by sorted position | `getOdinIdColor(domainName)` - domain-based color. Less variety | **MEDIUM** |
| **Cluster spacing** | 1dp between clustered messages, 6dp for isolated messages | Fixed 8dp for all messages | **MEDIUM** |
| **Forwarded indicator** | Shows "Forwarded" label on forwarded messages | Forward feature exists but **NO visual indicator** on forwarded messages | **MEDIUM** |

#### Bubble Shape Comparison (Visual)

```
SIGNAL - Sent message cluster:          HOMEBASE - Current:
                                        
  ┌──────────────┐  (ALONE: all 18dp)    ┌──────────────┐  (always same)
  │   Message 1  │                        │   Message 1  │
  └──────────────┘                        └─────────────┐│  (4dp BR)
                                                        
  ┌──────────────┐  (START: BR=4dp)       ┌──────────────┐
  │   Message 2  │                        │   Message 2  │
  └─────────────┐│                        └─────────────┐│
  ┌─────────────┐│  (MIDDLE: TR=4,BR=4)   ┌──────────────┐
  │   Message 3  │                        │   Message 3  │
  └─────────────┐│                        └─────────────┐│
  ┌─────────────┐│  (END: TR=4dp)         ┌──────────────┐
  │   Message 4  │                        │   Message 4  │
  └──────────────┘                        └─────────────┐│
```

#### Specific Fixes Needed

1. **Implement message clustering** - Group messages from same sender within 3-minute window
2. **4-state corner radius system** - ALONE (all 18dp), START (connecting corner 4dp), MIDDLE (both connecting corners 4dp), END (one connecting corner 4dp)
3. **Add sender avatar on received messages** - 28dp avatar aligned to bottom of last message in cluster
4. **Add opposite-edge margin** - 32dp minimum margin on the far side of bubbles
5. **Reduce cluster spacing** - 1-2dp between clustered messages vs 6-8dp between groups
6. **Add "Forwarded" label** - Visual indicator on forwarded messages

---

### 1.3 Input Bar / Composer

#### What Signal Does Well

| Feature | Signal Implementation | Homebase Current State | Gap Severity |
|---------|----------------------|----------------------|--------------|
| **Compose bubble shape** | 20dp rounded container with `surfaceVariant` background, 12dp side margins | 12dp radius text field | **LOW** |
| **Camera button** | Inline 24dp camera icon (16dp end margin) always visible in compose area | Recently added camera button in attachment preview. Not inline in compose bar | **MEDIUM** |
| **Voice recording slide-to-cancel** | 0.5 threshold (50% of width), 150ms fade, 1-second minimum recording, lock target (40dp x 64dp) for hands-free recording | Swipe left past -200dp threshold, 1000ms hold to start. No lock mechanism | **MEDIUM** |
| **Voice recording lock** | Slide UP to lock into hands-free recording mode (record without holding) | **MISSING** - Must hold button entire time | **HIGH** |
| **Sticker suggestions** | 90dp horizontal RecyclerView showing relevant stickers as you type | **MISSING** - No sticker support | **LOW** (stickers not critical) |
| **Edit mode** | "Edit message" title + 20dp thumbnail + checkmark send button | Exists: "Edit message" label + close button + check confirm | **OK** |
| **Link preview in compose** | Preview appears above input with close button, loading spinner during fetch | Exists: LinkPreviewCard with compact layout, cancel button, debounced fetch | **OK** |
| **Attachment picker** | 6-type grid: Gallery, File, Payment, Contact, Location, Poll + recent photos horizontal strip | Exists: AttachmentOptions menu. Missing: recent photos strip, contact/location/poll | **MEDIUM** |
| **Emoji button position** | Left side of compose field with 12dp padding | Left side when collapsed, toggle when expanded | **OK** |
| **Send/Attach toggle** | Animated 40dp button toggles between attach (+) and send arrow based on text content | Similar: Send button appears when text present, attach when empty | **OK** |

#### Specific Fixes Needed

1. **Add voice recording lock** - Slide up to lock into hands-free recording mode
2. **Add recent photos strip** - Horizontal strip of recent photos above attachment picker
3. **Inline camera button** - Always-visible camera shortcut in compose bar (not just in attachment editor)

---

### 1.4 Reactions

#### What Signal Does Well

| Feature | Signal Implementation | Homebase Current State | Gap Severity |
|---------|----------------------|----------------------|--------------|
| **Quick reaction bar** | 7 emoji (6 default + custom emoji selector). 320dp wide, 136dp tall scrubber. Each emoji 32dp x 48dp | 6 default emoji + "More" button. Horizontal scroll | **OK** |
| **Reaction selection animation** | Selected scales to 1.5x with upward translation over 200ms (DecelerateInterpolator). Overshoot: 1.2f -> 1.8f -> 1.2f | Scale animation on count change (spring) | **MEDIUM** |
| **Reaction pills on bubbles** | 26dp height, 1000dp corner radius (fully round), emoji 17dp, `surfaceVariant` bg with 1dp `background` stroke. Own reaction has distinct background | Surface with 16dp radius, surfaceContainerHigh bg. No stroke. No distinct "my reaction" styling | **MEDIUM** |
| **Reaction positioning** | -4dp overlap with bubble bottom. Aligned to bubble end (sent) or start (received) | 4dp padding below bubble. Bottom-start for sent, bottom-end for received | **LOW** |
| **Custom quick reactions** | Users can customize the 7 quick-react emojis | **MISSING** - Fixed set only | **LOW** |
| **Long-press + drag to react** | Scrubber appears on long-press, drag finger to select emoji without lifting | **MISSING** - Separate tap actions | **MEDIUM** |

#### Specific Fixes Needed

1. **Add "my reaction" distinct styling** - Different background color when user has reacted
2. **Add border/stroke on reaction pills** - 1dp stroke for better visual separation
3. **Improve reaction selection animation** - Scale + overshoot animation on selection

---

### 1.5 Media Display

#### What Signal Does Well

| Feature | Signal Implementation | Homebase Current State | Gap Severity |
|---------|----------------------|----------------------|--------------|
| **Audio waveform** | `WaveFormSeekBarView` with played/unplayed color distinction. Play/pause Lottie animation at 2x. Duration in MM:ss | Waveform exists (2dp bar width). No Lottie animation | **MEDIUM** |
| **Media bubble sizing** | Default 210dp, min solo 150dp, min with content 240dp, max width 240dp, max height 320dp (condensed 150dp) | Min height 100dp, max height 320dp, max width 240dp. Similar | **OK** |
| **Image viewer** | `MediaPreviewV2Activity` with subsampling for large images, media rail/thumbnail strip for navigation, caption overlay | FullScreenMediaViewer with pinch zoom, multi-image swipe, timestamp. No media rail, no captions | **MEDIUM** |
| **Sticker dimensions** | 175dp dedicated sticker rendering | **MISSING** - No sticker support | **LOW** |
| **GIF support** | Giphy-powered picker with MP4 playback for efficiency | **MISSING** - No GIF picker or inline GIF playback | **MEDIUM** |
| **Video trimming** | `VideoTrimTransform` for trimming before send | **MISSING** | **LOW** |
| **Face blur in editor** | Image editor with face detection and blur tool | Image editor has crop + freehand draw. No blur/face detection | **MEDIUM** |
| **Text overlay on images** | Image editor supports text overlay with fonts/colors | **MISSING** - Draw only, no text | **MEDIUM** |

#### Specific Fixes Needed

1. **Add media rail to fullscreen viewer** - Thumbnail strip for navigating between media
2. **Add GIF picker** - Integrate Giphy or Tenor for GIF search/send
3. **Enhance image editor** - Add text overlay and blur tools
4. **Add caption support** - Allow text captions on media messages

---

### 1.6 Theming & Appearance

#### What Signal Does Well

| Feature | Signal Implementation | Homebase Current State | Gap Severity |
|---------|----------------------|----------------------|--------------|
| **Chat wallpapers** | 12 solid colors + 9 gradients + custom photo. Per-conversation override. Dark mode dim (20% overlay). Auto-adjusts chat colors for readability | **MISSING** (in-progress worktree exists) | **HIGH** |
| **Bubble color customization** | 13 solid colors + 9 gradient options. Per-conversation override. Names: Ultramarine, Crimson, Vermilion, Burlap, Forest, Wintergreen, Teal, Blue, Indigo, Violet, Plum, Taupe, Steel | Fixed blue for sent bubbles. No customization | **HIGH** |
| **Message font size** | Configurable via appearance settings with multiple size options | **MISSING** - Fixed font sizes only | **HIGH** |
| **App icon customization** | Multiple icon options (Android 26+) | **MISSING** | **LOW** |
| **Navigation bar size** | Compact vs normal toggle | **MISSING** | **LOW** |
| **Wallpaper-aware UI** | Nearly every UI component checks `hasWallpaper()` and adjusts colors | No wallpaper = no awareness needed yet | **FUTURE** |
| **Dark mode** | Full Material3 dark theme with 17-shade grey scale | Exists: Dark/Light/System toggle. Functional | **OK** |
| **Language selection** | System + multiple languages | Exists: System/English US/English GB/Danish | **OK** |

#### Signal's Color Palette (for reference)

**Bubble Colors (Solids):**
| Name | Hex | Preview |
|------|-----|---------|
| Ultramarine (default) | #315FF4 | Blue |
| Crimson | #CF163E | Red |
| Vermilion | #C73F0A | Orange-red |
| Burlap | #6F6A58 | Brown-grey |
| Forest | #3B7845 | Green |
| Wintergreen | #1D8663 | Teal-green |
| Teal | #077D92 | Cyan |
| Blue | #336BA3 | Medium blue |
| Indigo | #6058CA | Purple-blue |
| Violet | #9932CB | Purple |
| Plum | #AA377A | Pink-purple |
| Taupe | #8F616A | Mauve |
| Steel | #71717F | Grey |

**Bubble Colors (Gradients):**
| Name | Colors | Angle |
|------|--------|-------|
| Ember | #E57C00 -> #5E0000 | 168deg |
| Midnight | #2C2C3A -> #787891 | 180deg |
| Infrared | #F65560 -> #442CED | 192deg |
| Lagoon | #004066 -> #32867D | 180deg |
| Fluorescent | #EC13DD -> #1B36C6 | 192deg |
| Basil | #2F9373 -> #077343 | 180deg |
| Sublime | #6281D5 -> #974460 | 180deg |
| Sea | #498FD4 -> #2C66A0 | 180deg |
| Tangerine | #DB7133 -> #911231 | 192deg |

**Wallpaper Colors (Solids):** Blush, Copper, Dust, Celadon, Rainforest, Pacific, Frost, Navy, Lilac, Pink, Eggplant, Silver

**Wallpaper Gradients:** Sunset, Noir, Heatmap, Aqua, Iridescent, Monstera, Bliss, Sky, Peach

#### Specific Fixes Needed

1. **Implement chat wallpapers** - Solid colors + gradients + custom photo with per-conversation override
2. **Implement bubble color customization** - At least 13 solid colors + gradients
3. **Add message font size setting** - Small/Normal/Large/Extra Large options
4. **Add wallpaper-aware color system** - Auto-adjust text/bubble colors based on wallpaper

---

### 1.7 Animations & Micro-interactions

#### What Signal Does Well

| Feature | Signal Implementation | Homebase Current State | Gap Severity |
|---------|----------------------|----------------------|--------------|
| **Typing indicator** | 3-dot wave animation: 1500ms cycle, 600ms/dot, staggered (0ms/150ms/300ms). Min alpha 0.4, min scale 0.75 | **MISSING** entirely | **CRITICAL** |
| **Swipe-to-reply** | 64dp trigger, 96dp max. Reply icon scales 1.0->1.2->1.8 overshoot over 200ms. 10ms haptic vibration | 72dp trigger, 100dp max. Icon scales 0.5->1.0. LongPress haptic | **OK** (minor polish) |
| **Long-press on message** | Message snapshot shrinks to 0.95x with 100ms delay. Reaction scrubber + menu appear simultaneously. 20f elevation | Spring-based scale 1.0->0.94. Menu appears. No reaction scrubber alongside | **MEDIUM** |
| **Send button animation** | Animated toggle between attach and send states | No animation on toggle | **LOW** |
| **Delivery status rotation** | Pending icon rotates 360deg over 1500ms infinitely | Alarm icon (static) for pending | **MEDIUM** |
| **Reaction overshoot** | 1.2f -> 1.8f -> 1.2f over 200ms on selection | Spring scale on count change only | **LOW** |
| **Shimmer/skeleton loading** | Not explicitly found in Signal either | Not present | **N/A** |
| **Pull-to-filter haptic** | Haptic feedback on state transitions during pull gesture | No pull gesture | **MEDIUM** |
| **Quote reveal** | 150ms animation when scrolling to quoted message | Click scrolls to message (no highlight animation) | **MEDIUM** |
| **Search highlight pulse** | Jump-and-pulse scroll strategy for found messages | Orange/yellow background highlight on found text. No pulse | **LOW** |

#### Specific Fixes Needed

1. **Implement typing indicator** - 3-dot wave animation with staggered timing
2. **Add pending message rotation** - Spinning indicator for messages being sent
3. **Add quote reveal animation** - Highlight/pulse when scrolling to quoted message
4. **Polish long-press** - Show reaction bar alongside context menu

---

### 1.8 Profile & Group Screens

#### What Signal Does Well

| Feature | Signal Implementation | Homebase Current State | Gap Severity |
|---------|----------------------|----------------------|--------------|
| **Conversation info screen** | Rich layout: header with avatar + story ring, action buttons (message, video call, audio call, mute, search), disappearing messages, chat color/wallpaper, sounds, shared media preview, starred messages, members, group link, permissions, danger zone | Minimal: AvatarNameDisplay + contact info navigation for 1:1, GroupSettingsScreen with members + actions for groups | **HIGH** |
| **Shared media tabs** | 3 tabs: Media grid, Documents list, Links list. Sort controls. Grid/list toggle | **MISSING** - No shared media gallery view | **CRITICAL** |
| **Action button row** | Horizontal row of circular icon buttons (Message, Video, Audio, Mute, Search) below profile header | **MISSING** - No quick action buttons | **HIGH** |
| **Disappearing messages** | Timer toggle with duration picker (various intervals) | **MISSING** | **MEDIUM** |
| **Group link sharing** | Shareable group invite link with QR code | **MISSING** | **MEDIUM** |
| **Starred/saved messages** | Section in conversation info showing starred messages | **MISSING** | **LOW** |
| **Group description** | Editable group description field | **MISSING** - Only group name | **MEDIUM** |
| **Member labels** | Admin badges, custom member labels | Admin badge exists. No custom labels | **LOW** |
| **Profile bio/about** | Editable about/bio text on user profile | **MISSING** - Only name + domain displayed | **MEDIUM** |
| **Username system** | Separate username with sharing bottom sheet | Domain-based identity (different approach) | **N/A** |
| **Safety number verification** | QR code + number comparison for contact verification | **MISSING** | **LOW** |

#### Specific Fixes Needed

1. **Rebuild conversation info screen** - Add action buttons, shared media, disappearing messages, chat appearance
2. **Add shared media gallery** - 3-tab layout: Media grid, Documents, Links
3. **Add action button row** - Quick action circular buttons in conversation info header
4. **Add group description** - Editable description field in group settings

---

### 1.9 Accessibility

| Feature | Signal Implementation | Homebase Current State | Gap Severity |
|---------|----------------------|----------------------|--------------|
| **Content descriptions** | On most interactive elements. Known gaps exist | ~20+ files with contentDescription. Basic coverage | **OK** |
| **Font scaling** | Message font size configurable in settings. All text uses `sp` units | Uses sp units but no font size setting | **MEDIUM** |
| **Screen reader support** | `contentDescValue` separate from display text for dates/timestamps | Basic contentDescription only | **LOW** |
| **High contrast** | Material3 handles some automatically | Material3 handles some automatically | **OK** |
| **Semantics modifiers** | Basic usage | Minimal usage | **LOW** |

---

## Part 2: Missing Features

### 2.1 CRITICAL - Users Will Leave Without These

| # | Feature | Signal Has It? | Effort Estimate | Impact |
|---|---------|---------------|-----------------|--------|
| 1 | **Typing indicators** | Yes (3-dot animation in conversation + replaces snippet in list) | Medium | Users feel the app is "dead" without this. Most basic real-time feature |
| 2 | **Message clustering** | Yes (3-min window, 4-state corners, 1dp vs 6dp spacing) | Medium | Without this, conversations look amateur and wasteful of space |
| 3 | **Shared media gallery** | Yes (3-tab: Media/Documents/Links in conversation info) | Medium | Users can't find photos/files shared in conversations |
| 4 | **Chat wallpapers & bubble colors** | Yes (12 solids + 9 gradients + photo wallpapers, 13+9 bubble colors) | Large | Personalization is #1 reason users "love" a chat app |
| 5 | **Swipe actions on conversation list** | Yes (swipe to archive with undo) | Small | Missing this makes the app feel less fluid than competitors |

### 2.2 HIGH PRIORITY - Users Expect These

| # | Feature | Signal Has It? | Effort Estimate | Impact |
|---|---------|---------------|-----------------|--------|
| 6 | **Received message avatars** | Yes (28dp at bottom-left of last message in cluster) | Small | Group chats look confusing without avatars next to messages |
| 7 | **Multi-select messages** | Yes (long-press to enter, bulk delete/forward/copy) | Medium | Can't bulk-manage messages |
| 8 | **Draft indicator in list** | Yes ("Draft:" prefix in snippet) | Small | Users lose unsent messages when switching conversations |
| 9 | **Muted/Pinned indicators** | Yes (bell-disabled + pin icons on conversation list items) | Small | Users can't see which conversations they've organized |
| 10 | **Conversation info redesign** | Yes (action buttons, shared media, disappearing msgs, appearance) | Large | Current info screen is bare-bones |
| 11 | **Message font size setting** | Yes (configurable in appearance settings) | Small | Accessibility requirement for many users |
| 12 | **Voice recording lock** | Yes (slide up to lock for hands-free recording) | Medium | Long voice messages are painful without this |
| 13 | **Search within conversation** | Yes (with jump-and-pulse highlight) | Medium | Partially exists but needs polish |
| 14 | **GIF picker** | Yes (Giphy-powered with MP4 playback) | Medium | Expected by younger users |
| 15 | **Contact/location sharing** | Yes (6 attachment types including contact + location) | Medium | Basic messaging features |

### 2.3 MEDIUM PRIORITY - Delight Features

| # | Feature | Signal Has It? | Effort Estimate | Impact |
|---|---------|---------------|-----------------|--------|
| 16 | **Disappearing messages** | Yes (timer with duration picker) | Large | Privacy-conscious users want this |
| 17 | **Image editor enhancements** | Yes (blur faces, text overlay, stickers) | Medium | Current editor: crop + draw only |
| 18 | **Media captions** | Yes (text captions on images/videos) | Small | Common feature in chat apps |
| 19 | **Chat folders** | Yes (horizontal scrollable folder tabs) | Medium | Organization for power users |
| 20 | **Reaction customization** | Yes (customize 7 quick-react emojis) | Small | Personalization |
| 21 | **Group description** | Yes (editable description field) | Small | Context for group purpose |
| 22 | **Forwarded message indicator** | Yes ("Forwarded" label on messages) | Small | Transparency about message origin |
| 23 | **Quote reveal animation** | Yes (150ms animation + pulse) | Small | Polish when tapping quoted messages |
| 24 | **Media rail in viewer** | Yes (thumbnail strip for navigating media) | Medium | Better media browsing experience |
| 25 | **Recent photos in attachment picker** | Yes (horizontal strip of recent photos) | Medium | Faster photo sharing |
| 26 | **Pull-to-refresh** | Not in Signal (uses pull-to-filter) | Small | Standard mobile pattern |
| 27 | **Group invite links** | Yes (shareable link + QR code) | Medium | Easier group onboarding |
| 28 | **Profile bio/about** | Yes (editable about text) | Small | Social presence |
| 29 | **Pending send animation** | Yes (rotating icon, 360deg/1500ms) | Small | Visual feedback for sending state |
| 30 | **2-line snippet in list** | Yes (max 2 lines for message preview) | Small | More context in conversation list |

### 2.4 NICE-TO-HAVE - Future Roadmap

| # | Feature | Signal Has It? | Effort Estimate | Impact |
|---|---------|---------------|-----------------|--------|
| 31 | **Stickers** | Yes (blessed packs + installable + search) | Large | Fun but not critical |
| 32 | **Payment integration** | Yes (MobileCoin payments) | Very Large | Different use case |
| 33 | **App icon customization** | Yes (Android 26+) | Small | Minor personalization |
| 34 | **Video trimming** | Yes (before send) | Medium | Nice for video sharing |
| 35 | **Safety number verification** | Yes (QR + number comparison) | Medium | Security feature |
| 36 | **Notification profiles** | Yes (custom notification schedules) | Medium | Power user feature |
| 37 | **Message scheduling** | No (Signal doesn't have this either) | Medium | Could be a differentiator |
| 38 | **Message pinning in chat** | No (Signal doesn't have this) | Medium | Could be a differentiator |
| 39 | **Starred/saved messages** | Yes | Medium | Bookmarking feature |

---

## Part 3: Implementation Checkpoints

### Phase 1: "Make It Feel Alive" (2-3 weeks)
*Goal: The app should feel responsive and real-time*

- [ ] **CP-1.1**: Typing indicators - protocol + UI (conversation + list)
- [ ] **CP-1.2**: Message clustering (3-min window, 4-state corners, spacing)
- [ ] **CP-1.3**: Received message avatars (28dp, bottom-left of cluster)
- [ ] **CP-1.4**: Draft indicator in conversation list
- [ ] **CP-1.5**: Muted icon + Pinned icon on conversation list items
- [ ] **CP-1.6**: Pending send rotating animation
- [ ] **CP-1.7**: 2-line snippet in conversation list

**Checkpoint validation**: Open any conversation with active participants. You should see typing dots, clustered bubbles with avatars, and the list should show drafts/pins/mutes.

### Phase 2: "Make It Beautiful" (3-4 weeks)
*Goal: Personalization and visual polish*

- [ ] **CP-2.1**: Chat wallpapers (12 solids + 9 gradients + custom photo)
- [ ] **CP-2.2**: Bubble color customization (13 solids + 9 gradients)
- [ ] **CP-2.3**: Per-conversation wallpaper/color override
- [ ] **CP-2.4**: Wallpaper-aware color system (auto-adjust text/footer colors)
- [ ] **CP-2.5**: Message font size setting (Small/Normal/Large/Extra Large)
- [ ] **CP-2.6**: Dark mode wallpaper dim overlay (20% opacity)
- [ ] **CP-2.7**: Forwarded message indicator

**Checkpoint validation**: Set different wallpapers and bubble colors on 3 conversations. Text should be readable on all combinations. Font size change should apply everywhere.

### Phase 3: "Make It Powerful" (3-4 weeks)
*Goal: Features that power users need*

- [ ] **CP-3.1**: Shared media gallery (3 tabs: Media/Documents/Links)
- [ ] **CP-3.2**: Conversation info screen redesign (action buttons, sections)
- [ ] **CP-3.3**: Multi-select messages (long-press to enter, bulk operations)
- [ ] **CP-3.4**: Swipe-to-archive on conversation list
- [ ] **CP-3.5**: Voice recording lock (slide up for hands-free)
- [ ] **CP-3.6**: GIF picker integration (Giphy/Tenor)
- [ ] **CP-3.7**: Multi-select on conversation list (bulk pin/mute/archive/delete)

**Checkpoint validation**: Open conversation info — see shared media grid, action buttons. Long-press message — enter multi-select. Swipe conversation in list — archives with undo.

### Phase 4: "Make It Complete" (4-6 weeks)
*Goal: Feature parity on remaining gaps*

- [ ] **CP-4.1**: Disappearing messages (timer + duration picker)
- [ ] **CP-4.2**: Image editor enhancements (text overlay, blur, stickers on images)
- [ ] **CP-4.3**: Media captions
- [ ] **CP-4.4**: Chat folders (horizontal scrollable tabs)
- [ ] **CP-4.5**: Recent photos strip in attachment picker
- [ ] **CP-4.6**: Group description field
- [ ] **CP-4.7**: Group invite links with QR code
- [ ] **CP-4.8**: Profile bio/about editing
- [ ] **CP-4.9**: Contact sharing (vCard)
- [ ] **CP-4.10**: Location sharing
- [ ] **CP-4.11**: Reaction quick-emoji customization

**Checkpoint validation**: Full feature walkthrough matching Signal's conversation info screen capabilities. Group creation with description + invite link. Attachment picker shows all 6+ types.

---

## Appendix A: Signal Dimensions Quick Reference

| Element | Value |
|---------|-------|
| Bubble corner radius (large) | 18dp |
| Bubble corner radius (collapsed) | 4dp |
| Bubble horizontal padding | 12dp |
| Bubble top/bottom padding | 7dp |
| Bubble edge margin | 32dp |
| Cluster message spacing | 1dp |
| Isolated message spacing | 6dp |
| Cluster time window | 3 minutes |
| List item min height | 84dp |
| List avatar size | 48dp |
| Message avatar size | 28dp |
| Unread badge radius | 12.5dp |
| Swipe-to-reply trigger | 64dp |
| Swipe-to-reply max | 96dp |
| Reaction scrubber width | 320dp |
| Reaction pill height | 26dp |
| Typing dot cycle | 1500ms |
| Typing dot duration | 600ms |
| Scroll-to-bottom FAB | 36dp |
| Compose field height | 44dp |
| Audio message width | 212dp |

## Appendix B: Signal Color Quick Reference

| Role | Light | Dark |
|------|-------|------|
| Primary (Ultramarine) | #2C6BED | #6191F3 |
| Surface | #FBFCFF | (dark equivalent) |
| OnSurface | #1B1B1D | (light equivalent) |
| OnSurfaceVariant | #545863 | (light equivalent) |
| Sent bubble default | #315FF4 | #315FF4 |
| Success (green) | #4CAF50 | #4CAF50 |
| Warning (yellow) | #FFD624 | #FFD624 |
| Error (red) | #F44336 | #F44336 |

## Appendix C: What Homebase Does BETTER Than Signal

Not everything is a gap. Homebase has advantages too:

| Feature | Homebase Advantage |
|---------|-------------------|
| **Cross-platform** | True KMP: Android, iOS, Desktop, Web. Signal is Android/iOS/Desktop (Electron) only |
| **Rich text editing** | Full RichTextEditor with markdown (bold, italic, underline, strikethrough, lists). Signal has basic formatting |
| **Adaptive layout** | ListDetailPaneScaffold with responsive 2-pane layout at 800dp+. Signal has separate tablet handling |
| **Image editor** | Crop + freehand draw with color picker, rotation dial, aspect lock. Signal's is more feature-rich but Homebase's is well-built |
| **Custom event messages** | Event type (dataType=210) with RSVP. Signal doesn't have this |
| **Dice roll messages** | Fun dice roll type (dataType=212). Unique feature |
| **Compose Multiplatform** | Modern declarative UI shared across all platforms. Signal uses legacy Views on Android |
| **Desktop first-class** | Full desktop app with hot reload, VLC video, native feel. Signal Desktop is Electron |
| **Introduction system** | Can introduce contacts to each other. Signal doesn't have this |

---

*End of audit. This document should be treated as the source of truth for UI/UX priorities until the listed checkpoints are completed.*
