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
| HTTP/2 | Local Node **HTTP/2 + Brotli** server, deployed bytes (`:8443`) | Renders → not HTTP/2 |
| Brotli framing (chunked, no `content-length`) | `:8443` re-served with **streamed Brotli, no content-length** (exactly Kestrel's framing) | Renders → not the framing |
| Wrong content-type for wasm | `text/html` seen for a `.wasm` was the **SPA fallback for a stale hash**; current real files (`0c7a3ff…` skiko, `1e6a451a…` app) serve as `application/wasm` | Not content-type |

**Piecewise mimicry exhausted.** Every individually-replicable attribute of Kestrel's response —
HTTP/2, Brotli (incl. streamed/no-content-length framing), `application/wasm` content-type, the exact
bytes — was reproduced on a local server and **renders every time**. Only the actual odin-core Kestrel
server blanks. So either it's the precise combination/timing of the real server, or a genuine
timing-sensitive skiko/Compose web rendering race that this server happens to trigger.

**Plan B (in progress):** stand up a **local odin-core Kestrel + odin-js** with frodo's exact setup,
wire in the freshly-built chat-wasm bundle, and reproduce against the real server locally — then bisect
its serving config / get a fix-iteration loop without frodo deploys.

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
| Python `:8004` (deployed bytes) | `/apps/chat-wasm/` | **brotli** (HTTP/1) | text ✓ |
| Node `:8443` (deployed bytes) | `/apps/chat-wasm/` | **brotli, HTTP/2** | text ✓ |
| Node `:8443` (deployed bytes) | `/apps/chat-wasm/` | **brotli streamed, no content-length, HTTP/2** | text ✓ |
| **Kestrel / odin-core / frodo (live)** | `/apps/chat-wasm/` | brotli, HTTP/2 | **blank ✗** |

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
- It's the **odin-core Kestrel HTTP serving**, full stop — not the build, app, backend, network speed,
  Brotli, HTTP/2, content-type, or response framing (all reproduced locally → render).
- **In progress (plan B):** stand up local **odin-core** (backend) + **odin-js** (frontend), wire in a
  freshly-built chat-wasm bundle, reproduce against the real Kestrel locally, then bisect the serving.
- Once a fix is found and verified: **port it to iOS** (cold start + foreground-after-long-idle).

## Notes for a JetBrains (Compose Multiplatform) issue report

Copy/adapt when filing. The hook that makes this report unusually strong: **byte-identical app, same
browser + GPU, same protocol/compression — text renders under one HTTP server and is blank under
another.**

- **Title:** [Web/wasmJs] Text/glyphs blank (frames render) when the wasm app is served by ASP.NET
  Kestrel, but renders identically-byte-for-byte under other HTTP servers.
- **Compose Multiplatform:** 1.10.3 (also reproduced on 1.11.1). **Kotlin:** 2.3.21. **Target:**
  `wasmJs` / `ComposeViewport`. **Browsers:** Firefox and Edge (Chromium/ANGLE) — both reproduce.
- **Symptom:** All `Text` is blank; non-text (boxes, icons, images) renders. **Freshly-composed**
  glyphs render (characters typed into a `BasicTextField`; a just-opened dropdown/menu) while
  already-on-screen glyphs stay blank → looks like a stale/never-populated GPU glyph atlas.
- **Canvas/GL state (DevTools, shadow-root canvas):** `1583×573`, `dpr 1`, `antialias false`,
  `samples 0`, `contextLost false`. `WebGL_debug_renderer_info` and `READ_BUFFER attachment is
  multisampled` warnings appear but are benign (also in Flutter CanvasKit).
- **The decisive observation:** the *exact same* built bytes (skiko.wasm byte-identical) **render**
  when served by Python `http.server` and a Node `http2` server (tested: HTTP/1.1 & HTTP/2; raw &
  Brotli; Brotli with fixed `content-length` & streamed/no-`content-length`; root & sub-path
  hosting), but are **blank** when served by the production **ASP.NET Core Kestrel** backend. Same
  machine, same browser, same GPU.
- **Recovery attempts that do NOT fix it (web):** `org.jetbrains.skia.Graphics.purgeAllCaches()` +
  redraw; forced full re-composition (re-key the tree); window/container resize. `WEBGL_lose_context`
  → skiko throws `kotlin.RuntimeException` and the canvas goes black (skiko does not survive WebGL
  context loss).
- **Parallel iOS symptom (likely same root):** intermittent blank text on **cold start** and after
  **long idle** (GPU-resource reclamation); a just-opened menu shows text. Background-entry variant
  was CMP-9488, fixed in 1.10.1.
- **Asks for JetBrains:** is there a known glyph-atlas population/residency issue sensitive to the
  HTTP response timing/streaming of the wasm? Is there a supported way to force a glyph-atlas
  rebuild / GrDirectContext resource purge from app code (the global `Graphics.purgeAllCaches` does
  not reach the per-context GPU atlas)? Does skiko handle/should it handle WebGL context loss?
