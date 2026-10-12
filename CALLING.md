# Calling

Peer-to-peer voice and video calls between connected identities. Media is WebRTC and goes device to device;
signalling rides odin-core Live Relay. This file is the architecture record for all slices.

## Layers

```
UI            calling/CallHost, CallScreens, CallActionButtons, CallLabScreen, CallEventBubble   (homebase-chat commonMain)
Service       services/calling/CallService         one CallController per signed-in identity
Controller    services/calling/CallController      single inbox loop; owns engine, timers, buffered ICE
State machine services/calling/CallStateMachine    pure reducer: (state, event) -> (state, effects)
Seams         CallSignaling, CallEngine            interfaces; LoopbackSignaling + FakeCallEngine are test/lab only
Signalling    LiveRelayCallSignaling               CallSignal <-> LiveRelay blob, push metadata, channel keys
Engines       WebRtcKmpCallEngine (Android, iOS)   webrtcKmpMain source set shared by androidMain + appleMain
              WebRtcJavaCallEngine (desktop JVM)   webrtc-java
Platform glue AndroidCallCoordinator (FCM, Telecom, full-screen notification, foreground service)
              AppleCallCoordinator + CallKitManager.swift (PushKit, CallKit)
              CallRingEffect (no-op; desktop rings from DesktopCallRingSink)
```

`CallService` is a Koin `single`. It tracks the active credentials and builds a new `CallController` whenever the
identity changes. The UI, the push receivers and the CallKit/Telecom glue all call the same instance, so there is
exactly one state machine, one busy check and one answer path.

`platformCallEngineFactory()` is null on wasmJs. `CallService.available` is then false and every entry point
(header buttons, call host, push glue) hides itself: calling fails closed. The fake engine and loopback are never
bound in DI; the Call lab (Developer menu) builds its own private pair.

## Signalling contract (odin-core Live Relay)

Every signal is one `POST /api/v2/live-relay` with `{channelKey, recipients, blob, push?}`.

- **channelKey**: `CallChannel.keyFor(odinId)`, a name-based UUID of `homebase-call-v1:<odinId lowercase>`.
  A signal addressed to X always travels on X's key; each device only listens on its own key, so a callee needs
  nothing but its own identity to receive an offer. `LiveRelayCallSignaling.incoming` drops every other channel
  (live location uses its own constant key).
- **blob**: base64 of the `CallSignal` JSON (`offer`, `ringing`, `answer`, `ice`, `hangup`, `restart`,
  `restart_answer`). `CallSignal.Ringing` is the callee's "my phone is ringing" ack, unrelated to push kind `ring`.
- **push**: the first `Offer` (seq 0) carries `{kind: ring, typeId: RING_TYPE_ID, tagId: callId, unEncryptedMessage: "<name> is calling"}`.
  A caller-side `Hangup` with reason `Cancelled` or `Missed` carries `kind: hangup` with the same `tagId` and
  `HANGUP_TYPE_ID`. Ring and hangup use distinct typeIds (defined once, in `CallPushRouter`, which also routes the
  delivered push by typeId because the payload has no `kind`).
  Everything else (re-published offers, answer, ICE, ringing, declined, normal hangup, glare yield, restart) is sent without a push.
- **last blob wins**: odin-core keeps only the LAST blob per sender per app and flushes it when a socket (re)connects.
  While a call is unanswered every blob the caller sends must therefore be a complete offer. The caller holds its
  local ICE candidates (`CallEffect.HoldIce`) instead of trickling them and re-publishes the offer with the candidates
  folded into the SDP (`SdpCandidates`), silent, with `seq` 1, 2, ... (debounced by `OFFER_REPUBLISH_MS`). Only when an
  `Answer` selects a device does the caller flush the held candidates as `ice` signals and trickle from then on. A
  second callee device that rings first (`Ringing`) changes nothing; a push-woken device reads the retained offer.
  The callee keeps the newest `seq` for the call it is ringing on and ignores older or equal ones.
- **replay**: the controller remembers the last 32 ended call ids and drops any signal or ring push for them, so a
  replayed offer of a finished call neither rings nor answers `Busy`.
- **receive**: `BackendEvent.LiveRelayReceived` becomes `InboundSignal(senderOdinId, signal, receivedAt)`.
  The state machine rejects offers older than the ring timeout, which also covers the event bus replay.
- A relay refusal (non-2xx) for an `Offer` fails the call with `NoRoute`; for ICE or hangup it is only logged.
- `LiveRelayCallSignalingTest` decrypts the request body in a ktor MockEngine and asserts the exact JSON shape.

STUN: `deviceHandshakeSuccess.stunUrls` is stored by `StunUrlStore` (created at app start, so a late-created
`CallService` still sees it) and handed to the engine as given. Nothing is parsed or derived (docs/stun.md).
There is no TURN server: callers behind symmetric NAT will fail to connect.

### Signal confidentiality: decision

The blob is visible to the two servers on the path (the sender's own server and the recipient's, over the mTLS
peer connection). It carries SDP, so a hostile relay could swap DTLS fingerprints and sit in the media path.

Decision for v1: **plaintext blob plus a verification code**, not app-level encryption.

- Both identities already trust their own server for everything else in the app, and the peer hop is mTLS to a
  connected identity, matching the Live Relay plan's recommendation (a) for v1.
- There is no existing per-pair symmetric key to encrypt with. The chat stack encrypts through drive keys, not a
  conversation secret the client can reuse for a transient channel. Inventing one would be new key management.
- Mitigation shipped: `CallVerification.code` hashes the two DTLS fingerprints (sorted) into `123 456`. Both ends
  show it as a chip in the call; if the two codes differ the setup was tampered with. It is 20 bits, so it detects
  accidental and lazy tampering but a relay that can grind certificates could match it. Treat it as a speed bump.
- Follow-up when a pair key exists: encrypt `blob` with AES-GCM under that key and bind the fingerprints into the AAD.
  `CallSignalCodec` is the only place that would change.

## State machine

Phases: Idle, Outgoing, Incoming, Connecting, Connected, Reconnecting, Ended(reason). Events carry user actions,
inbound signals, push ring/hangup, timers, media events. Effects are the only side channel.

- **One path for rings.** A ring push before the socket offer starts an `Incoming` call with no SDP
  (`PushRing`). The offer with the same callId fills it in; accepting before the offer arrives sets `acceptPending`
  and answers the moment it lands. A hangup push retracts an unanswered ring.
- **Busy and glare.** A second inbound call is refused with `Busy`; crossed offers between the same pair resolve by
  odin id ordering: the lower id keeps its offer and ignores the peer's, the higher id abandons its own call, sends
  `Hangup(Normal)` for it (no push, so no missed-call alert) and answers the lower id's offer.
- **ICE restart.** `Disconnected` moves to `Reconnecting`, starts the reconnect timer and, on the original caller's
  side only, emits `RestartIce`. Every timer tick (up to `MAX_RECONNECT_ATTEMPTS`) restarts again. The engine's new
  offer travels as `Restart`, the answer as `RestartAnswer`. Only the caller drives it so the sides never offer at once.
- **Thread safety.** All mutable controller state (engine, timers, buffered ICE, SDPs) is touched only on the inbox
  loop. `close()` posts a `Close` command; scope cancellation runs the same teardown in the loop's `finally`.
- **Other devices of the same user.** Live Relay fans out to connected peer identities (`LiveRelayService.RelayAsync`
  resolves each recipient through the circle network), so a sender does not receive its own relay. "Answered on
  another device" is therefore not detectable and was removed. Consequence: a callee's other devices keep ringing
  until the ring timeout (45 s) or a caller-side hangup push. Revisit if odin-core adds self-delivery.

## Call log message (C3)

`MessageContent.CallEvent`, `ChatProtocol.ChatCallEventMessageDataType = 217`, descriptor `CallEventDescriptor`
(`outcome`, `video`, `durationSeconds`, `schemaVersion`; no ids, author or time). The caller writes one message per
call when the machine reaches `Ended` (`callEventFor`): Completed with duration, NoAnswer, Declined, Cancelled, Busy;
failures are not logged. The receiver renders "Missed call" from the envelope author. Older clients show the
`Unknown` chip. Parse failures yield `CallEvent(null)` and an unparseable chip, never `null`.

## Engines

| Target | Engine | Notes |
|---|---|---|
| Android | `webrtc-kmp` 0.125.11 (Google WebRTC M125) | `CallAudioRouter` sets `MODE_IN_COMMUNICATION` and routes earpiece/speaker; video via `SurfaceViewRenderer` |
| iOS | same engine, `RTCMTLVideoView` renderer | `AVAudioSession` PlayAndRecord/VoiceChat; CallKit owns activation |
| Desktop | `webrtc-java` 0.19.0 + natives for macOS/Windows/Linux | frames converted to `ImageBitmap`; camera/mic capture untested on Windows/Linux |
| Web | none | `platformCallEngineFactory()` is null, calling hidden |

webrtc-kmp has no JVM variant, so Android and Apple share the `webrtcKmpMain` intermediate source set instead of
duplicating the engine. Desktop bundles all five webrtc-java natives (also declared in homebase-core because
desktopApp consumes a repackaged chat jar).

Both engines map native callbacks through `PeerEventMapper` (`MediaEventMappingTest` drives it from a fake peer connection
through real controllers). Both are idempotent on `close()`; the desktop engine serialises native calls with the close lock,
so a late ICE candidate is dropped instead of reaching a disposed connection.

Local self-preview is mirrored only for the user-facing camera (`CameraFacing.mirrorsLocalPreview`), at the renderer: the
Android `SurfaceViewRenderer`, the iOS view transform and the desktop canvas. The transmitted stream is never mirrored.
Android renders on webrtc-kmp's `WebRtc.rootEglBase` (the capturer's GL context) and keys the renderer on the track.
The engine reports its initial audio route (`MediaEvent.RouteChanged`): wired, then Bluetooth, else speaker for video and
earpiece for voice. Android also takes `AUDIOFOCUS_GAIN_TRANSIENT` for the call.

Desktop camera and microphone: macOS prompts on first capture (the JVM permission gate has nothing to ask); the packaged app
needs `NSCameraUsageDescription` and `NSMicrophoneUsageDescription` (Compose `infoPlist` and Conveyor `mac.info-plist`).

Tests: `WebRtcJavaCallEngineTest` runs two real webrtc-java engines over `LoopbackSignaling` through two
`CallController`s, reaches `Connected`, checks both sides compute the same verification code, then hangs up and
exercises a real ICE restart. It also proves the natives load on the host.

## Platform glue

- **Android**: `DriveFcmService` hands ring/hangup pushes to `CallPushRouter` before the WorkManager hop.
  `AndroidCallCoordinator` registers a self-managed Telecom account and reports incoming calls
  (`HomebaseConnectionService`), posts a `CallStyle` full-screen-intent notification (`IncomingCallNotifier`),
  starts a microphone/camera foreground service while a call is live. `CallRingCoordinator` (homebase-chat) routes
  the pushes into `CallService` and derives every notification/Telecom/foreground-service action from
  `CallService.state` alone, so a hangup mid-lookup or a ring refused while busy cannot leave a ring behind.
  Push events are queued in arrival order inside `CallService` until the identity binds. The launch from the
  notification may show over the lock screen only until the call ends. Outgoing calls are not Telecom-registered.
  "Accept" opens `MainActivity`, asks for the microphone if needed, waits for the call to be `Incoming` and accepts;
  "Decline" sends the decline hangup. A force-stopped app receives no FCM until the user opens it again (Android).
- **iOS Simulator**: `CallKitBridge.hostsOutgoingCalls` is false there and `CallKitMirror` then does not register
  outgoing calls. Evidence (unified log of the simulator, 5 of 5 outgoing attempts): `callservicesd` fails to open
  `facetime://?showInCallUI=1`, logs "Disconnecting call because there wont be a UI to host the call" and sends
  `CXEndCallAction`, which the app maps to a user hangup, so the caller cancelled its own call 1-3 s after the offer.
  A real device keeps the call. Call controller decisions are logged under the tags `CallController` and
  `CallStateMachine`; every `hangup()` and `decline()` logs its caller's stack.
- **iOS** (the `CallSystemUi` seam: `CallKitBridge`/`CallKitActions` in homebase-common, `CallKitManager.swift` is the iOS
  actual, `CallKitActionsAdapter` and `CallKitMirror` in homebase-chat are the Kotlin side). `CallKitManager.swift`
  registers PushKit, reports every VoIP push to CallKit before any other work, and forwards the payload to `CallPushRouter`.
  Every bridge call is hopped onto the main queue, which owns the call-UUID maps and `RTCAudioSession.useManualAudio`.
  `CallKitRingRouter` ends the CallKit call of any ring that does not leave the service ringing on that callId (replayed
  or ended id, busy, from self), and Swift ends it when no handler is set or the push is not a call. `CallKitMirror`
  mirrors outgoing/connected state, asks CallKit to answer (`requestAnswer`, a `CXAnswerCallAction`) when the user
  accepts a call CallKit is still ringing from inside the app so `didActivate` fires, and ends a locally declined or
  hung-up call through `requestEnd` (`CXEndCallAction`); remote ends use `reportCall(endedAt:)`. CallKit's answer, end and
  mute buttons go through `CallKitActionsAdapter` to the controller (tested in commonTest with a fake bridge). The VoIP
  token is sent as `voipDeviceToken` next to the FCM token (`NotificationService.onVoipToken`) and held in `VoipTokenRelay`
  until Koin is up. The relay needs the Apple `.p8` key before VoIP pushes arrive; until then rings arrive as
  time-sensitive alerts. While a call is registered with CallKit, `RTCAudioSession.useManualAudio` is on and audio is
  enabled in the provider's `didActivate` (off in `didDeactivate`); with no CallKit call it is off.
  `CXCallUpdate.hasVideo` is set from the offer via `reportVideo`. **Not verified on a device.**
- **Desktop**: the app is the receiver (socket offers). `DesktopCallRingSink` (driven by `CallRingCoordinator` from
  `CallService.state`, started in `startDesktopCallCoordinator`, so it works with the window minimised) posts the
  incoming-call notification through `RichNotificationDisplayer`, raises the window with
  `DesktopAppFocusManager.requestFocus("incoming-call")`, and beeps every 2 s until the call stops ringing; the in-app
  incoming screen is `CallHost`. No background ring when the app is closed.

## Known limits

- No TURN; no group calls; voice-to-video upgrade mid-call is not supported (the video track is added only when the
  call starts as video).
- Verification code is weak against a hostile relay (see above).
- Mic/camera permission is requested before Accept/Call (`CallPermissionGate`); a denied camera downgrades to voice.
- iOS link: webrtc-kmp does not bring the WebRTC framework, so `iosApp` links the SPM package `stasel/WebRTC`
  pinned to 125.0.0 (M125). Bump it together with webrtc-kmp. The extensions do not need it.
- webrtc-kmp is stale (pins M125); plan to carry patches if a blocker appears.
- Pending: end-to-end on iOS hardware (CallKit, PushKit), Windows/Linux desktop capture.
