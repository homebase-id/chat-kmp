# Live Relay — Live GPS Sharing (debug-flow build) — PR #778

## Context

odin-core PR [#1572](https://github.com/homebase-id/odin-core/pull/1572) adds **Live Relay**: a
generic, ephemeral, app-agnostic primitive for streaming live opaque data among already-connected
identities. The motivating use case is live GPS among friends. The server treats the carried bytes
(`blob`) as opaque, stores nothing durably (last-value-wins, TTL ~5 min), enforces app-isolation,
and auto-flushes each sender's last point on (re)connect.

**Goal of THIS build (intentionally minimal):** prove the data path end-to-end with logging only.
Tapping the existing in-conversation **"share location"** action streams live GPS to the
conversation's participants over the relay; the receiving side logs every inbound packet. No new UX,
no map — just confirm in the logs (tag `LiveRelay`) that data flows both ways. A follow-up plan will
design the real UX (map, per-sender last-value, freshness, duration picker, explicit start/stop).

Decisions locked with the user:
- **Cadence:** relay whenever a GPS update arrives, throttled to **≥ 3 s** (last-value-wins).
- **Channel model:** a **single fixed well-known `channelKey` GUID** meaning "live location update."
  The receiver filters on this fixed channelKey and logs/displays wherever needed.
- **Recipient model:** the sender's recipient list is **`{identity, end-time-utc-ms}` pairs**, not a
  flat on/off list (see "Recipient roster" below).
- **Blob encoding:** plaintext compact JSON, base64'd (server-visible to own infra is fine for v1).

### Contract (already live on the server)

- **Send (hop 1):** `POST /api/v2/live-relay`, app-authenticated, shared-secret-encrypted like every
  V2 JSON POST. Body: `{ "channelKey": "<guid>", "recipients": ["sam.dotyou.cloud", ...], "blob": "<base64>" }`.
  `appId` is inferred server-side (never sent). Response `204`. Unreachable/non-connected recipients
  silently dropped.
- **Receive (hop 3):** a `LiveRelay` client notification over the existing notification websocket.
  Its `data` payload: `{ "senderOdinId": "...", "channelKey": "<guid>", "blob": "<base64>", "receivedAt": <ms> }`.
  `senderOdinId` is authoritative; `receivedAt` is server-received ms.

### Permissions — none needed

The relay requires `UseTransitWrite` (backend key 210), already requested by the chat app as
`SendDataToOtherIdentitiesOnMyBehalf` in `AppConfig.appPermissions`. No re-authorization.

### Wire serialization of the notification type — confirmed against odin-core

`OdinSystemSerializer.cs` registers `JsonStringEnumConverter(JsonNamingPolicy.CamelCase)` globally,
and `ClientNotificationType.cs` declares `LiveRelay = 6001`. So the enum goes on the wire **by
camelCase name** — the value is the string `"liveRelay"`; the `6001` is only the C# numeric value
and never appears in JSON. The Kotlin side therefore adds an enum member **named `liveRelay`** (no
integer), matched by camelCase name like every other type.

---

## Recipient roster — `{identity, end-time}` pairs (the key design point)

A naïve "active + one recipient list" model is wrong as soon as a user has **two** live shares at
once: starting the second overwrites the first, dropping its recipients. The roster is instead a flat
list of **`{identity, end-time-utc-ms}` share entries** — one entry per share action, **duplicates
kept**. This needs **no wire change** (end-times are sender-side bookkeeping; the relay stays
ephemeral/last-value-wins):

- **Same recipient in two requests** → **two distinct entries** with their own end-times. They are
  kept separate on purpose so a UX can store an entry's end-time on its chat bubble (e.g. "share for
  15 min") and later remove **exactly that entry** without touching the other share to the same person.
- **Dedup at send time only** → on each GPS tick the sender prunes expired entries and fans out to
  the **unique** live identities (`liveRecipientIds`), so the same coordinate is never sent to the
  same identity twice — even when several entries name them.
- **Auto-expiry** → an entry drops off once its end-time passes; the tracker we started is stopped
  once the roster fully empties.

Implemented as a pure, unit-tested helper — `LiveShareRoster.add(current, add, endTimeMs, nowMs)`,
`.live(roster, nowMs)`, and `.liveRecipientIds(roster, nowMs)` over `TimedRecipient(odinId,
endTimeMs)` in `homebase-api/.../client/liverelay/LiveShareRoster.kt`. The debug toggle uses a
default 1-hour window; a real duration picker (and per-entry removal tied to a bubble) is a UX-plan
concern.

---

## Where the send lives — the GPS sink, not a UI Flow (background correctness)

The relay send fires from the **`onPointsBuffered` seam in `LocationPointStore.submit()`** that the
durable uploader already uses — the only hook that runs on OS-delivered points while the app is
**backgrounded or cold-woken**:

- **Android:** background batches arrive at `LocationUpdatesReceiver` (a `BroadcastReceiver`) which
  wakes even a **cold/killed** process, calls `submit(...)` on a process-scoped `supervisedScope`,
  and holds the process open with `goAsync()`/`pendingResult.finish()` — long enough for the awaited
  relay POST.
- **iOS:** the `CLLocationManager` delegate (`allowsBackgroundLocationUpdates = true`,
  `UIBackgroundModes: location`) delivers on its own process scope. Both funnel through `submit()` →
  `onPointsBuffered`.

It deliberately does **not** collect `LocationPointStore.lastPoint` as a Flow — that StateFlow is
only observed by UI, so a collector would silently stop sending the moment the app backgrounds.
Inside the sink hook it reads `lastPoint.value` synchronously (valid in background; `submit()` sets
it on the line before calling `onPointsBuffered`).

**A share lives until its end-time (or explicit stop / logout) — independent of app lifecycle.** The
roster (with absolute end-times) is persisted in keyValue and re-read on construction, so a 2-week
share keeps going no matter how the app is started or opened — cold start, manual reopen, or
`BroadcastReceiver`/SLC background wake. Opening the app does **not** stop it: `reset()` (run from
`onPostAuthenticated`, mirroring `LocationPreferences.reset`) **re-seeds** the roster from the current
identity's DB and never clears an ongoing share. The only things that end a share are: every entry
expiring, an explicit `stop()`, or **logout** (the keyValue DB is wiped, so the next re-seed reads an
empty roster).

**GPS is owned solely by `LocationTrackingCoordinator` — `LiveLocationShareService` never touches the
tracker.** The coordinator is the single arbiter of `tracker.start/stop/setMode`; it runs GPS when
the master switch is on **OR** a live share needs it (`wantsGps = trackingEnabled || liveShareActive()`).
The share service only declares its need — `hasLiveShare()` (read by the coordinator's `liveShareActive`
predicate) and `onLiveShareChanged()` (pokes `coordinator.refreshGpsHold()` when a share starts/stops/
expires). Benefits:

- **One owner, no contention** — a share and the master switch can't fight over the tracker, and the
  share ending never stops capture that tracking still wants (and vice-versa).
- **Correct mode for free** — the coordinator's foreground observer applies Foreground (high accuracy)
  while visible and Background (low power, ~60 s) when backgrounded; the share no longer forces
  Foreground.
- **Reliable cold-start re-arm (incl. iOS)** — `coordinator.onProcessStart()` runs on **every** process
  start (Android `MainApplication.onCreate`; iOS `initializeApp`, which also fires on the
  significant-location-change relaunch after a full OS termination) and re-arms GPS whenever
  `wantsGps()` — so a 2-week share resumes capturing after a kill without needing the UI. This is the
  reliability fix; it lives in the existing process-start hook, not in `onPostAuthenticated` (which
  never runs on a headless wake).

**Background liveness is OS-throttled, not real-time.** Android background `LocationRequest` is
`BALANCED_POWER` (~60 s interval, up to 600 s coalescing, 25 m displacement); iOS background uses
`kCLLocationAccuracyHundredMeters` / 50 m `distanceFilter` and may suspend when stationary. The 3 s
throttle is really only exercised in the foreground — in the background expect ~1 packet/min.

**Receiver side:** a backgrounded watcher's notification websocket is typically torn down, so it
gets nothing live until it foregrounds/reconnects — at which point the server's flush-on-connect
delivers each sender's last point automatically.

---

## Received positions — in-memory only (hydration)

The receive side keeps a **volatile** map of each sender's last known position + receipt time
(`LiveLocationReceiveStore`: `StateFlow<Map<OdinId, LivePosition>>`, last-value-wins, fed from
`BackendEvent.LiveRelayReceived`, cleared on logout). A future map/dashboard binds to it. It is
**never persisted to the DB**, by design:

- **The server is the source of truth and rehydrates us.** Live Relay's server retains each sender's
  last point (TTL ~5 min) and **auto-flushes every sender's last point on (re)connect/foreground**.
  So this map starts empty on a cold start and refills from the server within a second of the socket
  connecting — no local copy needed.
- **A persisted copy would render data staler than the server will ever serve** — e.g. an hour-old
  "ghost" position for a feature called *live* location. (So the write-amplification a KV row would
  need to debounce on a 10-person share is a non-problem: we don't write at all.)

This is the deliberate **asymmetry** with the send side: the **send roster** IS persisted (it's the
user's own intent and must survive a cold background wake to keep sending); **received positions** are
not (someone else's ephemeral data, rehydrated from the server). Staleness is derived in the UI from
`LivePosition.receivedAtMs` (`age = now − receivedAt`) to fade and eventually drop a sender's marker
once it exceeds the server TTL.

---

## Files

All new code is `commonMain`/`commonTest` — no platform actuals.

**homebase-api**
- `client/liverelay/LiveRelayContract.kt` — fixed `LIVE_LOCATION_CHANNEL_KEY`, `LiveLocationPoint`,
  `LiveLocationCodec` (base64-JSON).
- `client/liverelay/LiveShareRoster.kt` — `TimedRecipient` + pure roster merge/live helpers.
- `client/liverelay/LiveRelayProvider.kt` — `relay(channelKey, recipients, blob)` →
  `POST /api/v2/live-relay` via `encryptedPostJson`; `LiveRelayRequest` DTO. Registered in `ApiModule`.
- `client/websockets/ClientNotificationType.kt` — add `liveRelay` (by name).
- `client/websockets/LiveRelayReceivedNotification.kt` — the hop-3 `data` DTO.
- `client/websockets/OdinWebSocketClient.kt` — `dispatchNotification` case: log `RECV`, emit
  `BackendEvent.LiveRelayReceived`.
- `client/eventbus/BackendEvent.kt` — `LiveRelayReceived` event.

**homebase-chat**
- `services/livelocation/LiveLocationShareService.kt` — the sender: roster append/expiry, fired from
  the GPS sink, ≥3 s throttle, persisted roster that survives app open/kill until expiry. Owns no
  tracker — declares its GPS need to the coordinator via `hasLiveShare()` / `onLiveShareChanged()`.
- `widget/ConversationContent.kt` — `onLocationClick` toggles the live share to the conversation's
  participants (a persistent toggle, not screen-scoped — it must keep streaming while backgrounded).

**homebase-common**
- `core/location/tracking/LocationTrackingCoordinator.kt` — now the single owner of the GPS tracker
  for live shares too: `liveShareActive` predicate + `wantsGps()` + `refreshGpsHold()`; `onProcessStart`
  re-arms when a share needs GPS (the cold-start / iOS-relaunch reliability fix).

**homebase-core**
- `ui/screens/location/livelocation/LiveLocationReceiveStore.kt` — in-memory
  `StateFlow<Map<OdinId, LivePosition>>` of each sender's last position + receipt time (the map's
  data source); also logs `RECV-DECODED … lat/lon/ageMs/tracked`.
- `di/AppModule.kt` — extend `onPointsBuffered` to drive `onGpsBuffered()`; wire the
  coordinator↔share-service seams (`liveShareActive` / `onLiveShareChanged`); register + lifecycle the
  sender service and the receive store.

**Tests (homebase-api commonTest)**
- `LiveRelayContractTest` — codec round-trip, server-payload parse, no-appId request body.
- `LiveShareRosterTest` — same-recipient-twice keeps two distinct entries, send dedups to unique
  identities, overlapping shares union, expired pruned.

## Verification

1. **CI (this PR)** compiles **all targets**: JVM + Android unit tests, wasmJs tests, Android
   `assembleDebug`, Desktop `createDistributable`, **iOS `linkDebugFrameworkIosArm64`** + sim tests.
   Verified locally: JVM + Android + wasmJs compile, `homebase-api:jvmTest` green.
2. **Manual two-identity flow (the real goal — read the logs):** identities **A** and **B**,
   connected, both on chat. A taps **share location** in a conversation with B. A's
   `homebase.log | grep LiveRelay` shows `SEND ch=7a1e9c40… -> 204` every ~3 s; B's shows `RECV …`
   and `RECV-DECODED … lat/lon`. Confirm the wire `notificationType` is `liveRelay` (absence of
   `RECV` ⇒ wrong wire value).
3. **Background send (the key test):** A taps share, then backgrounds / force-quits. Move so the OS
   emits points → A's log still shows `SEND` (sparser), proving the relay fires from
   `onPointsBuffered` and survives cold-wake via the persisted roster.
4. **Flush-on-connect:** while A shares, force-quit + reopen B → B logs A's last point immediately on
   connect, then resumes live.
5. **Negative:** share to a non-connected identity → no delivery, no client error.

## Out of scope (this build)

- Map / dashboard UX, freshness UI, duration picker, per-conversation stop (today `stop()` is
  stop-all). The per-sender last-value **data** exists (`LiveLocationReceiveStore`); only the
  rendering is deferred to the follow-up UX plan.
- Server changes — the backend is complete in odin-core #1572.
