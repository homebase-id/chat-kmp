# Live Relay — Live GPS Sharing (debug-flow build) — PR #778

## Context

odin-core PR [#1572](https://github.com/homebase-id/odin-core/pull/1572) adds **Live Relay**: a
generic, ephemeral, app-agnostic primitive for streaming live opaque data among already-connected
identities. The motivating use case is live GPS among friends. The server treats the carried bytes
(`blob`) as opaque, stores nothing durably (last-value-wins, TTL ~5 min), enforces app-isolation,
and auto-flushes each sender's last point on (re)connect.

**Goal of THIS build (intentionally minimal):** prove the data path end-to-end with logging only,
then land the plumbing so the real UX can be built on top. The send/receive pipeline (provider,
roster, GPS-sink hook, coordinator ownership, in-memory receive store) is the deliverable; it was
validated by temporarily wiring it to the in-conversation "share location" action and reading the
logs (tag `LiveRelay`) — **that temporary trigger has since been removed** (see "How to activate live
sharing" below). A follow-up plan builds the real UX (map, per-sender last-value, freshness, duration
picker, explicit start/stop) on this plumbing.

### How to activate live sharing (no UI wires it yet)

The plumbing is fully functional but **nothing in the app currently calls it** — the temporary chat
trigger was removed before merge so the half-built feature can't be reached by users. To start/stop a
live share from code (e.g. the upcoming UX), resolve `LiveLocationShareService` from Koin and call:

- `suspend fun start(recipients: List<OdinId>, durationMs: Long = 1h)` — begin (or extend) sharing
  live location to `recipients` for `durationMs`. Appends to the persisted roster; arms GPS via the
  coordinator. Safe to call repeatedly (each call is a distinct share entry).
- `suspend fun stop()` — stop **all** live sharing now.
- `fun isActive(): Boolean` / `fun hasLiveShare(): Boolean` — whether any share window is live.

Everything downstream is already wired and runs whenever a share is active: the GPS-sink relay
(`onGpsBuffered`, registered on `LocationPointStore.onPointsBuffered` in `AppModule`), the
coordinator GPS hold, and the in-memory receive store + websocket dispatch. So the UX layer only
needs a picker/duration and these three calls.

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
- **Auto-expiry** → an entry drops off once its end-time passes; once the roster fully empties the
  service tells the coordinator to drop the GPS hold (see "GPS ownership" below).

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
the user's location-tracking switch (`LocationPreferences.trackingEnabled`) is on **OR** a live share
needs it (`wantsGps = trackingEnabled || liveShareActive()`).
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
`BackendEvent.LiveRelayReceived`; its collector is reset + re-subscribed on each login bootstrap
(`onPostAuthenticated`), which clears the previous identity's positions). A future map/dashboard binds
to it. It is **never persisted to the DB**, by design:

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

All code added or changed is in `commonMain`/`commonTest` — no platform source sets touched.

**homebase-api**
- `client/liverelay/LiveRelayContract.kt` — fixed `LIVE_LOCATION_CHANNEL_KEY`, `LiveLocationPoint`,
  `LiveLocationCodec` (base64-JSON).
- `client/liverelay/LiveShareRoster.kt` — `TimedRecipient` + pure roster helpers: `add`
  (append a share entry, prune expired), `live`, and `liveRecipientIds` (unique fan-out set).
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
- `widget/ConversationContent.kt` — **unchanged in the merged build.** It was temporarily wired
  (`onLocationClick` toggling `start`/`stop`) only to validate the pipeline on real devices; that
  trigger was reverted before merge (see "How to activate live sharing"). The static-location preview
  it already had is untouched.

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

- **CI (this PR)** compiles **all targets**: JVM + Android unit tests, wasmJs tests, Android
  `assembleDebug`, Desktop `createDistributable`, **iOS `linkDebugFrameworkIosArm64`** + sim tests.
  All green; `LiveRelayContractTest` + `LiveShareRosterTest` pass.

### Real-device end-to-end result (2026-06-20) — both halves confirmed

Validated with the (now-removed) temporary chat trigger, frodo (Android, real device) → sam
(Desktop App), already connected. Logs are filtered on tag `LiveRelay`.

- **Send (frodo, Android):**
  - `START +1 entries=1 uniqueLive=1 until=…` then `SEND ch=7a1e9c40… n=1 bytes=172 -> 204` on each
    captured GPS point — every buffered fix produced a `204`, zero `relay failed`.
  - **Background cadence is OS-throttled, as designed:** sends tracked GPS deliveries 1:1, with
    multi-minute gaps when the backgrounded/stationary device emitted no points (not a throttle
    artefact — the ≥3 s gate was never the limiter).
  - **Auto-expiry proven:** a GPS point buffered *after* the share's `until` produced **no** `SEND` —
    the expired roster entry was pruned and the relay correctly stopped.
- **Receive (sam, Desktop):**
  - `RECV from=frodo… ch=7a1e9c40… bytes=172 receivedAt=…` →
    `RECV-DECODED from=frodo… lat=55.84… lon=12.57… ageMs=401 tracked=1`.
  - **Wire serialization confirmed live** — the `liveRelay` case fired, so the server really sends the
    camelCase name `"liveRelay"` and our enum member matched.
  - **Blob decodes cleanly** across the Android→Desktop boundary (no `RECV-DECODE-FAIL`); the position
    landed in `LiveLocationReceiveStore` (`tracked=1`).
  - **End-to-end latency ~400 ms** (`ageMs=401`) across all three relay hops — genuinely live.

### Re-running later (the chat trigger is gone)

To re-verify after merge, drive `LiveLocationShareService.start(recipients)` from code (see "How to
activate live sharing") or the upcoming UX, then watch each side's `homebase.log | grep LiveRelay`.
Still-untested paths worth covering in the UX build: **share with the location-tracking switch OFF**
(exercises the coordinator's `liveShareActive` GPS re-arm, incl. the iOS-relaunch path) and
**flush-on-connect** (relay while the watcher is offline, then connect → server flushes the last
point).

## Out of scope (this build)

- Map / dashboard UX, freshness UI, duration picker, per-conversation stop (today `stop()` is
  stop-all). The per-sender last-value **data** exists (`LiveLocationReceiveStore`); only the
  rendering is deferred to the follow-up UX plan.
- Server changes — the backend is complete in odin-core #1572.
