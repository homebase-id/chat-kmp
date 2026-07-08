# iOS Release Link OOM — Root Cause & Fix

**Status (2026-07-08): fixed** on the CI release pipeline by disabling the
Kotlin/Native `DevirtualizationAnalysis` LTO phase for the release framework
link, keeping `-Xmx12g` as heap headroom. iOS release job: **86–99 min → ~65
min**, with the recurring OOM cause removed rather than papered over with ever
larger heaps.

This document records what was broken, the evidence, why the fix is safe, and
the measurements behind every number, so the next person doesn't have to
re-derive it.

## Symptom history

`:homebase-core:linkReleaseFrameworkIosArm64` (the K/N compiler linking the
`ComposeApp` release framework inside the Xcode build, macos-15 runner, 7 GB
RAM) kept OOMing, and each "fix" was a bigger `-Xmx` that the app's growth
then caught up with:

| Date | Event |
|---|---|
| 2026-06 | Repeated OOMs. PR #712 moved every K/N compile into a fresh short-lived process (`kotlin.native.disableCompilerDaemon=true`) with `-Xmx8g`, plus GC logs + heap dump on OOM. PR #718 removed a double link. Green at ~70 min. |
| 2026-06 | MAT dominator analysis of run 27407420160's heap dump identified the owner of the (then) ~5.1 GB live set: the **`DevirtualizationAnalysis` phase of K/N link-time optimization** — it OOM'd growing a 1 GB `long[]` in `CondensationBuilder.mergeEdges` condensing a call graph with >67 M edges. |
| 2026-07-07 | The app grew; 8g OOM'd again (run 28881233018: heap pinned at ~8.19 GB **live** through 16 consecutive Full GCs that reclaimed nothing, 34 Full GCs total at 60–165 s each — swap-amplified). Bumped to `-Xmx12g`: green, but the iOS job took 86–99 min, most of it a 12 GB heap thrashing through swap on a 7 GB machine. |
| 2026-07-08 | This fix: skip the phase that owns the memory. |

The pattern to recognize: **the live set during the link grows with app size**
(~5.1 GB in June → ~8.2 GB in July with devirtualization on). Any fixed `-Xmx`
only buys time.

## Root cause

In optimized (release) builds, the K/N backend runs a whole-program
devirtualization analysis: it builds a data-flow graph of the entire program
(app + all libraries — the ObjC export surface is irrelevant, so trimming
`export()`s would *not* help), then propagates types over a constraint graph
whose condensation held >67 M edges for this app. The analysis working set —
giant `long[]`/`int[]` hash sets and DFG bookkeeping — is several GB, genuinely
live (Full GCs reclaim nothing while it runs), and scales with program size.

Measured peak live heap of the release link:

- devirtualization **on**: ~8.2 GB (2026-07, run 28881233018)
- devirtualization **off**: ~6.1–6.5 GB peak during the middle-end
  (minutes ~2–14 of the link), settling to ~3.9–4.4 GB afterwards

## The fix

Two pieces, both CI-only:

1. **`homebase-core/build.gradle.kts`** — the iOS framework config adds
   `-Xdisable-phases=DevirtualizationAnalysis` to `freeCompilerArgs`, gated on
   `buildType == RELEASE` **and** the Gradle property
   `homebase.native.disableDevirtualization=true`. Local builds, debug builds,
   and every other workflow are untouched.

2. **`.github/workflows/build-mobile-release-prod.yml` and
   `build-mobile-release-dev.yml`** — both workflows build the same iOS
   release framework (prod on release dispatch/tags, dev on the nightly cron);
   each one's "Apply iOS CI memory overrides" step appends the property (plus
   the existing memory settings, still `-Xmx12g`) to `gradle.properties` on
   the runner.

To turn it back on: delete the property line from the workflows. Worth
re-testing if JetBrains makes the analysis memory-proportionate (track
Kotlin/Native release notes for devirtualization/LTO memory work).

## Why disabling the phase is safe

Verified against the Kotlin **2.3.10** sources (the exact version in
`gradle/libs.versions.toml` at the time; re-verify on major compiler bumps):

- The compiler itself runs the phase conditionally —
  `runAndMeasurePhase(DevirtualizationAnalysisPhase, …, disable = !optimize)`
  (`TopLevelPhases.kt`). "Analysis absent" is the normal state of every debug
  build; `-Xdisable-phases` just forces that supported path in a release build.
- `DevirtualizationPhase` (the IR rewrite) reads per-call-site annotations the
  analysis would have written and no-ops without them:
  `expression.devirtualizedCallSite ?: return expression`.
- DCE builds its call graph via `CallGraphBuilder`, which handles
  un-devirtualized virtual calls explicitly: *"Callsite has not been
  devirtualized — conservatively assume the worst: any inheritor of the
  receiver type is possible here."* Sound; it just keeps more code.
- `EscapeAnalysis` for a **framework** (no entry point) already refuses to
  unfold non-devirtualized call sites (unfold factor −1 — *"Can't tolerate any
  non-devirtualized call site for a library"*), so it degrades to pessimistic
  lifetimes (fewer stack allocations), not to a blow-up.

Trade-offs, measured and expected:

- **IPA size: slightly smaller** (66.46 MB vs 67.22 MB baseline).
  Counter-intuitive but real: devirtualization *unfolds* call sites into
  type-check dispatch chains, which costs more code than the conservative
  DCE retains.
- **Runtime performance**: virtual calls stay virtual and fewer objects are
  stack-allocated. Not measured on-device; no regression reported from
  TestFlight builds. If a hot path ever measurably regresses, that's the
  trade to revisit.

## Measurements (all on this app, macos-15 runner, fresh-process link)

| Run | Config | Result | build-ios job | Link process | Full GCs | Peak live |
|---|---|---|---|---|---|---|
| 28881233018 | devirt ON, 8g | **OOM** | died at 66 min | 46 min† | 34, no-op | ~8.2 GB |
| 28889418331 / 28898133081 | devirt ON, 12g | green | 99 / 86 min | — | — | (swap-bound) |
| 28927080827 | devirt OFF, 12g | green | **65 min** | 57 min | **0** | ~6.1–6.5 GB |
| 28931927413 | devirt OFF, 6g | **OOM** | died at 28 min | 21 min† | 56, no-op | pinned 6.1 GB |
| 28934048254 | devirt OFF, 8g | green | 68 min | 60 min | 1 (38 s) | ~6.5 GB |

†died mid-link.

Why 12g and not 8g, given both are green: 8g grazed the ceiling (its one Full
GC sat exactly at the middle-end peak) and the peak grew ~1 GB in the month
before this fix, so 8g would erode again; 12g ran GC-clean and slightly faster.
The heap only *commits* what it uses plus garbage slack — the 30-minute LLVM
codegen stretch in the middle of the link allocates in native memory with the
JVM idle, so oversizing `-Xmx` costs little there.

Link profile with devirt off (from the GC logs): ~15 min JVM middle-end
(lowerings, DFG, DCE, escape analysis — this is where the 6.1 GB peak lives),
then ~30 min of JVM-silent LLVM codegen/optimization, then a short tail.

## Diagnostics you get from every run

The `ios-jvm-oom-diagnostics` artifact uploads on **success and failure**
(`if: always()`): `kn-gc-<pid>.log` per K/N process (the link is the
long-lived one). On OOM it also contains `java_pid<pid>.hprof` (multi-GB;
open with Eclipse MAT, look at the dominator tree). This is how every number
in this document was obtained — keep it working.

Related workflow hardening that rode along with this fix: the TestFlight
upload steps are gated to `main`/tags, because `workflow_dispatch` on an
experiment branch used to run them unconditionally and would have shipped an
experimental IPA to testers.

## If it OOMs again

1. Pull the GC log from the artifact; bucket `Pause` lines by time and read
   the *after-Full-GC* values — that's the true live set. Don't trust
   peak-used at a large `-Xmx` (it's mostly garbage G1 hasn't collected).
2. If the live set has grown toward the cap: raise `-Xmx` (swap absorbs it;
   green is better than fast) and, in parallel, MAT the heap dump to see if a
   *new* phase became the owner before assuming it's general growth.
3. Escalation ladder beyond that: `macos-15-xlarge` (14 GB RAM, ~2× cost/min).
