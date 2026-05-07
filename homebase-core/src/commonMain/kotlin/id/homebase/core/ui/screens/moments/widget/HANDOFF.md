# Moments media widgets — handoff

## What's here

Faithful clones of the chat media widgets, forked into Moments so they can diverge:

- `MomentMediaItem.kt` — clone of `homebase-chat/.../widget/MediaItem.kt`
- `MomentMediaGallery.kt` — clone of `homebase-chat/.../widget/MediaGallery.kt`
  (its 1/2/3/4+ layouts call `MomentMediaItem` rather than chat's `MediaItem`)

Originals in `homebase-chat/.../widget/` are untouched and still in use by chat.

## Compile status

`./gradlew :homebase-core:compileKotlinJvm` — green as of fork.

## Differences from the chat originals

Only one functional change so far: `formatDurationLabel` is inlined as a
`private` helper at the bottom of `MomentMediaItem.kt`. The chat version
(`id.homebase.chat.widget.video.formatDurationLabel`) is `internal` to
the chat module and therefore unreachable from `homebase-core`.

Everything else is a literal copy; the package is
`id.homebase.core.ui.screens.moments.widget` and the public composables
are renamed `MomentMediaItem` / `MomentMediaGallery`.

## Imports still pointing at chat

The clones still depend on these chat types/composables (homebase-core
already depends on homebase-chat, so they resolve fine):

- `id.homebase.chat.conversationlist.DecryptedFileKey`
- `id.homebase.chat.services.ChatProtocol` (PAYLOAD_KEY_LINKS, PAYLOAD_KEY_LOCATION)
- `id.homebase.chat.services.LocalAttachmentContext` / `LocalAttachmentContextStore`
- `id.homebase.chat.services.builder.LinkPreviewDescriptor` / `LocationPreviewDescriptor`
- `id.homebase.chat.widget.LinkPreviewCard`
- `id.homebase.chat.widget.LocationPreviewCard`
- `id.homebase.chat.widget.DocumentMediaItem`

These are likely candidates to drop or swap as Moments diverges (see below).

## Suggested next steps (divergence work)

Pick what makes sense for Moments — these are not all required.

1. **Trim non-photo/video branches.** If Moments is photo+video only:
   - Remove the `audio/*` branch (drops `AudioPlayerWidget` import).
   - Remove the `application/*` / `text/*` document branch (drops `DocumentMediaItem`).
   - Remove the `PAYLOAD_KEY_LINKS` and `PAYLOAD_KEY_LOCATION` branches
     (drops `ChatProtocol`, `LinkPreviewCard`, `LocationPreviewCard`,
     `LinkPreviewDescriptor`, `LocationPreviewDescriptor`,
     `OdinSystemSerializer`).

2. **Decide on `LocalAttachmentContextStore`.** It's chat-specific
   (keyed by chat `messageId`). Moments will probably want its own
   pre-upload context store keyed by moment id. Until that exists, the
   clones happily pass `messageId = null` and skip the lookup.

3. **Replace `messageId` parameter naming.** Both composables take a
   `messageId: Uuid`/`Uuid?` purely as a key for downloading-files
   tracking and the local-attachment store. Consider renaming to
   `momentId` (or whatever the Moments primary key ends up being) and
   adjusting `downloadingFiles` lookups accordingly.

4. **Swap `Dimens.Message.*`, `Dimens.Album.*`, `Dimens.MediaBubble.*`.**
   These are sized for chat bubbles. Moments grids will likely want
   different widths/heights/corner radii — either add `Dimens.Moment.*`
   or pass dimensions in as parameters.

5. **Replace chat string resources.** `MR.string.chat_message_image_attachment`
   and `MR.string.chat_message_video_thumbnail` are used as
   `contentDescription`. Add Moments equivalents (e.g.
   `moments_image_attachment`) once the Moments string set is being
   built up.

6. **`DecryptedFileKey`.** Used only by the audio branch — drops out
   automatically if step 1 lands.

## Where this is wired up

Nowhere yet. `MomentsScreen.kt`, `MomentDetailScreen.kt`, and
`MomentCreateScreen.kt` don't call these composables. Wiring is the
next caller-side task.

## Why fork instead of reuse

The chat `MediaItem`/`MediaGallery` are tied to chat's bubble dimensions,
chat's link/location preview cards, chat's local-attachment store keyed
by `messageId`, and chat string resources. Forking lets Moments evolve
its own UX (full-bleed photo grids, different aspect-ratio rules,
moment-keyed attachment context) without destabilising chat.

If the Moments versions stay ~95% identical after divergence, revisit
extracting the shared core into `homebase-common` and wrapping per-feature.
