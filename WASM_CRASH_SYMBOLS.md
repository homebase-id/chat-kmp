# WASM crash symbols

The production web app ships a `.wasm` without function names, so a crash reads as
`…/0dd0051027289d0e8ea4.wasm:wasm-function[39318]:0x1221b78`. Every deploy build keeps the names
and a source map for exactly that binary, so the trace can be turned back into Kotlin.

## Symbolicating a trace

1. In the frozen tab's console, run `__homebaseFirstError()` (defined in `index.html`). It returns
   the **first** uncaught error. The console scrolls past it under the cascade that follows (for
   example `ComposeRuntimeError … pending composition has not been applied`), and that cascade is
   only a consequence.
2. Save the output to a file and run:

   ```bash
   python3 scripts/wasm-symbolicate.py trace.txt      # or pipe it on stdin
   ```

   ```
   ComposerAutocomplete  homebase-common/src/commonMain/kotlin/id/homebase/core/widget/ComposerAutocomplete.kt:110
   MentionAutocomplete  homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MentionAutocomplete.kt:42
   …
   androidx.compose.runtime.Recomposer.performRecompose
   ```

The script reads the `.wasm` hash from the frame URLs, downloads the artifact
`chat-wasm-symbols-<hash>` from `homebase-id/odin-core` with `gh` (needs `gh auth login`), and
caches it in `~/.cache/homebase-wasm-symbols/`. The artifact also holds `chat-kmp-commit.txt`,
the commit the bundle was built from. Frames without a file are Kotlin/Compose runtime code.

- **`bad cast`** is a failed `ref.cast`, Kotlin's `ClassCastException`. Wasm can't catch it, so
  it kills Compose's frame loop and the UI freezes. Reproduce on desktop (JVM) for a readable
  exception.
- **No `<hash>.wasm` in the frames** (some Chrome views show `wasm://wasm/<id>`): pass
  `--hash <20 hex>`. Get the hash from the Network tab, or from
  `performance.getEntriesByType('resource').map(e => e.name).filter(n => n.endsWith('.wasm'))`.
- **A local build:** `--symbols webApp/build/dist/wasmJs/symbols`.

## When there is no artifact

Artifacts expire after 90 days. Bundles built before this existed have none. Rebuilding the same
commit does **not** reproduce the shipped binary: two wasm-opt runs on one input differ by a few
bytes. To read an old trace anyway:

1. Find the commit in the deploy log: `Resolved homebase-id/chat-kmp@main to <sha>`.
2. Rebuild that commit.
3. Map each frame by function index plus its offset within the function body, not by absolute
   offset.

Function indices matched in practice. Check that each frame's function body is the same size in
both binaries before trusting a line.

## How it is produced

- **One run, two copies.** `webApp/build.gradle.kts` runs the production wasm-opt with `-g` and
  a source map, which costs about 0.5 GB of extra peak memory. It saves that output as the
  symbols, then cuts the `name` section out of the copy that ships. The `name` section sits after
  the code, so every function index and code offset in the shipped file matches the symbols.
- **Binaryen's input map.** Binaryen can't read Kotlin's source map as written: it asserts on
  the `null` `sourcesContent` entries, so the build strips them first. Kotlin's own "optimized"
  `.map` is an unmodified copy of the pre-optimization map; it is wrong for the shipped file.
- **Collection.** `collectWasmCrashSymbols` runs after `wasmJsBrowserDistribution`. It files
  `<hash>.wasm` and `<hash>.wasm.map` under the content-hashed name webpack gave the shipped file,
  in `webApp/build/dist/wasmJs/symbols/`, a sibling of the deployed `productionExecutable/`.
- **Upload.** odin-core's `.github/actions/host/build-kotlin-wasm` uploads that folder as an
  artifact on every deploy build, cache hits included. The folder stays outside the Docker
  context, so it never ships.
