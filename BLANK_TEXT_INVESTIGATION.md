# Blank Text Investigation — iOS + Web (WASM)

Living record of the "text/labels are missing while the rest of the UI renders" bug on the
Skia-backed targets (iOS and Web). Update as we learn more.

## Symptom

- UI frames, boxes, icons and images render normally, but **text/labels are blank**.
- **iOS:** intermittent — manifests on **cold start**, or after the app has been **idle for a
  very long time**. Not reproducible on demand.
- **Web (wasm):** on the live deploy (`frodo.baggins.demo.rocks/apps/chat-wasm`) the login screen
  is **consistently** text-less. Works fine when run/served locally.
- **Tell-tale:** *freshly drawn* glyphs render fine while *already-on-screen* glyphs stay blank:
  - Web: characters **typed into the login field appear** (the visible text is Skia-drawn; the
    `<input>` is `color:transparent z-index:-1` — Compose's IME-capture only).
  - iOS: a screen is text-less, but **opening the three-dot menu shows that menu's text** (the
    menu is freshly composed; confirmed in source that the menu does **not** trigger the existing
    nudge — `TextRenderingHelper.nudge()` only fires at `AppNavHost.kt:332/383/417`).

## Root cause (current best understanding)

A **stale / never-populated Skia GPU glyph atlas**. Skia rasterizes glyphs once into a GPU texture
atlas and records which are "resident." If the atlas contents are missing/dead but Skia still
thinks them resident, already-drawn text samples dead atlas slots → blank, while atlas-miss glyphs
(new text) get rasterized + uploaded fresh → visible. Frames survive because solid fills don't
sample the atlas. This is the **shared layer** between iOS and Web (both use skiko:
`SkiaFontLoader` / `FontFamilyResolverImpl` / `TypefaceRequestCache`); Android (native
`android.graphics`) and Desktop (JVM/AWT font manager) are unaffected.

Triggers differ per platform:

- **Web:** **PROVEN to be the live deployment runtime, not the build.** The *same deployed bytes*
  render under a local Python server but go blank when served by the live server. **frodo is served
  directly by the odin-core Kestrel backend — there is NO CDN** (the `x-odin-cdn-payload:
  https://cdn.ravenhosting.cloud` response header is misleading: it's an odin-core header, the
  payload is not actually fetched through a CDN). So the difference is purely Kestrel's HTTP serving
  (HTTP/2 + Brotli) vs the local servers.
  - ~~Backend-contention hypothesis~~ **RULED OUT.** The live console logs show the login screen does
    **zero** backend work (unauthenticated: `wsClient=null`, `dsmRunning=false`, no WebSocket, no
    sync, `DriveSync stop() size=0`), and the deferred `promoteToForeground()` logged "already
    foreground, no-op." Same minimal work as localhost, yet blank — so it is not startup/backend
    contention.
- **iOS:** **cold start** (first paint races the Metal surface/atlas) and **long idle** (iOS
  reclaims GPU resources during long background/idle; on resume the cached glyph atlas is dead).
  The earlier background-GPU variant — JetBrains **CMP-9488** "dirty font cache when entering
  background" (inverted `MetalRedrawer.isActive` guard submitting GPU work from background) — is
  **already fixed in Compose 1.10.1**, which the repo has, so that path is closed.

## Ruled out (web), with method

| Hypothesis | How tested | Result |
|---|---|---|
| App build / artifact | Served the **deployed bytes** via local Python (`:8003`) | Renders → not the build |
| Sub-path hosting (`/apps/chat-wasm/`) | Built with `-PpublicPath=/apps/chat-wasm/`, served at that path locally (`:8002`) | Renders → not the sub-path |
| Network speed / latency | DevTools throttle to 3G/4G on local serve | Renders (slowly) → not speed |
| Brotli compression | Served deployed bytes Brotli-compressed like Kestrel (`:8004`, custom Python server) | Renders → not compression |
| Skia version | Upgraded to Compose **1.11.1 / Skia m144**, deployed | Still blank → not a Skia-version bug (upgrade later reverted; see below) |
| Font availability | `Graphics.fontCacheUsed` was 16504 before purge | Glyphs **do** rasterize → not missing fonts |
| Font file 404 | No `.ttf/.otf` in the dist; default font embedded in `skiko.wasm` | n/a — nothing to 404 |
| MSAA / antialiasing | Canvas GL `samples: 0`, `antialias: false` | Not MSAA (the recurring `READ_BUFFER attachment is multisampled` warning is from an internal Skia target and is benign — Flutter CanvasKit emits it too) |
| DevicePixelRatio mismatch | `dpr: 1`, canvas buffer == css size (1583×573) | Not DPR |
| Browser-specific (Firefox) | Reproduced in **Edge** (Chromium/ANGLE) too | Not browser-specific |

**Being tested now:** **HTTP/2** in isolation — a local Node HTTP/2 + Brotli server (self-signed
cert) serving the deployed bytes at `https://localhost:8443/apps/chat-wasm/`, mimicking Kestrel's
serving exactly. (No CDN to test — frodo serves directly from odin-core Kestrel.) If `:8443`
reproduces the blank, we have a **local repro**; if not, the remaining plan is to run a **local
odin-core Kestrel backend** with the exact frodo setup and reproduce against that.

## Console commands run on the live (blank) site + results

```js
// 1. Plain redraw — NO effect (reuses stale text blobs).
window.dispatchEvent(new Event('resize'))

// 2. Manual purge hook (deployed in main.wasm.kt). Logged: fontCacheUsed 16504 -> 0, still blank.
//    => Graphics.purgeAllCaches() clears the CPU strike cache but NOT the per-context GPU atlas.
window.dispatchEvent(new Event('homebase:recover-text'))

// 3. Real container resize (triggers Compose ResizeObserver / relayout). Redrew, still blank.
(() => { const el = document.getElementById('ComposeApp'); const h = el.clientHeight;
  el.style.height = (h - 2) + 'px'; setTimeout(() => { el.style.height = ''; }, 150); })()

// 4. Canvas discovery — the Compose canvas lives in a SHADOW ROOT (querySelector('canvas') is null).
(() => { const deep=[]; const walk=r=>r.querySelectorAll('*').forEach(e=>{if(e.tagName==='CANVAS')deep.push(e); if(e.shadowRoot)walk(e.shadowRoot);}); walk(document);
  return { plain: document.querySelectorAll('canvas').length, deep: deep.length, info: deep.map(c=>c.width+'x'+c.height) }; })()
// => { plain: 0, deep: 1, info: ["1583x573 @shadow"] }

// 5. GL state + WebGL context loss/restore on the shadow canvas.
//    [state] => { buffer:"1583x573", css:"1583x573", dpr:1, drawingBuffer:"1583x573",
//                 antialias:false, samples:0, contextLost:false }
//    loseContext() => "WebGL context was lost" + kotlin.RuntimeException + whole canvas went BLACK.
//    => skiko does NOT survive a WebGL context loss (throws); context-recreation is not a console-recoverable path.
```

## Local reproduction matrix

| Server | publicPath | encoding | Result |
|---|---|---|---|
| Python `:8000` | `/` | none | text ✓ |
| Python `:8002` | `/apps/chat-wasm/` | none | text ✓ |
| Python `:8003` (deployed bytes) | `/apps/chat-wasm/` | none | text ✓ |
| Python `:8004` (deployed bytes) | `/apps/chat-wasm/` | **brotli** | text ✓ |
| **Kestrel / frodo (live)** | `/apps/chat-wasm/` | brotli, HTTP/2 | **blank ✗** |

Deployed `skiko.wasm` is **byte-identical** to the local build (hash `6e23e5428398b92da386`); the app
wasm/js differ only by ~100/~24 bytes (build timestamp). Conclusion: **same code + same browser/GPU,
only the server differs.**

## Fixes attempted

1. **Compose 1.11.1 / Skia m144 upgrade** (commit `55b85b72`) — to test the Skia-version
   hypothesis. Did **not** fix web. Also pulled compose-ui/foundation to a mismatched **1.10.2**
   transitively (material3 1.11.0-alpha07 constraint), which broke CI
   (`checkAndroidMainAarMetadata`, `Skiko dependencies' versions are incompatible`). A `force()`
   align attempt then surfaced an unrelated m144 `Path`→`PathBuilder` API break in
   `ImageUtil.skia.kt`. **Reverted entirely** — wrong hypothesis + CI liability.
2. **Web: `Graphics.purgeAllCaches()` + redraw** (console hook) — no effect (purge doesn't reach
   the GPU atlas).
3. **Web: purge + forced full re-composition** (re-key the tree) — no effect on the live deploy
   (confirmed live via wasm string markers); same reason — the per-context GPU atlas survives.
4. **Web: defer backend connect past first paint** (`main.wasm.kt`) — **deployed, did NOT work.**
   The live logs then showed *why*: the login screen does no backend work at all (see "RULED OUT"
   above), so there was nothing to defer. Disproven; the deferred call was a no-op. (Still in the
   branch; should be reverted once a real fix lands — keep the manual `homebase:recover-text` hook.)

## Current status / next steps

- Branch `upgrade-compose-1-11` / PR #656: 1.11 upgrade reverted (net diff is just `main.wasm.kt`,
  the now-disproven defer-backend change + the manual `homebase:recover-text` hook). The PR title is
  stale and the defer-backend change should be reverted once a real fix lands.
- It's the **odin-core Kestrel HTTP serving** (HTTP/2 + Brotli), not the build, not the app, not the
  backend, not the network speed, not Brotli alone. **HTTP/2 is the prime remaining suspect.**
- **In progress:** test HTTP/2 locally (`https://localhost:8443/apps/chat-wasm/`, Node HTTP/2 +
  Brotli, deployed bytes):
  - Blank → **local repro** of the HTTP/2 trigger → iterate the real fix locally, no more deploys.
  - Renders → HTTP/2 ruled out → **run a local odin-core Kestrel** with frodo's exact setup and
    reproduce against that, then bisect the serving config.
- Once a fix is found and verified: **port it to iOS** (cold start + foreground-after-long-idle).
