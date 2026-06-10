# Community — Part 2: Greenfield Feature (chat-kmp)

> **Prerequisite: Part 1 (`COMMUNITY_PART1_TRANSPORT.md`) is done and has passed its validation gate.**
> That means a drive hosted on another identity (the community owner) can already be synced into the
> local DB, subscribed to over a peer websocket, queried, and written to over peer — and it fails soft if
> the owner is offline. From here on, **treat the community drive as if it were a normal local drive**;
> the only difference (owner-hosted) has been made transparent by Part 1.

This doc distills Homebase **Community** (a Slack-like, multi-channel, multi-member messenger), finished
as a web app in the **sibling checkout** `../odin-js/` (relative to this `chat-kmp/` repo root), and maps
it onto chat-kmp's patterns. The **Moments** add-on is your proven template throughout.

> **JS source of truth.** This doc summarizes; it does not reproduce every field, edge case, or screen.
> When a section here is incomplete, read the original under `../odin-js/`:
> - data + wire format → `../odin-js/packages/apps/community-app/src/providers/`
> - use-cases / domain logic → `../odin-js/packages/apps/community-app/src/hooks/community/`
> - screens / routing → `../odin-js/packages/apps/community-app/src/{app,templates/Community}/`
> - shared SDK (`@homebase-id/js-lib`) → `../odin-js/packages/libs/js-lib/`
>
> All `*.ts` / `use*` paths cited below are relative to that tree.

## Ground rules

- **Greenfield port.** Reuse Community's *semantics*, not its wire format. Pick KMP-native file-types,
  IDs, and models that fit chat-kmp conventions (a `CommunityProtocol`, mirroring `MomentsProtocol`).
- **Reuse chat-kmp infrastructure; don't port the JS sync/caching.** The JS app's react-query caches,
  `useCommunityInboxProcessor`, `useCommunityPeerWebsocket`, `useCommunityWebsocket`, optimistic-update
  bookkeeping, and version-conflict retry loops are all provided by chat-kmp's DriveSync + EventBus +
  SQLDelight + Stream/Service pattern (Part 1 extended these to the owner-hosted drive). Copy what
  Chat/Moments do.
- **Ignore rendering & editor.** Chat bubbles, the RTE/markdown editor, media rendering, link previews —
  reuse chat-kmp's existing widgets and editor. Do **not** port the JS components or RTE customizations.
- **MVP-core first.** Communities list → channels → messages (text + media) → threads/replies →
  reactions → per-channel unread. Defer: member status, per-channel drafts, pinned messages,
  saved-for-later, collaborative editing, DMs.

## Distribution model (recap — already solved by Part 1)

The community is an **owner-owned collaborative drive** (one per community, on its creator's identity).
Members sync that remote drive locally and websocket to it; one physical copy, no mesh fan-out. **This
does not change the feature design below at all** — channels, messages, threads, reactions, read-state
are identical regardless of where the drive is hosted. Writes target the community drive: the owner
writes locally, a member writes over peer (Part 1's `remoteTargetDrive` path). Per-user private state
(read-state, drafts) stays on the member's **own** drive, never the shared community drive.

---

## Part A — Community domain model (extracted from JS, verified against source)

> Constants/types below are the JS wire format, read from
> `odin-js/packages/apps/community-app/src/providers/`. In the greenfield KMP port you keep the *shapes
> and relationships* but assign your own KMP file-type numbers in `CommunityProtocol`.

### A.1 Entities & relationships

```
Community (definition)            one per community the user belongs to
  └─ Channel                      many per community  (+ a hard-coded "general" channel)
       └─ Message                 many per channel
            └─ Thread reply       many per message    (a Message with parent = message)
            └─ Reaction           emoji, aggregated on the message header
  └─ Member status                one per member  (emoji + text presence)        [DEFER]
  └─ (per-user, local) Metadata   read-state, pins, saved msgs, notif flag       [DEFER most]
  └─ (per-user, local) Drafts     RichText draft per channel/thread              [DEFER]
```

### A.2 The types (JS source of truth)

`CommunityDefinitionProvider.ts`
```ts
COMMUNITY_DRIVE_TYPE = '63db75f1-e999-40b2-a321-41ebffa5e363'  // each community = its own drive
COMMUNITY_FILE_TYPE  = 7010
interface CommunityDefinition { title: string; members: string[]; acl: AccessControlList }
```

`CommunityProvider.ts` (channels)
```ts
COMMUNITY_CHANNEL_FILE_TYPE  = 7015
COMMUNITY_DEFAULT_GENERAL_ID = '7d64f4e4-f8e2-4c3b-bc4b-48bbb86e8f9a'  // synthetic, never persisted
interface CommunityChannel { title: string; description: string }
// stored with groupId = communityId, uniqueId = toGuidId(title)
```

`CommunityMessageProvider.ts` (messages + threads)
```ts
COMMUNITY_MESSAGE_FILE_TYPE = 7020
COMMUNITY_MESSAGE_PAYLOAD_KEY = 'comm_web'      // media payload key prefix
COMMUNITY_LINKS_PAYLOAD_KEY   = 'comm_links'    // link-preview payload
COMMUNITY_PINNED_TAG          = toGuidId('pinned-message')
MESSAGE_CHARACTERS_LIMIT      = 1600            // embed-in-header vs payload threshold
BACKEDUP_PAYLOAD_KEY          = 'bckp_key'      // collaborative-edit backup [DEFER]
CommunityDeletedArchivalStaus = 2              // soft-delete marker

enum CommunityDeliveryStatus { Sending = 15, Sent = 20, Failed = 50 }

interface CommunityMessage {
  message: RichText | undefined;     // body (rich text)
  deliveryStatus: CommunityDeliveryStatus;
  channelId: string;
  threadId?: string;
  isEdited?: boolean;
  lastEditedBy?: string;
  isCollaborative?: boolean;         // [DEFER]
  collaborators?: string[];          // [DEFER]
}
```
- **Channel message:** `groupId = channelId`, `tags = [channelId]`, `fileSystemType = 'Standard'`.
- **Thread reply:** `fileSystemType = 'Comment'`, `referencedFile.globalTransitId = parentMessageId`;
  threads are **not a separate entity** — a thread is just the set of replies referencing a parent.

`CommunityStatusProvider.ts`  *(DEFER)*
```ts
COMMUNITY_STATUS_FILE_TYPE = 7030
interface CommunityStatus { emoji?: string; status?: string; validTill?: number }
// uniqueId = toGuidId(odinId) → one presence file per member
```

Per-user **local** state — JS keeps these on a private "local app" drive, owner-only ACL. *(mostly DEFER)*
```ts
// CommunityMetadataProvider.ts
COMMUNITY_METADATA_FILE_TYPE = 7011
interface CommunityMetadata {
  lastReadTime: number; threadsLastReadTime: number;
  channelLastReadTime: Record<string, number>;       // ← MVP needs this (unread badges)
  pinnedChannels: string[]; savedMessages: {...}[]; notifiationsEnabled?: boolean;
  odinId: string; communityId: string;
}
// CommunityDraftsProvider.ts                          [DEFER]
COMMUNITY_DRAFTS_FILE_TYPE = 7017
interface Draft { message: RichText; updatedAt: number }
interface CommunityDrafts { drafts?: Record<channelOrThreadId, Draft>; ... }
```

### A.3 Reactions

Reactions are **not** separate files. They live aggregated in the message header's `reactionPreview`
(emoji → count), and the current user's own reactions come from a per-user mirror. **chat-kmp already
does exactly this** for chat & moments (`fileMetadata.reactionPreview` + `fileMetadata.localAppData.localReactions`
maintained by `OptimisticWriter`). → Reuse wholesale; design nothing new.

---

## Part B — Feature inventory → KMP mapping

**"Reuse"** = chat-kmp already has it; **"New"** = build it (following the cited template).

| Feature (JS hook) | Domain behaviour | KMP target |
|---|---|---|
| Communities list (`useCommunities`) | enumerate communities the user is in | **New** `CommunityStream` (cold-load `CommunityDefinition` files + EventBus). Template: `MomentsFeedService` |
| Community CRUD (`useCommunity`) | create collaborative drive + circle, rename, set ACL, invite link | **New** `CommunityService.create/update`. Owner: create the collaborative drive (`IsCollaborativeChannel`, `allowSubscriptions`) + circle grant. Template: `ConversationService` group create |
| Channels (`useCommunityChannels`, `useCommunityChannel`) | list / create / rename / delete; synthetic "general" | **New** channel files (`groupId = communityId`) on the **community drive** (owner: local; member: over peer). Template: Moments group files (`MomentGroupService`) |
| Channels w/ recent msg (`useCommunityChannelsWithRecentMessages`) | last message per channel for the sidebar | **Reuse** — derive from `ChatMessageStream`-style per-group last message |
| Messages (`useCommunityMessages`, `useCommunityMessage`) | paginated history; send text+media; edit; delete (soft) | **Reuse** `ChatMessageSenderService` + `ChatMessageStream` pattern, scoped by `groupId = channelId`; storage target = the community drive |
| Media / attachments | images, video, files with thumbnails | **Reuse** `MessageAttachmentBuilder` + `PayloadBundleEncryptor` (payload keys `comm_0000…`) |
| Threads / replies (`useCommunityThreads`) | replies referencing a parent message; participants; per-thread view | **Reuse Moments comments** shape: `groupId = parentMessageId`. See `MomentCommentsService` |
| Reactions (`useCommunityReaction`) | emoji add/remove, aggregated counts, own-reactions | **Reuse** `reactionPreview` + `localAppData.localReactions` (no new code) |
| Read-state (`useMarkCommunityAsRead`, `channelLastReadTime`) | per-channel last-read → unread badges | **New-thin**: a per-user marker on a **private own drive** — never the shared community drive |
| Realtime (`useLiveCommunityProcessor`, `*Websocket`) | inbox backlog + live push from the owner's host | **Reuse EventBus/Stream pattern** (Part 1 wired the peer sync + peer websocket into `BatchReceived`/`DriveEvent.Stopped`). **Do not port the JS react-query/inbox-processor code.** |
| Notifications (`useCommunityNotifications`) | push on new message via owner host | **Adapt** — arm a peer push-subscription against the owner; writes set `useAppNotification` + `PushNotificationOptions` like Moments |
| Status / drafts / pins / saved / collab-edit / DMs | presence, drafts, etc. | **DEFER** — extra file types + local state; post-MVP |

### Navigation graph (from JS `src/app` + `templates/Community`)
- Communities list / new-community
- Community shell → channel list (sidebar) + selected channel
- Channel view: message list + composer (defer pins/info)
- Thread view: parent message + replies + composer
- Activity / threads / later / pins overview screens — **defer**
- Notification deep-link → message

---

## Part C — How to build it (the Moments blueprint, grounded in real files)

chat-kmp already shipped one complete add-on app — **Moments** — and documents the recipe in
`ADDING_ADDON_APPS.md` (~50 KB, 10 steps) and `ADDING_TYPED_MESSAGE_KIND.md`. Community follows the same
skeleton. Reference these **real** files:

- `homebase-core/.../moments/services/MomentsProtocol.kt` — file-type constants pattern
- `homebase-core/.../moments/services/MomentsPostSenderService.kt` — send/update/comment via
  `OutboxSync.tryEnqueue|replaceEnqueue`, `OptimisticWriter.writeNewFile|writeUpdate|removeOptimisticFile`,
  `PayloadBundleEncryptor.encryptBundle`, `MessageAttachmentBuilder.build`, two-phase optimistic send
- `homebase-core/.../moments/services/MomentsFeedService.kt` — the **Stream** template: cold-load via
  `QueryBatch(identityId).queryBatchAsync(filetypesAnyOf=…)`, then EventBus over `BatchReceived` (live),
  `DriveEvent.Stopped(totalCount>0)` (silent bulk catch-up re-load), `OptimisticRollback`, `SessionEnded`
  reset; in-memory `byId` map → sorted `StateFlow`; soft-delete handling
- `homebase-core/.../moments/services/MomentCommentsService.kt` — **thread/reply** template
- `homebase-common/.../moments/MomentsPreferences.kt` — activation + icon-visibility flags
- `homebase-core/.../ui/screens/moments/` — onboarding/permission/feed/detail/compose screens
- chat reuse targets: `homebase-chat/services/convo/ConversationService.kt`,
  `homebase-chat/services/ChatMessageSenderService.kt`, `homebase-chat/services/ChatMessageStream.kt`,
  `homebase-chat/services/builder/MessageAttachmentBuilder.kt`, `homebase-chat/services/PayloadBundleEncryptor.kt`,
  `homebase-chat/services/outbox/OptimisticWriter.kt`; infra `homebase-api/.../sync/database/OutboxSync`,
  `homebase-api/.../client/eventbus/EventBus`, `homebase-api/.../sync/database/DatabaseManager`

### Step list (mirrors `ADDING_ADDON_APPS.md`)
1. **Drive = one collaborative drive per community, hosted on its creator's identity** (not a single
   labeled drive like Moments). A member belongs to communities owned by *different* identities, so the
   drive is `(ownerOdinId, TargetDrive{alias = communityId, type = CommunityDriveType})`. Mount it via
   Part 1's owner-aware registry/sync. Owner creates the drive collaboratively (`IsCollaborativeChannel`,
   `allowSubscriptions`, `Read+Write+React+Comment`) plus a circle to grant members.
2. **Protocol** — `community/services/CommunityProtocol.kt` with KMP-native file types
   (`CommunityDefinition`, `Channel`, `ChannelMessage`, later `MemberStatus`), version constants, AppId.
3. **Content models** — `@Serializable` `CommunityContent`, `ChannelContent`, `ChannelMessageContent`
   (body reuses chat-kmp message content / `MessageContent` machinery; don't invent a body format).
4. **Scoping convention** (all on the community drive):
   - Community def: `fileType = CommunityDefinition`, `uniqueId = communityId`
   - Channel: `fileType = Channel`, `groupId = communityId`, `uniqueId = channelId`
   - Channel message: `fileType = ChannelMessage`, `groupId = channelId`, `uniqueId = messageId`
   - Thread reply: `fileType = ChannelMessage`, `groupId = parentMessageId` (Moments-comment style)
   - Reactions: embedded `reactionPreview` + `localReactions` (no file)
   - Per-user read-state/drafts: **NOT** on the community drive — private own drive.
5. **Services** (three-service pattern):
   - `CommunityStream` (communities + channels live view) — template `MomentsFeedService`
   - `ChannelMessageStream` (messages per channel, paged) — template `ChatMessageStream`
   - `CommunityService` (community/channel CRUD) + `ChannelMessageSenderService` (send/edit/delete/reply,
     media) — templates `ConversationService` + `ChatMessageSenderService` + `MomentsPostSenderService`
6. **Preferences** `CommunityPreferences` (activation, icon visibility) — template `MomentsPreferences`.
7. **DI** — register singletons + `reset()`/`start()` on `onPostAuthenticated` (logout safety!).
8. **Navigation + screens** — routes for list / channel / thread; **reuse chat composer, message bubbles,
   attachment sheet, media widgets, RTE/markdown editor**. Build only Community-specific chrome (channel
   sidebar, community switcher).
9. **Notifications** — `TransitOptions.useAppNotification` + `PushNotificationOptions` (Moments currently
   sends under `ChatProtocol.ChatAppId`; decide whether Community gets its own AppId).
10. **Typed system messages** *(optional, post-MVP)* — "channel created / member joined / topic changed"
    via `ADDING_TYPED_MESSAGE_KIND.md`.

### Hard-won gotchas to carry over (from the Moments files/docs)
- **`DriveEvent.Stopped(totalCount>0)` re-cold-load.** Bulk `DriveSync.performSync` is *silent* (no
  per-file `BatchReceived`). Without the Stopped branch, mounting a drive at runtime writes rows to the
  DB but the in-memory list stays empty until restart. Copy the Moments handling.
- **Read recipients/parents from the local DB**, not the server
  (`dbm.driveMainIndex.selectHomebaseFileByUnique`). A transient network blip on a server GET otherwise
  turns into a lost message. Applies to resolving thread-reply recipients.
- **Reset every user-scoped singleton on logout** or user B sees user A's communities (Koin singletons
  outlive the session).
- **Two-phase optimistic send** (placeholder row → background build/encrypt/enqueue/finalize) for snappy
  UI on media messages — see `MomentsPostSenderService.postMomentAsync`.

---

## One-paragraph kickoff prompt

> You are implementing **Community** (a Slack-like multi-channel messenger) inside the chat-kmp project,
> **after** the peer-hosted-drive transport (`COMMUNITY_PART1_TRANSPORT.md`) already exists and is proven —
> so treat the community drive as a normal local drive. Read this doc, `ADDING_ADDON_APPS.md`, and the
> **Moments** add-on (`homebase-core/.../moments/`) — Moments is your template; take it apart for
> reference. Community is a **greenfield** port: reuse the *semantics* here with KMP-native
> file-types/models, and reuse chat-kmp's sync/attachment/editor/message-bubble infrastructure — **do not**
> port the JS app's react-query caches, websocket handlers, or inbox processor. Each community is an
> **owner-owned collaborative drive** (one per community, on its creator's identity). Build **MVP-core
> first**: communities list → channels → text+media messages → threads/replies (Moments-comment shape:
> `groupId = parentMessageId`) → reactions (reuse `reactionPreview` + `localReactions`) → per-channel
> unread (on a private own drive). Defer: member status, drafts, pins, saved-for-later, collaborative
> editing, DMs.

---

## Verification

- **Build:** `./gradlew :homebase-core:compileKotlin…` (see `CLAUDE.md` for per-target build/run) after
  each service lands.
- **Realtime smoke test:** owner + member on two devices; owner creates community/channel, posts a
  message → member sees it live via the peer-drive sync (`DriveEvent.Stopped` / `BatchReceived`).
- **Member-write test:** member posts a message / thread reply → it lands on the owner's drive and the
  owner (and other members) receive it.
- **Thread test:** reply to a message (`groupId = parentMessageId`) → shows under the parent
  (Moments-comment parity).
- **Reaction test:** add/remove emoji → count + own-reaction state survive a re-sync.
- **Logout test:** log in as a second user → no leakage of the first user's communities (singleton
  `reset()`).
