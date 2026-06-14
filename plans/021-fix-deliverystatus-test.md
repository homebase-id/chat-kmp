# Plan 021: Make DeliveryStatusTest actually assert something

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md (create the file with a one-row table if it does not yet exist).
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-chat/src/commonTest/kotlin/id/homebase/chat/widget/DeliveryStatusTest.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageBubble.kt`. If this prints ANY changed-file lines, the source has moved since this plan was written — STOP and re-read the two files below, re-confirming the line numbers in "Current state" before proceeding.

## Status
Priority P2; Effort S; Risk LOW; Depends on: none; Category tests; Planned at: commit 45e2832e, 2026-06-14.

## Why this matters
`DeliveryStatusTest.kt` is the only `@Test` file in the repo that asserts nothing: all six tests call `setContent { DeliveryStatus(...) }` then `waitForIdle()` with no `assert*` afterward. They pass even if every delivery-status branch (pending / sent / delivered / read / failed / stale-pending) rendered an identical icon, the wrong icon, or nothing at all. That gives false coverage confidence on a user-visible message-status indicator: a regression that, say, made "read" render the "sent" icon would ship green. This plan adds real semantics assertions so each branch is pinned to its intended icon, and — critically — proves the stale-pending case is visually distinct from a fresh-pending case (the bug the warning tint exists to surface). The source change is one `testTag` confined to the `DeliveryStatus` composable; everything else is in the test file.

## Current state

### File 1 (test, to be rewritten): `homebase-chat/src/commonTest/kotlin/id/homebase/chat/widget/DeliveryStatusTest.kt`
All six tests follow this shape (lines 15-27 for the first; the other five are structurally identical, lines 29-98):

```kotlin
@Test
fun rendersForPendingSend() = runComposeUiTest {
    setContent {
        MaterialTheme {
            DeliveryStatus(
                isPendingSend = true,
                deliveryStatus = 0,
                contentColor = Color.White,
            )
        }
    }
    waitForIdle()        // <-- nothing asserted after this
}
```

The stale-pending case (lines 85-98) is identical except it passes `pendingSince = Clock.System.now() - 2.minutes`. Existing imports at the top of the file (lines 1-10): `MaterialTheme`, `Color`, `ExperimentalTestApi`, `runComposeUiTest`, `ChatDeliveryStatus`, `kotlin.test.Test`, `kotlin.time.Clock`, `kotlin.time.Duration.Companion.minutes`. The class is annotated `@OptIn(ExperimentalTestApi::class)` (line 12).

### File 2 (composable, ONE testTag to add): `homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageBubble.kt`
The `DeliveryStatus` composable, lines 823-877:

```kotlin
@Composable
fun DeliveryStatus(
    isPendingSend: Boolean,
    deliveryStatus: Int,
    contentColor: Color,
    pendingSince: Instant? = null,
) {
    val warning = deliveryFailureTint()
    if (isPendingSend) {
        val stale = pendingSince != null && rememberPendingStale(pendingSince)
        Icon(
            Icons.Default.Alarm,
            contentDescription = stringResource(MR.string.message_sending),   // line 835
            modifier = Modifier.size(16.dp),                                  // line 836
            tint = if (stale) warning else contentColor,
        )
    } else {
        when (deliveryStatus) {
            ChatDeliveryStatus.Failed.value -> {
                Icon(Icons.Default.ErrorOutline,
                    contentDescription = stringResource(MR.string.message_send_failed), ...)   // line 844
            }
            ChatDeliveryStatus.Read.value -> {
                Icon(HomebaseIcons.MessageSentAndRead,
                    contentDescription = stringResource(MR.string.message_read), ...)           // line 853
            }
            ChatDeliveryStatus.Delivered.value -> {
                Icon(HomebaseIcons.MessageSentAndDelivered,
                    contentDescription = stringResource(MR.string.message_delivered), ...)       // line 862
            }
            ChatDeliveryStatus.Sent.value -> {
                Icon(HomebaseIcons.MessageSent,
                    contentDescription = stringResource(MR.string.message_sent), ...)            // line 870
            }
        }
    }
}
```

Important facts the executor must rely on:
- Every branch ALREADY sets a `contentDescription` from `stringResource`. The five distinct strings exist in `homebase-common/src/commonMain/composeResources/values/strings.xml` (lines 1082-1086): `message_sending`="Sending", `message_read`="Read", `message_delivered`="Delivered", `message_sent`="Sent", `message_send_failed`="Failed to send".
- The fresh-pending branch and the stale-pending branch are the SAME `Icon` — they share `contentDescription = message_sending` and differ ONLY in `tint` (`warning` vs `contentColor`). Tint is not exposed in the semantics tree, so contentDescription alone CANNOT distinguish stale from fresh. This is why one `testTag` (encoding staleness) must be added to the pending Icon.
- Imports already present in MessageBubble.kt: `androidx.compose.ui.Modifier` (line 51), `androidx.compose.foundation.layout.size` (line 24), `androidx.compose.foundation.layout.height` (line 21). `androidx.compose.ui.platform.testTag` is NOT yet imported in this file (it is imported in the sibling `ReplyPreviewBar.kt`) — you must add it.

### Convention + exemplar to match
Assert-bearing Compose UI tests in this repo use `runComposeUiTest { setContent { ... }; onNodeWith*(...).assertExists() }`. The canonical sibling exemplar is `homebase-chat/src/commonTest/kotlin/id/homebase/chat/widget/ReplyPreviewBarTest.kt`: it imports `androidx.compose.ui.test.onNodeWithTag`, calls `onNodeWithTag("reply_preview_bar").assertExists()`, and asserts a callback with `assertTrue(...)`. The matching source side sets the tag with `Modifier.testTag("reply_preview_bar")` in `ReplyPreviewBar.kt` (lines 138, 211). Follow that exact pattern. `onNodeWithContentDescription` is the other available matcher (from `androidx.compose.ui.test`) and is the right tool for the five non-pending branches since they already carry contentDescription.

## Commands you will need
| Purpose | Command | Expected on success |
|---|---|---|
| Drift check (run FIRST) | `git diff --stat 45e2832e..HEAD -- homebase-chat/src/commonTest/kotlin/id/homebase/chat/widget/DeliveryStatusTest.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageBubble.kt` | No output (no lines printed) |
| Confirm strings still defined | `grep -nE 'name="message_(sending\|read\|delivered\|sent\|send_failed)"' homebase-common/src/commonMain/composeResources/values/strings.xml` | 5 matching lines |
| Compile the chat composable (common) | `./gradlew :homebase-chat:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Run the target test class | `./gradlew :homebase-chat:jvmTest --tests 'id.homebase.chat.widget.DeliveryStatusTest'` | `BUILD SUCCESSFUL`, 6 tests run, 0 failed |
| Full chat jvm tests (regression) | `./gradlew :homebase-chat:jvmTest` | `BUILD SUCCESSFUL` |
| Konsist string-literal guard | `./gradlew :homebase-common:jvmTest --tests '*ArchitectureTest*'` | `BUILD SUCCESSFUL` (no `Text("…")` introduced) |

## Scope
In scope (only these two files may be modified):
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageBubble.kt` — add ONE `testTag` to the pending-send `Icon` (and the `import` for `testTag`). No other change.
- `homebase-chat/src/commonTest/kotlin/id/homebase/chat/widget/DeliveryStatusTest.kt` — add assertions to all six tests; add the test-side imports.

Out of scope (do NOT touch):
- The `when (deliveryStatus)` branch logic, icon choices, or tints in `DeliveryStatus` — this plan asserts current behavior, it does not change it.
- `rememberPendingStale` (MessageBubble.kt lines 884-895) — the staleness threshold/timer logic stays as-is.
- `getDeliveryStatus` / `ChatDeliveryStatus` enum in `homebase-chat/.../services/` — delivery-status computation is out of scope.
- `homebase-common/.../composeResources/values/strings.xml` — the five strings already exist; do not add or rename any. (Only add a string if Step 1's grep shows one missing, which is not expected.)

## Steps

1. **Run the drift check** (first command in the table). If it prints any file lines, STOP (see STOP conditions). Then run the "Confirm strings still defined" grep and verify all 5 lines print. If any is missing, STOP.
   Verify: `git diff --stat 45e2832e..HEAD -- <the two paths>` → no output, AND the grep → 5 lines.

2. **Add the `testTag` import to MessageBubble.kt.** Insert `import androidx.compose.ui.platform.testTag` in the import block alongside the other `androidx.compose.ui.*` imports (it currently sits near `import androidx.compose.ui.Modifier` at line 51 — add it in alphabetical proximity, e.g. right after the existing `androidx.compose.ui.platform.*` imports or after the `androidx.compose.ui.Modifier` line). Do not reorder unrelated imports.
   Verify: `grep -n 'import androidx.compose.ui.platform.testTag' homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageBubble.kt` → exactly one match.

3. **Tag the pending-send Icon with a staleness-encoding testTag.** In the `if (isPendingSend) { ... }` block of `DeliveryStatus` (around lines 831-838), change the pending `Icon`'s `modifier` so it carries a stable, staleness-dependent tag. Replace:
   ```kotlin
       modifier = Modifier.size(16.dp),
   ```
   with:
   ```kotlin
       modifier = Modifier
           .size(16.dp)
           .testTag(if (stale) "delivery_status_pending_stale" else "delivery_status_pending_fresh"),
   ```
   Do NOT change `contentDescription`, `tint`, or the `Icons.Default.Alarm` argument. Leave the five `else`-branch Icons untouched (they are asserted via their existing contentDescription).
   Verify: `./gradlew :homebase-chat:compileKotlinJvm` → `BUILD SUCCESSFUL`.

4. **Rewrite `DeliveryStatusTest.kt` to assert.** Replace the file body so every test ends with a real assertion. Keep the `@OptIn(ExperimentalTestApi::class)` class annotation. Add these test imports (in addition to the existing ones): `androidx.compose.ui.test.onNodeWithContentDescription`, `androidx.compose.ui.test.onNodeWithTag`, `androidx.compose.ui.test.assertIsDisplayed`. Use exactly these assertions per test (the contentDescription literals are the English string VALUES from `strings.xml`, resolved at runtime by `stringResource`; the testTags are the ones added in Step 3):
   - `rendersForPendingSend` (fresh): after `waitForIdle()` →
     `onNodeWithTag("delivery_status_pending_fresh").assertIsDisplayed()` and `onNodeWithTag("delivery_status_pending_stale").assertDoesNotExist()`.
   - `rendersForSentStatus` → `onNodeWithContentDescription("Sent").assertIsDisplayed()`.
   - `rendersForDeliveredStatus` → `onNodeWithContentDescription("Delivered").assertIsDisplayed()`.
   - `rendersForReadStatus` → `onNodeWithContentDescription("Read").assertIsDisplayed()`.
   - `rendersForFailedStatus` → `onNodeWithContentDescription("Failed to send").assertIsDisplayed()`.
   - `rendersForStalePendingSend` → `onNodeWithTag("delivery_status_pending_stale").assertIsDisplayed()` and `onNodeWithTag("delivery_status_pending_fresh").assertDoesNotExist()`.
   Add `import androidx.compose.ui.test.assertDoesNotExist` if your IDE/compiler reports it unresolved — note `assertDoesNotExist()` is a member function on `SemanticsNodeInteraction`, so it typically needs no separate import; the matcher imports (`onNodeWithTag`, `onNodeWithContentDescription`) and `assertIsDisplayed` are the ones that must be imported. Do not change the `setContent { MaterialTheme { DeliveryStatus(...) } }` bodies — only append the assertions and add imports.
   Verify: `./gradlew :homebase-chat:jvmTest --tests 'id.homebase.chat.widget.DeliveryStatusTest'` → `BUILD SUCCESSFUL`, 6 tests, 0 failed.

5. **Prove the tests would catch a regression (temporary mutation — DO NOT COMMIT).** Temporarily break ONE branch to confirm the new assertions fail when the wrong icon renders. For example, in `DeliveryStatus`, temporarily swap the `Read` branch's `contentDescription` to `stringResource(MR.string.message_sent)`, recompile, and run `rendersForReadStatus`. Confirm it now FAILS (`onNodeWithContentDescription("Read")` finds no node). Then revert the mutation exactly.
   Verify: with the mutation, `./gradlew :homebase-chat:jvmTest --tests 'id.homebase.chat.widget.DeliveryStatusTest'` → `rendersForReadStatus FAILED`. After revert + rerun → all 6 pass. (If the test still passes WITH the mutation, the assertion is not actually pinning the icon — STOP and fix the test before continuing.)

6. **Run the surrounding gates.** Run the full chat jvm test suite and the Konsist architecture guard to confirm no string-literal violation was introduced (a `testTag("…")` literal is allowed by the guard; a `Text("…")` is not — you did not add any `Text`).
   Verify: `./gradlew :homebase-chat:jvmTest` → `BUILD SUCCESSFUL`; `./gradlew :homebase-common:jvmTest --tests '*ArchitectureTest*'` → `BUILD SUCCESSFUL`.

7. **Update the plan index.** In `plans/README.md` mark this plan (021) done. If `plans/README.md` does not exist, create it with a minimal one-row table header (`| Plan | Title | Status |`) and the 021 row marked done. This is the only file outside the two in-scope source files you may create.
   Verify: `grep -n '021' plans/README.md` → the row is present and marked done.

## Test plan
- File modified: `homebase-chat/src/commonTest/kotlin/id/homebase/chat/widget/DeliveryStatusTest.kt`.
- New behavior pinned by the six tests:
  - `rendersForPendingSend` — pending icon renders with the FRESH tag; stale tag absent. (Regression caught: pending icon missing, or fresh/stale mixed up.)
  - `rendersForSentStatus` / `rendersForDeliveredStatus` / `rendersForReadStatus` / `rendersForFailedStatus` — each renders the icon whose contentDescription is "Sent" / "Delivered" / "Read" / "Failed to send" respectively. (Regression caught: a branch rendering the wrong icon, e.g. Read showing the Sent glyph, or a branch rendering nothing.)
  - `rendersForStalePendingSend` — pending icon renders with the STALE tag; fresh tag absent. This is THE regression this plan most directly fixes: previously fresh-pending and stale-pending were indistinguishable in test (same contentDescription, tint not in semantics), so a bug that never flipped to the warning state would have passed. The testTag added in Step 3 makes staleness observable.
- Model after: `homebase-chat/src/commonTest/kotlin/id/homebase/chat/widget/ReplyPreviewBarTest.kt` (`onNodeWithTag(...).assertExists()` + `Modifier.testTag(...)` on the source side).
- Verify command: `./gradlew :homebase-chat:jvmTest --tests 'id.homebase.chat.widget.DeliveryStatusTest'` → 6 tests pass; Step 5 proves they fail under a wrong-icon mutation.

## Done criteria
- [ ] `git diff --stat 45e2832e..HEAD -- <the two in-scope paths>` shows ONLY those two files changed; no other source file is modified.
- [ ] `grep -c 'assert' homebase-chat/src/commonTest/kotlin/id/homebase/chat/widget/DeliveryStatusTest.kt` ≥ 6 (every test asserts at least once; pending/stale tests assert twice).
- [ ] `grep -n 'testTag' homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageBubble.kt` shows the pending Icon's staleness-encoding `testTag` and the new `import`.
- [ ] `./gradlew :homebase-chat:compileKotlinJvm` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-chat:jvmTest --tests 'id.homebase.chat.widget.DeliveryStatusTest'` → 6 tests run, 0 failed.
- [ ] Step 5 demonstrated: with a deliberate wrong-icon mutation, `rendersForReadStatus` (or the chosen branch) FAILS; after revert, all 6 pass.
- [ ] `./gradlew :homebase-chat:jvmTest` and `./gradlew :homebase-common:jvmTest --tests '*ArchitectureTest*'` → both `BUILD SUCCESSFUL`.
- [ ] No new `Text("…")` literal introduced; no `contentDescription`/`tint`/icon argument in `DeliveryStatus` changed (only a `testTag` and an import added).
- [ ] `plans/README.md` row for 021 marked done.

## STOP conditions
- The drift check (Step 1) prints any changed-file line for either in-scope path → the source moved; STOP, re-read both files, re-confirm the line numbers in "Current state", and only then continue.
- Any of the five `message_*` strings is missing from `homebase-common/src/commonMain/composeResources/values/strings.xml` → STOP; the contentDescription assertions depend on those exact English values, and a missing string means the composable also changed.
- In Step 5 the chosen test still PASSES with the deliberate wrong-icon mutation → the assertion is not pinning the icon; STOP and strengthen the test before reverting.
- The English string values differ from what this plan assumes ("Sent"/"Delivered"/"Read"/"Failed to send"/"Sending") → STOP; update the contentDescription literals in the test to match `strings.xml` exactly (the test resolves the live string), and note the change.
- `compileKotlinJvm` fails after Step 3 with an unresolved `testTag` → the import was not added or was placed in a non-`commonMain` block; STOP and fix the import.

## Maintenance notes
- A reviewer should scrutinize: (a) that the source change is EXACTLY one `testTag` plus one import — no branch logic, icon, or tint was altered; (b) that the stale-vs-fresh distinction is asserted via testTag, not contentDescription (they share `message_sending`, so a contentDescription-only test would be a no-op for staleness); (c) that Step 5's mutation-proof was actually performed and reverted (the assertions are worthless if they pass against a wrong icon).
- The contentDescription literals in the test are coupled to the English `strings.xml` values. If those user-facing strings are reworded later, these five assertions break — that is intended (the test pins the rendered semantics). A future maintainer who reworords the strings should update the test in the same change. If this coupling becomes annoying, the cleaner follow-up is to give all five non-pending branches their own `testTag`s too (mirroring the pending one) and assert on tags instead of contentDescription — deferred here to keep the source change minimal.
- Deferred follow-up (out of scope): there is no test that the stale tint actually equals `deliveryFailureTint()` (tint is not in the semantics tree). If tint correctness ever regresses, a screenshot/pixel test would be needed; the testTag added here only proves the stale BRANCH was taken, not the exact color.
