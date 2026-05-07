# Adding a New Typed Message Kind

This guide shows how to add a new "typed message kind" — a self-contained chat
message whose full descriptor rides on the message header (no payloads), with
its own bubble UI and composer. Examples in tree:

- **Event** (`dataType = 210`) — `homebase-chat/src/commonMain/kotlin/id/homebase/chat/event/`
- **DiceRoll** (`dataType = 212`) — `homebase-chat/src/commonMain/kotlin/id/homebase/chat/dice/`

For brevity this guide uses a hypothetical **Poll** kind throughout. Substitute
`Poll` / `poll` / `PollDescriptor` for your actual kind names.

---

## What is a typed message kind?

A chat message whose `appData.content` is a kind-specific descriptor JSON
(parsed by `MessageContentParser`) and `appData.dataType` is a reserved integer
that the receiver dispatches off. Receivers render a custom bubble for the
kind. No payloads — the descriptor must fit in `appData.content` (capped by
`ChatProtocol.MaxHeaderContentBytes = 7000` bytes).

### When to use a typed kind vs. payload-attached vs. an add-on app

| If you want… | Build it as… |
|---|---|
| A self-contained content card (event, poll, dice, sticker) where the descriptor fits in <7 KB JSON, with no separate screen | **Typed message kind** (this doc) |
| A message with binary attachments (photo, video, document) — alongside text | **Payload-attached message** (use `sendNewMessage` + `PayloadRenderer`) |
| A whole new feature with its own bottom-bar icon and onboarding flow | **Add-on app** (`ADDING_ADDON_APPS.md`) |

Location is the awkward middle case: it has a header `dataType` (211) for
server-side queryability *and* a payload (`chat_loc`) for the descriptor + map
image. New typed kinds should not follow Location's pattern — pick a side.

---

## Anatomy — file/folder layout

```
homebase-chat/src/commonMain/kotlin/id/homebase/chat/poll/
    PollDescriptor.kt           — @Serializable wire format
    PollPreferences.kt          — (optional) keyValue-backed last-choice persistence
    PollComposerSheet.kt        — fullscreen Dialog composer
    PollBubble.kt               — in-stream bubble (handles null descriptor)
homebase-chat/src/commonTest/kotlin/id/homebase/chat/poll/
    PollDescriptorTest.kt       — descriptor-bound tests
```

Plus modifications to existing files (see Steps below). Class names are
load-bearing for Koin's `singleOf(::PollPreferences)` — renaming requires an
`AppModule.kt` update.

---

## Step 1 — Reserve a `dataType` integer

`homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/ChatProtocol.kt`.
Pick the next free integer above the current maximum (212). **Don't** reuse
211 — that's Location, which is `MessageAppData`-shaped, not typed-descriptor-
shaped.

```kotlin
const val ChatPollMessageDataType = 213
```

Add a one-line kdoc explaining what the kind is. Bump the comment block at the
top of the file (currently around lines 25-30) to mention the new kind so the
reservation is documented in the protocol's "kinds" preamble.

---

## Step 2 — Define the wire descriptor

`@Serializable data class PollDescriptor(...)` in
`homebase-chat/src/commonMain/kotlin/id/homebase/chat/poll/PollDescriptor.kt`.
Reference `EventDescriptor.kt` and `DiceRollDescriptor.kt` for canonical
shapes. Required pieces:

- All wire fields, with sensible defaults so additive changes don't break
  older clients.
- `schemaVersion: Int = 1` — forward-compat marker. Not currently inspected
  by anything in tree, but bump it on a breaking shape change so a future
  parser can route old payloads.
- A summary helper (e.g. `summaryLine(): String`) returning a one-liner for
  the conversation-list preview / push notification text. Keep it ≤80 chars;
  this becomes `displayLabel`.
- `isValid(): Boolean` — bounds-check every field (allowed enum values, list
  sizes, string lengths). The parser will treat invalid descriptors the same
  as malformed JSON (renders the unparseable chip), so be strict here.
  Reference `DiceRollDescriptor.isValid()` as the canonical example.

---

## Step 3 — Add to `MessageContent`

`homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/content/MessageContent.kt`.
Add a sealed-interface subtype:

```kotlin
data class Poll(val descriptor: PollDescriptor?) : MessageContent {
    override val displayLabel: String get() = descriptor?.summaryLine() ?: UNPARSEABLE_POLL_LABEL
}
```

And add `UNPARSEABLE_POLL_LABEL = "Poll"` to the existing companion object
alongside `UNPARSEABLE_EVENT_LABEL` and `UNPARSEABLE_DICE_LABEL`.

**Important:**

- `descriptor` **must be nullable**. The receiver-side parse-failure branch
  returns `Poll(descriptor = null)` so the bubble can render the unparseable
  chip. Sender-side construction always supplies a non-null descriptor;
  `MessageContentParser.serialize` enforces that with `requireNotNull`.
- `displayLabel` runs outside Compose — it's a plain Kotlin getter consumed
  by the conversation-list preview, push notification text, and search
  index. Use a hardcoded English fallback constant. The bubble itself uses
  the localized `chat_poll_unparseable` resource (Step 8).

---

## Step 4 — Choose an `ActionPolicy`

Every `MessageContent` subtype carries an `ActionPolicy` that controls what
the long-press menu shows. The dispatcher reads it once
(`MessageItem.kt:49`) and conditionally wires callbacks — there are *no*
hard-coded `if (is X)` checks anywhere in the menu code, so this is the
single point where you decide what users can do with your message.

The six flags:

| Flag | Controls |
|---|---|
| `allowEdit` | "Edit" entry — edits route through `ChatMessageSenderService.updateMessage` |
| `allowReply` | "Reply" entry — sets reply-to on the composer |
| `allowForward` | "Forward to…" entry — opens the recipient picker |
| `allowShare` | "Share" / copy text |
| `allowInlineReactions` | Emoji quick-strip on long-press + "Add reaction" hover icon |
| `allowReactionDetails` | "Show all reactions" — per-emoji reactor breakdown sheet |

Two presets in the `MessageContent` companion:

- **`Standard`** — all six true. Used by plain text + media messages (the
  `messageContent == null` fall-through in `MessageItem.kt:49`).
- **`StructuredOneShot`** — all six false. The default for typed kinds.

### How to override

```kotlin
data class Poll(val descriptor: PollDescriptor?) : MessageContent {
    override val actions: ActionPolicy = ActionPolicy(
        allowEdit = false,           // poll question is immutable
        allowReply = false,          // replies confuse vote tallies
        allowForward = false,        // votes are conversation-scoped
        allowShare = false,
        allowInlineReactions = true, // votes ARE reactions
        allowReactionDetails = true, // "who voted what" matters
    )
    override val displayLabel: String get() = descriptor?.summaryLine() ?: UNPARSEABLE_POLL_LABEL
}
```

Document the *why* for each non-default flag in a comment — the next person
to read this needs to understand the product reasoning.

### What each existing kind does, and why

- **Event** — `StructuredOneShot`. RSVP happens via reactions, but inside the
  bubble's own detail dialog (`EventBubble.kt`), not via the long-press menu.
  The detail dialog bypasses `ActionPolicy` entirely; the policy is only
  about the long-press surface.
- **DiceRoll** — `StructuredOneShot`. Rolls are immutable historical records
  — no reactions, no edit, no reply.
- **Plain text + media** — falls back to `Standard` because
  `messageContent == null`.

### Pattern: in-bubble interactions vs. long-press menu

If your kind needs a custom interaction (RSVP, vote, react with constraints),
prefer the **Event approach**: handle it in the bubble itself with a
tap-to-open detail dialog, and keep `ActionPolicy.StructuredOneShot` so the
long-press menu stays clean. Don't try to expose your interaction through the
standard "react" or "reply" flows — the policy flags are coarse and you'll
fight the dispatcher.

---

## Step 5 — Wire the parser

`homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/content/MessageContentParser.kt`.
Three branches:

```kotlin
// In parse(...), add a case to the existing when:
ChatProtocol.ChatPollMessageDataType -> parsePoll(content)

// New private helper, mirroring parseEvent / parseDiceRoll:
private fun parsePoll(content: String): MessageContent.Poll = try {
    val descriptor = OdinSystemSerializer.deserialize<PollDescriptor>(content)
    if (descriptor.isValid()) {
        MessageContent.Poll(descriptor)
    } else {
        Logger.w(tag = TAG) { "Poll descriptor failed validation" }
        MessageContent.Poll(descriptor = null)
    }
} catch (e: Exception) {
    Logger.w(tag = TAG, throwable = e) { "Poll parse failed; rendering chip" }
    MessageContent.Poll(descriptor = null)
}

// In serialize(...):
is MessageContent.Poll ->
    OdinSystemSerializer.serialize(
        requireNotNull(content.descriptor) { "Poll descriptor must be non-null on send" }
    )

// In dataTypeFor(...):
is MessageContent.Poll -> ChatProtocol.ChatPollMessageDataType
```

### The two big traps

- **Never return `null` from `parsePoll` on failure.** Returning `null`
  routes the message into `ChatMessageStream`'s `MessageAppData`-fallback
  path; the descriptor JSON fails to deserialize as `MessageAppData`; the
  message vanishes from the stream. Always return `Poll(descriptor = null)`
  on parse or validation failure — the bubble renders the unparseable chip.
- **Don't add the new dataType to the `null` allowlist** (`{0, 202, 211}`).
  That allowlist is for kinds whose `appData.content` is a `MessageAppData`
  (plain text/media, status, location). Typed descriptor kinds go in the
  parsing `when` arms.

---

## Step 6 — Add the bubble

`homebase-chat/src/commonMain/kotlin/id/homebase/chat/poll/PollBubble.kt`.
Signature:

```kotlin
@Composable
fun PollBubble(descriptor: PollDescriptor?, modifier: Modifier = Modifier) {
    if (descriptor == null) {
        UnparseablePollChip(modifier)
        return
    }
    // … render the full UI from descriptor
}
```

The unparseable chip handles the `descriptor == null` branch — render a
tonal-surface row with an icon and `stringResource(MR.string.chat_poll_unparseable)`.
Mirror `EventBubble`'s `UnparseableEventBubble` private composable
(`EventBubble.kt`) or `DiceRollBubble`'s null branch
(`DiceRollBubble.kt`).

Then dispatch in
`homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageBubbleRaw.kt`:

```kotlin
is MessageContent.Poll -> {
    PollBubble(descriptor = content.descriptor, modifier = modifier)
    return
}
```

---

## Step 7 — Composer + attachment-sheet entry

### Composer

`PollComposerSheet(conversationId, onDismiss, onSent)` — fullscreen `Dialog`
mirroring `EventComposerSheet.kt` / `DiceRollComposerSheet.kt`. Inside, use
Koin to get `ChatMessageSenderService`:

```kotlin
val sender: ChatMessageSenderService = koinInject()
…
sender.sendNewTypedMessage(
    messageUniqueId = Uuid.random(),
    conversationId = conversationId,
    content = MessageContent.Poll(descriptor),
    previousMessageUniqueId = null,
)
```

`sendNewTypedMessage` always passes `payloadBundle = null` and derives the
header `dataType` from `MessageContentParser.dataTypeFor(content)`. You don't
plumb either through manually.

### Attachment sheet

`homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/AttachmentOptions.kt`:
add `onPollClick: () -> Unit` to `AttachmentOptions`'s parameter list, and
render a new `AttachmentOption` entry with a Material icon, the
`chat_poll_share` label, and `testTag("attachment_poll")`.

Update `homebase-chat/src/commonTest/kotlin/id/homebase/chat/widget/AttachmentOptionsTest.kt`
— the three `setSpec` blocks each call `AttachmentOptions(...)` and need
`onPollClick = {}` added.

### ConversationContent wiring

`homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationContent.kt`:
mirror the `showEventComposer` / `showDiceRollComposer` pattern.

```kotlin
var showPollComposer by remember { mutableStateOf(false) }
…
AttachmentOptions(
    …
    onPollClick = {
        showAttachmentSheet = false
        showPollComposer = true
    },
)
…
if (showPollComposer) {
    PollComposerSheet(
        conversationId = conversation.conversation.id,
        onDismiss = { showPollComposer = false },
        onSent = { showPollComposer = false },
    )
}
```

---

## Step 8 — Strings

`homebase-common/src/commonMain/composeResources/values/strings.xml`. Minimum
set:

- `chat_poll_share` — attachment-sheet label.
- `chat_poll_composer_title` — composer title bar.
- `chat_poll_unparseable` — bubble's null-descriptor chip. Use the existing
  pattern: `"Unable to display this poll. Please update the app."`.
- Whatever kind-specific labels the composer needs.

English-only is the norm — the existing `chat_event_*` and `chat_dice_*`
families don't have Danish translations and CI is fine with that.

---

## Step 9 — DI (only if you have kind-specific singletons)

`homebase-core/src/commonMain/kotlin/id/homebase/core/di/AppModule.kt`.
Register kind-specific singletons:

```kotlin
singleOf(::PollPreferences)
```

Platform actuals (sensors, etc.) live in each `platformModule()` — see
`AppModule.android.kt` / `AppModule.native.kt` / `AppModule.desktop.kt`.

Most typed kinds need nothing here.

---

## Step 10 — Tests

- `PollDescriptorTest.kt`:
  - `isValid()` boundary cases (allowed enum values, list size limits,
    string length caps).
  - Round-trip via `MessageContentParser.serialize` → `parse` (asserts the
    descriptor survives the OdinSystemSerializer round trip).
  - Parse-failure path: hand-craft an invalid descriptor JSON, parse it,
    assert the result is `MessageContent.Poll(descriptor = null)` — **not**
    `null`. The displayLabel should be `UNPARSEABLE_POLL_LABEL`.
- **Don't** add parser-routing tests for a new kind. Those live in
  `homebase-chat/src/commonTest/kotlin/id/homebase/chat/services/content/MessageContentParserTest.kt`
  and stay kind-agnostic. Just confirm your descriptor round-trips.
- Update `AttachmentOptionsTest.kt` callsites to pass `onPollClick = {}`.

---

## Things that happen automatically (don't touch them)

### Defragmenter probe

The Defragmenter (`DefragmenterViewModel` + `LiveDefragSource` in
`homebase-core`) runs a probe over every chat-message file's
`appData.content` to decide whether the row is salvageable. The probe lives
in `homebase-core/src/commonMain/kotlin/id/homebase/core/di/AppModule.kt`
(the `decodeMessageContentProbe` block, currently around line 275). It has
three arms:

1. Status messages → deserialize as `StatusMessageData`.
2. **Anything `MessageContentParser.parse` recognizes → return non-null →
   "decoded".**
3. Everything else → deserialize as `MessageAppData`.

Adding your typed kind's parser branch in Step 5 *is* what wires the
Defragmenter. Concretely:

- `MessageContentParser.parse(213, validJson)` returns
  `MessageContent.Poll(descriptor)` for a valid poll → probe sees non-null →
  marks the row decoded ✓.
- `MessageContentParser.parse(213, garbage)` returns
  `MessageContent.Poll(null)` for a malformed poll → probe still sees
  non-null → marks decoded ✓ (the bubble renders the unparseable chip; the
  file isn't broken, just unrenderable).
- `MessageContentParser.parse(213, "")` returns `null` (per the early-return
  on blank content) → probe falls through to `MessageAppData.deserialize`,
  which throws → flagged as broken. This is correct: an empty-content
  typed-kind row genuinely is corrupt.

You **do not** edit the probe. Don't add `213 ->` branches to it. The probe
is generic on purpose so future kinds slot in for free. The only reason it
exists at all is because it predates the parser's "always returns non-null
for known dataTypes" contract; that contract makes the probe a one-liner
that delegates to the parser. Touching the probe is how you accidentally
re-introduce the silent-message-drop bug for unknown kinds.

If your descriptor's `isValid()` is *too* strict (rejecting messages that
older senders legitimately produce), the probe still passes — `Poll(null)`
is non-null — so the Defragmenter won't quarantine the row. But the user
will see the unparseable chip even on legitimate older messages, which is a
UX bug, not a defrag bug. Fix `isValid()` if that happens, not the probe.

### Older clients render an "update the app" chip

Receivers running an older version of the app — one that doesn't know about
your `dataType` — surface the message as `MessageContent.Unknown(N)` and
render the `UnknownMessageBubble` chip ("Unknown message type — please
update the app. (type N)"). Forward-compat is built in; you don't write
code for it. The chip is in
`homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/UnknownMessageBubble.kt`.

### `displayLabel` flows everywhere

The conversation-list preview, push notification text, and search index all
read your descriptor's summary helper via `ChatMessageStream.kt:481-482`,
which writes `messageAppData.message = JsonPrimitive(messageContent.displayLabel)`.
Make `summaryLine()` (or whatever you named it) good and you're done.

---

## Wire-up checklist (copy into PR description)

```
- [ ] ChatProtocol.kt: const ChatPollMessageDataType = 213
- [ ] PollDescriptor.kt: @Serializable wire format with isValid() + summary helper
- [ ] MessageContent.Poll(descriptor: PollDescriptor?) + UNPARSEABLE_POLL_LABEL
- [ ] ActionPolicy decided — default StructuredOneShot, override only if your kind
      needs reactions/edit/reply/forward (document the *why* in a comment)
- [ ] MessageContentParser: parsePoll() returns Poll(null) on failure (NOT null);
      serialize/dataTypeFor branches
- [ ] PollBubble.kt: handles descriptor == null with "update the app" chip
- [ ] MessageBubbleRaw.kt: dispatch branch for MessageContent.Poll
- [ ] PollComposerSheet.kt: fullscreen Dialog using sendNewTypedMessage
- [ ] AttachmentOptions.kt: onPollClick parameter + entry
- [ ] AttachmentOptionsTest.kt: pass onPollClick = {} in all setSpec blocks
- [ ] ConversationContent.kt: showPollComposer state + wire-up + sheet block
- [ ] strings.xml: chat_poll_share, chat_poll_composer_title, chat_poll_unparseable
- [ ] AppModule.kt: register kind-specific singletons (only if needed)
- [ ] PollDescriptorTest.kt: isValid + round-trip + parse-failure
- [ ] Defragmenter probe NOT touched (it's generic on purpose; Step 5's parser
      branch is what wires it)
```

---

## Known gotchas

- **`descriptor` MUST be nullable on the `MessageContent` subtype.** Every
  existing kind enforces this. The parser uses null to signal "I know it's a
  Poll but couldn't decode the JSON." The bubble renders an unparseable chip
  on null. The sender path `requireNotNull`s before serializing.
- **The 7000-byte header limit.** `ChatProtocol.MaxHeaderContentBytes`. If
  your descriptor could exceed it (e.g. embedded base64 image data), you
  need a payload-based design, not a typed kind.
- **No payloads on a typed message.** `sendNewTypedMessage` always passes
  `payloadBundle = null`. Combining typed content with attachments is not
  supported — if you need both, build it as a text + media message.
- **Don't override `actions = ActionPolicy.Standard` casually.** Polls and
  dice rolls shouldn't be edited or forwarded. The default
  `StructuredOneShot` is the right answer for almost every typed kind.
- **Don't replicate the recipe inline anywhere.** This doc is the single
  source of truth — link to it from code comments, don't restate it. The
  old kdoc at `MessageContent.kt:13-19` rotted because the recipe grew and
  the comment didn't keep up; that's why this file exists.
