# Special fields

Values that have a shared control, and the shared components that show or pick them. Use these
rather than a raw `OutlinedTextField`, `AsyncImage`, `Text` or picker; if one doesn't fit, say why
before writing another. Paths are under each module's `src/commonMain/kotlin/id/homebase/`.

## Typed values

Any UI that captures one of these — primary slot, additional/extra slots, add flows, anywhere —
uses its control and stores the canonical form.

- **Homebase IDs** (an identity's domain: sign-in, adding a contact, connecting, sharing with
  someone) are captured with `HomebaseIdField` (`homebase-common/.../widget/HomebaseIdField.kt`).
  It holds the text space-encoded and shows the spaces as dots, so mobile keyboards don't
  auto-insert periods. Keep a local `TextFieldValue` for the field and hand the view model the
  dotted form from `cleanDomain()` (`homebase-api/.../util/StringExtensions.kt`), as
  `AddContactScreen` and `ConnectRequestBottomSheet` do. Label it "Homebase ID", with the
  `add_contact_odinid_hint` placeholder (`name.homebase.id`).
- **Phone numbers** are stored as **E.164** (`+14155550123`). Capture them with
  `PhoneNumberField` (`…/contactbook/components/PhoneNumberField.kt`) — a country-code
  selector + national-number field that emits a normalized E.164 string (or `""` when
  blank). The user never types the `+` or country code. Never persist a hand-typed national
  string. The control is stateful (it seeds its country/national once and then owns them),
  so render lists of them under a **stable `key(id)`**, not the list index — index-keying
  shuffles a row's seeded state when a sibling above it is removed. See the keyed
  `DraftPhone` rows in `ContactEditSheet.kt`.
- **Email addresses** must be validated before they can be saved.
- Use `ContactFieldValidation` (`…/contactbook/ContactFieldValidation.kt`) as the single
  source of truth: `isValidPhone` (E.164), `isValidEmail`, `normalizePhone`. Validators
  treat blank as valid (fields are optional); the empty-vs-required decision is the caller's.
- **Legacy data may not be E.164.** Show it (seed it into `PhoneNumberField`, render it in
  the email field) and flag it with the field's `isError` + `errorText`, but **block Save
  until it's corrected** — gate the Save button on every phone/email being valid, primary
  *and* additional. Error strings: `contactbook_error_phone`, `contactbook_error_email`.
- **Time zones** are picked with `TimezonePickerSheet` (homebase-chat `chat/event/TimezonePicker.kt`),
  a full-screen searchable list.
- **Emoji** are picked with `EmojiSelectorDialog` or `EmojiSelectorSheet` (homebase-common
  `core/widget/EmojiSelection.kt`).
- **Search** boxes are `StyledSearchTextField` or `MinimalSearchTextField` (homebase-common
  `core/widget/StyledSearchTextField.kt`).

## People

- **Choosing people:** `RecipientPicker` (homebase-chat `chat/widget/ConversationContent.kt`) for a
  "send to" sheet; `CircleMemberPickerScreen` and `ShareContactPickerScreen` (homebase-core
  `core/ui/screens/contactbook/`) for circles and for sharing a contact.
- **A person's name:** `ContactName` (homebase-common `core/widget/ContactName.kt`), from their
  `OdinId` and the name we know, if any.
- **Avatars** (homebase-common `core/avatars/`): `OwnerAvatar` for the signed-in identity, with
  its connection and sync badge; `ConversationAvatar` and `GroupAvatar` for conversations;
  `FallbackAvatar` for initials. Chat's name-and-avatar header is `AvatarNameDisplay` (homebase-chat
  `chat/widget/AvatarNameDisplay.kt`). Initials come from `String.initials()` (see CLAUDE.md,
  "Strings & Unicode").

## Images and video

Read [`THUMBNAILS.md`](THUMBNAILS.md) for sizing, and [`UPLOADS.md`](UPLOADS.md) before changing
what a picked file becomes.

- **A drive image:** `HomebaseImage` (homebase-common `core/image/HomebaseImage.kt`) shows the
  tiny thumb at once, then the right thumbnail and the full image as they load.
- **An image on this device:** `LocalFileImage` (homebase-common `core/image/LocalFileImage.kt`),
  keyed by path so a rebuilt row draws from memory on its first frame.
- **Full screen:** `FullScreenMediaViewer` (homebase-chat `chat/widget/FullScreenMediaViewer.kt`)
  for images, with `ZoomableSubSamplingImage` (homebase-common `core/media/subsample/`) for
  zooming into large ones; `FullScreenVideoPlayer` (homebase-chat `chat/widget/`) for video. Add
  buttons to the viewer's top bar through its `extraActions`.
- **Playing video inline:** `VideoPlayerSurface` (homebase-chat `chat/widget/video/`), the
  platform's own player on all four platforms; trimming uses `VideoTrimScrubber` beside it.
- **Taking a photo or video:** `CameraCaptureDialog` (homebase-common `core/camera/`), in its own
  window so it stacks above the sheet that opened it.
- **Picking files:** there is no single picker yet. Chat's composer uses
  `rememberAttachmentLaunchers` (homebase-chat `chat/widget/ConversationAttachmentEditor.kt`);
  elsewhere screens call FileKit's `rememberFilePickerLauncher` directly.

## Text and time

- **Message text:** `MessageTextFieldCompact`, `MessageTextFieldExpanded` and
  `RichTextEditorButtons` (homebase-chat `chat/widget/MessageInputBar.kt`).
- **Showing formatted text:** `ChatMarkdown` (homebase-chat `chat/widget/MarkdownRenderer.kt`), the
  one read-only markdown renderer.
- **Timestamps:** `formatTimestamp` (just now, minutes ago, today's time, yesterday, weekday, then
  the date) and `formatMessageTimestamp` (just now, minutes ago, then the time), in homebase-common
  `core/util/TimeFormatter.kt`.

## Signing in

- **Opening a sign-in in the browser:** `rememberSignInAgainLauncher` (homebase-core
  `core/ui/screens/privatedrive/PrivateDriveBanners.kt`). It starts desktop's local callback
  server and web's message listener before the browser opens; a plain `openUri` doesn't, and the
  sign-in never comes back.
