# Blank Text Investigation — iOS + Web (WASM)

Living record of the "text/labels are missing while the rest of the UI renders" bug on the
Skia-backed targets (iOS and Web). Update as we learn more.

---

## ✅ SOLVED (web) — 2026-06-07 — ROOT CAUSE FOUND, FIX VERIFIED

**The web blank-text bug is NOT a GPU/Skia glyph-atlas issue.** It is a **server MIME
misconfiguration in odin-core (ASP.NET Kestrel)**. The glyph-atlas / first-render-race theory
that dominates the rest of this doc was a wrong turn — keep it for the record but it is superseded.

**Mechanism (proven by hard evidence):**
Compose Multiplatform on web loads its entire string table over HTTP from
`.../composeResources/id.homebase.resources/values/strings.commonMain.cvr`. `.cvr` is an extension
Kestrel's content-type map doesn't know, so `UseStaticFiles` **404s it**, and the chat-wasm SPA
fallback (`chatWasmApp.Run → SendFileAsync(index.html)`) then returns **`index.html`** (5207 bytes,
`text/html`) with a 200. Compose receives HTML bytes for its string table, fails to parse them, and
**every `stringResource()` resolves to empty → all labels blank**. Frames render (no strings); typed
characters render (drawn directly); the one visible error "Valid Homebase ID is required" renders
because it's a **hardcoded literal** (`LoginViewModel.kt:120`), not a resource — which is exactly
why it survived while resource-backed labels did not.

**Decisive evidence** (same bundle bytes, two servers):

| server | `.cvr` content-type | size | sha256 vs disk | renders? |
|---|---|---|---|---|
| Kestrel :443 (odin-core) | `text/html` | 5207 (=index.html) | MISMATCH | **blank** |
| Node :9443 (test server) | `application/octet-stream` | 57557 | match | renders |

**The fix (odin-core `Startup.cs`)** — on BOTH the dev (~line 235) and **production** (~line 352)
chat-wasm `UseStaticFiles(StaticFileOptions)` blocks:
```csharp
ServeUnknownFileTypes = true,
DefaultContentType = "application/octet-stream"
```
Existing `.cvr` (and any future unknown-extension Compose resource) is then served with real bytes;
genuinely missing paths still fall through to the `index.html` SPA fallback (deep-links keep working
— verified: a non-existent path still returns index.html 200). Verified at the wire (sha matches
disk, with compression on too) and visually in-browser on the real Kestrel site.

**Production impact:** `frodo.baggins.demo.rocks` is broken for this exact reason; deploying odin-core
with the production-route fix resolves it. No chat-kmp change is required.

**Shipped:** odin-core **PR #1547** (base `chat-wasm-bundling` #1545) — the `ServeUnknownFileTypes`
MIME fix on the chat-wasm static route, PLUS a shared `SpaFallback` helper applied to every SPA route
(owner/feed/chat/mail/community/chat-wasm) so a missing asset 404s instead of being masked as
`200 index.html` (Accept-based: only `text/html` navigations get the shell). Unit-tested in
`Odin.Hosting.Tests.V2/Hosting/SpaFallbackTests.cs`. The bundle itself is NOT committed (CI's
`build-kotlin-wasm` action builds it from chat-kmp).

**Consequence for iOS:** the web cause was server-side, so the iOS blank text is a SEPARATE bug.
Crucially, this means all the WEB "purge/redraw didn't recover" experiments below tell us NOTHING
about iOS — on web there was never a real glyph atlas problem to recover (the string table was HTML,
so strings were empty; purging glyph caches couldn't help because there were no glyphs to fix). The
iOS evidence (screenshot: all text blank EXCEPT a just-opened menu) stands on its own and is genuine
stale-GPU-atlas evidence. Treat iOS fresh, uncontaminated by the web red herring.

**iOS is a SEPARATE bug.** This cause is web-only — iOS reads composeResources from the app bundle,
not over HTTP, so there is no MIME/server step to fail. The hope that iOS + web shared one root cause
does NOT hold for this finding. iOS's intermittent cold-start blank remains its own investigation
(see the Skia/Metal/CMP-9488 notes below).

---

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

---

## UPDATE — LOCAL REPRODUCTION ACHIEVED (session 2)

**Breakthrough: the blank is reproducible locally, narrowed to odin-core's ASP.NET *Kestrel*
static-file serving on :443 — NOT the build, app, bytes, compression, network speed, HTTP/2,
Brotli, the real-domain origin, or a service worker.**

### Local repro setup (resumable)
- Built bundle: `./gradlew webApp:wasmJsBrowserDistribution --no-configuration-cache -PpublicPath=/apps/chat-wasm/`,
  copied `webApp/build/dist/wasmJs/productionExecutable` → `odin-core/src/apps/Odin.Hosting/client/apps/chat-wasm`.
- odin-core on branch **`chat-wasm-bundling`** (PR #1545; restores the `/apps/chat-wasm` route that
  main reverted via #1543). Run: `dotnet run --no-build --project Odin.Hosting.csproj`,
  ASPNETCORE_ENVIRONMENT=Development, DOTNET_ROOT=/home/seifert/.dotnet9.
- **Local edits to odin-core `Startup.cs` (REVERT when done):**
  1. Added a **dev-mode** `/apps/chat-wasm` static route (~line 225) — #1545's route is in the
     production `else` branch only; in Development `/apps/*` proxies to Vite (:3000–3006) and the
     catch-all swallowed chat-wasm. The dev route serves it via Kestrel static files (the repro).
  2. **Commented out `app.UseResponseCompression();`** (line 91) for the compression test.
- Test URLs (same bundle, same `frodo.dotyou.cloud` host):
  - `https://frodo.dotyou.cloud/apps/chat-wasm/#login` — **Kestrel :443 → BLANK** (the repro)
  - `https://frodo.dotyou.cloud:9443/apps/chat-wasm/#login` — **Node :9443 → RENDERS** (`/tmp/h2/frodo.js`)

### Ruled out this session (all on the same origin / local repro)
- **Bytes**: Kestrel-served (decompressed) sha256 == originals for every wasm/js; range requests sane.
- **Response compression** (Brotli, `EnableForHttps=true`, incl. application/wasm): disabled → still blank.
- **Real-domain origin/hostname**: Node @ `frodo.dotyou.cloud:9443` renders → not the hostname.
- **Service worker**: only one, scoped `/owner/` (not chat-wasm); unregister + cache-clear didn't help.
- **CdnMiddleware**: only sets the `x-odin-cdn-payload` header when CDN enabled; no body manipulation.
- **Backend contention** (the deployed defer-backend fix): disproven — login does zero backend work.

### Still confirmed
- **Fresh-renders-vs-static-blank**: typed text, a just-opened menu, AND the red "Valid Homebase ID
  is required" validation error all render; only already-on-screen/initial text is blank → a
  first-render GPU glyph-atlas state issue, *triggered by* how Kestrel serves the assets.

### NEXT THINGS TO TRY (in order)
1. **Isolate the Kestrel trait** by ADDING Kestrel's response behaviors to the *working* Node:9443
   server one at a time (fast, no odin-core rebuild) until it blanks. Prime suspect first:
   **`accept-ranges: bytes` + `ETag` (+ handle `Range:` → 206)** — i.e. the browser range-requesting
   the 15 MB wasm. Then misc headers (`strict-transport-security`, `x-odin-version`).
2. **Diff DevTools Network** Kestrel:443 (blank) vs Node:9443 (renders): per request → status,
   transferred size, timing, from-cache, and especially **is the wasm fetched via 206 range requests
   on Kestrel vs a single 200 on Node?**
3. **Kestrel static-file delivery mechanics**: `UseStaticFiles`/`PhysicalFileProvider` may
   stream/sendfile/chunk the wasm differently than Node's single `res.end`, shifting when
   `instantiateStreaming` finishes relative to GPU/canvas readiness → losing the first-render
   glyph-atlas race. Test fix: serve chat-wasm with `EnableRangeProcessing=false`, or a one-shot
   `SendFileAsync` middleware mirroring Node.
4. Root issue is still a **skiko/Compose first-render glyph-atlas race** this serving triggers
   (app-side purge/redraw/recomposition all failed; WebGL context-loss crashes skiko). If steps
   1–3 don't yield a server-side fix, **file the JetBrains issue** with this decisive repro:
   *byte-identical bundle renders under Node but is blank under ASP.NET Kestrel static files.*

### Cleanup owed
- Revert odin-core `Startup.cs` (dev chat-wasm route + the commented `UseResponseCompression`),
  rebuild/relaunch. Kill test servers: `pkill -f frodo.js`; `pkill -f "http.server"`.
- chat-kmp `main.wasm.kt` reverted to clean by the user. PR #656 branch `upgrade-compose-1-11` net
  content is now just this doc (1.11 upgrade + defer-backend both reverted).
