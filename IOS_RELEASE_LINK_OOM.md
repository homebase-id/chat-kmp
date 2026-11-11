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
upload steps used to run unconditionally, so a `workflow_dispatch` on an
experiment branch would have shipped an experimental IPA to testers. Now:
**prod** uploads only from `main`/tags; **dev** uploads on nightlies always,
and on manual dispatch per the `upload_to_testflight` checkbox (default on, so
dispatching a feature branch to test it on the dev TestFlight app still works —
untick it for experiment/CI-debug runs).

## If it OOMs again

1. Pull the GC log from the artifact; bucket `Pause` lines by time and read
   the *after-Full-GC* values — that's the true live set. Don't trust
   peak-used at a large `-Xmx` (it's mostly garbage G1 hasn't collected).
2. If the live set has grown toward the cap: raise `-Xmx` (swap absorbs it;
   green is better than fast) and, in parallel, MAT the heap dump to see if a
   *new* phase became the owner before assuming it's general growth.
3. Escalation ladder beyond that: `macos-15-xlarge` (14 GB RAM, ~2× cost/min).

## 2026-10-26: the live set outgrew the 7 GB runner

Dev nightlies hit the 120-min step timeout from Oct 14 onwards (prod, at 180,
took 132–177 min). Sweep of every `ios-jvm-oom-diagnostics` artifact since
2026-07-28 (189 runs). "Live" is the lowest heap after a Mixed GC at the
middle-end peak; "marking" is the summed Concurrent Mark Cycle time:

| Period | Live | Marking | build-ios job |
|---|---|---|---|
| late Jul | 7.3–7.6 GB | 5–8 min | 55–80 min |
| early Sep | 8.0 GB | 8–15 min | 65–100 min |
| late Sep | 8.5–8.7 GB | 15–24 min | 90–134 min |
| Oct 7–13 | 9.0–9.3 GB | 20–30 min | 112–141 min |
| Oct 14–26 | 9.7–9.9 GB | 27–43 min | prod 132–177; dev killed at 121 |

- **`-Xmx12g` is not oversized.** The live set (~9.8 GB) is the floor: a
  smaller heap would OOM (6g OOM'd at 6.1 GB live in July). Heap after GC
  sits at 10.2–11.6 GB of 12 GB through the middle-end.
- **Stop-the-world GC is not the cost.** Total pause time per link is
  5–10 s with 0 Full GCs. Concurrent marking runs most of the middle-end
  (live is ~82 % of the heap), 150–224 s per cycle.
- **Swap is real but not the main cost** (see the Intel run below: 14 GB, no
  swap, same ~50-min middle-end). The link got slow because its work grew:
  middle-end ~10 → 36–54 min, LLVM codegen after it ~45 → 60–95 min.
- **Where the time goes:** the whole Gradle part of the Xcode build is one
  `Compile Kotlin Framework` script phase (xcpretty hides Gradle's output):
  78 min in the green dev run 39995126786, 147 min in prod 40065553353, and
  >117 min when 40284718950 was killed. Link start/end come from the artifact
  zip's file mtimes. The link was killed in LLVM codegen.
- **Biggest single step:** +0.5–0.7 GB of live set between run 38525141431
  (main 340a0032a) and 38575327085 (Oct 14), when PR #1930 (call-video-upgrade)
  brought `webrtc-kmp` into the iOS framework.

**More RAM alone does not make it fast.** Experiment run 40408054253 (dev, on
`macos-15-intel`: free standard runner, 4 vCPU, 14 GB, GC log confirms
`Memory: 14336M`): green, but the job took **160 min** (link 134 min: JVM
middle-end ~50 min, LLVM ~80 min). With no swap the heap hit the 12g cap
(12282M) and ran one **211 s Full GC** — the live set is now at the ceiling.
The slower Intel cores cancel the swap savings, so `build-ios` stays on
`macos-15` (arm64).

**Change made:** dev's `Build iOS` step timeout is 180 min, like prod's. The
dev runs that were killed at 121 min are the same build that prod finishes in
132–177 min.

**Still open (decision for the owner):**

1. **Headroom is about gone.** Live ~9.8 GB under a 12g cap; ~+0.7 GB/month.
   Expect OOMs or >180-min jobs within weeks. Raising `-Xmx` on a 7 GB runner
   only adds swap.
2. **Options:** `macos-15-xlarge` (paid; arm64, more RAM and cores — the
   escalation already named above), or cut the link's live set. To find what
   holds it, MAT a heap dump from a deliberately capped run (e.g. `-Xmx9g`
   on a branch, `upload_to_testflight` off). Start with the webrtc-kmp jump.

## 2026-11-10: the static-initializer phase owned the live set

Prod and dev release builds hit the 180-min timeout (live set ≥11.5 GB on 12g; 31–39 Full GCs
of ~200 s each in swap). Two runs on branches, TestFlight and Play upload off:

**Profile** (run 42845381943, `macos-15-intel` 14 GB, `-Xprofile-phases`, a `jcmd
GC.class_histogram` + main-thread stack every 10 min): green, link 129 min.

| Phase | Time | Live during it |
|---|---|---|
| `RemoveRedundantCallsToStaticInitializersPhase` | **54 min** | **11.7–11.8 GB** |
| `ObjectFiles` (external clang) | 36 min | ~2.9 GB (JVM idle) |
| `ModuleBitcodeOptimization` + `LTOBitcodeOptimization` | 15 + 9.5 min | 3–5 GB |
| Everything else | ~15 min | 3–5.7 GB |

- The histogram during that phase: 176 M `CallGraphNode$CallSite` (5.6 GB) plus 2.9 GB of
  `Object[]` holding them. The stacks are in `CallGraphBuilder.build` /
  `StaticInitializersOptimization$InterproceduralAnalysis`.
- Why it exploded: the phase builds its call graph with unfold factors `Int.MAX_VALUE`
  (`LTO.kt`), and with devirtualization off every virtual call site gets an edge to every
  override. Turning off devirtualization in July moved the call-graph cost here, and it has
  grown with the app since.
- Why skipping it is safe (2.4.21 `TopLevelPhases.runCodegen` bytecode): the phase runs only
  when `shouldOptimize()`, so every debug build already runs without it. It only deletes
  calls to file initializers that are already guarded; without it each call stays and checks
  a flag.

**Fix** (run 42874363855, `macos-15` 7 GB arm64, Xmx12g, otherwise main's settings):
`-Xdisable-phases=DevirtualizationAnalysis,RemoveRedundantCallsToStaticInitializersPhase`.
Green: `Build iOS` 92 min (was timing out at 180), link 76 min, **zero Full GCs**, peak
heap after GC 6.6 GB. IPA 95 MB, the same as the profile run of the same commit.

To profile phases again, add `-Xprofile-phases` to the release framework's `freeCompilerArgs` on
a branch: the per-phase `Name: N msec` lines land in the gym log in `ios-jvm-oom-diagnostics`.

The exported ObjC surface is large: `ComposeApp.h` is 7.9 MB with 3960 classes, and the
framework binary is 375 MB. Exported declarations are DCE roots, so trimming the export
shrinks the binary and the LLVM phases: see the next section.

## 2026-11-11: an ObjC export list shrinks the LLVM phases

K/N 2.4.21 can limit ObjC export to a list of entry points
(`-Xbinary=objcExportEntryPointsPath=homebase-core/objc-entry-points.txt`). Only the listed
functions and properties get ObjC bridges, and the rest are no longer DCE roots; classes stay
in the header. Swift used 169 of the 35,746 exported members.

Runs on branch `ios-link-experiments` (dev workflow, TestFlight off, `-Xprofile-phases` on
both sides). Each pair started together on `macos-15` with #2287's settings:

| | Link | LTO / Module / ObjectFiles | Peak heap (Remark) | Framework | IPA |
|---|---|---|---|---|---|
| Without the list, run 43007215840 | 98 min | 30.5 / 24.7 / 16.0 min | 6.5 GB | 377 MB | 102.3 MB |
| With the list, run 43007197113 | **50 min** | 12.1 / 10.1 / 11.7 min | 5.7 GB | 316 MB | 92.8 MB |
| Without the list, run 42988668452 | 98 min | 28.6 / 25.4 / 17.8 min | 6.3 GB | 377 MB | 102.3 MB |
| With the list, run 42988672351 (Swift failed, below) | 71 min | 20.4 / 16.7 / 12.8 min | 5.6 GB | 316 MB | |
| With the list, PR #2300's TestFlight build, run 43038548740 | 87 min | not profiled | 6.3 GB | | 91.7 MB artifact |
| Without the list, nightly run 42988940008 | 45 min | not profiled | 6.4 GB | | 100.6 MB artifact |

`ComposeApp.h` went from 7.9 MB to 1.8 MB. What the list saves in time is not settled: on the same
`macos-15` label the link took 45-98 min without the list and 50-87 min with it, and even the runs
that started together ran in different GitHub data centers (`.diag` file names: sat12, sjc20,
sjc22, iad20). The smaller binary is less work for LLVM; to measure the time it saves, link
both ways in one job on the same machine.

The first run failed at the Swift compile: `callable <Interface>.*` on a Kotlin interface that
Swift implements also matches the `equals`/`hashCode`/`toString` every interface inherits,
which then became required protocol methods. Those interfaces list their members by name, and
`scripts/check-objc-entry-points.py` (Lint workflow) fails when one is missing, because a
missing member is dropped from the protocol without a compile error and fails at runtime.

Measured in the same batch, and not worth it for time:

- `-Xbinary=smallBinary=true` (run 42988676346): link 101 min. It sets the LLVM size level to
  aggressive, which moves work between the LLVM phases. IPA 102.3 → 89.5 MB, if size ever matters.
- `-Xoverride-konan-properties=clangOptFlags.ios_arm64=-O1` (run 42988680049): link 106 min,
  `ObjectFiles` unchanged, framework 361 MB.
