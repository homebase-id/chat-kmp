# Plan 012: Split the 3334-line MomentDetailScreen.kt into focused sibling files

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/moments/MomentDetailScreen.kt homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/moments/MomentsScreen.kt`. If either in-scope file changed since this plan was written, compare the Current state excerpts below to live code first; on mismatch, STOP and re-derive line numbers — the cut ranges below are line-number-sensitive.

## Status
- Priority: P3
- Effort: M
- Risk: LOW (pure code move, no logic/string change)
- Depends on: none
- Category: tech-debt
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters
`MomentDetailScreen.kt` is 3334 lines holding 42 composables for one screen, while the actual view-model logic already lives in a separate `MomentDetailViewModel.kt` — so the file is pure view code that has simply grown unmanageable. A file this size is slow to open, slow for the Kotlin compiler/IDE to analyse incrementally, and forces every reviewer touching one widget (a comment row, a delivery chip) to scroll past dozens of unrelated composables. Splitting the self-contained clusters (reactions sheet, comments sheet, delivery/metadata sections) into sibling files in the same package makes each concern independently reviewable and shrinks the orchestrator file to the pager/scaffold it should be. This is a mechanical move: no behaviour, no strings, no public API change.

## Current state

All paths below are under `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/moments/`.

### `MomentDetailScreen.kt` (3334 lines) — the file being split
- `package id.homebase.core.ui.screens.moments` (line 1). All new files share this package, so a `private` top-level function in one file is **NOT** visible from a sibling file in the same package — anything called across files must be `internal`. `HeartEmoji`/`FlameEmoji` are NOT defined here; they are `internal const` in `MomentsScreen.kt:1563` and already imported. Moved files re-import the same symbols.
- Composables/helpers to move, with their **current** definition line + visibility (verified by read at commit 45e2832e):

  Reactions cluster:
  - `982: internal fun ReactionsBottomSheet(` (already internal)
  - `1075: private fun ReactionFilterBar(`
  - `1123: private fun ReactionDetailRow(`
  - `1867: internal fun EmojiReactionButton(` (already internal)
  - `1946: internal fun countReactionsByEmoji(` (already internal)
  - `2355: private fun ReactionsRow(` — **DEAD CODE** (no call site anywhere; only a KDoc `[ReactionsRow]` reference at line 972). Move verbatim anyway; do NOT delete (pure-move rule).
  - `2459: private fun decodeReactionEmoji(reactionContent: String): String? = runCatching {`

  Comments cluster:
  - `947: private fun DeleteCommentDialog(`
  - `2026: private fun CommentsSheet(`
  - `2100: private fun CommentsPanelContent(`
  - `2226: private fun CommentRowFromState(`
  - `2267: internal fun MomentCommentsSheet(` (already internal — called by `MomentsScreen.kt:796`)
  - `3005: private fun CommentsHeader(modifier: Modifier = Modifier) {`
  - `3014: private fun CommentsEmpty(modifier: Modifier = Modifier) {`
  - `3024: private fun AddCommentRow(`
  - `3079: private fun CommentRow(`
  - `3196: private fun CommentOverflowMenu(`
  - `3239: private fun CommentReactionsRow(`

  Delivery/Metadata cluster:
  - `2466: private fun DescriptionSection(` — **DEAD CODE** (no call site; not the same as `MomentDescriptionSection`). Move verbatim; do NOT delete.
  - `2487: private fun MetadataSection(`
  - `2538: private fun SharedWithRow(`
  - `2563: private fun PrivateRow() {`
  - `2583: private fun JustYouRow() {`
  - `2603: private fun RecipientsRow(`
  - `2685: private fun AvatarStack(`
  - `2735: private fun RecipientPlainRow(recipient: RecipientBaseUiModel) {`
  - `2767: private fun DeliveryStatusSection(`
  - `2822: private fun DeliveryRow(row: RecipientDeliveryUiModel) {`
  - `2852: private fun SharedWithEntryRow(entry: SharedWithEntry) {`
  - `2897: private fun MetadataRow(label: String, value: String) {`
  - `2919: private fun MomentDescriptionSection(`

  Shared formatting helper (used by TWO clusters — see cross-references):
  - `3316: private val capturedAtFormat = kotlinx.datetime.LocalDateTime.Format {`
  - `3330: private fun formatCapturedAt(epochMs: Long): String {`

- Functions that STAY (orchestrator/pager/scaffold + main media content): `MomentDetailPager` (260), `MomentDetailLoadedPager` (335), `MomentsReelsView` (462), `MomentDetailPane` (479), `DetailContent` (645), `MomentOverflowMenu` (821), `DeleteMomentDialog` (912), `MomentMediaScaffold` (1161), `MomentDetailContent` (1404), `DetailActionColumn` (1819), `OverlayActionButton` (1912), `DetailBottomOverlay` (1963), `PagerDots` (2331). Plus file-level constants used by staying code: `MOMENT_DEEPLINK_SYNC_TIMEOUT_MS` (218), `MOMENT_MEDIA_FRACTION_WITH_COMMENTS` (632), `MOMENT_COMMENTS_SHEET_FRACTION` (641), `MediaWideBreakpoint` (2320), `MediaWidthFraction` (2321), `MediaMaxWidth` (2322), `CommentsRailWidth` (2328) — **all stay in MomentDetailScreen.kt** (`ReactionsRow`, which is dead, also reads `MediaWideBreakpoint`/`MediaWidthFraction`/`MediaMaxWidth` at 2316–2322, but the live readers are staying code at 1156/1366–1378/711, so the vals stay put and `ReactionsRow` will read them via `internal` if needed — see Step 3).

### Cross-reference map (this is what forces specific `internal` upgrades)
Verified by `grep`. A moved function whose caller lands in a *different* file must be `internal`.

| Moved fn | Caller(s) | Caller's home after split | Required visibility |
|---|---|---|---|
| `ReactionsBottomSheet` | `DetailContent:796` (stays); `MomentsScreen.kt:917` | MomentDetailScreen.kt; MomentsScreen.kt | `internal` (already is) |
| `EmojiReactionButton` | `DetailActionColumn:1840,1847` (stays); `MomentsScreen.kt:1261,1272` | MomentDetailScreen.kt; MomentsScreen.kt | `internal` (already is) |
| `countReactionsByEmoji` | `MomentDetailContent:1733,1736` (stays); `MomentsScreen.kt:1249,1252` | MomentDetailScreen.kt; MomentsScreen.kt | `internal` (already is) |
| `MomentCommentsSheet` | `MomentsScreen.kt:796` | MomentsScreen.kt | `internal` (already is) |
| `CommentsSheet` | `DetailContent:742` (stays); `MomentCommentsSheet:2276` (moves to Comments) | MomentDetailScreen.kt | **upgrade private→internal** |
| `CommentsPanelContent` | `DetailContent/Pane:715` (stays); `CommentsSheet:2073` (moves to Comments) | MomentDetailScreen.kt | **upgrade private→internal** |
| `DeleteCommentDialog` | `DetailContent:774` (stays); `MomentCommentsSheet:2294` (moves to Comments) | MomentDetailScreen.kt | **upgrade private→internal** |
| `MomentDescriptionSection` | `CommentsPanelContent:2134` (moves to Comments) | Comments file | **upgrade private→internal** (def lives in Delivery file, caller in Comments file) |
| `formatCapturedAt` + `capturedAtFormat` | `MetadataSection:2504` (Delivery file) AND `CommentRow:3124` (Comments file) | both files | **upgrade private→internal** (shared across two new files) |
| `decodeReactionEmoji` | `ReactionsRow:2364` (dead, moves to Reactions) AND `CommentReactionsRow:3246` (moves to Comments) | both files | **upgrade private→internal** (used by Reactions-file dead code AND Comments file) |

Everything else (`ReactionFilterBar`, `ReactionDetailRow`, `ReactionsRow`, `CommentRowFromState`, `CommentsHeader`, `CommentsEmpty`, `AddCommentRow`, `CommentRow`, `CommentOverflowMenu`, `CommentReactionsRow`, `MetadataSection`, `SharedWithRow`, `PrivateRow`, `JustYouRow`, `RecipientsRow`, `AvatarStack`, `RecipientPlainRow`, `DeliveryStatusSection`, `DeliveryRow`, `SharedWithEntryRow`, `MetadataRow`, `DescriptionSection`) is only called from within its own cluster, so it may **stay `private`** in its new file.

### Convention to match
This is a same-module, same-package file split — the established pattern in this very package is `MomentsScreen.kt` calling `internal` composables (`ReactionsBottomSheet`, `MomentCommentsSheet`) defined in `MomentDetailScreen.kt`. Match that: cross-file shared composables are `internal`, cluster-local ones stay `private`. Exemplar sibling file in the package: `MomentsScreen.kt` (same `package id.homebase.core.ui.screens.moments`, imports the same Compose/resource symbols).

## Commands you will need
| Purpose | Command | Expected on success |
|---|---|---|
| Drift check | `git diff --stat 45e2832e..HEAD -- homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/moments/MomentDetailScreen.kt homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/moments/MomentsScreen.kt` | no output (no drift) |
| Fast compile gate (run after EVERY cluster move) | `./gradlew :homebase-core:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Module unit tests (final) | `./gradlew :homebase-core:jvmTest` | `BUILD SUCCESSFUL`, 0 failures |
| Konsist string-literal guard (final, cross-module) | `./gradlew :homebase-common:jvmTest` | `BUILD SUCCESSFUL` (no new `Text("…")` introduced) |
| Confirm orchestrator shrank | `wc -l homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/moments/MomentDetailScreen.kt` | < 2200 lines |
| Confirm no stray dupes | `git diff --stat` then grep (see Done criteria) | only in-scope files changed |

Note: `:homebase-core` is a KMP library module — it has NO `assembleDebug`/`compileDebugKotlinAndroid` task. Use `:homebase-core:compileKotlinJvm` (and optionally `:homebase-core:compileAndroidMain`, `:homebase-core:compileKotlinIosSimulatorArm64` on macOS) as the gate.

## Scope
In scope (modify/create — nothing else):
- `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/moments/MomentDetailScreen.kt` (remove moved bodies; upgrade visibilities per the table; keep orchestrator + constants)
- `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/moments/MomentReactionsSheet.kt` (NEW)
- `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/moments/MomentCommentsSheet.kt` (NEW)
- `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/moments/MomentDeliveryMetadataSections.kt` (NEW)
- `plans/README.md` (add/append this plan's row at the end)

Out of scope (do NOT touch):
- `MomentDetailViewModel.kt` — VM logic; not view code, not part of the split.
- `MomentsScreen.kt` — sibling caller; it must keep compiling unchanged. Do NOT edit it. (It is read-only here only to confirm the cross-package calls listed above.)
- Any string resource, any `MR.string.*`, any composable signature/parameter/name — this is a verbatim move; renaming is forbidden (only `private`→`internal` visibility changes per the table).
- `ReactionsRow` / `DescriptionSection` dead code — move verbatim, do NOT delete (deletion is a logic change; defer to a separate follow-up plan).
- File-level constants (`MediaWideBreakpoint`, `CommentsRailWidth`, the `MOMENT_*` consts) — they stay in `MomentDetailScreen.kt`.

## Steps

Work one cluster at a time. After each cluster: the module compiles. Name the new file's `MR.string.*` imports explicitly when you move a composable that references resources (each `MR.string.X` needs its own `import id.homebase.resources.X`; a missing import compiles against a stale cache locally but fails clean CI). The safe way to get imports right is: in each new file, start with the SAME `package` line and copy the import block from `MomentDetailScreen.kt`, then let `./gradlew :homebase-core:compileKotlinJvm` report unused-import warnings (warnings, not errors — fine) and unresolved-reference errors (fix by adding the missing import). Do NOT delete imports from `MomentDetailScreen.kt` in the same step you move code — leave its imports as-is; unused imports are warnings, and a follow-up tidy can prune them once everything compiles. (Pruning the orchestrator's now-unused imports is optional and may be done as the final step.)

0. **Drift check.** Run the Drift check command. Expected: no output. If anything prints, STOP and re-derive line numbers from live code before proceeding.

1. **Create the Delivery/Metadata file first** (it owns the shared `formatCapturedAt`/`capturedAtFormat`, so do it before Comments). Create `MomentDeliveryMetadataSections.kt` with `package id.homebase.core.ui.screens.moments` and the import block copied from `MomentDetailScreen.kt`. Cut these bodies VERBATIM from `MomentDetailScreen.kt` and paste them in, in this order: `DescriptionSection` (2466), `MetadataSection` (2487), `SharedWithRow` (2538), `PrivateRow` (2563), `JustYouRow` (2583), `RecipientsRow` (2603), `AvatarStack` (2685), `RecipientPlainRow` (2735), `DeliveryStatusSection` (2767), `DeliveryRow` (2822), `SharedWithEntryRow` (2852), `MetadataRow` (2897), `MomentDescriptionSection` (2919), `capturedAtFormat` (3316), `formatCapturedAt` (3330).
   - In the new file: upgrade `MomentDescriptionSection` to `internal` (its caller lands in the Comments file), and upgrade `formatCapturedAt` + `capturedAtFormat` to `internal` (shared with Comments file). Leave the rest `private`.
   - In `MomentDetailScreen.kt`: delete those same bodies. Leave the file-level `Media*`/`Comments*`/`MOMENT_*` constants in place.
   - Verify: `./gradlew :homebase-core:compileKotlinJvm` -> `BUILD SUCCESSFUL`. (At this point `MomentDescriptionSection` is referenced by `CommentsPanelContent` which still lives in `MomentDetailScreen.kt`; that's same-package + `internal`, so it resolves.)

2. **Create the Comments file.** Create `MomentCommentsSheet.kt` (same package, copied import block). Cut these bodies VERBATIM in this order: `DeleteCommentDialog` (947), `CommentsSheet` (2026), `CommentsPanelContent` (2100), `CommentRowFromState` (2226), `MomentCommentsSheet` (2267), `CommentsHeader` (3005), `CommentsEmpty` (3014), `AddCommentRow` (3024), `CommentRow` (3079), `CommentOverflowMenu` (3196), `CommentReactionsRow` (3239).
   - In the new file: `MomentCommentsSheet` stays `internal` (called by `MomentsScreen.kt`). Upgrade `CommentsSheet`, `CommentsPanelContent`, and `DeleteCommentDialog` to `internal` (each has a caller remaining in `MomentDetailScreen.kt`). The rest stay `private`. `CommentReactionsRow` calls `decodeReactionEmoji` — that helper is still in `MomentDetailScreen.kt` for now; you will make it `internal` in Step 3 (until then it is still `private` in the same module but a DIFFERENT file, so the Comments file will NOT compile yet — therefore do Step 3 immediately, OR temporarily upgrade `decodeReactionEmoji` to `internal` now and move it in Step 3). RECOMMENDED: upgrade `decodeReactionEmoji` to `internal` in place in `MomentDetailScreen.kt` as part of this step so the Comments file compiles; it will physically move in Step 3.
   - In `MomentDetailScreen.kt`: delete those same bodies.
   - Verify: `./gradlew :homebase-core:compileKotlinJvm` -> `BUILD SUCCESSFUL`.

3. **Create the Reactions file.** Create `MomentReactionsSheet.kt` (same package, copied import block — it needs `HeartEmoji`/`FlameEmoji` via `import id.homebase.core.ui.screens.moments.HeartEmoji`? No — they are top-level in the same package, so no import needed; verify the compile). Cut these bodies VERBATIM in this order: `ReactionsBottomSheet` (982), `ReactionFilterBar` (1075), `ReactionDetailRow` (1123), `EmojiReactionButton` (1867), `countReactionsByEmoji` (1946), `ReactionsRow` (2355, dead), `decodeReactionEmoji` (2459).
   - In the new file: `ReactionsBottomSheet`, `EmojiReactionButton`, `countReactionsByEmoji` stay `internal` (already are; called by staying code + `MomentsScreen.kt`). `decodeReactionEmoji` stays `internal` (you upgraded it in Step 2; it is used by `CommentReactionsRow` in the Comments file). `ReactionFilterBar`, `ReactionDetailRow`, `ReactionsRow` stay `private`.
   - `ReactionsRow` (dead) reads the file-level vals `MediaWideBreakpoint`/`MediaWidthFraction`/`MediaMaxWidth` which STAY in `MomentDetailScreen.kt`. Since those are currently `private`, a cross-file read will not resolve. Two options, pick the minimal one: (a) since `ReactionsRow` is dead, the compiler still type-checks its body, so you must make those three vals reachable — **upgrade `MediaWideBreakpoint`, `MediaWidthFraction`, `MediaMaxWidth` to `internal` in `MomentDetailScreen.kt`** (they are also read by staying code, no harm); OR (b) if the executor confirms `ReactionsRow` references no staying `private` symbol after the move, leave them `private`. Run the compile to discover which symbols `ReactionsRow` actually needs and upgrade exactly those to `internal`. Do NOT move the vals — they belong to the staying media layout code.
   - In `MomentDetailScreen.kt`: delete those same bodies.
   - Verify: `./gradlew :homebase-core:compileKotlinJvm` -> `BUILD SUCCESSFUL`.

4. **(Optional) Prune now-unused imports** in `MomentDetailScreen.kt` only if it is zero-risk; warnings are acceptable, so this step may be skipped. If pruned, re-run `./gradlew :homebase-core:compileKotlinJvm`.

5. **Run the module test + Konsist guard.** `./gradlew :homebase-core:jvmTest` -> `BUILD SUCCESSFUL`; `./gradlew :homebase-common:jvmTest` -> `BUILD SUCCESSFUL` (verifies you introduced no `Text("…")` string literal — you moved code verbatim, so this must still pass).

6. **Confirm shrink + cleanliness.** `wc -l .../MomentDetailScreen.kt` -> under ~2200 lines. `git status --porcelain` -> only the 4 in-scope source paths + `plans/README.md` + this plan file.

7. **Update plans/README.md.** If `plans/README.md` does not exist, create it with a header and a table; append a row for plan 012. (See Done criteria for the row.)

## Test plan
No new tests. This is a verbatim move with zero behaviour change, so the correct verification is the compiler plus the existing suites — adding a unit test would assert nothing the compile gate does not already prove. The relevant safety nets:
- **Compile gate** `:homebase-core:compileKotlinJvm` after each cluster: catches any unresolved cross-file reference (the whole risk surface of this change).
- **Existing suite** `:homebase-core:jvmTest`: there are currently no moments-specific tests in `homebase-core/src/jvmTest` (the moments code is pure UI); the suite still must stay green to prove the module links.
- **Existing Konsist** `homebase-common/src/jvmTest/kotlin/id/homebase/core/architecture/ArchitectureTest.kt`: scans every `@Composable` for `Text` string literals — model nothing new after it; just keep it green by not editing any string.
- Regression this guards against: a moved `private` composable becoming unreachable from its caller in another file. The cross-reference table above enumerates exactly which functions must be `internal`; the compile gate fails loudly if one was missed.

If, after the move, you want a belt-and-suspenders structural assertion, the model to copy is any plain-JVM logic test under `homebase-core/src/jvmTest/kotlin/id/homebase/core/ui/screens/vault/` (e.g. `VaultEntryTest.kt`) — but only `formatCapturedAt`/`countReactionsByEmoji`/`decodeReactionEmoji` are pure functions testable without Compose; adding such a test is OPTIONAL and out of the minimal scope.

## Done criteria
- `git diff --stat 45e2832e..HEAD -- <in-scope paths>` shows only the 4 source files + `plans/README.md` + `plans/012-split-momentdetailscreen.md`.
- `./gradlew :homebase-core:compileKotlinJvm` -> `BUILD SUCCESSFUL`.
- `./gradlew :homebase-core:jvmTest` -> `BUILD SUCCESSFUL`, 0 failures.
- `./gradlew :homebase-common:jvmTest` -> `BUILD SUCCESSFUL` (Konsist string-literal guard green).
- `wc -l homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/moments/MomentDetailScreen.kt` -> under ~2200 lines (started at 3334; ~1100+ lines moved).
- No duplicate definitions: for each moved symbol, `grep -rn --include='*.kt' '<symbol>(' homebase-core/src/commonMain/.../moments/` shows exactly ONE definition site (in its new file) and the call sites — never two definitions. Spot-check: `ReactionsBottomSheet`, `CommentsSheet`, `MetadataSection`, `formatCapturedAt` each defined once.
- `grep -rn 'package id.homebase.core.ui.screens.moments' homebase-core/src/commonMain/.../moments/MomentReactionsSheet.kt homebase-core/src/commonMain/.../moments/MomentCommentsSheet.kt homebase-core/src/commonMain/.../moments/MomentDeliveryMetadataSections.kt` -> all three print the package line (correct package, files compile as siblings).
- No composable was renamed: `git log -p` / `git diff` for the move shows the same function names, only file location + (for the 7 listed) `private`→`internal`.
- `git status --porcelain` shows only in-scope files.
- `plans/README.md` contains a row: `| 012 | Split MomentDetailScreen.kt into focused sibling files | P3 | tech-debt | <status> |` (match the column layout already used in README if one exists; otherwise create the table).

## STOP conditions
- **Drift:** Step 0 prints any diff for `MomentDetailScreen.kt` or `MomentsScreen.kt` and the Current-state excerpts no longer match live code — STOP, re-derive line ranges, do not blindly cut by the numbers above.
- **A moved composable references a `private` symbol that cannot be made `internal` without a wider change** (e.g. it needs a `private` constructor, sealed type, or a symbol whose widening would change another module's surface) — STOP and report the symbol; the spec forbids wider changes.
- **`:homebase-core:compileKotlinJvm` fails twice** on the same cluster after you have added the obvious missing import / widened the obvious symbol — STOP and report the unresolved reference; do not start deleting code or adding `@Suppress` to force it green.
- **Assumption "ReactionsRow / DescriptionSection are dead" proves false** (Step 3 compile reveals an actual call site, e.g. via reflection or a generated file) — STOP; their visibility/placement assumptions change.
- **Any fix requires editing `MomentsScreen.kt` or `MomentDetailViewModel.kt`** — STOP; that is out of scope and signals a wrong visibility decision (the four cross-package callees are already `internal`; if you find yourself needing to touch `MomentsScreen.kt`, you narrowed a visibility you should not have).
- **`:homebase-common:jvmTest` fails** with a Konsist `Text` literal violation — you altered a string during the move (forbidden). STOP and revert that edit to verbatim.

## Maintenance notes
- **What interacts:** `MomentsScreen.kt` depends on four now-relocated `internal` composables (`ReactionsBottomSheet`, `EmojiReactionButton`, `countReactionsByEmoji`, `MomentCommentsSheet`). A future edit must keep these `internal`, not narrow them to `private`, or the timeline screen breaks. The shared `formatCapturedAt`/`capturedAtFormat`/`decodeReactionEmoji`/`MomentDescriptionSection` are now `internal` and consumed across two new files — keep them so.
- **Reviewer should scrutinise:** that the move is truly verbatim (diff each moved body line-for-line; the only intended deltas are the `private`→`internal` upgrades on the 7 symbols listed in the cross-reference table, plus possibly `decodeReactionEmoji` and up to three `Media*` vals). Any strings, any `MR.string.*` import, any parameter default must be byte-identical. Confirm no symbol ended up defined twice and none deleted.
- **Deferred follow-ups (separate plans, NOT this one):**
  1. `ReactionsRow` (was 2355) and `DescriptionSection` (was 2466) are dead code — propose deleting them in a follow-up once their move is confirmed; deletion is a logic change excluded here. If they were deleted, the `internal` widening on `decodeReactionEmoji` (used by `ReactionsRow`) and on the `Media*` vals could be reconsidered.
  2. Pruning the orchestrator's now-unused imports (Step 4) is left optional to keep this PR a pure move; a tidy-imports pass can follow.
  3. The remaining `MomentDetailContent` (1404) is still ~400 lines of media-layout composition — a future split could extract a `MomentMediaContent.kt`, but it is tightly coupled to the pager state and out of scope here.
