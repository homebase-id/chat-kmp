# Plan 010: Delete three independent piles of dead code

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first):
> `git diff --stat 45e2832e..HEAD -- archive/transcoder_v2 homebase-chat/src/jvmTest/kotlin/id/homebase/api/video/FFmpegCompressBaselineJvmTest.kt homebase-api/src/androidDeviceTest/kotlin/id/homebase/api/video/CompressVideoAndroidInstrumentedTest.kt FFMPEG_COMPRESSION_NOTES.md homebase-common/src/commonMain/kotlin/id/homebase/core/util/FileUtilities.kt homebase-common/src/androidMain/kotlin/id/homebase/core/util/FileUtilities.android.kt homebase-common/src/jvmMain/kotlin/id/homebase/core/util/FileUtilities.jvm.kt homebase-common/src/nativeMain/kotlin/id/homebase/core/util/FileUtilities.native.kt homebase-common/src/wasmJsMain/kotlin/id/homebase/core/util/FileUtilities.web.kt homebase-common/src/commonMain/kotlin/id/homebase/core/util/KeyboardUtils.kt homebase-common/src/nativeMain/kotlin/id/homebase/core/util/KeyboardUtils.native.kt homebase-common/src/jvmMain/kotlin/id/homebase/core/util/KeyboardUtils.jvm.kt homebase-common/src/androidMain/kotlin/id/homebase/core/util/KeyboardUtils.android.kt homebase-common/src/wasmJsMain/kotlin/id/homebase/core/util/KeyboardUtils.web.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/requests/ConnectionRequestService.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/contact/ConnectionCacheRepository.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/data/IncomingConnectionRequestUiModel.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/data/OutgoingConnectionRequestUiModel.kt`
> If any in-scope file changed since this plan was written, compare the Current state excerpts to live code first; on mismatch, STOP.

## Status
- Priority: P2
- Effort: S
- Risk: LOW
- Depends on: none
- Category: tech-debt
- Planned at: commit 45e2832e, 2026-06-14

> ### Drift / framing note (read before starting)
> The spec for this plan described Item B as "expect/actual stubs". That is only partly accurate against the live code (verified at 45e2832e), so the steps below correct it:
> - `editFile` and `openAppStore` are **interface methods on `FileSystemHandler`** (commonMain), *not* `expect fun` declarations. They are overridden as `TODO("Not yet implemented")` in the android/jvm/native `object : FileSystemHandler` actuals, and as `{}` (empty no-op) in the **web** actual (`FileUtilities.web.kt`). The web actual exists and the spec did not list it — it is in scope.
> - `keyboardHeightAsState` IS a genuine `expect fun` (commonMain `KeyboardUtils.kt:29`). But only the **native (iOS)** actual is a throwing `TODO()`. The android, jvm, and web actuals have **real implementations** (window-insets listener on Android; `mutableStateOf(0)` on jvm/web). Removing the `expect` therefore deletes 3 working actuals along with 1 stub — which is correct *only because there are zero callers* (verified below). If a caller is found, STOP.
> - All three members (`editFile`, `openAppStore`, `keyboardHeightAsState`) have **zero callers** in non-`build` source (verified via grep at plan time).
>
> For Item A: the spec said "the two tests hardcoded fixture paths". Verified: only **one** test actually loads the fixture binary — `FFmpegCompressBaselineJvmTest.kt`. Two other files (`CompressVideoAndroidInstrumentedTest.kt` and the `FFMPEG_COMPRESSION_NOTES.md` doc) reference `archive/transcoder_v2/...` **only in comments/prose**, not as a loaded path. Those comment references must still be updated so the final grep for `transcoder_v2` returns nothing.
>
> codeMatchedFinding for the line numbers: the Item C assignment lines drifted to 277/293/322/333 (service) and 63/80 (repo) — matches the spec. Item B `editFile` is `FileUtilities.android.kt:36-38`, `openAppStore` is `:230-232`; `FileUtilities.jvm.kt` `editFile` is `:22-24`, `openAppStore` `:124-126`; native `editFile` `:48-50`, `openAppStore` `:294-296`; `KeyboardUtils.native.kt:7-8` is the iOS stub. The spec's cited line numbers were slightly off (it said android editFile line 37, jvm openAppStore 125, native keyboard 7) but point at the right members.

## Why this matters
The repo carries three disconnected piles of dead code that cost reader time and invite drift. (A) `archive/transcoder_v2/` is a 17-file dormant Android transcoder that is not declared in `settings.gradle.kts` and is imported by zero production code; it lingers only because one JVM benchmark test walks the tree to find a committed `.mp4` fixture. (B) Three interface/expect members (`editFile`, `openAppStore`, `keyboardHeightAsState`) are unreachable — every one is either a throwing `TODO()` or has no caller — so they are latent crashes that look like real API. (C) `senderName`/`recipientName` on the two connection-request UI models are set to a literal `"TODO …"` placeholder that the UI never reads, so the wrong-looking string ships in models and the two write-only fields can silently drift. Deleting all three shrinks the surface, removes the `"TODO …"` placeholders from shipped objects, and turns "is this used?" from a research task into a non-question.

## Current state

### Item A — `archive/transcoder_v2/`
- **`settings.gradle.kts`** — does NOT contain `transcoder_v2` (grep returns nothing). The directory is not a Gradle module; nothing builds it.
- **`archive/transcoder_v2/src/...`** — 17 `.kt` files under `androidMain` + `androidDeviceTest` (e.g. `HomebaseVideoTranscoder.kt`, `internal/PreflightProbe.kt`, `internal/gl/TextureRender.kt`). Package `id.homebase.api.video.transcoder_v2`. Zero production imports.
- **`archive/transcoder_v2/test-fixtures/`** — three committed binaries: `bbb_1080p_2mb.mp4`, `bbb_1080p_10mb.mp4`, `hdr10_720p.mp4`. Only `bbb_1080p_2mb.mp4` is loaded by a live test.
- **`homebase-chat/src/jvmTest/kotlin/id/homebase/api/video/FFmpegCompressBaselineJvmTest.kt`** — the ONLY live consumer of a fixture. Relevant lines:
  - `:25` KDoc: ``The fixture lives at `archive/transcoder_v2/test-fixtures/bbb_1080p_2mb.mp4` ``
  - `:138` `val fixtureSrc = findArchiveFixture("bbb_1080p_2mb.mp4")`
  - `:140` `"bbb_1080p_2mb.mp4 fixture missing from archive/transcoder_v2/test-fixtures/",`
  - `:187-195` the `findArchiveFixture(name)` helper walks parents looking for ``File(dir, "archive/transcoder_v2/test-fixtures/$name")`` (`:190`).
  - The class is `@Ignore("manual ffmpeg benchmark — run on demand, see KDoc")` (`:114`), so `jvmTest` does not execute it; it must still **compile**.
- **`homebase-api/src/androidDeviceTest/kotlin/id/homebase/api/video/CompressVideoAndroidInstrumentedTest.kt`** — comment-only refs at `:43-45` and `:121` (``archive/transcoder_v2/.../CompressVideoV2InstrumentedTest.kt``, ``archive/transcoder_v2/README.md``, ``benchmark test in archive/transcoder_v2``). No fixture is loaded here.
- **`FFMPEG_COMPRESSION_NOTES.md`** — `:49` doc line ``archive/transcoder_v2/test-fixtures/bbb_1080p_2mb.mp4`` (prose only).

Convention: JVM test fixtures belong in `homebase-chat/src/jvmTest/resources/` and are read with `Class.getResource(...)` / classpath, not via filesystem parent-walking. Exemplar of a jvmTest reading a resource off the classpath: search `homebase-chat/src/jvmTest` for an existing `getResource`/`resources/` usage; if none exists, the self-contained filesystem approach below (place fixture under `jvmTest/resources/` and read it via `this::class.java.getResource`) is the target.

### Item B — unreachable interface/expect members
- **`homebase-common/src/commonMain/kotlin/id/homebase/core/util/FileUtilities.kt`** — interface declarations:
  - `:11` `fun editFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit = {})`
  - `:27` `fun openAppStore(onError: (Throwable) -> Unit = {})`
- **`FileUtilities.android.kt`** — overrides `:36-38` (`editFile` → `TODO`), `:230-232` (`openAppStore` → `TODO`).
- **`FileUtilities.jvm.kt`** — overrides `:22-24` (`editFile` → `TODO`), `:124-126` (`openAppStore` → `TODO`).
- **`FileUtilities.native.kt`** — overrides `:48-50` (`editFile` → `TODO`), `:294-296` (`openAppStore` → `TODO`).
- **`FileUtilities.web.kt`** — overrides `:15` (`editFile` → `{}`), `:20` (`openAppStore` → `{}`).
- **`KeyboardUtils.kt`** (commonMain) — `:29` `@Composable expect fun keyboardHeightAsState(): State<Int>`.
- **`KeyboardUtils.native.kt`** — `:6-9` actual is `TODO("Not yet implemented")` (the stub).
- **`KeyboardUtils.jvm.kt`** — `:8-9` actual `= remember { mutableStateOf(0) }` (real, but unused).
- **`KeyboardUtils.android.kt`** — `:13-32` actual is a full window-insets listener (real, but unused).
- **`KeyboardUtils.web.kt`** — `:8-9` actual `= remember { mutableStateOf(0) }` (real, but unused).

Callers (verified zero, non-`build`): `grep -rn "editFile"` / `"openAppStore"` / `"keyboardHeightAsState"` excluding the defining files returns nothing.

Convention: dead interface members and `expect`/`actual` pairs should be removed entirely (both the contract and every implementation) rather than left throwing, so the type system stops advertising an API that crashes. `openFileBrowser` (real on jvm/native) and `keyboardAsState()` (the live boolean variant in the same file) are intentionally kept.

### Item C — write-only placeholder fields
- **`homebase-chat/src/commonMain/kotlin/id/homebase/chat/data/IncomingConnectionRequestUiModel.kt`** — `@Immutable data class`; `:11` `val senderName: String,` (non-default, 2nd param).
- **`homebase-chat/src/commonMain/kotlin/id/homebase/chat/data/OutgoingConnectionRequestUiModel.kt`** — `@Immutable data class`; `:13` `val recipientName: String,` (last non-default param).
- **`ConnectionRequestService.kt`** — assignment sites (named args):
  - `:277` `senderName = "TODO $sender",`
  - `:293` `recipientName = "TODO ${recipient.domainName}",`
  - `:322` `senderName = "TODO " + serverResponse.senderOdinId,`
  - `:333` `recipientName = "TODO " + serverResponse.recipient.domainName,`
- **`ConnectionCacheRepository.kt`** — assignment sites (named args):
  - `:63` `senderName = "TODO $sender",`
  - `:80` `recipientName = "TODO ${recipient.domainName}",`

Reads (verified zero): every `.senderName` / `.recipientName` dot-read in the repo is on a *different* type (`ConversationMedia` item, `DiceBubbleLines`, `RichNotificationData`) — NONE on these two models. `homebase-core`'s `AppViewModel.kt:261` holds `List<IncomingConnectionRequestUiModel>` but never reads `.senderName`. `ConnectionsUiState.kt` does not reference these models at all.

Neither model is `@Serializable`, so removing a property only requires deleting the property line + its 6 assignment sites. All 6 assignments use named arguments, so deleting the named arg lines is sufficient and order-independent.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Drift check (run first) | the `git diff --stat 45e2832e..HEAD -- …` from the header | empty output, or only files you compared against Current state |
| Item A — gate the test still compiles & passes | `./gradlew :homebase-chat:jvmTest` | `BUILD SUCCESSFUL`; the `@Ignore`d benchmark compiles but does not run |
| Item B — common contract | `./gradlew :homebase-common:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Item B — android actual | `./gradlew :homebase-common:compileAndroidMain` | `BUILD SUCCESSFUL` |
| Item B — iOS actual (macOS host only) | `./gradlew :homebase-common:compileKotlinIosSimulatorArm64` | `BUILD SUCCESSFUL` |
| Item B — web actual | `./gradlew :homebase-common:compileKotlinWasmJs` | `BUILD SUCCESSFUL` |
| Item C — chat compiles | `./gradlew :homebase-chat:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Whole-repo confidence (optional, slow) | `./gradlew :homebase-chat:jvmTest :homebase-common:jvmTest` | `BUILD SUCCESSFUL` |
| Final dead-symbol grep | see Done criteria | each returns no matches |

> Note on iOS: `:compileKotlinIosSimulatorArm64` only runs on a macOS host. If you are on Linux/Windows, that one command is deferred to CI — state so explicitly in your report and do NOT treat its absence as a failure.

## Scope

In scope:
- **Item A**: delete `archive/transcoder_v2/` Kotlin source tree (all 17 `.kt` under `src/`); relocate `archive/transcoder_v2/test-fixtures/bbb_1080p_2mb.mp4` to `homebase-chat/src/jvmTest/resources/video/bbb_1080p_2mb.mp4`; delete the now-unused `archive/transcoder_v2/test-fixtures/bbb_1080p_10mb.mp4` and `hdr10_720p.mp4` and the `archive/transcoder_v2/` directory; rewrite the fixture-loading helper in `FFmpegCompressBaselineJvmTest.kt`; update comment-only references in `CompressVideoAndroidInstrumentedTest.kt` and `FFMPEG_COMPRESSION_NOTES.md`.
- **Item B**: `FileUtilities.kt` (remove `editFile` + `openAppStore` declarations), `FileUtilities.android.kt`, `FileUtilities.jvm.kt`, `FileUtilities.native.kt`, `FileUtilities.web.kt` (remove the 2 overrides each); `KeyboardUtils.kt` (remove `expect`), `KeyboardUtils.native.kt`, `KeyboardUtils.jvm.kt`, `KeyboardUtils.android.kt`, `KeyboardUtils.web.kt` (remove the actual).
- **Item C**: `IncomingConnectionRequestUiModel.kt` (remove `senderName`), `OutgoingConnectionRequestUiModel.kt` (remove `recipientName`); `ConnectionRequestService.kt` and `ConnectionCacheRepository.kt` (remove the 6 assignment lines).
- `plans/README.md` (create or append this plan's row).

Out of scope (do NOT touch, with reason):
- `archive/transcoder_v2/README.md` and `archive/transcoder_v2/src/.../SPEC.md` — they live inside the deleted directory and go with it; do not separately edit them.
- `FileUtilities` `openFileBrowser` — real implementation on jvm/native, keep. `keyboardAsState()` (boolean) in `KeyboardUtils.kt` — live and used, keep.
- Any `senderName`/`recipientName` outside the two connection-request models — `ConversationOverview`, `DiceBubbleLines`, `RichNotificationData`, `InAppNotificationBanner`, notification displayers, `DeveloperMenuViewModel.kt:77` — these are unrelated types; touching them is a STOP.
- `androidApp` / `desktopApp` / `iosApp` — none reference the deleted members; no platform-entry change needed.
- The commented-out `//    val …` lines already in the two UiModel files — leave as-is (separate clean-up, not this plan).

## Steps

Do the three items as independent, separately-verified blocks. If any one item hits a STOP, you may still complete the other two and report the blocked one.

### Item A — delete `archive/transcoder_v2/`, relocate the one needed fixture

1. **Create the jvmTest resources fixture.** Make directory `homebase-chat/src/jvmTest/resources/video/` and move the fixture into it:
   `git mv archive/transcoder_v2/test-fixtures/bbb_1080p_2mb.mp4 homebase-chat/src/jvmTest/resources/video/bbb_1080p_2mb.mp4`
   (If `git mv` is disallowed in this environment, `mkdir -p` the dir, copy the file, then delete the original.)
   Verify: `test -f homebase-chat/src/jvmTest/resources/video/bbb_1080p_2mb.mp4 && echo OK` -> `OK`.

2. **Rewrite the fixture loader in `FFmpegCompressBaselineJvmTest.kt`** to read off the test classpath instead of walking the filesystem to `archive/`. Replace the `findArchiveFixture` helper (`:181-195`) and its call (`:138`) and the `assumeTrue` message (`:140`) so they reference the classpath resource `/video/bbb_1080p_2mb.mp4`. Replacement helper (drop-in for the old `findArchiveFixture`):
   ```kotlin
   /**
    * Load the 1080p Big Buck Bunny baseline clip from jvmTest resources.
    * Returns null when the fixture isn't on the classpath (e.g. a stripped CI build),
    * so the test assumes-out rather than failing.
    */
   private fun loadFixtureBytes(resourcePath: String): ByteArray? =
       this::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
   ```
   At the call site, replace:
   ```kotlin
   val fixtureSrc = findArchiveFixture("bbb_1080p_2mb.mp4")
   assumeTrue(
       "bbb_1080p_2mb.mp4 fixture missing from archive/transcoder_v2/test-fixtures/",
       fixtureSrc != null,
   )
   val fixture = File.createTempFile("vidfixture_", "_bbb_1080p_2mb.mp4").also {
       it.writeBytes(fixtureSrc!!.readBytes())
   }.absolutePath
   ```
   with:
   ```kotlin
   val fixtureBytes = loadFixtureBytes("/video/bbb_1080p_2mb.mp4")
   assumeTrue(
       "bbb_1080p_2mb.mp4 fixture missing from jvmTest resources (video/)",
       fixtureBytes != null,
   )
   val fixture = File.createTempFile("vidfixture_", "_bbb_1080p_2mb.mp4").also {
       it.writeBytes(fixtureBytes!!)
   }.absolutePath
   ```
   Also update the KDoc at `:25` from ``archive/transcoder_v2/test-fixtures/bbb_1080p_2mb.mp4`` to ``homebase-chat/src/jvmTest/resources/video/bbb_1080p_2mb.mp4`` and the `:181-186` KDoc that mentions "Walk up from the JVM-test working directory … archived v2 test-fixtures folder" — reword to "Load from jvmTest classpath resources." Remove the now-unused `import java.io.File`? No — `File.createTempFile` still uses it; keep the `File` import.
   Verify: `grep -n "transcoder_v2\|archive/" homebase-chat/src/jvmTest/kotlin/id/homebase/api/video/FFmpegCompressBaselineJvmTest.kt` -> no output.

3. **Compile + run the test module gate.** `./gradlew :homebase-chat:jvmTest`
   Verify: `BUILD SUCCESSFUL`. (The benchmark stays `@Ignore`d; this proves the rewritten helper compiles and the rest of `jvmTest` is green.)
   STOP if it fails for any reason touching `FFmpegCompressBaselineJvmTest` — reconcile before deleting the archive (you still have it as a fallback reference).

4. **Update the comment-only references** so the final grep is clean:
   - `CompressVideoAndroidInstrumentedTest.kt:43-45,121` — these comments point readers at `archive/transcoder_v2/...` files that will no longer exist. Reword to past tense / drop the path, e.g. change "live in `archive/transcoder_v2/.../CompressVideoV2InstrumentedTest.kt` — see `archive/transcoder_v2/README.md`" to "were maintained in a now-removed `archive/` transcoder prototype (deleted in plan 010)". The exact prose is your call; the only hard requirement is that the literal token `transcoder_v2` no longer appears.
   - `FFMPEG_COMPRESSION_NOTES.md:49` — change ``archive/transcoder_v2/test-fixtures/bbb_1080p_2mb.mp4`` to ``homebase-chat/src/jvmTest/resources/video/bbb_1080p_2mb.mp4``.
   Verify: `grep -rn "transcoder_v2" homebase-api/src/androidDeviceTest/kotlin/id/homebase/api/video/CompressVideoAndroidInstrumentedTest.kt FFMPEG_COMPRESSION_NOTES.md` -> no output.

5. **Delete the archive tree.** Remove the Kotlin source, the two now-unused fixtures, the README/SPEC, and the directory:
   `git rm -r archive/transcoder_v2` (or `rm -rf archive/transcoder_v2` if not tracked the same way — but it is tracked; prefer `git rm -r`).
   Verify: `test -d archive/transcoder_v2 && echo STILL_THERE || echo GONE` -> `GONE`.
   Verify: `grep -rn "transcoder_v2" . --include=*.kt --include=*.md --include=*.kts | grep -v plans/010` -> no output.

6. **Re-gate.** `./gradlew :homebase-chat:jvmTest`
   Verify: `BUILD SUCCESSFUL` (proves nothing in the live build depended on the deleted tree, and the fixture still loads from its new home).

### Item B — remove unreachable members (`editFile`, `openAppStore`, `keyboardHeightAsState`)

7. **Re-confirm zero callers** (cheap insurance against drift since plan time):
   `grep -rn "editFile\|openAppStore\|keyboardHeightAsState" --include=*.kt . | grep -v "/build/" | grep -vE "FileUtilities|KeyboardUtils"`
   Verify: no output. **If ANY line prints, STOP for Item B** and report the caller — a real caller means the member is live and must NOT be deleted (per spec, replace its `TODO` with a logged no-op instead and note it; do not delete).

8. **Remove `editFile` + `openAppStore` from the interface** in `FileUtilities.kt`: delete line `:11` (`fun editFile(...)`) and line `:27` (`fun openAppStore(...)`). Leave every other member untouched.
   (Do not compile yet — actuals still override the now-removed members and won't compile until step 9.)

9. **Remove the overrides in all four actuals.** In each file delete the two override blocks (keep everything else):
   - `FileUtilities.android.kt` — `:36-38` and `:230-232`.
   - `FileUtilities.jvm.kt` — `:22-24` and `:124-126`.
   - `FileUtilities.native.kt` — `:48-50` and `:294-296`.
   - `FileUtilities.web.kt` — `:15` and `:20`.
   Verify (common contract): `./gradlew :homebase-common:compileKotlinJvm` -> `BUILD SUCCESSFUL` (covers commonMain + jvm actual together).
   Verify (android): `./gradlew :homebase-common:compileAndroidMain` -> `BUILD SUCCESSFUL`.
   Verify (web): `./gradlew :homebase-common:compileKotlinWasmJs` -> `BUILD SUCCESSFUL`.
   Verify (iOS, macOS host only): `./gradlew :homebase-common:compileKotlinIosSimulatorArm64` -> `BUILD SUCCESSFUL`; otherwise note deferred to CI.

10. **Remove `keyboardHeightAsState`** — the `expect` and all four actuals:
    - `KeyboardUtils.kt` — delete `:28-29` (the `@Composable` line + `expect fun keyboardHeightAsState(): State<Int>`). Keep `keyboardAsState()` and `Modifier.dismissKeyboardOnTap()`.
    - `KeyboardUtils.native.kt` — delete the `keyboardHeightAsState` actual (`:6-9`); if that leaves the file with only its `package`/imports and nothing else, delete the whole file with `git rm`.
    - `KeyboardUtils.jvm.kt` — delete the actual (`:8-9`); if the file is then empty of declarations, `git rm` it.
    - `KeyboardUtils.android.kt` — delete the actual (`:13-32`); if empty, `git rm` it.
    - `KeyboardUtils.web.kt` — delete the actual (`:8-9`); if empty, `git rm` it.
    > Whether you delete a file or leave its `package` line is a judgment call. Prefer `git rm` when the only declaration in the file was the removed actual (avoids orphan files of imports). Removing now-unused `import androidx.compose.runtime.*` lines is required if you keep the file, or the build warns/fails on unused-import lint depending on config — simplest is to `git rm` the emptied actual files.
    Verify (all four targets again): re-run the four `compile*` commands from step 9.
    All -> `BUILD SUCCESSFUL` (iOS deferred off-mac).

### Item C — remove write-only `senderName` / `recipientName`

11. **Re-confirm zero reads** (drift insurance):
    `grep -rn "\.senderName\|\.recipientName" --include=*.kt . | grep -v "/build/"`
    Inspect every hit: confirm each is on `ConversationMedia` item / `DiceBubbleLines` / `RichNotificationData` (NOT on an `IncomingConnectionRequestUiModel` / `OutgoingConnectionRequestUiModel`). **If any hit reads one of the two connection-request models, STOP for Item C** and report it.

12. **Remove the property from each model:**
    - `IncomingConnectionRequestUiModel.kt` — delete `:11` `val senderName: String,`.
    - `OutgoingConnectionRequestUiModel.kt` — delete `:13` `val recipientName: String,`.
    (Do not compile yet — assignment sites still pass the now-removed named args.)

13. **Remove the 6 assignment lines** (they are standalone named-arg lines; delete the whole line each):
    - `ConnectionRequestService.kt` — `:277` `senderName = "TODO $sender",`; `:293` `recipientName = "TODO ${recipient.domainName}",`; `:322` `senderName = "TODO " + serverResponse.senderOdinId,`; `:333` `recipientName = "TODO " + serverResponse.recipient.domainName,`.
    - `ConnectionCacheRepository.kt` — `:63` `senderName = "TODO $sender",`; `:80` `recipientName = "TODO ${recipient.domainName}",`.
    Verify: `./gradlew :homebase-chat:compileKotlinJvm` -> `BUILD SUCCESSFUL`.
    Verify: `grep -rn "senderName\|recipientName" homebase-chat/src/commonMain/kotlin/id/homebase/chat/data/IncomingConnectionRequestUiModel.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/data/OutgoingConnectionRequestUiModel.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/requests/ConnectionRequestService.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/contact/ConnectionCacheRepository.kt` -> no output.

### Finalize

14. **Whole-module gate** (one pass to catch any cross-item interaction):
    `./gradlew :homebase-chat:jvmTest :homebase-common:jvmTest :homebase-common:compileKotlinJvm :homebase-common:compileAndroidMain :homebase-common:compileKotlinWasmJs :homebase-chat:compileKotlinJvm`
    Verify: `BUILD SUCCESSFUL`. (Add `:homebase-common:compileKotlinIosSimulatorArm64` on a macOS host.)

15. **Update `plans/README.md`.** If the file does not exist, create it with a header and a table whose columns are `Plan | Title | Priority | Status` and add the 010 row. If it exists, append (or update) the row:
    `| 010 | Delete three independent piles of dead code | P2 | Done |`
    Verify: `grep -n "010" plans/README.md` -> the row prints.

## Test plan
No new product code paths are added, so no new behavioral test is warranted; the safety net is "everything still compiles and the existing suites stay green," which the per-item compile/test gates above provide. Specifically:

- **Item A regression guard**: `./gradlew :homebase-chat:jvmTest` compiles `FFmpegCompressBaselineJvmTest` with the rewritten classpath loader and runs the rest of jvmTest. The `@Ignore`d benchmark won't execute, but compilation proves the resource-loading rewrite is type-correct. If you want to *prove the fixture actually loads* (recommended, optional), temporarily remove `@Ignore`, run `./gradlew :homebase-chat:jvmTest --tests "*FFmpegCompressBaselineJvm*"`, confirm at least one `BASELINE platform=jvm …` line prints (i.e. the `assumeTrue(fixtureBytes != null)` passed because the resource was found), then **restore `@Ignore`**. Model after the existing `@Ignore`/`assumeTrue` shape already in that file. Do not leave `@Ignore` removed.
- **Item B & C regression guard**: the multi-target `compile*` tasks are the test — they fail loudly if any deletion left a dangling reference or broke `expect`/`actual` matching. There is no behavior to assert because the deleted members had no callers.

There is no Konsist/`ArchitectureTest` interaction: no `Text("…")` literal is added (Item C *removes* `"TODO …"` literals; they were data assignments, not `Text` composables, so the Konsist rule was never triggered by them and is unaffected).

## Done criteria
- Drift check at top produced no surprises (or you reconciled them).
- **Item A**:
  - `test -d archive/transcoder_v2` -> directory GONE.
  - `grep -rn "transcoder_v2" . --include=*.kt --include=*.md --include=*.kts | grep -v "plans/010"` -> no output.
  - `test -f homebase-chat/src/jvmTest/resources/video/bbb_1080p_2mb.mp4` -> file present.
  - `grep -n "archive/" homebase-chat/src/jvmTest/kotlin/id/homebase/api/video/FFmpegCompressBaselineJvmTest.kt` -> no output.
  - `./gradlew :homebase-chat:jvmTest` -> `BUILD SUCCESSFUL`.
- **Item B**:
  - `grep -rn "editFile\|openAppStore\|keyboardHeightAsState" --include=*.kt . | grep -v "/build/"` -> no output (every defining line is gone too).
  - `./gradlew :homebase-common:compileKotlinJvm :homebase-common:compileAndroidMain :homebase-common:compileKotlinWasmJs` -> `BUILD SUCCESSFUL`.
  - On a macOS host: `./gradlew :homebase-common:compileKotlinIosSimulatorArm64` -> `BUILD SUCCESSFUL` (else: stated as deferred-to-CI in the report).
- **Item C**:
  - `grep -rn "senderName\|recipientName" homebase-chat/src/commonMain/kotlin/id/homebase/chat/data/ homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/requests/ConnectionRequestService.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/contact/ConnectionCacheRepository.kt` -> no output.
  - `grep -rn "\"TODO " homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/requests/ConnectionRequestService.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/contact/ConnectionCacheRepository.kt` -> no output (the placeholder literals are gone).
  - `./gradlew :homebase-chat:compileKotlinJvm` -> `BUILD SUCCESSFUL`.
- `git status --porcelain` -> only the in-scope files listed in Scope (plus `plans/`). Ignore the pre-existing untracked `iosApp/...xcuserstate`, `.agents/`, and `skills-lock.json` noted at branch start.
- `plans/README.md` contains the 010 row.

## STOP conditions
- **Drift**: the drift-check diff shows an in-scope file already changed and its content no longer matches the Current-state excerpt (e.g. a member already deleted, a fixture already moved, a placeholder already replaced). Reconcile; if the change is already done, mark that item "already fixed" and skip — do not re-apply.
- **Item B caller found** (step 7): `editFile` / `openAppStore` / `keyboardHeightAsState` has a real caller. Do NOT delete it. Per spec, replace its throwing `TODO` with a logged no-op (`Logger.w { "… not implemented on <platform>" }`) and report. STOP the deletion for that member only.
- **Item C reader found** (step 11): a `.senderName` / `.recipientName` read resolves to one of the two connection-request models. Do NOT remove that field. STOP Item C and report which screen/VM reads it.
- **Out-of-scope edit needed to compile**: if removing any member forces a change to a file not in Scope (a screen, another VM, a serializer, `androidApp`/`iosApp`), STOP — an assumption here is wrong.
- **A compile/test gate fails twice** for a reason unrelated to your edit (pre-existing module breakage). STOP and report the failing task + first error; the breakage is not yours.
- **Item A fixture won't load** after the rewrite (the optional un-`@Ignore` run finds no `BASELINE` line because `assumeTrue(fixtureBytes != null)` assumed-out): the resource path or `src/jvmTest/resources/` layout is wrong. Fix the resource path; do NOT fall back to re-adding the `archive/` walk.

## Maintenance notes
- **Item A**: jvmTest resources for `homebase-chat` now include a ~2 MB binary at `resources/video/bbb_1080p_2mb.mp4`. That is intentional and small; if a reviewer objects to committing a binary into a module's test resources, the alternative is to keep the file in a dedicated `test-fixtures/` top-level dir referenced by an absolute project path — but the classpath-resource approach is self-contained and survives `cwd` changes, which the old parent-walk did not. The two other fixtures (`bbb_1080p_10mb.mp4`, `hdr10_720p.mp4`) had **no live consumer** and are deleted; if a future iOS/Android device transcoder test is revived, re-add them under that test's own `resources/` rather than resurrecting `archive/`.
- **Item B**: a reviewer should scrutinize that no *new* code on `main`/an in-flight branch started calling `keyboardHeightAsState` (the iOS soft-keyboard inset is a plausible future need). If iOS keyboard-height handling is wanted later, re-introduce it as a fresh `expect`/`actual` with a real iOS implementation (the deleted native actual was only a `TODO`, so nothing of value is lost). Likewise `editFile` (in-place edit + re-share) and `openAppStore` (rate-the-app / update prompt) are features that may be wanted — they should come back as real implementations, not resurrected stubs.
- **Item C**: the two models no longer carry a display name. If a future "pending connections" screen needs to show the requester's name, derive it from `senderOdinId` / `recipientOdinId` (the `OdinId.domainName`) at render time via the existing contact/display-name lookup — do NOT re-add a write-only `senderName` field populated with a placeholder. The whole point of this deletion is to stop shipping a `"TODO …"` string in a data object.
- Deferred follow-up (out of scope here): the commented-out `//    val …` lines in both UiModel files (`circleIds`, `connectionRequestOrigin`, `contactData`, etc.) are leftover scaffolding; a separate cleanup could delete them, but this plan leaves them untouched to keep the diff tightly scoped to the named fields.
