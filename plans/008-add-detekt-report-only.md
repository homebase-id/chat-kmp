# Plan 008: Add a KMP-aware static-analysis gate (detekt) in report-only mode, plus an .editorconfig and a CI job

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- build.gradle.kts gradle/libs.versions.toml settings.gradle.kts gradle.properties .github/workflows/lint.yml .editorconfig config/`. If any in-scope file changed since this plan was written, compare the Current state excerpts below to live code first; on mismatch, STOP.

## Status
- Priority: P2
- Effort: M
- Risk: LOW
- Depends on: none
- Category: dx
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters
The repo's only automated code-quality gate is `./gradlew lint` (Android Lint), wired in `.github/workflows/lint.yml:37`. Android Lint only runs via the Android Gradle plugin, which is applied to just two modules (`androidApp` and `homebase-common` via `com.android.kotlin.multiplatform.library`); it does NOT analyze `commonMain`, `nativeMain`, `jvmMain`, or `wasmJsMain` Kotlin as plain `.kt` static analysis. That leaves the bulk of the codebase — roughly 210K LOC across all the KMP source sets — with no static-analysis gate at all: no detekt, no ktlint, no spotless, no `.editorconfig`, no pre-commit hook (`.git/hooks/` holds only the stock `*.sample` files). Detekt analyzes every Kotlin source set regardless of target, so adding it (in report-only mode first) gives the team a baseline for code smells, complexity, and style across the entire repo without breaking any build today. Report-only (`ignoreFailures = true`) means CI surfaces findings as artifacts/SARIF without failing the wall of pre-existing violations; the team ratchets to `ignoreFailures = false` later once the baseline is triaged.

## Current state

### The only existing gate is Android Lint
`.github/workflows/lint.yml` (full file, 41 lines). Key lines:
```yaml
1   name: Lint
3   on:
4     push:
5       branches: [main]
6     pull_request:
7       branches: [main]
17        - name: Set up JDK 21
18          uses: actions/setup-java@v5
19          with:
20            java-version: "21"
21            distribution: "temurin"
36        - name: Run Android Lint
37          run: ./gradlew lint
38          env:
39            JDK_21: ${{ env.JAVA_HOME }}
```
The new detekt workflow mirrors this structure (checkout → setup-java 21 temurin → gradle cache → chmod gradlew → run gradle task).

### Root build applies shared config via `subprojects {}` — this is where detekt is applied
`build.gradle.kts` (full file, 34 lines). The repo's idiom for "configure every module" is the `subprojects {}` block already present:
```kotlin
1  plugins {
2      // this is necessary to avoid the plugins to be loaded multiple times
3      // in each subproject's classloader
4      alias(libs.plugins.androidApplication) apply false
...
12     alias(libs.plugins.androidLint) apply false
13     alias(libs.plugins.googleServices) apply false
14     alias(libs.plugins.firebaseCrashlytics) apply false
15     alias(libs.plugins.buildConfigPlugin) apply false
16 }
17
18 subprojects {
19     configurations.all {
20         resolutionStrategy {
21             // Force encrypted sqlite-jdbc version everywhere
22             force("io.github.willena:sqlite-jdbc:3.51.2.0")
...
31         }
32     }
33 }
```
There is NO `buildSrc/` and NO convention-plugin module. `buildsystem/` contains only keystores (`keystore`, `debug.keystore`, `keystore-dev`) — it is NOT a Gradle build-logic dir. So detekt is wired by: (a) declaring the plugin in the root `plugins {}` block with `apply false`, and (b) applying + configuring it inside the existing `subprojects {}` block. Do NOT create a convention plugin — match the repo's flat `subprojects {}` style.

### Version catalog has no static-analysis tooling
`gradle/libs.versions.toml`. The `[versions]` table ends at line 90 (`metadataExtractor = "2.19.0"`); `kotlin = "2.3.21"` is at line 40. The `[plugins]` table is lines 226–241; the last entry is:
```toml
241  buildConfigPlugin = { id = "com.github.gmazzo.buildconfig", version.ref = "buildconfig" }
242
```
No `detekt`, `ktlint`, or `spotless` entry exists anywhere in the file (verified by grep over `*.toml`/`*.kts`/`*.yml`/`*.properties` → zero hits). The detekt plugin id is `io.gitlab.arturbosch.detekt`.

### Config-cache and Kotlin code style are already on
`gradle.properties`:
```
1   kotlin.code.style=official
21  org.gradle.caching=true
23  org.gradle.parallel=true
28  org.gradle.configuration-cache=true
```
`kotlin.code.style=official` (line 1) means the repo already declares the Kotlin official style; the new `.editorconfig` must encode the SAME official style so the two never disagree. `org.gradle.configuration-cache=true` (line 28) means **every** Gradle invocation is config-cached — the detekt tasks MUST be config-cache compatible (detekt 1.23.x is). Step 6 verifies this explicitly.

### Plugin repositories already include the Gradle Plugin Portal
`settings.gradle.kts:15-29` — `pluginManagement.repositories` lists `mavenCentral()`, `google {}`, and `gradlePluginPortal()`. Detekt is published to both mavenCentral and the Plugin Portal, so no repository change is needed.

### Gradle / wrapper facts
- Gradle wrapper is `9.5.0` (`gradle/wrapper/gradle-wrapper.properties` → `gradle-9.5.0-bin.zip`).
- `settings.gradle.kts:11-13` force-disables parallel project execution for any invocation whose task names contain `wasm`. `detekt` does not contain `wasm`, so it runs parallel as normal — no interaction.
- `**/build/` is gitignored (`.gitignore:10`), so detekt reports written under each module's `build/reports/detekt/` are NOT tracked and will never show in `git status`.

### Convention to match
Shared cross-module Gradle config goes in the root `build.gradle.kts` `subprojects {}` block (exemplar: the sqlite-jdbc force at lines 18–33). Plugin coordinates go in `gradle/libs.versions.toml` `[versions]` + `[plugins]` and are referenced from the root `plugins {}` with `apply false` (exemplar: `buildConfigPlugin` at catalog line 241 + root build line 15).

## Commands you will need
| Purpose | Command | Expected on success |
|---|---|---|
| Drift check | `git diff --stat 45e2832e..HEAD -- build.gradle.kts gradle/libs.versions.toml settings.gradle.kts gradle.properties .github/workflows/lint.yml .editorconfig config/` | empty output (no in-scope file changed) |
| Confirm latest stable detekt version | `./gradlew dependencyUpdates 2>/dev/null \|\| true` then check the Gradle Plugin Portal page `https://plugins.gradle.org/plugin/io.gitlab.arturbosch.detekt` | a `1.23.x` (or newer stable) version string; pick the newest stable |
| Generate the default detekt config (then prune) | `./gradlew detektGenerateConfig` | writes `config/detekt/detekt.yml`; BUILD SUCCESSFUL |
| Run detekt across all modules (report-only) | `./gradlew detekt` | BUILD SUCCESSFUL, exit 0 (because `ignoreFailures = true`); reports written under `*/build/reports/detekt/` |
| Confirm config-cache still works | `./gradlew help` then `./gradlew help` (run twice) | 2nd run prints `Reusing configuration cache.`; BUILD SUCCESSFUL |
| Confirm detekt is config-cache friendly | `./gradlew detekt` then `./gradlew detekt` (run twice) | 2nd run reuses or recomputes the config cache without a `problems-report`/error; BUILD SUCCESSFUL both times |
| Per-module sanity that nothing else broke | `./gradlew :homebase-api:compileKotlinJvm` | BUILD SUCCESSFUL (unchanged behaviour) |
| Verify git scope at the end | `git status --porcelain` | only in-scope files (see Done criteria) |

> Network note: choosing the detekt version requires network to mavenCentral / Plugin Portal (CI has it; local dev usually does). If you are fully offline and cannot resolve any detekt version, STOP and report — do NOT guess a version that fails to resolve.

## Scope
**In scope (only these files):**
- `gradle/libs.versions.toml` — add `detekt` to `[versions]` and a `detekt` plugin alias to `[plugins]`.
- `build.gradle.kts` (root) — add `alias(libs.plugins.detekt) apply false` to `plugins {}`; apply + configure detekt inside the existing `subprojects {}` block.
- `config/detekt/detekt.yml` (NEW) — generated then pruned baseline config.
- `.editorconfig` (NEW, repo root) — Kotlin official style.
- `.github/workflows/detekt.yml` (NEW) — JDK 21 CI job running `./gradlew detekt`, mirroring `lint.yml`.
- `plans/README.md` — only if it exists; add/update this plan's row (see Done criteria; if the file does not exist, do NOT create it — note that in your report).

**Out of scope (do NOT touch):**
- Any `.kt` / `.kts` source under `src/` — this plan is report-only; do NOT fix a single reported violation.
- `settings.gradle.kts` — repositories already include `gradlePluginPortal()`; no change needed.
- `gradle.properties` — config-cache and `kotlin.code.style=official` are already set; changing them is out of scope.
- `.github/workflows/lint.yml` — the existing Android Lint job stays as-is; detekt is a NEW separate workflow.
- Per-module `build.gradle.kts` files — detekt is applied centrally via the root `subprojects {}`; do NOT add `apply false` / `id("…detekt")` to individual modules.
- `buildsystem/` — keystores only; not build logic.

## Steps

1. **Drift check.** Run the Drift-check command above. If any in-scope file differs from the Current-state excerpts, STOP and reconcile before continuing.
   Verify: `git diff --stat 45e2832e..HEAD -- build.gradle.kts gradle/libs.versions.toml settings.gradle.kts gradle.properties .github/workflows/lint.yml .editorconfig config/` → empty.

2. **Pick the detekt version.** Determine the newest stable detekt release compatible with this toolchain. The detekt `1.23.x` line is the current stable series and is config-cache compatible and Gradle 9 / JDK 21 friendly; check the Plugin Portal page (`https://plugins.gradle.org/plugin/io.gitlab.arturbosch.detekt`) for the newest stable `1.23.x` tag and use that exact version (e.g. `1.23.8`). If a newer stable major (`>=1.24` / `2.x`) exists and its docs list Gradle 9 + JDK 21 support, you MAY use it instead — but prefer the proven `1.23.x` line to keep risk LOW. Record the chosen version; the next step pins it.
   Note on Kotlin: detekt bundles its own Kotlin-compiler analysis binary and does NOT require matching the project's `kotlin = "2.3.21"`. Detekt's default rules run in non–type-resolution mode and parse newer Kotlin syntax fine; we are NOT enabling `--type-resolution`. So a version mismatch between detekt's bundled compiler and project Kotlin 2.3.21 is expected and acceptable for report-only.
   Verify: you have a concrete version string that resolves (it will be exercised in Step 5).

3. **Add detekt to the version catalog.** Edit `gradle/libs.versions.toml`.
   - In `[versions]`, add a line (alphabetical-ish placement near other tool versions is fine; immediately after `kotlin = "2.3.21"` at line 40 is clean):
     ```toml
     detekt = "<chosen version from Step 2>"
     ```
   - In `[plugins]`, after the last entry (`buildConfigPlugin`, line 241), add:
     ```toml
     detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
     ```
   Verify: `./gradlew help` → BUILD SUCCESSFUL (catalog parses; the plugin is declared but not yet applied, so this must still succeed).

4. **Declare + apply detekt in the root build.** Edit `build.gradle.kts`.
   - In the root `plugins {}` block, add after line 15 (`alias(libs.plugins.buildConfigPlugin) apply false`):
     ```kotlin
     alias(libs.plugins.detekt) apply false
     ```
   - Inside the existing `subprojects {}` block (after the `configurations.all { … }` block, still inside `subprojects`), apply and configure detekt. Use the fully-qualified plugin id and configure via the `Detekt` task type so no extra imports leak in the wrong place:
     ```kotlin
     subprojects {
         configurations.all {
             // … existing sqlite-jdbc force block, unchanged …
         }

         apply(plugin = "io.gitlab.arturbosch.detekt")

         extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
             // Report-only: surface findings without failing the build on the
             // existing baseline. Flip to false once the baseline is triaged.
             ignoreFailures = true
             parallel = true
             buildUponDefaultConfig = true
             config.setFrom(rootProject.files("config/detekt/detekt.yml"))
             // Analyse every Kotlin source set this module has (commonMain,
             // nativeMain, jvmMain, androidMain, wasmJsMain, *Test, …).
             source.setFrom(
                 files(
                     "src/commonMain/kotlin",
                     "src/androidMain/kotlin",
                     "src/jvmMain/kotlin",
                     "src/nativeMain/kotlin",
                     "src/webMain/kotlin",
                     "src/wasmJsMain/kotlin",
                     "src/desktopMain/kotlin",
                     "src/commonTest/kotlin",
                     "src/jvmTest/kotlin",
                 ).filter { it.exists() },
             )
         }

         tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
             jvmTarget = "17"
             reports {
                 html.required.set(true)
                 sarif.required.set(true)
                 xml.required.set(false)
                 txt.required.set(false)
             }
         }
     }
     ```
     Notes for the executor:
     - `extensions.configure<…DetektExtension>` and `tasks.withType<…Detekt>` reference detekt's own classes; because the plugin is on the buildscript classpath (declared in root `plugins {}` with `apply false`), these types resolve in the root `build.gradle.kts` without an extra `buildscript {}` block. If the IDE/compiler cannot resolve `io.gitlab.arturbosch.detekt.*`, add `import io.gitlab.arturbosch.detekt.Detekt` and `import io.gitlab.arturbosch.detekt.extensions.DetektExtension` at the top of `build.gradle.kts` (top-of-file imports are the repo idiom — see `homebase-api/build.gradle.kts:1-2`).
     - `jvmTarget = "17"` matches the project's Java 17 baseline (CLAUDE.md). It only affects detekt's own analysis JVM target, not module compilation.
     - The `.filter { it.exists() }` keeps modules that lack a given source set from erroring; the `source.setFrom(...)` explicit list is belt-and-suspenders so detekt sees `nativeMain`/`wasmJsMain` even on modules where the KMP plugin wouldn't auto-register a detekt source set.
   - Do NOT remove or alter the existing `configurations.all { resolutionStrategy { … } }` block — only add the detekt apply/configure after it inside the same `subprojects {}`.
   Verify: `./gradlew help` runs twice; 2nd run prints `Reusing configuration cache.` → BUILD SUCCESSFUL. (Applying detekt must not break config-cache. If it does, see STOP conditions.)

5. **Generate and prune the detekt config.** Run `./gradlew detektGenerateConfig` — this writes the default `config/detekt/detekt.yml`. Then prune it to a trimmed baseline:
   - Keep the top-level `build:` / `config:` / `processors:` / `console-reports:` / `output-reports:` sections.
   - Keep the rule-set sections (`comments`, `complexity`, `coroutines`, `empty-blocks`, `exceptions`, `naming`, `performance`, `potential-bugs`, `style`, etc.) but REMOVE rules that are noisy or irrelevant for a KMP/Compose codebase to keep the file readable. At minimum disable the following (set `active: false`) so report-only output is signal, not noise:
     - `style > MagicNumber` (Compose dimensions/weights trip this constantly)
     - `style > WildcardImport` (the repo uses wildcard imports in places)
     - `style > MaxLineLength` OR raise `maxLineLength` to `140`
     - `naming > FunctionNaming` add `ignoreAnnotated: ['Composable']` (Composables are PascalCase by Compose convention and would otherwise all flag)
     - `complexity > LongMethod`, `complexity > LongParameterList` — raise thresholds rather than delete, since Compose composables legitimately take many params (e.g. `functionThreshold`/`constructorThreshold` to `8`).
   - Leave `ignoreFailures` OUT of this YAML — failure behaviour is controlled by the Gradle extension (`ignoreFailures = true`) from Step 4, which is the single source of truth.
   - The pruned file must remain valid YAML (detekt validates it on every run). Aim for a focused file, not the full ~600-line default dump.
   Verify: `./gradlew detekt` → BUILD SUCCESSFUL, exit 0 (report-only), and reports exist: `find . -path '*/build/reports/detekt/*' -name 'detekt.*' | head` returns at least one `.html`/`.sarif` file.

6. **Confirm config-cache stays usable WITH detekt on the graph.** Run `./gradlew detekt` twice in a row.
   Verify: both runs BUILD SUCCESSFUL; the 2nd prints `Reusing configuration cache.` (or recomputes cleanly with no `Configuration cache problems found` / `problems-report.html` error). Also re-run `./gradlew help` twice and confirm `Reusing configuration cache.`. If detekt emits config-cache *problems* (not just a recompute), see STOP conditions — scope it down (e.g. only register detekt on subprojects, never `allprojects`; avoid capturing `project` in task config) so the cache stays usable.

7. **Add the repo-root `.editorconfig`.** Create `/Users/biswa/Documents/GitHub/chat-kmp/.editorconfig` encoding Kotlin official style (consistent with `gradle.properties:1` `kotlin.code.style=official`):
   ```ini
   # Kotlin official code style — keep in sync with gradle.properties (kotlin.code.style=official).
   root = true

   [*]
   charset = utf-8
   end_of_line = lf
   insert_final_newline = true
   trim_trailing_whitespace = true
   indent_style = space
   indent_size = 4

   [*.{kt,kts}]
   indent_size = 4
   max_line_length = 140
   ij_kotlin_allow_trailing_comma = true
   ij_kotlin_allow_trailing_comma_on_call_site = true
   ij_kotlin_name_count_to_use_star_import = 2147483647
   ij_kotlin_name_count_to_use_star_import_for_members = 2147483647

   [*.{yml,yaml,json,toml}]
   indent_size = 2

   [*.md]
   trim_trailing_whitespace = false
   ```
   (`max_line_length = 140` matches the detekt `maxLineLength` from Step 5 so the two gates agree.)
   Verify: `test -f .editorconfig && echo OK` → `OK`. (`.editorconfig` is advisory metadata; it does not change any Gradle task, so no build re-run is needed.)

8. **Add the CI workflow `.github/workflows/detekt.yml`.** Mirror `lint.yml` (checkout → setup-java 21 temurin → gradle cache → chmod gradlew → run task). Create:
   ```yaml
   name: Detekt

   on:
     push:
       branches: [main]
     pull_request:
       branches: [main]

   jobs:
     detekt:
       name: Detekt (static analysis, report-only)
       runs-on: ubuntu-latest
       steps:
         - name: Checkout
           uses: actions/checkout@v5

         - name: Set up JDK 21
           uses: actions/setup-java@v5
           with:
             java-version: "21"
             distribution: "temurin"

         - name: Gradle Cache
           uses: actions/cache@v5
           with:
             path: |
               ~/.gradle/caches
               ~/.gradle/wrapper
             key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
             restore-keys: |
               ${{ runner.os }}-gradle-

         - name: Make gradlew executable
           run: chmod +x ./gradlew

         - name: Run Detekt (report-only)
           run: ./gradlew detekt
           env:
             JDK_21: ${{ env.JAVA_HOME }}

         - name: Upload Detekt reports
           if: always()
           uses: actions/upload-artifact@v4
           with:
             name: detekt-reports
             path: '**/build/reports/detekt/'
             if-no-files-found: ignore
   ```
   The `if: always()` + upload-artifact step surfaces the report-only findings as a downloadable artifact even though the gate never fails. (Mirroring `lint.yml` exactly for the first 35 lines, then adding the upload step.)
   Verify: the YAML parses — `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/detekt.yml'))" && echo OK` → `OK` (if `pyyaml` is unavailable, fall back to `./gradlew help` having already proven the gradle side; the workflow is exercised on the next push).

9. **Update `plans/README.md`.** If `plans/README.md` exists, add/update the row for plan 008 (status: done / wired report-only). If it does NOT exist (verified at planning time it did not), do NOT create it — note in your report that there is no `plans/README.md` to update.
   Verify: `test -f plans/README.md && grep -q '008' plans/README.md && echo UPDATED || echo 'no README to update'`.

## Test plan
This plan adds NO Kotlin source and NO unit tests — detekt itself IS the new gate, and report-only means it must not fail the build. The "regression this prevents" is *future* unreviewed style/complexity drift across the KMP source sets; that is enforced by the detekt task existing and running in CI, not by a JUnit test.

- The behavioural assertion to confirm (model after the existing `lint.yml` gate, which simply runs a gradle task): `./gradlew detekt` exits 0 today on the full repo despite pre-existing findings (because `ignoreFailures = true`), and produces HTML + SARIF reports under `*/build/reports/detekt/`.
- No `commonTest`/`jvmTest` file is added. Do NOT add a Konsist `ArchitectureTest` rule — that suite (`homebase-common/src/jvmTest/.../architecture/ArchitectureTest.kt`) is out of scope and unrelated.
- Verify command (the whole test plan): `./gradlew detekt && find . -path '*/build/reports/detekt/*' -name 'detekt.sarif' | head -1` → BUILD SUCCESSFUL and at least one SARIF path printed.

## Done criteria
- [ ] `git diff --stat 45e2832e..HEAD -- build.gradle.kts gradle/libs.versions.toml settings.gradle.kts gradle.properties .github/workflows/lint.yml .editorconfig config/` was empty at start (drift check passed).
- [ ] `grep -n 'detekt' gradle/libs.versions.toml` shows one `[versions]` entry and one `[plugins]` entry (2 hits).
- [ ] `grep -n 'detekt' build.gradle.kts` shows the `alias(libs.plugins.detekt) apply false` line plus the `apply(plugin = "io.gitlab.arturbosch.detekt")` / `DetektExtension` / `Detekt` config inside `subprojects {}`.
- [ ] `test -f config/detekt/detekt.yml && echo OK` → `OK`; the file is valid YAML and pruned (not the full default dump).
- [ ] `test -f .editorconfig && echo OK` → `OK`.
- [ ] `test -f .github/workflows/detekt.yml && echo OK` → `OK`.
- [ ] `./gradlew detekt` → BUILD SUCCESSFUL, exit 0 (report-only); `find . -path '*/build/reports/detekt/*' -name 'detekt.*' | head` returns ≥1 report.
- [ ] `./gradlew help` run twice → 2nd run prints `Reusing configuration cache.` (config-cache intact).
- [ ] `./gradlew detekt` run twice → no `Configuration cache problems found`; both BUILD SUCCESSFUL.
- [ ] `./gradlew :homebase-api:compileKotlinJvm` → BUILD SUCCESSFUL (no collateral breakage).
- [ ] `git status --porcelain` shows ONLY: `gradle/libs.versions.toml`, `build.gradle.kts`, `config/detekt/detekt.yml` (new), `.editorconfig` (new), `.github/workflows/detekt.yml` (new), and `plans/008-add-detekt-report-only.md` — and `plans/README.md` only if it already existed. No `.kt`/`.kts` source under `src/` changed. (Note: `*/build/reports/detekt/` is gitignored via `.gitignore:10` `**/build/`, so reports must NOT appear in `git status`.)
- [ ] `grep -rIn 'ignoreFailures' build.gradle.kts` confirms `ignoreFailures = true` (report-only is in place; the team has NOT been switched to fail-the-build).
- [ ] `plans/README.md` row updated if the file exists (else reported as absent).

## STOP conditions (specific to this plan)
- **Drift:** any in-scope file changed since commit 45e2832e and the live code no longer matches the Current-state excerpts — STOP and reconcile.
- **No resolvable detekt version:** fully offline / Plugin Portal + mavenCentral both unreachable so no detekt version resolves — STOP; do NOT guess a version that fails `./gradlew help`.
- **Config-cache breaks:** if applying/running detekt makes `./gradlew help` or `./gradlew detekt` report `Configuration cache problems found` (a hard failure, not a benign recompute), do NOT disable the project-wide config cache (`gradle.properties:28`). Instead scope detekt so the cache stays usable (apply only on `subprojects`, never `allprojects`; avoid capturing `project`/`Task` references in task config blocks; use `rootProject.files(...)` not `project` inside lazy providers). If it still cannot be made config-cache clean, STOP and report — do NOT ship a plan that degrades the cache for the whole repo.
- **detekt would FAIL the build:** if for any reason `./gradlew detekt` exits non-zero on the current baseline (e.g. `ignoreFailures` got dropped, or a rule with `active: true` is configured to fail), STOP — report-only is the explicit requirement; fix the config so exit code is 0.
- **Fix needs an out-of-scope file:** if making detekt pass requires editing a `.kt` source, a per-module `build.gradle.kts`, or `gradle.properties` — STOP. This plan is report-only and centrally wired; touching those is a different plan.
- **Verification fails twice:** any Verify step fails on two consecutive attempts after a genuine fix attempt — STOP and report the exact command + output.
- **Assumption proven false:** if `subprojects {}` cannot host the detekt apply (e.g. a module's plugin order rejects late `apply(plugin=…)`) — STOP and report which module; do NOT silently switch to per-module application without surfacing it.

## Maintenance notes
- **Ratchet to enforcing later.** The single lever is `ignoreFailures = true` in `build.gradle.kts`'s `subprojects {}` detekt config. The intended follow-up (separate plan) is: triage the report-only baseline, optionally generate a `detekt-baseline.xml` (`./gradlew detektBaseline`) to freeze existing findings, then flip `ignoreFailures = false` so NEW violations fail CI. Do that as a deliberate, reviewed change — not silently.
- **A reviewer should scrutinize:** (1) that no `.kt`/`.kts` source under `src/` was modified (report-only contract); (2) that config-cache still reuses (`Reusing configuration cache.`) after detekt is on the graph — this is the highest-risk interaction with `gradle.properties:28`; (3) that the pruned `config/detekt/detekt.yml` is valid YAML and didn't accidentally set a rule to fail the build; (4) that detekt is applied via the root `subprojects {}` only, not duplicated into per-module build files.
- **Future interactions:** when a NEW module is added to `settings.gradle.kts`, the root `subprojects {}` automatically applies detekt to it — no per-module wiring needed. When Kotlin is bumped past 2.3.21, detekt's bundled analyzer may lag; since we run without type-resolution this is usually fine, but if detekt starts failing to parse new syntax, bump the detekt version (the `[versions] detekt` entry) — do NOT pin the project Kotlin down.
- **Deferred follow-ups (out of this plan):** ktlint/spotless auto-formatting; a pre-commit hook running `detekt` on staged files; enabling detekt type-resolution (`detektMain` with classpath) for the deeper rule set; uploading SARIF to GitHub code-scanning via `github/codeql-action/upload-sarif` instead of a plain artifact.
