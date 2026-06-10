# Community — Part 1: Peer-hosted Drive Transport (chat-kmp)

> **This is a self-contained infrastructure task. It builds NO Community feature.** It teaches
> chat-kmp to sync, query, websocket, and write to a drive hosted on **another identity's** host, and
> proves it against a real, already-existing community. The actual Community feature is Part 2
> (`COMMUNITY_PART2_FEATURE.md`) and must not be started until this passes the validation gate below.

## Goal

A "community" (the Slack-like feature we'll build in Part 2) lives as a single **collaborative drive on
its creator's identity**. Every member syncs that *one remote drive* into their local client and gets
live updates over a websocket bound to the owner's host. There is one physical copy of the data (on the
owner), not a per-member copy.

chat-kmp today cannot do this: **every** sync/query/websocket/upload targets the logged-in user's own
host (`creds.domain`). This task adds the missing capability — a drive whose **owning identity ≠ me** —
as a reusable, feature-agnostic addition to `homebase-api` (+ drive registry/config).

> **JS source of truth.** The finished web implementation is a **sibling checkout** at `../odin-js/`
> (relative to this `chat-kmp/` repo root). Every `*.ts` file cited below is under
> `../odin-js/packages/apps/community-app/src/` (providers, hooks/community), and the SDK they call
> (`uploadFileOverPeer`, `queryBatchOverPeer`, `getContentFromHeaderOverPeer`, peer notify) lives in
> `../odin-js/packages/libs/js-lib/`. When this doc is thin on a detail, read the original there.

## Why it's needed — what's missing (verified against `homebase-api`)

| Capability | Today in chat-kmp | Needed |
|---|---|---|
| **Sync** | `DriveSync`/`DriveSyncManager.mountDrive` bind `identityId` = active creds; one host only (`sync/DriveSync.kt`, `DriveSyncManager.kt:287-347`) | Sync a drive whose **owning identity ≠ me** into the local DB, keyed by owning identity |
| **Query** | `DriveQueryProvider.queryBatch` always hits `creds.domain` `/drives/$id/files/query-batch`. `PeerDriveQueryProvider` only has `fileExistsBy…` (healing) | A real `queryBatchOverPeer(ownerOdinId, drive, …)` → `/peer/$owner/drives/$id/files/query-batch` for catch-up |
| **WebSocket** | `OdinWebSocketClient` connects `wss://${creds.domain}/…`, single host, drive list from own registry (`OdinWebSocketClient.kt:241,260,810`) | A **peer subscription** to the owner's host for the community drive (peer-subscription endpoint, or a second WS connection per owner host) |
| **Upload/write** | `TransitOptions.remoteTargetDrive` field exists but has **no consumers**; `DriveUploadProvider.uploadFile` always posts to `creds.domain` | Honour `remoteTargetDrive` so a write lands on the owner's drive over peer |
| **Drive registry/config** | `LabeledDrive`/`TargetDrive` carry no owning identity; `DriveRegistry` reads only the user's own Chat drive | Track drives as `(ownerOdinId, TargetDrive)` and mount them for sync/WS |

## Decision: local mirror (DECIDED)

Land the remote drive's files in the **local SQLDelight DB**, tagged with the owning identity — exactly
as own drives are stored. This keeps every future Stream/Service reading the local DB unchanged, and
gives offline support for free.

Concrete surface:
- `DriveMainIndex` rows keyed/tagged by **owning identity** so a member's own drives and each remote
  community drive coexist without collision. (Implementation detail to settle in code: add an
  owning-identity column vs. repurpose the existing `identityId` column to mean "owner of this drive."
  Check the SQLDelight schema + `QueryBatch`/`DriveSync` call sites and pick the lower-impact option.)
- `DriveSync` / `DriveSyncManager.mountDrive` take an owning-identity/host parameter.
- The sync pull uses `queryBatchOverPeer` against the owner instead of the local-host query.
- Incoming peer-websocket events feed the **same** `EventBus` / `BackendEvent.DataEvent.BatchReceived`
  pipeline the existing Streams already consume — no new event plumbing.

> Rejected alternative — *live peer-query (JS parity)*: query the owner's host on demand with no local
> mirror. Smaller DB change, but breaks the "everything reads local DB" assumption, loses offline, and
> would be community-specific. Not chosen.

## Fail-soft resilience (key requirement)

Unlike your own host, a community owner's host can be **offline or slow**, and that must never degrade
your own drives or other communities. Good news: `DriveSyncManager` already isolates failure per drive —
each drive is its own `DriveSync`; an `Aborted` result only reschedules that one drive
(`DriveSyncManager.kt:106-122`), a `403` only unmounts that one drive (`:123-128`), and `mountDrive`
splits "register in-memory (always succeeds)" from "kick the network sync" (`:287-347`), so mounting a
currently-unreachable owner is fine.

What still needs hardening for remote drives:
- **Backoff, not a 1s hammer.** The abort path retries on a flat `delay(1000L)` loop (`:114-122`); an
  offline owner would be polled forever every second. Give remote drives exponential backoff + a ceiling.
- **Don't pollute the global sync state.** A remote failure currently flips the aggregate `syncState`
  and emits `SyncAllStopped(Failure)` (`:61-83`) — one offline community would make the whole app read
  "sync failed." Exclude remote/community drives from the global indicator; surface their health as a
  per-community status.
- **Don't barrier on remote drives.** `syncAll()` does `jobs.joinAll()` (`:214-217`); ensure the HTTP
  client has a real timeout, and consider not awaiting remote drives in the aggregate so one unreachable
  owner can't stall the round.
- **Tolerate access revocation.** An owner who removes your drive grant should unmount that community
  cleanly (reuse the `PermissionDenied`/`unmountDrive` path, `:123-128`/`:354-363`) without error spam.

## The drive you're connecting to (JS mechanics, for the test target)

To validate, point the new transport at a **real, existing community** that the JS web app
(`odin-js/packages/apps/community-app/`) created. The relevant mechanics to interoperate with:

- **Drive identity & type.** Each community is its own drive: `TargetDrive { alias = communityId,
  type = '63db75f1-e999-40b2-a321-41ebffa5e363' }`, hosted on the community creator's identity
  (`getTargetDriveFromCommunityId`, `CommunityDefinitionProvider.ts`).
- **It's a collaborative drive.** Created with `attributes: { IsCollaborativeChannel: 'true' }`,
  `allowSubscriptions: true`, permissions `Read+Write+React+Comment`; members are granted access via a
  circle (`hooks/community/useCommunity.ts`, `useCommunityMemberUpdater.ts`). For the test you just need
  to be a member (in the owner's community circle) of an existing community.
- **Messages to look for.** `fileType 7020`, `groupId = channelId`, content embedded in header or in the
  `comm_web…` payload (`CommunityMessageProvider.ts`). The default "general" channel id is
  `7d64f4e4-f8e2-4c3b-bc4b-48bbb86e8f9a`.
- **Read path JS uses (mirror it).** `queryBatchOverPeer(ownerOdinId, communityDrive, …)` +
  `getContentFromHeaderOverPeer(...)`.
- **Live path JS uses (mirror it).** A websocket subscriber bound to the **owner's** host watching the
  community drive for `['fileAdded','fileModified','fileDeleted','statisticsChanged']`
  (`useCommunityPeerWebsocket.ts`); push armed via
  `POST notify/peer/subscriptions/push-notification { identity: ownerOdinId, subscriptionId: communityId }`
  (`PeerNotificationSubscriber.ts`).
- **Write path JS uses (mirror it).** A non-owner writes straight to the owner's drive over peer:
  `uploadFileOverPeer(TransitInstructionSet { remoteTargetDrive = communityDrive, recipients:
  [ownerOdinId], … })`.

> Reading these JS wire constants is a **test affordance only** — the simplest way to exercise the
> transport against real, already-populated data. Part 2's feature is greenfield and chooses its own
> file-types/models; do **not** treat any of these constants as a long-term interop contract.

## Validation gate (all three must pass before Part 2)

With a member identity that belongs to an existing JS community owned by a connected identity:

1. **Sync in.** Mount the owner's community drive → its existing messages land in the local DB and are
   queryable like any own-drive file.
2. **Live updates.** Owner posts a message in the JS web client → the KMP client receives it live over
   the peer websocket and it appears in the local DB, no restart.
3. **Write out.** KMP writes a (raw) message file to the owner's drive over peer → it appears in the JS
   web client.

Plus the fail-soft checks: with the owner's host unreachable, the app's own drives keep syncing, the
global sync indicator does **not** read "failed", and retries back off rather than hammer.

## When this passes

Hand off to **`COMMUNITY_PART2_FEATURE.md`**, which builds the actual Community feature on top of this
transport. From Part 2's perspective the community drive then behaves like any local drive — the only
difference is that it's owner-hosted, which this layer has made transparent.
