# Calling: WebRTC libraries

Decision record for P2P calling (slice C0). No call logic yet; `homebase-chat/.../calling/WebRtcBackend*.kt`
is a compile proof only.

| Target | Engine | Version | Wired |
|---|---|---|---|
| Android | `com.shepeliev:webrtc-kmp` (Google WebRTC M125 via `io.github.webrtc-sdk:android`) | 0.125.11 | `androidMain` |
| iOS | same `webrtc-kmp` (Kotlin API over the ObjC `WebRTC` framework) | 0.125.11 | `appleMain` |
| Desktop JVM | `dev.onvoid.webrtc:webrtc-java` + natives (macos aarch64/x86_64, windows x86_64, linux x86_64/aarch64) | 0.19.0 | `jvmMain` |
| wasmJs | browser `RTCPeerConnection` (webrtc-kmp also ships a wasmJs artifact) | n/a | not wired; `Unsupported` stub |

## Why

- **webrtc-kmp**: the only maintained KMP WebRTC API with Android + iOS. Last release 2025-09-07, M125, built with
  Kotlin 2.1.21; it compiles against this repo's Kotlin 2.3.21 / CMP 1.10.3 on Android and iosSimulatorArm64.
  It has no JVM target (open upstream JVM PRs are drafts), so it cannot serve desktop.
- **webrtc-java**: active (pushed 2026-10-11, release 0.19.0 on 2026-09-27), JNI natives published as Maven
  classifier jars. `WebRtcJavaNativesTest` loads the macOS arm64 natives and creates a real offer, so desktop
  calling is feasible. Windows/Linux natives are declared but not exercised here.
- Common code cannot depend on webrtc-kmp (no JVM variant). Later slices put a `CallEngine` interface in
  commonMain and the engines in androidMain/appleMain/jvmMain.

## Known limits

- **iOS link step (not done)**: webrtc-kmp does not bring the WebRTC framework transitively. This repo uses SPM,
  not CocoaPods, so the first slice that really runs WebRTC on iOS must add the SPM package
  `https://github.com/stasel/WebRTC` (M125-compatible tag; webrtc-kmp pins the pod `WebRTC-SDK` 125.6422.07) to
  `iosApp.xcodeproj` for the app target. Kotlin compile is green without it.
- No TURN server: callers behind symmetric NAT will fail to connect.
- iOS VoIP push: the server has no Apple `.p8` key yet; CallKit needs a real iPhone.
- Desktop: natives for all five platforms are on the runtime classpath of the JVM jar, so each platform jar grows
  by the other platforms' natives; splitting them per Conveyor platform (like `ffmpeg/`) is deferred. Mic/camera
  capture on desktop (`AudioDeviceModule`, `VideoDeviceSource`) is untested.
- webrtc-kmp is stale (13 months) and pins M125; plan to carry patches or fork if a blocker appears.
- wasmJs: wire browser `RTCPeerConnection` via JS interop later; until then the backend reports `Unsupported`.
