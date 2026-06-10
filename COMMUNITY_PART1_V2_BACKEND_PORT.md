# Community Part 1 — V2 Backend Port (odin-core)

> **Handoff spec for a fresh session working in `..\odin-core\`.** It defines the V2 server routes
> the chat-kmp client needs so a member can sync / query / write / live-subscribe to a **community
> drive hosted on another identity** (the community owner). The client side (chat-kmp, branch
> `generic-video-compressor`) is already written against the V2 shapes below; this doc is the
> server contract it expects. The equivalent logic already exists as **V1 transit** endpoints
> (what the odin-js web app uses) — the task is to expose V2 routes that wrap that logic.

## Why this exists

A community is one collaborative drive on its creator's identity; members sync that single remote
drive. chat-kmp is a **pure V2 client** (`https://{host}/api/v2/...`, `Authorization: Bearer` CAT +
shared-secret body/response encryption). The over-peer operations it needs do **not** exist as V2
routes yet — only as V1 `/transit/...` routes (used by odin-js). The V1 smoke test from chat-kmp
returned **404** because chat-kmp can't reasonably reach V1 (different base + browser-cookie auth
model). So: add the V2 routes.

## The pattern to follow (already proven on V2)

chat-kmp already calls one over-peer V2 route successfully today:

```
GET /api/v2/peer/{odinId}/drives/{driveId}/files/by-uid/{uniqueId}/exists
GET /api/v2/peer/{odinId}/drives/{driveId}/files/by-gtid/{globalTransitId}/exists
```

(see chat-kmp `homebase-api/.../client/peer/PeerDriveQueryProvider.kt`). This proves the V2 server
has a **peer-drive controller mounted at `/api/v2/peer/{odinId}/drives/{driveId}/...`**, it accepts
chat-kmp's bearer CAT + shared-secret, and it returns headers **re-encrypted to the caller's own
shared secret**. **All new routes below extend that same controller family** and delegate to the
existing peer/transit services — they are thin V2 wrappers, not new logic.

> **In odin-core, locate:** (1) the controller serving the `…/by-uid/{uid}/exists` route above
> (that's the V2 peer-drive controller to extend), and (2) the V1 `/transit/query/batch`,
> `/transit/sender/files/send`, and `/notify/peer/*` handlers (the existing logic to delegate to).
> The V2 routes map path params → the existing service calls.

---

## MVP routes (required for the Part 1 validation gate)

### 1. Query over peer (READ) — highest priority

```
POST /api/v2/peer/{odinId}/drives/{driveId}/files/query-batch
```

- **Auth/encryption:** identical to the existing `…/exists` route (bearer CAT + shared-secret
  envelope). Request body and response are shared-secret encrypted (`{iv,data}`).
- **Request body** (decrypted) = chat-kmp `QueryBatchRequest`:
  ```jsonc
  {
    "queryParams": { /* FileQueryParams: fileType?, dataType?, groupId?, tagsMatchAtLeastOne?, ... — NO targetDrive (it's in the path) */ },
    "resultOptionsRequest": { "cursorState": null, "maxRecords": 10, "includeMetadataHeader": true, "includeTransferHistory": false, "ordering": "OldestFirst", "sorting": "AnyChangeDate" }
  }
  ```
- **Response** (encrypted to the **caller's** shared secret) = chat-kmp `QueryBatchResponse`:
  ```jsonc
  {
    "name": null, "invalidDrive": false, "queryTime": 0, "includeMetadataHeader": true,
    "cursorState": "…", "hasMoreRows": false,
    "searchResults": [ { "fileId", "fileState", "fileSystemType", "sharedSecretEncryptedKeyHeader", "fileMetadata", "serverMetadata", "priority", "fileByteCount" } ]
  }
  ```
  Each `sharedSecretEncryptedKeyHeader` must be re-encrypted to the **caller's** shared secret (so
  the member can decrypt) — exactly what the V1 transit query and the V2 exists-check already do.
- **V1 logic to wrap:** `/transit/query/batch` (odin-js `PeerDriveQueryService.ts`), whose body is
  `{ queryParams: {…, targetDrive}, resultOptionsRequest, odinId }`. The V2 version takes `odinId`
  and `targetDrive`(=driveId) from the **path** instead of the body.
- **Client call site (no change needed):** `DriveQueryProvider.queryBatch(driveId, request, ownerOdinId)`.

### 2. Write over peer (member → owner's drive) (WRITE)

```
POST /api/v2/peer/{odinId}/drives/{driveId}/files        (multipart/form-data)
```

- Mirrors the own-host create `POST /api/v2/drives/{driveId}/files`, but the target drive is hosted
  on `{odinId}`, so the server performs the **transit send** to the owner and returns the result.
- **Multipart parts** (identical to own-host create):
  - `instructions` — JSON `UploadInstructionSet` (`storageOptions?`, `transitOptions?`, `transferIv`, `manifest`)
  - `metadata` — shared-secret-encrypted `UploadFileDescriptor` (`{encryptedKeyHeader, fileMetadata}`)
  - `payload` / `thumbnail` — streamed (encrypted with the file key header)
- **Response:** the create result (`fileId`/`globalTransitId`/`newVersionTag`, and per-recipient
  `recipientStatus`). Throw/encode failure if the owner rejects.
- **V1 logic to wrap:** `/transit/sender/files/send` (odin-js `PeerFileUploader.ts`), whose
  `TransitInstructionSet` carries `recipients=[owner]` + `remoteTargetDrive`. In V2 both are implicit
  from the path, so the controller injects `recipients=[{odinId}]`, `remoteTargetDrive={driveId}` and
  delegates to the existing transit-send service.
- **Client follow-up (simplification):** once this exists, chat-kmp drops its `TransitInstructionSet`
  /`TransitUploadResult` layer and routes the existing `DriveUploadProvider.uploadFile` at this URL —
  a one-line change. (The current `PeerDriveUploadProvider` is a placeholder for the V1 shape.)

### 3. Live updates over peer (WEBSOCKET) — most design latitude

The member needs `fileAdded / fileModified / fileDeleted / statisticsChanged` for the community
drive, live. **Two designs — please pick (A) is strongly preferred:**

**(A) Broker peer drives on the member's own V2 socket (preferred).**
Extend the existing V2 notify hub (`wss://{member}/api/v2/notify/ws-token`) so its
`establishConnectionRequest` can include **owner-hosted drives**, and the member's host relays the
owner's drive events to the member. One connection, no peer token, no second host.
- Client impact: chat-kmp **deletes** `PeerWebSocketClient`/`PeerWebSocketManager` and just adds the
  community drive (tagged with owner) to the existing `OdinWebSocketClient` subscription. Simplest.
- Requires: a way to express `(ownerOdinId, targetDrive)` in the establish payload, and the hub
  brokering the owner subscription server-side.

**(B) Direct peer socket (V1 parity, fallback).** Keep the V1 model but on V2:
```
POST /api/v2/notify/peer/token              { identity }  ->  { authenticationToken64, sharedSecret }
wss://{owner}/api/v2/notify/peer/ws          (auth via the token; establish wrapper carries clientAuthToken64)
```
- Client impact: chat-kmp keeps `PeerWebSocketClient` and just points its two route constants here
  (`PEER_WS_PATH`, token route). This is what the client has wired today.
- **V1 logic to wrap:** odin-js `WebsocketProviderOverPeer.ts` (token + `notify/peer/ws`).

Also (both designs), for closed-app push:
```
POST /api/v2/notify/peer/subscriptions/push-notification   { identity, subscriptionId }
```
(V1: `PeerNotificationSubscriber.ts`). Client: `PeerNotificationProvider.subscribeToPeerNotifications`.

---

## Follow-up routes (Part 2 / media — not needed for the gate, list for completeness)

Same `/api/v2/peer/{odinId}/drives/{driveId}/...` family, wrapping the V1 transit equivalents:

| V2 route | V1 equiv (odin-js) | For |
|---|---|---|
| `GET …/files/{fileId}/header` | `/transit/query/header` | header fetch |
| `GET …/files/{fileId}/payload?key=` (range) | `/transit/query/payload` | media payload |
| `GET …/files/{fileId}/thumb?key=&width=&height=` | `/transit/query/thumb` | thumbnails |
| `POST …/files/senddeleterequest` (or DELETE) | `/transit/sender/files/senddeleterequest` | delete over peer |
| `POST /api/v2/peer/drives/metadata/type` | `/transit/query/metadata/type` | discover community drives by type |

---

## Auth & encryption (must match the existing V2 peer route)

- **Auth:** bearer CAT (`Authorization: Bearer {clientAccessToken}`), same handler as
  `…/by-uid/{uid}/exists`. Do **not** require the browser-cookie model the V1 routes use — chat-kmp
  has no cookie.
- **Request encryption:** body is shared-secret AES envelope `{iv, data}` (POST/PUT/PATCH);
  the V2 framework already decrypts this for the exists-check — reuse it.
- **Response encryption:** encrypt to the **caller's** shared secret; set `X-SSE: 1`. Re-encrypt all
  `sharedSecretEncryptedKeyHeader`s to the caller (the member must decrypt with their own SS, since
  they don't hold the owner's drive key).
- **Permissions:** the member is granted access via the owner's community circle
  (Read+Write+React+Comment on a collaborative drive). A non-member must get **403** (not 404) so the
  client can distinguish "not allowed" from "wrong route".

## Validation (against chat-kmp's built-in smoke test)

chat-kmp already has a Developer-Menu action **"Query community (peer)"** (logs under `PeerSmokeTest`)
that calls route #1. After deploying the V2 query route:
1. In chat-kmp desktop, log in as a community member, Developer Menu → **Query community (peer)**.
2. Expect `PeerSmokeTest OK got N file(s); fileType breakdown={7020=…,7015=…,7010=…}` (7020 messages,
   7015 channels, 7010 community def). A 403 means the member isn't in the owner's circle; a 404 means
   the route still isn't matched.
3. Then exercise mount (sync-in to local DB + live), and write-out, as the gate requires
   (`COMMUNITY_PART1_TRANSPORT.md`).

## Client route constants to confirm after the BE lands

| Client file | Constant / call | Set to |
|---|---|---|
| `DriveQueryProvider.queryBatch` | path when `ownerOdinId != null` | `/peer/{owner}/drives/{id}/files/query-batch` (already matches #1) |
| `PeerDriveUploadProvider` → replace with `DriveUploadProvider` over-peer | `TRANSIT_SEND_PATH` | `/peer/{owner}/drives/{id}/files` (#2) |
| `PeerWebSocketClient` | `PEER_WS_PATH` + token route | per WS design chosen (#3) — or delete if (A) |

## odin-js reference files (the V1 logic to port)

Under `..\odin-js\packages\libs\js-lib\src\peer\`:
- `peerData/Query/PeerDriveQueryService.ts` — `/transit/query/batch`
- `peerData/Upload/PeerFileUploader.ts` — `/transit/sender/files/send`
- `peerData/File/PeerFileProvider.ts` — `/transit/query/{header,payload,thumb}`
- `WebsocketData/WebsocketProviderOverPeer.ts` — `/notify/peer/token`, `notify/peer/ws`
- `..\odin-js\packages\apps\community-app\src\providers\PeerNotificationSubscriber.ts` — push subscription
- Core transport: `core/DotYouClient.ts` (base + interceptors), `core/InterceptionEncryptionUtil.ts`
